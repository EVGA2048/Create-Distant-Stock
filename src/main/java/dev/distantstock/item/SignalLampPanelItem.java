package dev.distantstock.item;

import com.simibubi.create.content.logistics.factoryBoard.FactoryPanelBehaviour;
import com.simibubi.create.content.logistics.factoryBoard.FactoryPanelBlock;
import com.simibubi.create.content.logistics.factoryBoard.FactoryPanelBlockEntity;
import dev.distantstock.block.ModBlocks;
import dev.distantstock.block.SignalPanelBlock;
import dev.distantstock.block.SignalPanelBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;

import java.util.EnumMap;
import java.util.ArrayList;

public final class SignalLampPanelItem extends BlockItem {
    public enum Material {
        ANDESITE,
        BRASS
    }

    public enum Color {
        CYAN,
        ORANGE,
        RED,
        GREEN,
        WHITE
    }

    private final Material material;
    private final Color color;

    public SignalLampPanelItem(Block standaloneBlock, Properties properties, Material material, Color color) {
        // The BlockItem must be associated with the real standalone lamp. Binding every lamp item
        // to SIGNAL_PANEL overwrites BlockItem.BY_BLOCK and lets normal placement create a factory
        // panel instead of a lamp (as well as leaving the lamp blocks without their own items).
        super(standaloneBlock, properties);
        this.material = material;
        this.color = color;
    }

    /** A lamp has to be named after itself: a BlockItem would otherwise carry the block's name. */
    @Override
    public String getDescriptionId() {
        return material == Material.BRASS
                ? "item.distantstock.brass_signal_lamp"
                : "item.distantstock." + color.name().toLowerCase(java.util.Locale.ROOT) + "_indicator_lamp";
    }

    public Material material() {
        return material;
    }

    public Color color() {
        return color;
    }

    public static SignalLampPanelItem from(ItemStack stack) {
        return stack.getItem() instanceof SignalLampPanelItem lamp ? lamp : null;
    }

    @Override
    public InteractionResult useOn(UseOnContext context) {
        Level level = context.getLevel();
        BlockPos pos = context.getClickedPos();
        BlockState state = level.getBlockState(pos);

        if (state.getBlock() instanceof FactoryPanelBlock) {
            var targeted = FactoryPanelBlock.getTargetedSlot(pos, state, context.getClickLocation());
            if (level.getBlockEntity(pos) instanceof FactoryPanelBlockEntity old
                    && old.panels.get(targeted).isActive()) return InteractionResult.FAIL;
            if (level.isClientSide) {
                return InteractionResult.SUCCESS;
            }
            SignalPanelBlockEntity be = state.is(ModBlocks.SIGNAL_PANEL.get())
                    ? level.getBlockEntity(pos, ModBlocks.SIGNAL_PANEL_ENTITY_TYPE()).orElse(null)
                    : convertFactoryPanel(level, pos, state);
            if (be == null) {
                return InteractionResult.FAIL;
            }
            FactoryPanelBlock.PanelSlot slot = FactoryPanelBlock.getTargetedSlot(pos, be.getBlockState(),
                    context.getClickLocation());
            return install(new BlockPlaceContext(context), be, slot);
        }

        if (context.getPlayer() != null && context.getPlayer().isShiftKeyDown()
                && context.getClickedFace().getAxis().isHorizontal()) {
            // Use vanilla placement validation without changing this registered item's block mapping.
            return new BlockItem(ModBlocks.SIGNAL_PANEL.get(), new net.minecraft.world.item.Item.Properties())
                    .place(new BlockPlaceContext(context));
        }

        // For every ordinary surface use vanilla BlockItem placement. Since this item is now
        // registered against the correct IndicatorLampBlock, no fallback can create SIGNAL_PANEL.
        return super.useOn(context);
    }

    public static void finishPlacement(SignalPanelBlockEntity be, FactoryPanelBlock.PanelSlot slot, ItemStack held) {
        FactoryPanelBehaviour behaviour = be.panels.get(slot);
        if (behaviour == null || !behaviour.isActive()) {
            if (!be.addPanel(slot, null)) {
                return;
            }
            behaviour = be.panels.get(slot);
        }
        behaviour.setFilter(held.copyWithCount(1));
        behaviour.count = 0;
        be.redraw = true;
        be.sendData();
    }

    @Override
    public void appendHoverText(ItemStack stack, TooltipContext context,
                                java.util.List<net.minecraft.network.chat.Component> tooltip,
                                net.minecraft.world.item.TooltipFlag flag) {
        super.appendHoverText(stack, context, tooltip, flag);
        tooltip.add(net.minecraft.network.chat.Component.translatable("item.distantstock.lamp.placement")
                .withStyle(net.minecraft.ChatFormatting.GRAY));
    }

    private InteractionResult install(BlockPlaceContext context, SignalPanelBlockEntity be,
                                      FactoryPanelBlock.PanelSlot slot) {
        if (!be.addPanel(slot, null)) {
            return InteractionResult.FAIL;
        }
        finishPlacement(be, slot, context.getItemInHand());
        if (context.getPlayer() == null || !context.getPlayer().isCreative()) {
            context.getItemInHand().shrink(1);
        }
        return InteractionResult.SUCCESS;
    }

    private static SignalPanelBlockEntity convertFactoryPanel(Level level, BlockPos pos, BlockState oldState) {
        if (!(level.getBlockEntity(pos) instanceof FactoryPanelBlockEntity oldBe)) {
            return null;
        }

        EnumMap<FactoryPanelBlock.PanelSlot, CompoundTag> saved =
                new EnumMap<>(FactoryPanelBlock.PanelSlot.class);
        for (var entry : oldBe.panels.entrySet()) {
            if (!entry.getValue().isActive()) {
                continue;
            }
            CompoundTag tag = new CompoundTag();
            entry.getValue().write(tag, level.registryAccess(), false);
            saved.put(entry.getKey(), tag);
        }

        BlockState replacement = ModBlocks.SIGNAL_PANEL.get().defaultBlockState()
                .setValue(BlockStateProperties.ATTACH_FACE, oldState.getValue(BlockStateProperties.ATTACH_FACE))
                .setValue(BlockStateProperties.HORIZONTAL_FACING, oldState.getValue(BlockStateProperties.HORIZONTAL_FACING))
                .setValue(BlockStateProperties.WATERLOGGED, oldState.getValue(BlockStateProperties.WATERLOGGED))
                .setValue(FactoryPanelBlock.POWERED, oldState.getValue(FactoryPanelBlock.POWERED));
        // Avoid FactoryPanelBlockEntity.destroy dropping extra gauges during an in-place conversion.
        // Existing connected boards need a transactional migration; leave them untouched for now.
        if (oldBe.panels.values().stream().anyMatch(panel -> !panel.targetedBy.isEmpty()
                || !panel.targetedByLinks.isEmpty() || !panel.targeting.isEmpty())) return null;
        for (var panel : oldBe.panels.values()) {
            if (panel.isActive()) panel.disable();
        }
        level.setBlock(pos, replacement, 3);

        if (!(level.getBlockEntity(pos) instanceof SignalPanelBlockEntity newBe)) {
            return null;
        }
        for (var entry : saved.entrySet()) {
            newBe.addPanel(entry.getKey(), null);
            newBe.panels.get(entry.getKey()).read(entry.getValue(), level.registryAccess(), false);
        }
        reconnectCopiedPanels(level, newBe);
        newBe.redraw = true;
        newBe.sendData();
        return newBe;
    }

    private static void reconnectCopiedPanels(Level level, SignalPanelBlockEntity be) {
        for (FactoryPanelBehaviour panel : be.panels.values()) {
            if (!panel.isActive()) {
                continue;
            }
            // Replacing the original block disconnects both ends. The copied NBT
            // restores this side; replaying the relationships restores the peers.
            for (var connection : new ArrayList<>(panel.targetedBy.values())) {
                panel.addConnection(connection.from);
            }
            for (var connection : new ArrayList<>(panel.targetedByLinks.values())) {
                panel.addConnection(connection.from);
            }
            for (var targetPosition : new ArrayList<>(panel.targeting)) {
                FactoryPanelBehaviour target = FactoryPanelBehaviour.at(level, targetPosition);
                if (target != null) {
                    target.addConnection(panel.getPanelPosition());
                }
            }
        }
    }
}
