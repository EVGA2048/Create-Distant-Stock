package dev.distantstock.item;

import com.simibubi.create.content.logistics.factoryBoard.FactoryPanelBehaviour;
import com.simibubi.create.content.logistics.factoryBoard.FactoryPanelBlock;
import com.simibubi.create.content.logistics.factoryBoard.FactoryPanelBlockEntity;
import com.simibubi.create.content.logistics.factoryBoard.FactoryPanelConnection;
import com.simibubi.create.content.logistics.factoryBoard.FactoryPanelPosition;
import dev.distantstock.block.ModBlocks;
import dev.distantstock.block.SignalPanelBlock;
import dev.distantstock.block.SignalPanelBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
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
import java.util.HashMap;
import java.util.Map;

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
            if (!state.is(ModBlocks.SIGNAL_PANEL.get()) && !state.is(ModBlocks.REMOTE_GAUGE.get())
                    && net.neoforged.fml.ModList.get().isLoaded("deployer")) {
                return installOnSomeoneElsesBoard(context, level, pos, targeted);
            }
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

        // For every ordinary surface use vanilla BlockItem placement. Since this item is now
        // registered against the correct IndicatorLampBlock, no fallback can create SIGNAL_PANEL.
        return super.useOn(context);
    }

    @Override
    protected BlockState getPlacementState(BlockPlaceContext context) {
        if (context.getPlayer() != null && context.getPlayer().isShiftKeyDown()
                && context.getClickedFace().getAxis().isHorizontal()) {
            BlockState state = ModBlocks.SIGNAL_PANEL.get().getStateForPlacement(context);
            return state != null && canPlace(context, state) ? state : null;
        }
        return super.getPlacementState(context);
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
        if (material == Material.BRASS) {
            // The watch list is the brass lamp's whole point and nothing on screen advertises it.
            tooltip.add(net.minecraft.network.chat.Component.translatable("item.distantstock.brass_signal_lamp.usage")
                    .withStyle(net.minecraft.ChatFormatting.DARK_GRAY));
        }
    }

    /**
     * Puts the lamp into a free slot of a board that is not ours, and leaves the board alone.
     *
     * <p>Without this the lamp would convert the board into one of our signal panels, because that is
     * the only place a lamp panel has ever existed. With Deployer installed a lamp is a kind of panel,
     * so it can sit beside a plain factory gauge on a board somebody already built — and the board
     * stays the block it was.
     */
    private InteractionResult installOnSomeoneElsesBoard(UseOnContext context, Level level,
                                                         BlockPos pos,
                                                         FactoryPanelBlock.PanelSlot slot) {
        if (level.isClientSide) {
            return InteractionResult.SUCCESS;
        }
        if (!(level.getBlockEntity(pos) instanceof FactoryPanelBlockEntity board)
                || !dev.distantstock.panel.DeployerPanels.installLamp(board, slot,
                        context.getItemInHand())) {
            return InteractionResult.FAIL;
        }
        if (context.getPlayer() == null || !context.getPlayer().isCreative()) {
            context.getItemInHand().shrink(1);
        }
        return InteractionResult.SUCCESS;
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

    /**
     * The panel a click really targets. A panel only carries a hitbox for the slots that are already
     * occupied, so aiming at a free slot makes the crosshair pass straight through and land on the
     * wall behind it. Create's own placement resolves that through {@code BlockPlaceContext}'s
     * relative position; the event based paths have to do the same or they never see the panel.
     */
    public static BlockPos panelUnder(Level level, BlockPos hitPos, Direction hitFace) {
        if (level.getBlockState(hitPos).getBlock() instanceof FactoryPanelBlock) {
            return hitPos;
        }
        BlockPos mounted = hitPos.relative(hitFace);
        return level.getBlockState(mounted).getBlock() instanceof FactoryPanelBlock ? mounted : null;
    }

    public static SignalPanelBlockEntity convertFactoryPanel(Level level, BlockPos pos, BlockState oldState) {
        if (!(level.getBlockEntity(pos) instanceof FactoryPanelBlockEntity oldBe)) {
            return null;
        }

        EnumMap<FactoryPanelBlock.PanelSlot, CompoundTag> saved =
                new EnumMap<>(FactoryPanelBlock.PanelSlot.class);
        EnumMap<FactoryPanelBlock.PanelSlot, Map<FactoryPanelPosition, FactoryPanelConnection>> outputs =
                new EnumMap<>(FactoryPanelBlock.PanelSlot.class);
        for (var entry : oldBe.panels.entrySet()) {
            if (!entry.getValue().isActive()) {
                continue;
            }
            CompoundTag tag = new CompoundTag();
            entry.getValue().write(tag, level.registryAccess(), false);
            saved.put(entry.getKey(), tag);
            Map<FactoryPanelPosition, FactoryPanelConnection> peers = new HashMap<>();
            for (var targetPos : entry.getValue().targeting) {
                FactoryPanelBehaviour target = FactoryPanelBehaviour.at(level, targetPos);
                if (target != null) {
                    FactoryPanelConnection connection = target.targetedBy.get(entry.getValue().getPanelPosition());
                    if (connection != null) peers.put(targetPos, connection);
                }
            }
            outputs.put(entry.getKey(), peers);
        }

        BlockState replacement = ModBlocks.SIGNAL_PANEL.get().defaultBlockState()
                .setValue(BlockStateProperties.ATTACH_FACE, oldState.getValue(BlockStateProperties.ATTACH_FACE))
                .setValue(BlockStateProperties.HORIZONTAL_FACING, oldState.getValue(BlockStateProperties.HORIZONTAL_FACING))
                .setValue(BlockStateProperties.WATERLOGGED, oldState.getValue(BlockStateProperties.WATERLOGGED))
                .setValue(FactoryPanelBlock.POWERED, oldState.getValue(FactoryPanelBlock.POWERED));
        // Disable the old entity after taking a complete snapshot; this detaches live peers,
        // which reconnectCopiedPanels reattaches from the snapshot below.
        for (var panel : oldBe.panels.values()) {
            if (panel.isActive()) panel.disable();
        }
        level.setBlock(pos, replacement, 3);

        if (!(level.getBlockEntity(pos) instanceof SignalPanelBlockEntity newBe)) {
            return null;
        }
        newBe.restocker = oldBe.restocker;
        for (var entry : saved.entrySet()) {
            newBe.addPanel(entry.getKey(), null);
            newBe.panels.get(entry.getKey()).read(entry.getValue(), level.registryAccess(), false);
            if (oldState.is(ModBlocks.REMOTE_GAUGE.get())) newBe.setRemoteGauge(entry.getKey(), true);
        }
        reconnectCopiedPanels(level, newBe, outputs);
        newBe.redraw = true;
        newBe.sendData();
        return newBe;
    }

    private static void reconnectCopiedPanels(Level level, SignalPanelBlockEntity be,
            EnumMap<FactoryPanelBlock.PanelSlot, Map<FactoryPanelPosition, FactoryPanelConnection>> outputs) {
        for (FactoryPanelBehaviour panel : be.panels.values()) {
            if (!panel.isActive()) {
                continue;
            }
            // Replacing the original block detaches both ends. The copied NBT restores this
            // side; the peers are reattached from the pre-conversion snapshot so their
            // per-connection settings survive instead of being rebuilt from scratch.
            for (var connection : new ArrayList<>(panel.targetedBy.values())) {
                FactoryPanelBehaviour source = FactoryPanelBehaviour.at(level, connection.from);
                if (source != null) {
                    source.targeting.add(panel.getPanelPosition());
                    source.blockEntity.sendData();
                }
            }
            for (var connection : new ArrayList<>(panel.targetedByLinks.values())) {
                var link = FactoryPanelBehaviour.linkAt(level, connection);
                if (link != null) link.connect(panel);
            }
            for (var targetPosition : new ArrayList<>(panel.targeting)) {
                FactoryPanelBehaviour target = FactoryPanelBehaviour.at(level, targetPosition);
                if (target != null) {
                    FactoryPanelConnection snapshot = outputs.getOrDefault(panel.slot, Map.of()).get(targetPosition);
                    if (snapshot != null) {
                        target.targetedBy.put(panel.getPanelPosition(), snapshot);
                        target.blockEntity.sendData();
                    }
                }
            }
        }
    }
}
