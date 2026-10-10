package dev.distantstock.item;

import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.ItemUtils;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.UseAnim;
import net.minecraft.world.item.component.CustomModelData;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.BlockHitResult;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.fluids.FluidStack;
import net.neoforged.neoforge.fluids.SimpleFluidContent;
import net.neoforged.neoforge.fluids.capability.IFluidHandler;
import net.neoforged.neoforge.fluids.capability.IFluidHandlerItem;

import java.util.List;

/**
 * A portable tank that holds any fluid and never puts one in the ground.
 *
 * <p>Three tiers of one idea: a vessel that moves fluid between machines by hand. It is filled by a
 * spout, emptied into anything with a fluid handler -- Create's item drain, basin or tank, or
 * another mod's -- and drunk from when what is inside is drinkable. What it deliberately does not do
 * is place a source block: a 5500 mB tank that could would be a bucket worth five and a half
 * buckets, and the mod's bottles already draw that line ("a bottle is a dose, not a bucket").
 *
 * <p>Aimed at something that stores fluid, one right-click transfers; aimed anywhere else, it
 * drinks. Same rule as the bottles, for the same reason: one gesture, one meaning. A tank moves what
 * fits and keeps the rest rather than emptying whole or not at all -- a 5500 mB tank decanted a
 * bucket at a time into a drain is the normal case, not an error -- and a sneak-click asks this item
 * directly for whatever the target has room for, which covers topping up a container that is nearly
 * full, the one thing Create's own bucket-at-a-time path refuses.
 *
 * <p>The three tiers are one class because they differ only in capacity and in which artwork their
 * models name; the shell, the fluid layer and the level logic are identical. Capacity is an instance
 * field, never a shared constant: three items sharing one number is the bug this class replaced.
 */
public final class PortableFluidTankItem extends Item {
    /** What one sip takes, matching a bottle so a tank and a bottle are worth the same mouthful. */
    public static final int DRINK_AMOUNT = 250;

    private static final int DRINK_TICKS = 32;

    /** How many icon variants exist per tier: empty, half, full. See {@link #fluidLevel}. */
    public static final int LEVELS = 3;

    public enum Tier {
        COPPER("copper", 1500),
        STURDY("sturdy", 3500),
        RESONANT("resonant", 5500);

        private final String id;
        private final int capacity;

        Tier(String id, int capacity) {
            this.id = id;
            this.capacity = capacity;
        }

        /** Matches the texture's own suffix, so art and code agree on which tank is which. */
        public String id() {
            return id;
        }

        public int capacity() {
            return capacity;
        }
    }

    private final Tier tier;

    public PortableFluidTankItem(Tier tier, Properties properties) {
        super(properties);
        this.tier = tier;
    }

    public Tier tier() {
        return tier;
    }

    public int capacity() {
        return tier.capacity();
    }

    public static FluidStack fluid(ItemStack stack) {
        return stack.getOrDefault(ModDataComponents.CANISTER_FLUID, SimpleFluidContent.EMPTY).copy();
    }

    /** True for any of the three tiers. */
    public static boolean isTank(ItemStack stack) {
        return stack.getItem() instanceof PortableFluidTankItem;
    }

    // --- what the icon shows ------------------------------------------------------------------

    /**
     * Which icon variant a fluid amount calls for: 0 empty, 1 up to half, 2 more than half.
     *
     * <p>Three states rather than a bar-with-many-steps because the precision is already there --
     * the durability bar under the icon is the real gauge, and it reads in thirteenths. What the
     * icon has to answer is the glance question, "is there anything in there and roughly how much",
     * and two filled states answer it at 16x16 where more would not be tellable apart.
     */
    public static int fluidLevel(FluidStack fluid, int capacity) {
        if (fluid.isEmpty()) return 0;
        return fluid.getAmount() * 2 <= capacity ? 1 : 2;
    }

    /**
     * Keeps {@code custom_model_data} in step with the contents, which is what selects the model.
     *
     * <p>Written only on a change: the component is network-synchronized, and setting it every tick
     * would push a packet per tank per tick for a number that almost never moves.
     */
    public static void writeModelIndex(ItemStack stack, FluidStack fluid, int capacity) {
        int level = fluidLevel(fluid, capacity);
        CustomModelData current = stack.get(DataComponents.CUSTOM_MODEL_DATA);
        if (current == null || current.value() != level) {
            stack.set(DataComponents.CUSTOM_MODEL_DATA, new CustomModelData(level));
        }
    }

    /**
     * Backfill for stacks that did not go through the capability: everything written before this
     * class existed, anything a command or an editor put together, and the case of a tank whose
     * fluid was set by a mod that does not know about the component.
     *
     * <p>Not merely a safety net for old saves -- without it a 1000 mB canister from an existing
     * world would draw as an empty tank until something happened to touch its fluid.
     */
    @Override
    public void inventoryTick(ItemStack stack, Level level, Entity entity, int slot, boolean selected) {
        if (level.isClientSide) return;
        writeModelIndex(stack, fluid(stack), capacity());
    }

    // --- the contents gauge -------------------------------------------------------------------

    @Override
    public boolean isBarVisible(ItemStack stack) {
        FluidStack fluid = fluid(stack);
        return !fluid.isEmpty() && fluid.getAmount() < capacity();
    }

    @Override
    public int getBarWidth(ItemStack stack) {
        FluidStack fluid = fluid(stack);
        return fluid.isEmpty() ? 0
                : Math.min(13, Math.round(13f * fluid.getAmount() / capacity()));
    }

    @Override
    public int getBarColor(ItemStack stack) {
        int tint = TankFluidColors.of(fluid(stack));
        // The bar is only drawn while there is fluid, so there is always a colour here; the opaque
        // fallback is for a fluid whose tint came back transparent.
        return tint == TankFluidColors.NONE || (tint >>> 24) == 0 ? 0xFF6ED2C8 : tint;
    }

    // --- transferring -------------------------------------------------------------------------

    @Override
    public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
        ItemStack stack = player.getItemInHand(hand);
        FluidStack stored = fluid(stack);
        BlockHitResult hit = getPlayerPOVHitResult(level, player, ClipContext.Fluid.NONE);
        IFluidHandler target = null;
        if (hit.getType() == BlockHitResult.Type.BLOCK && level.mayInteract(player, hit.getBlockPos())) {
            target = level.getCapability(Capabilities.FluidHandler.BLOCK, hit.getBlockPos(),
                    hit.getDirection());
        }
        if (target == null) {
            // Nothing here stores fluid, so this click is a drink.
            if (!DrinkableFluids.isDrinkable(stored)) return InteractionResultHolder.fail(stack);
            return ItemUtils.startUsingInstantly(level, player, hand);
        }
        if (stored.isEmpty()) {
            return takeFrom(level, stack, target, hit.getBlockPos());
        }
        return giveTo(level, stack, stored, target, hit.getBlockPos());
    }

    /**
     * The sneak-click pour: everything the target can take, in one go.
     *
     * <p>A plain click on a Create block goes through Create's own container handling, which works a
     * bucket at a time -- {@code GenericItemEmptying.emptyItem} draws at most 1000 mB, and the target
     * only has to have room for that much. That is the right rate for topping up a drain or a basin
     * and it is what a tank does by default; nothing here is needed to make a big tank pour into a
     * small one, because a bucket's worth at a time is exactly how it goes in.
     *
     * <p>What Create's path cannot do is pour <em>less</em> than a bucket. Faced with a target that
     * has room for 400 mB it gives up, and because the block answers such a click with success rather
     * than a pass, the click never reaches this item to be handled another way. Topping up a nearly
     * full container is therefore the one case that needs a gesture of its own, and sneaking is what
     * gets the click here: the engine skips a block's interaction entirely when the player sneaks
     * with an item in hand, which leaves it to {@code useOn}.
     */
    @Override
    public InteractionResult useOn(UseOnContext context) {
        Player player = context.getPlayer();
        if (player == null || !player.isShiftKeyDown()) return InteractionResult.PASS;
        Level level = context.getLevel();
        if (!level.mayInteract(player, context.getClickedPos())) return InteractionResult.PASS;
        IFluidHandler target = level.getCapability(Capabilities.FluidHandler.BLOCK,
                context.getClickedPos(), context.getClickedFace());
        if (target == null) return InteractionResult.PASS;
        ItemStack stack = context.getItemInHand();
        FluidStack stored = fluid(stack);
        if (stored.isEmpty()) {
            return takeFrom(level, stack, target, context.getClickedPos()).getResult();
        }
        return giveTo(level, stack, stored, target, context.getClickedPos()).getResult();
    }

    /** Empties into the target, as much as it will take. */
    private InteractionResultHolder<ItemStack> giveTo(Level level, ItemStack stack,
                                                      FluidStack stored, IFluidHandler target,
                                                      BlockPos at) {
        int accepted = target.fill(stored, IFluidHandler.FluidAction.SIMULATE);
        if (accepted <= 0) return InteractionResultHolder.fail(stack);
        if (level.isClientSide) return InteractionResultHolder.success(stack);
        // Charge the tank for what the target actually took, not for what it promised: an
        // under-delivering handler must never cost the player fluid.
        int moved = target.fill(stored.copyWithAmount(accepted), IFluidHandler.FluidAction.EXECUTE);
        if (moved > 0 && drainItem(stack, moved) <= 0) {
            // The tank refused after the transfer succeeded. Nothing sensible is left to do but say
            // so; this cannot happen while the capability and the item agree on the capacity.
            return InteractionResultHolder.fail(stack);
        }
        level.playSound(null, at, SoundEvents.BUCKET_EMPTY, SoundSource.BLOCKS, 1f, 1f);
        return InteractionResultHolder.success(stack);
    }

    /** Fills from the target. Same partial-transfer rule, the other way round. */
    private InteractionResultHolder<ItemStack> takeFrom(Level level, ItemStack stack,
                                                        IFluidHandler target,
                                                        BlockPos at) {
        IFluidHandlerItem item = stack.getCapability(Capabilities.FluidHandler.ITEM);
        if (item == null) return InteractionResultHolder.fail(stack);
        FluidStack offered = target.drain(capacity(), IFluidHandler.FluidAction.SIMULATE);
        if (offered.isEmpty()) return InteractionResultHolder.fail(stack);
        int accepted = item.fill(offered, IFluidHandler.FluidAction.SIMULATE);
        if (accepted <= 0) return InteractionResultHolder.fail(stack);
        if (level.isClientSide) return InteractionResultHolder.success(stack);
        // Take only after the tank has agreed to it, and only as much as it agreed to.
        FluidStack taken = target.drain(offered.copyWithAmount(accepted), IFluidHandler.FluidAction.EXECUTE);
        if (taken.isEmpty()) return InteractionResultHolder.fail(stack);
        item.fill(taken, IFluidHandler.FluidAction.EXECUTE);
        level.playSound(null, at, SoundEvents.BUCKET_FILL, SoundSource.BLOCKS, 1f, 1f);
        return InteractionResultHolder.success(stack);
    }

    /** Drains the tank through its capability, so the icon level follows. Returns what moved. */
    private static int drainItem(ItemStack stack, int amount) {
        IFluidHandlerItem handler = stack.getCapability(Capabilities.FluidHandler.ITEM);
        if (handler == null) return 0;
        return handler.drain(amount, IFluidHandler.FluidAction.EXECUTE).getAmount();
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
    public ItemStack finishUsingItem(ItemStack stack, Level level, LivingEntity entity) {
        // Read the contents before draining: what was drunk is what has to be applied, and by then
        // the tank holds the remainder.
        FluidStack stored = fluid(stack);
        if (!level.isClientSide && stored.getAmount() >= DRINK_AMOUNT) {
            if (drainItem(stack, DRINK_AMOUNT) == DRINK_AMOUNT) {
                DrinkableFluids.applyDrink(level, entity, stored);
                level.playSound(null, entity.getX(), entity.getY(), entity.getZ(),
                        SoundEvents.GENERIC_DRINK, SoundSource.NEUTRAL, 1f, 1f);
            }
        }
        // The tank is the container, so it stays: no glass bottle, no item swap.
        return stack;
    }

    // --- tooltip ------------------------------------------------------------------------------

    @Override
    public void appendHoverText(ItemStack stack, TooltipContext context, List<Component> tooltip,
                                TooltipFlag flag) {
        super.appendHoverText(stack, context, tooltip, flag);
        FluidStack fluid = fluid(stack);
        if (fluid.isEmpty()) {
            tooltip.add(Component.translatable("item.distantstock.fluid_tank.empty", capacity())
                    .withStyle(ChatFormatting.DARK_GRAY));
        } else {
            tooltip.add(Component.translatable("item.distantstock.fluid_tank.contents",
                            fluid.getHoverName(), fluid.getAmount(), capacity())
                    .withStyle(ChatFormatting.GRAY));
            if (DrinkableFluids.isDrinkable(fluid)) {
                tooltip.add(Component.translatable("item.distantstock.fluid_tank.drink", DRINK_AMOUNT)
                        .withStyle(ChatFormatting.GREEN));
            }
            DrinkableFluids.WaterBridge bridge = DrinkableFluids.waterBridge();
            Component purity = bridge == null ? null : bridge.describePurity(fluid);
            if (purity != null) tooltip.add(purity);
            // Two batches of the same fluid with different data do not mix -- the capability is
            // strict about components on purpose, because relaxing it would quietly average away a
            // purity. Saying so is the least a player deserves when a spout seems to do nothing.
            if (!fluid.getComponentsPatch().isEmpty()) {
                tooltip.add(Component.translatable("item.distantstock.fluid_tank.mixed")
                        .withStyle(ChatFormatting.DARK_GRAY));
            }
        }
        tooltip.add(Component.translatable("item.distantstock.fluid_tank.usage")
                .withStyle(ChatFormatting.DARK_GRAY));
    }
}
