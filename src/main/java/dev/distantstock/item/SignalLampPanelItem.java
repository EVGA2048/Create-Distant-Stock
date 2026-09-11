package dev.distantstock.item;

import com.simibubi.create.AllBlocks;
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
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.player.Player;
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

    public SignalLampPanelItem(Properties properties, Material material, Color color) {
        super(ModBlocks.SIGNAL_PANEL.get(), properties);
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
    public InteractionResult place(BlockPlaceContext context) {
        Level level = context.getLevel();
        BlockPos pos = context.getClickedPos();
        BlockState state = level.getBlockState(pos);

        if (state.getBlock() instanceof FactoryPanelBlock) {
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
            return install(context, be, slot);
        }

        return placeStandalone(context);
    }

    /**
     * Lamps are standalone wall lights by default; a factory panel is only used when the click targets one.
     * Without this the item would place an empty signal panel showing four gauge slots.
     */
    private InteractionResult placeStandalone(BlockPlaceContext context) {
        Level level = context.getLevel();
        Block lamp = standaloneBlock();
        if (lamp == null) {
            return super.place(context);
        }
        BlockPos target = context.getClickedPos().relative(context.getClickedFace());
        BlockPlaceContext at = BlockPlaceContext.at(context, target, context.getClickedFace());
        if (!level.getBlockState(target).canBeReplaced(at)) {
            return super.place(context);
        }
        BlockState state = lamp.getStateForPlacement(at);
        if (state == null) {
            // Face-attached placement can refuse the clicked face; a floor lamp always fits a solid top.
            state = lamp.defaultBlockState()
                    .setValue(net.minecraft.world.level.block.state.properties.BlockStateProperties
                            .ATTACH_FACE,
                            net.minecraft.world.level.block.state.properties.AttachFace.FLOOR)
                    .setValue(net.minecraft.world.level.block.state.properties.BlockStateProperties
                            .HORIZONTAL_FACING, context.getHorizontalDirection().getOpposite());
        }
        if (!state.canSurvive(level, target)) {
            return super.place(context);
        }
        if (level.isClientSide) {
            return InteractionResult.SUCCESS;
        }
        level.setBlock(target, state, 11);
        level.playSound(null, target, state.getSoundType().getPlaceSound(), SoundSource.BLOCKS, 1f, 0.9f);
        Player player = context.getPlayer();
        if (player == null || !player.isCreative()) {
            context.getItemInHand().shrink(1);
        }
        return InteractionResult.SUCCESS;
    }

    private Block standaloneBlock() {
        return switch (material) {
            case BRASS -> ModBlocks.BRASS_INDICATOR_LAMP.get();
            case ANDESITE -> switch (color) {
                case CYAN -> ModBlocks.CYAN_INDICATOR_LAMP.get();
                case ORANGE -> ModBlocks.ORANGE_INDICATOR_LAMP.get();
                case RED -> ModBlocks.RED_INDICATOR_LAMP.get();
                case GREEN -> ModBlocks.GREEN_INDICATOR_LAMP.get();
                case WHITE -> ModBlocks.WHITE_INDICATOR_LAMP.get();
            };
        };
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
