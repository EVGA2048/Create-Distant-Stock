package dev.distantstock.item;

import net.minecraft.core.BlockPos;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.ItemUtils;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.UseAnim;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.phys.BlockHitResult;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.fluids.FluidStack;
import net.neoforged.neoforge.fluids.capability.IFluidHandler;

/**
 * A bottle holds a quarter of a bucket.
 *
 * Vanilla's {@code BucketItem} always places a full source block, which would make one bottle worth
 * a thousand millibuckets. This one empties into whatever actually stores fluid, a basin or a tank,
 * and refuses to source the ground: a bottle is a dose, not a bucket.
 *
 * Aimed at something that stores fluid it pours; aimed anywhere else it drinks. That split keeps
 * one right-click meaning one thing, and it is why drinking needs no modifier key.
 */
public final class FluidBottleItem extends Item {
    /** What one bottle holds. */
    public static final int MILLIBUCKETS = 250;

    /** A full block is eight fluid units, so a 250mB bottle is two of them. */
    public static final int AMOUNT = 2;

    private static final int DRINK_TICKS = 32;

    private final Fluid content;

    public FluidBottleItem(Fluid content, Properties properties) {
        super(properties);
        this.content = content;
    }

    public Fluid content() {
        return content;
    }

    @Override
    public void appendHoverText(ItemStack stack, TooltipContext context,
                                java.util.List<net.minecraft.network.chat.Component> tooltip,
                                TooltipFlag flag) {
        super.appendHoverText(stack, context, tooltip, flag);
        tooltip.add(net.minecraft.network.chat.Component.translatable(getDescriptionId() + ".usage")
                .withStyle(net.minecraft.ChatFormatting.GRAY));
    }

    // --- pouring ------------------------------------------------------------------------------

    @Override
    public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
        ItemStack stack = player.getItemInHand(hand);
        BlockHitResult hit = getPlayerPOVHitResult(level, player, net.minecraft.world.level.ClipContext.Fluid.NONE);
        IFluidHandler target = null;
        if (hit.getType() == BlockHitResult.Type.BLOCK && level.mayInteract(player, hit.getBlockPos())) {
            target = level.getCapability(Capabilities.FluidHandler.BLOCK, hit.getBlockPos(), hit.getDirection());
        }
        if (target == null) {
            // Nothing here stores fluid, so this click is a drink rather than a pour.
            return ItemUtils.startUsingInstantly(level, player, hand);
        }
        FluidStack pour = new FluidStack(content, MILLIBUCKETS);
        if (target.fill(pour, IFluidHandler.FluidAction.SIMULATE) < MILLIBUCKETS) {
            // A bottle empties whole or not at all; a partial pour would silently destroy fluid.
            return InteractionResultHolder.fail(stack);
        }
        if (level.isClientSide) {
            return InteractionResultHolder.success(stack);
        }
        target.fill(pour, IFluidHandler.FluidAction.EXECUTE);
        level.playSound(null, hit.getBlockPos(), SoundEvents.BOTTLE_EMPTY, SoundSource.BLOCKS, 1.0F, 1.0F);
        return InteractionResultHolder.success(
                ItemUtils.createFilledResult(stack, player, new ItemStack(Items.GLASS_BOTTLE)));
    }

    // --- drinking -----------------------------------------------------------------------------

    @Override
    public UseAnim getUseAnimation(ItemStack stack) {
        return UseAnim.DRINK;
    }

    @Override
    public int getUseDuration(ItemStack stack, LivingEntity entity) {
        return DRINK_TICKS;
    }

    @Override
    public net.minecraft.world.item.ItemStack finishUsingItem(ItemStack stack, Level level, LivingEntity entity) {
        if (!level.isClientSide) {
            applyEffect(level, entity);
            level.playSound(null, entity.getX(), entity.getY(), entity.getZ(),
                    drinkSound(), SoundSource.NEUTRAL, 1.0F, 1.0F);
        }
        if (entity instanceof Player player) {
            return ItemUtils.createFilledResult(stack, player, new ItemStack(Items.GLASS_BOTTLE));
        }
        stack.shrink(1);
        return stack;
    }

    /**
     * What a mouthful of this fluid does.
     *
     * Left deliberately empty for now: the effect is not decided yet, and inventing one would be
     * harder to notice than having none. Everything else about drinking works, so this is the one
     * place to fill in when the design lands. Thirst integration would go here too, but it has to
     * target a specific mod, which is a decision rather than an implementation detail.
     */
    private void applyEffect(Level level, LivingEntity entity) {
    }

    private SoundEvent drinkSound() {
        return content == dev.distantstock.fluid.ModFluids.MOLTEN_AMETHYST.get()
                ? SoundEvents.LAVA_EXTINGUISH
                : SoundEvents.GENERIC_DRINK;
    }
}
