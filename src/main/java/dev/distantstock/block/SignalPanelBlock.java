package dev.distantstock.block;

import com.mojang.serialization.MapCodec;
import com.simibubi.create.content.equipment.wrench.IWrenchable;
import com.simibubi.create.content.logistics.factoryBoard.FactoryPanelBlock;
import com.simibubi.create.content.logistics.factoryBoard.FactoryPanelBlockEntity;
import com.simibubi.create.content.logistics.packagerLink.LogisticallyLinkedBlockItem;
import dev.distantstock.item.SignalLampPanelItem;
import dev.distantstock.item.RequesterData;
import dev.distantstock.item.RequesterItem;
import dev.distantstock.menu.MonitorMenu;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.ItemInteractionResult;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.phys.BlockHitResult;

import java.util.UUID;

public final class SignalPanelBlock extends FactoryPanelBlock implements IWrenchable {

    /** Rotation would silently move the cabin or the panel slots, so a wrench click only reports state. */
    @Override
    public net.minecraft.world.InteractionResult onWrenched(net.minecraft.world.level.block.state.BlockState state,
                                                            net.minecraft.world.item.context.UseOnContext context) {
        return net.minecraft.world.InteractionResult.SUCCESS;
    }
    public static final MapCodec<SignalPanelBlock> CODEC = simpleCodec(SignalPanelBlock::new);

    public SignalPanelBlock(Properties properties) {
        super(properties);
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    @Override
    public Class<FactoryPanelBlockEntity> getBlockEntityClass() {
        return (Class) SignalPanelBlockEntity.class;
    }

    @Override
    public BlockEntityType<? extends FactoryPanelBlockEntity> getBlockEntityType() {
        return ModBlockEntities.SIGNAL_PANEL.get();
    }

    @Override
    public void setPlacedBy(Level level, BlockPos pos, BlockState state, LivingEntity placer, ItemStack stack) {
        super.setPlacedBy(level, pos, state, placer, stack);
        if (level.isClientSide || !(stack.getItem() instanceof SignalLampPanelItem)) {
            return;
        }
        if (!(level.getBlockEntity(pos) instanceof SignalPanelBlockEntity be) || placer == null) {
            return;
        }
        var hit = placer.pick(placer.getAttributeValue(net.minecraft.world.entity.ai.attributes.Attributes.BLOCK_INTERACTION_RANGE) + 1,
                1, false);
        FactoryPanelBlock.PanelSlot slot = FactoryPanelBlock.getTargetedSlot(pos, state, hit.getLocation());
        SignalLampPanelItem.finishPlacement(be, slot, stack);
    }

    @Override
    protected ItemInteractionResult useItemOn(ItemStack stack, BlockState state, Level level, BlockPos pos,
                                              Player player, InteractionHand hand, BlockHitResult hit) {
        FactoryPanelBlock.PanelSlot slot = FactoryPanelBlock.getTargetedSlot(pos, state, hit.getLocation());
        if (level.getBlockEntity(pos) instanceof SignalPanelBlockEntity be
                && be.isRemoteGauge(slot)
                && stack.getItem() instanceof RequesterItem
                && RequesterData.network(stack).isPresent()) {
            // A remote gauge panel on this board orders from another server, so it is pointed at a
            // warehouse the same way the dedicated board's panels are: hold a tuned requester and
            // click the panel. Sneak clears it.
            if (!level.isClientSide) {
                if (player.isShiftKeyDown()) {
                    be.unbind(slot);
                    player.displayClientMessage(
                            Component.translatable("gui.distantstock.remote_gauge.unbound"), true);
                } else {
                    var network = RequesterData.network(stack).get();
                    be.bind(slot, network, RequesterData.receivingGroup(stack).orElse(null),
                            RequesterData.address(stack));
                    player.displayClientMessage(
                            Component.translatable("gui.distantstock.remote_gauge.bound",
                                    network.shortLabel()), true);
                }
            }
            return ItemInteractionResult.sidedSuccess(level.isClientSide);
        }
        if (level.getBlockEntity(pos) instanceof SignalPanelBlockEntity be && be.isLamp(slot)) {
            UUID network = lampBinding(stack);
            if (network != null) {
                // A tuned requester or a Create stock link points the lamp at a logistics network.
                // Sneak clears the binding and returns the lamp to reading its gauges.
                if (!level.isClientSide) {
                    boolean unbind = player.isShiftKeyDown();
                    be.setLampNetwork(slot, unbind ? null : network);
                    player.displayClientMessage(unbind
                            ? Component.translatable("gui.distantstock.lamp.unbound")
                            : Component.translatable("gui.distantstock.lamp.bound",
                                    RequesterData.shortFreq(network)), true);
                }
                return ItemInteractionResult.sidedSuccess(level.isClientSide);
            }
            if (stack.is(Items.NAME_TAG) && stack.has(DataComponents.CUSTOM_NAME)) {
                if (!level.isClientSide) {
                    ItemStack marker = be.lampStack(slot).copy();
                    marker.set(DataComponents.CUSTOM_NAME, stack.get(DataComponents.CUSTOM_NAME));
                    be.panels.get(slot).setFilter(marker);
                    be.sendData();
                }
                return ItemInteractionResult.SUCCESS;
            }
            return ItemInteractionResult.PASS_TO_DEFAULT_BLOCK_INTERACTION;
        }
        return super.useItemOn(stack, state, level, pos, player, hand, hit);
    }

    /**
     * Sneak-use with an empty hand opens the lamp's watch list. Sneaking is deliberate: Create
     * opens its value panel on a held right-click, so the plain interaction is already spoken for.
     */
    @Override
    protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos,
                                               Player player, BlockHitResult hit) {
        FactoryPanelBlock.PanelSlot slot = FactoryPanelBlock.getTargetedSlot(pos, state, hit.getLocation());
        if (player.isShiftKeyDown() && level.getBlockEntity(pos) instanceof SignalPanelBlockEntity be
                && be.isLamp(slot) && player instanceof ServerPlayer serverPlayer) {
            serverPlayer.openMenu(new SimpleMenuProvider(
                            (id, inv, p) -> MonitorMenu.server(id, inv, pos, slot, be.monitor(slot)),
                            Component.translatable("gui.distantstock.lamp_monitor")),
                    buf -> {
                        buf.writeBlockPos(pos);
                        buf.writeEnum(slot);
                    });
            return InteractionResult.sidedSuccess(false);
        }
        return super.useWithoutItem(state, level, pos, player, hit);
    }

    /** The logistics frequency a held item carries, or null when it carries none. */
    static UUID lampBinding(ItemStack stack) {
        if (stack.getItem() instanceof RequesterItem && RequesterData.tuned(stack)) {
            return RequesterData.freq(stack);
        }
        if (stack.getItem() instanceof LogisticallyLinkedBlockItem) {
            return LogisticallyLinkedBlockItem.networkFromStack(stack);
        }
        return null;
    }

    @Override
    public InteractionResult onSneakWrenched(BlockState state, UseOnContext context) {
        Level level = context.getLevel();
        BlockPos pos = context.getClickedPos();
        FactoryPanelBlock.PanelSlot slot = FactoryPanelBlock.getTargetedSlot(pos, state, context.getClickLocation());
        if (level.getBlockEntity(pos) instanceof SignalPanelBlockEntity be
                && be.panels.get(slot).isActive()) {
            if (!level.isClientSide) {
                ItemStack drop = be.panelItem(slot);
                be.removePanel(slot);
                Player player = context.getPlayer();
                if (player == null || !player.isCreative()) {
                    popResource(level, pos, drop);
                }
                if (be.activePanels() == 0) {
                    level.removeBlock(pos, false);
                } else {
                    be.sendData();
                }
            }
            return InteractionResult.SUCCESS;
        }
        return super.onSneakWrenched(state, context);
    }

    @Override
    public boolean onDestroyedByPlayer(BlockState state, Level level, BlockPos pos, Player player,
                                       boolean willHarvest, FluidState fluid) {
        var hit = player.pick(player.getAttributeValue(
                net.minecraft.world.entity.ai.attributes.Attributes.BLOCK_INTERACTION_RANGE) + 1, 1, false);
        FactoryPanelBlock.PanelSlot slot = FactoryPanelBlock.getTargetedSlot(pos, state, hit.getLocation());
        if (level.getBlockEntity(pos) instanceof SignalPanelBlockEntity be
                && be.activePanels() >= 2 && be.panels.get(slot).isActive()) {
            if (!level.isClientSide) {
                ItemStack drop = be.panelItem(slot);
                be.removePanel(slot);
                if (!player.isCreative()) {
                    player.getInventory().placeItemBackInInventory(drop);
                }
                be.sendData();
            }
            return false;
        }
        return super.onDestroyedByPlayer(state, level, pos, player, willHarvest, fluid);
    }

    @Override
    protected MapCodec<? extends SignalPanelBlock> codec() {
        return CODEC;
    }
}
