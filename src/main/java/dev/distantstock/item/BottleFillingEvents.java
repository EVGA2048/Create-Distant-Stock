package dev.distantstock.item;

import dev.distantstock.DistantStock;
import dev.distantstock.fluid.ModFluids;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.level.material.FluidState;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import net.neoforged.neoforge.fluids.FluidStack;
import net.neoforged.neoforge.fluids.capability.IFluidHandler;

/**
 * Filling a glass bottle from an ether fluid.
 *
 * Buckets get this for free because {@code Fluid#getBucket} points at them; a bottle has no such
 * hook, so the glass bottle is intercepted before vanilla can decide the block is not water.
 *
 * Three ways out of a fluid, then: right-clicking a running stream or a container with a bottle,
 * pouring one back out with {@link FluidBottleItem}, and drinking it.
 */
@EventBusSubscriber(modid = DistantStock.MODID)
public final class BottleFillingEvents {
    @SubscribeEvent(priority = EventPriority.LOW)
    public static void fill(PlayerInteractEvent.RightClickBlock event) {
        ItemStack stack = event.getItemStack();
        if (!stack.is(Items.GLASS_BOTTLE)) {
            return;
        }
        Level level = event.getLevel();
        BlockPos pos = event.getPos();
        if (!level.mayInteract(event.getEntity(), pos)
                || !event.getEntity().mayUseItemAt(pos, event.getHitVec().getDirection(), stack)) {
            return;
        }
        // A basin or a tank first. Bottling a running stream is the rarer case, and a container is
        // the only way to get the fluid back out of one, so it wins the click.
        if (scoop(level, pos, event.getHitVec().getDirection(), stack, event.getEntity())) {
            event.setCancellationResult(InteractionResult.sidedSuccess(level.isClientSide));
            event.setCanceled(true);
            return;
        }
        ItemStack filled = filledBottle(level, pos);
        if (filled.isEmpty()) {
            return;
        }
        if (!level.isClientSide) {
            drain(level, pos);
            if (!event.getEntity().isCreative()) {
                stack.shrink(1);
            }
            if (!event.getEntity().getInventory().add(filled)) {
                event.getEntity().drop(filled, false);
            }
            level.playSound(null, pos, SoundEvents.BOTTLE_FILL, SoundSource.BLOCKS, 1.0F, 1.0F);
        }
        event.setCancellationResult(InteractionResult.sidedSuccess(level.isClientSide));
        event.setCanceled(true);
    }

    /**
     * Fills from a container, basin or tank. True when the click was handled.
     *
     * Drains a bottle's worth of whatever is in there rather than asking for a named fluid, so the
     * same code serves both of ours and leaves anything else alone.
     */
    private static boolean scoop(Level level, BlockPos pos, net.minecraft.core.Direction side,
                                 ItemStack bottle, Player player) {
        IFluidHandler tank = level.getCapability(Capabilities.FluidHandler.BLOCK, pos, side);
        if (tank == null) {
            return false;
        }
        FluidStack drained = tank.drain(FluidBottleItem.MILLIBUCKETS, IFluidHandler.FluidAction.SIMULATE);
        ItemStack filled = bottleFor(drained.getFluid());
        if (filled.isEmpty() || drained.getAmount() < FluidBottleItem.MILLIBUCKETS) {
            return false;
        }
        if (!level.isClientSide) {
            tank.drain(FluidBottleItem.MILLIBUCKETS, IFluidHandler.FluidAction.EXECUTE);
            if (!player.isCreative()) {
                bottle.shrink(1);
            }
            if (!player.getInventory().add(filled)) {
                player.drop(filled, false);
            }
            level.playSound(null, pos, SoundEvents.BOTTLE_FILL, SoundSource.BLOCKS, 1.0F, 1.0F);
        }
        return true;
    }

    /**
     * Takes exactly one bottle's worth out of the block. A full source drops to three quarters
     * rather than vanishing, so filling never destroys fluid the player paid for.
     */
    private static void drain(Level level, BlockPos pos) {
        FluidState state = level.getFluidState(pos);
        int left = state.getAmount() - FluidBottleItem.AMOUNT;
        if (left <= 0) {
            level.setBlock(pos, Blocks.AIR.defaultBlockState(), 3);
            return;
        }
        level.setBlock(pos, state.createLegacyBlock()
                .setValue(LiquidBlock.LEVEL, FluidState.AMOUNT_FULL - left), 3);
    }

    /** The bottle our fluid fills into, or empty for anything else. */
    private static ItemStack filledBottle(Level level, BlockPos pos) {
        return bottleFor(level.getFluidState(pos).getType());
    }

    /** The bottle a given fluid fills into, or empty for anything that is not ours. */
    private static ItemStack bottleFor(Fluid fluid) {
        if (fluid.isSame(ModFluids.ETHER.get())) {
            return new ItemStack(ModItems.ETHER_BOTTLE.get());
        }
        if (fluid.isSame(ModFluids.MOLTEN_AMETHYST.get())) {
            return new ItemStack(ModItems.MOLTEN_AMETHYST_BOTTLE.get());
        }
        return ItemStack.EMPTY;
    }

    private BottleFillingEvents() {
    }
}
