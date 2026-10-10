package dev.distantstock;

import dev.distantstock.block.ModBlocks;
import dev.distantstock.item.EtherCasingArmorItem;
import dev.distantstock.item.ModItems;
import dev.distantstock.item.PortableFluidTankItem;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.level.material.Fluids;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.fluids.FluidStack;
import net.neoforged.neoforge.fluids.capability.IFluidHandler;
import net.neoforged.neoforge.fluids.capability.IFluidHandlerItem;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.List;

/**
 * The three portable tanks: what they hold, what they never do, and what drinking costs.
 *
 * <p>The headline invariant here is {@link #pouringNeverPlacesASourceBlock}. A 5500 mB vessel that
 * could empty itself into the world would be a bucket worth five and a half buckets, so the tank is
 * written to only ever talk to {@code IFluidHandler}s; that is easy to state and easy to break by
 * reaching for a convenience method, which is why it has a test.
 *
 * <p>The tests fill and drain through {@code Capabilities.FluidHandler.ITEM} rather than by poking
 * the data component, because that capability is the seam everything else uses -- Create's spout and
 * item drain included -- and a component that only the tests can write would prove nothing.
 */
@GameTestHolder("distantstock")
@PrefixGameTestTemplate(false)
public final class FluidTankGameTests {
    /** The three tiers as the item registry knows them, with their capacities. */
    private static final List<PortableFluidTankItem.Tier> TIERS =
            List.of(PortableFluidTankItem.Tier.COPPER,
                    PortableFluidTankItem.Tier.STURDY,
                    PortableFluidTankItem.Tier.RESONANT);

    private static ItemStack tank(PortableFluidTankItem.Tier tier) {
        for (var holder : ModItems.FLUID_TANKS) {
            if (holder.get().tier() == tier) return new ItemStack(holder.get());
        }
        throw new IllegalStateException("no item registered for " + tier);
    }

    private static IFluidHandlerItem handler(ItemStack stack) {
        return stack.getCapability(Capabilities.FluidHandler.ITEM);
    }

    private static Fluid fluid(String id) {
        Fluid fluid = BuiltInRegistries.FLUID.get(ResourceLocation.parse(id));
        if (fluid == null || fluid == Fluids.EMPTY) throw new IllegalStateException("no fluid " + id);
        return fluid;
    }

    @GameTest(template = "empty", timeoutTicks = 40)
    public static void everyTierTakesAnyFluid(GameTestHelper h) {
        // Three fluids that between them cover the three ways this used to be refused: the one fluid
        // the canister was built for, one it would never have accepted, and one from another mod.
        List<Fluid> fluids = List.of(Fluids.WATER, Fluids.LAVA, fluid("create:tea"));
        for (PortableFluidTankItem.Tier tier : TIERS) {
            for (Fluid content : fluids) {
                ItemStack stack = tank(tier);
                IFluidHandlerItem handler = handler(stack);
                h.assertTrue(handler != null, tier + ": no fluid capability");
                h.assertTrue(handler.getTankCapacity(0) == tier.capacity(),
                        tier + ": capacity is " + handler.getTankCapacity(0));
                int filled = handler.fill(new FluidStack(content, tier.capacity()),
                        IFluidHandler.FluidAction.EXECUTE);
                h.assertTrue(filled == tier.capacity(),
                        tier + " refused " + BuiltInRegistries.FLUID.getKey(content) + ": took " + filled);
                h.assertTrue(PortableFluidTankItem.fluid(stack).is(content),
                        tier + ": contents are not what was poured in");
            }
        }
        h.succeed();
    }

    /**
     * Two batches of the same fluid carrying different data do not mix.
     *
     * <p>This is NeoForge's {@code isSameFluidSameComponents}, inherited rather than overridden, and
     * it is worth a test because relaxing it looks like an improvement: it would let a tank of plain
     * water be topped up from a source of purified water, and it would silently average the purity
     * away. Whoever "fixes" that has to delete this test first.
     */
    @GameTest(template = "empty", timeoutTicks = 40)
    public static void sameFluidWithDifferentComponentsWillNotMix(GameTestHelper h) {
        ItemStack stack = tank(PortableFluidTankItem.Tier.COPPER);
        IFluidHandlerItem handler = handler(stack);
        handler.fill(new FluidStack(Fluids.WATER, 500), IFluidHandler.FluidAction.EXECUTE);

        FluidStack tagged = new FluidStack(Fluids.WATER, 1000);
        tagged.set(DataComponents.CUSTOM_NAME, Component.literal("clean"));
        int accepted = handler.fill(tagged, IFluidHandler.FluidAction.EXECUTE);
        h.assertTrue(accepted == 0, "a differently-tagged batch was mixed in: took " + accepted);
        h.assertTrue(PortableFluidTankItem.fluid(stack).getAmount() == 500,
                "the contents changed anyway");

        // ...and the plain half still tops up, so the strictness is about components, not about
        // refusing the second pour of anything at all.
        int plain = handler.fill(new FluidStack(Fluids.WATER, 1000), IFluidHandler.FluidAction.EXECUTE);
        h.assertTrue(plain == 1000, "plain water no longer tops up a plain water tank: " + plain);
        h.succeed();
    }

    /**
     * Create's own filling path, which is what a spout actually runs.
     *
     * <p>Worth driving directly: it is {@code GenericItemFilling} that decides whether an item can be
     * filled at all, and it is the step that would stop working if the capability or the item count
     * ever disagreed.
     */
    @GameTest(template = "empty", timeoutTicks = 40)
    public static void aSpoutFillsTheTank(GameTestHelper h) {
        var level = h.getLevel();
        ItemStack stack = tank(PortableFluidTankItem.Tier.STURDY);
        FluidStack pour = new FluidStack(Fluids.WATER, 1000);
        h.assertTrue(com.simibubi.create.content.fluids.transfer.GenericItemFilling
                        .canItemBeFilled(level, stack),
                "a spout does not think the tank can be filled");
        // A spout asks for one bucket's worth at a time, whatever the container's capacity is (the
        // amount comes from a 1000 mB probe, not from the tank). A big tank therefore fills over
        // several passes under the spout, which is the same "as much as fits, keep the rest" rule the
        // tank follows when it pours.
        h.assertTrue(com.simibubi.create.content.fluids.transfer.GenericItemFilling
                        .getRequiredAmountForItem(level, stack, pour) == 1000,
                "a spout's step is no longer one bucket: "
                        + com.simibubi.create.content.fluids.transfer.GenericItemFilling
                        .getRequiredAmountForItem(level, stack, pour));
        ItemStack filled = com.simibubi.create.content.fluids.transfer.GenericItemFilling
                .fillItem(level, 1000, stack, pour);
        h.assertTrue(PortableFluidTankItem.fluid(filled).getAmount() == 1000,
                "the spout path did not fill the tank: " + PortableFluidTankItem.fluid(filled));
        h.succeed();
    }

    /**
     * The plain right-click pour, which is Create's own: one bucket at a time, remainder kept.
     *
     * <p>This is the case a big tank lives in. A drain holds 1500 mB and a basin 1000 per tank, so a
     * 3500 mB tank never fits anywhere whole; the pour has to be incremental or it never happens at
     * all -- and Create already does it that way. {@code GenericItemEmptying.emptyItem} draws at most
     * 1000 mB, and the target only needs room for that much, so a bucket goes in per operation and
     * the rest stays. On a drain, whose own logic retries when it cannot take a bucket, that reads as
     * "pour a batch, wait for the pipes to carry it away, pour the next".
     *
     * <p>Pinned here because it is easy to break from this side: making the item's {@code drain} hand
     * over everything at once would look like a kindness and would make the tank impossible to empty
     * into anything smaller than itself.
     */
    @GameTest(template = "empty", timeoutTicks = 40)
    public static void createPoursTheTankOneBucketAtATime(GameTestHelper h) {
        var level = h.getLevel();
        BlockPos pos = h.absolutePos(new BlockPos(2, 2, 2));
        var basinBlock = BuiltInRegistries.BLOCK.get(ResourceLocation.parse("create:basin"));
        h.assertTrue(basinBlock != Blocks.AIR, "create:basin is missing");
        level.setBlockAndUpdate(pos, basinBlock.defaultBlockState());
        var basin = level.getBlockEntity(pos);
        h.assertTrue(basin instanceof com.simibubi.create.content.processing.basin.BasinBlockEntity,
                "no basin block entity");

        var player = h.makeMockPlayer(GameType.SURVIVAL);
        ItemStack stack = tank(PortableFluidTankItem.Tier.RESONANT);
        handler(stack).fill(new FluidStack(Fluids.WATER, 3500), IFluidHandler.FluidAction.EXECUTE);
        player.setItemInHand(InteractionHand.MAIN_HAND, stack);

        h.assertTrue(pourByHand(h, player, basin),
                "Create's hand-pour refused a tank it had room for a bucket of");
        // Read the hand back rather than the local variable: Create works on a copy of the stack and
        // writes the result into the inventory, so the object passed in is stale.
        ItemStack after = player.getItemInHand(InteractionHand.MAIN_HAND);
        h.assertTrue(PortableFluidTankItem.fluid(after).getAmount() == 2500,
                "one pour moved " + (3500 - PortableFluidTankItem.fluid(after).getAmount())
                        + " mB; a bucket is 1000");

        IFluidHandler block = level.getCapability(Capabilities.FluidHandler.BLOCK, pos, null);
        h.assertTrue(block != null, "the basin exposes no fluid handler");
        h.assertTrue(total(block) == 1000, "the basin received " + total(block) + " mB");

        // Then the basin is emptied -- by a pipe, in play -- and the very next pour moves another
        // bucket. That is the whole loop a player with a big tank lives in: pour a batch, let
        // something take it away, pour the next.
        block.drain(1000, IFluidHandler.FluidAction.EXECUTE);
        h.assertTrue(total(block) == 0, "draining the basin left " + total(block) + " mB");
        h.assertTrue(pourByHand(h, player, basin), "the basin refused a second bucket once it was empty");
        ItemStack twice = player.getItemInHand(InteractionHand.MAIN_HAND);
        h.assertTrue(PortableFluidTankItem.fluid(twice).getAmount() == 1500,
                "two pours moved " + (3500 - PortableFluidTankItem.fluid(twice).getAmount()) + " mB");

        // Filled to the brim, a pour either moves a whole bucket or moves nothing. Never a partial
        // charge: the tank gives fluid up in buckets and keeps the rest, so a target with 400 mB of
        // room is left alone rather than half-paid.
        block.fill(new FluidStack(Fluids.WATER, 100000), IFluidHandler.FluidAction.EXECUTE);
        int before = PortableFluidTankItem.fluid(
                player.getItemInHand(InteractionHand.MAIN_HAND)).getAmount();
        boolean accepted = pourByHand(h, player, basin);
        int lost = before - PortableFluidTankItem.fluid(
                player.getItemInHand(InteractionHand.MAIN_HAND)).getAmount();
        h.assertTrue(accepted ? lost == 1000 : lost == 0,
                "a pour charged the tank " + lost + " mB (accepted=" + accepted + ")");
        h.succeed();
    }

    private static int total(IFluidHandler handler) {
        int sum = 0;
        for (int i = 0; i < handler.getTanks(); i++) sum += handler.getFluidInTank(i).getAmount();
        return sum;
    }

    private static boolean pourByHand(GameTestHelper h,
                                      net.minecraft.world.entity.player.Player player,
                                      net.minecraft.world.level.block.entity.BlockEntity be) {
        return com.simibubi.create.foundation.fluid.FluidHelper.tryEmptyItemIntoBE(
                h.getLevel(), player, InteractionHand.MAIN_HAND,
                player.getItemInHand(InteractionHand.MAIN_HAND),
                (com.simibubi.create.foundation.blockEntity.SmartBlockEntity) be);
    }

    /**
     * A spout tops a tank up rather than insisting on a whole bucket, and a full one asks for nothing.
     *
     * <p>The amount a spout wants is what the item would take from a 1000 mB probe, not a fixed
     * thousand: a tank with 150 mB of room asks for 150 and reaches 3500 exactly. Pinned because it
     * is a property of what our capability answers rather than of the spout -- a handler that
     * reported "1500, take it or leave it" would leave every part-filled tank unfillable.
     */
    @GameTest(template = "empty", timeoutTicks = 40)
    public static void aSpoutTopsUpAPartialTankExactly(GameTestHelper h) {
        var level = h.getLevel();
        ItemStack stack = tank(PortableFluidTankItem.Tier.STURDY);
        FluidStack pour = new FluidStack(Fluids.WATER, 1000);
        handler(stack).fill(new FluidStack(Fluids.WATER, 3350), IFluidHandler.FluidAction.EXECUTE);

        int wanted = com.simibubi.create.content.fluids.transfer.GenericItemFilling
                .getRequiredAmountForItem(level, stack, pour);
        h.assertTrue(wanted == 150, "a tank with 150 mB of room asked the spout for " + wanted);
        ItemStack filled = com.simibubi.create.content.fluids.transfer.GenericItemFilling
                .fillItem(level, wanted, stack, pour);
        h.assertTrue(PortableFluidTankItem.fluid(filled).getAmount() == 3500,
                "topping up left the tank at " + PortableFluidTankItem.fluid(filled).getAmount());

        h.assertTrue(com.simibubi.create.content.fluids.transfer.GenericItemFilling
                        .getRequiredAmountForItem(level, filled, pour) == -1,
                "a full tank still asked a spout for fluid");
        // Filling a full tank changes nothing: it takes none of the fluid and keeps what it has.
        ItemStack overflow = com.simibubi.create.content.fluids.transfer.GenericItemFilling
                .fillItem(level, 1000, filled, pour);
        h.assertTrue(PortableFluidTankItem.fluid(overflow).getAmount() == 3500,
                "a full tank ended up holding " + PortableFluidTankItem.fluid(overflow).getAmount());
        h.succeed();
    }

    /**
     * A container with less room than a bucket is topped up by sneaking, and only by sneaking.
     *
     * <p>A plain click belongs to Create's own container handling, which works a bucket at a time and
     * refuses outright when the target has less room than that; the block answers such a click with
     * success rather than a pass, so the click never reaches the item. Sneaking does: the engine
     * skips a block's interaction entirely when the player sneaks with an item in hand, which leaves
     * the click to {@code useOn}. This gesture is what fills the last 400 mB of a container.
     */
    @GameTest(template = "empty", timeoutTicks = 40)
    public static void theSneakPourLeavesTheRemainderInTheTank(GameTestHelper h) {
        var level = h.getLevel();
        BlockPos pos = h.absolutePos(new BlockPos(2, 2, 2));
        // Looked up by id rather than through AllBlocks: its entries are registrate's BlockEntry,
        // which lives in a jar-in-jar and is not on this source set's classpath.
        var basinBlock = BuiltInRegistries.BLOCK.get(ResourceLocation.parse("create:basin"));
        h.assertTrue(basinBlock != Blocks.AIR, "create:basin is missing");
        level.setBlockAndUpdate(pos, basinBlock.defaultBlockState());
        var basin = level.getBlockEntity(pos);
        h.assertTrue(basin instanceof com.simibubi.create.content.processing.basin.BasinBlockEntity,
                "no basin block entity");

        var player = h.makeMockPlayer(GameType.SURVIVAL);
        ItemStack stack = tank(PortableFluidTankItem.Tier.RESONANT);
        handler(stack).fill(new FluidStack(Fluids.WATER, 2000), IFluidHandler.FluidAction.EXECUTE);
        player.setItemInHand(InteractionHand.MAIN_HAND, stack);
        player.setShiftKeyDown(true);

        // Sneaking is what gets the click here at all: the engine skips a block's own interaction
        // when the player sneaks with an item in hand, and Create's basin would otherwise answer the
        // click itself and refuse it whole (2000 mB will not fit in one 1000 mB tank).
        InteractionResult result = stack.getItem().useOn(new UseOnContext(player, InteractionHand.MAIN_HAND,
                new BlockHitResult(Vec3.atCenterOf(pos), Direction.UP, pos, false)));
        h.assertTrue(result.consumesAction(), "the sneak pour did not take the click");
        FluidStack left = PortableFluidTankItem.fluid(stack);
        h.assertTrue(left.getAmount() < 2000, "nothing left the tank: " + left.getAmount());
        IFluidHandler block = level.getCapability(Capabilities.FluidHandler.BLOCK, pos, null);
        h.assertTrue(block != null, "the basin exposes no fluid handler");
        int stored = 0;
        for (int i = 0; i < block.getTanks(); i++) stored += block.getFluidInTank(i).getAmount();
        h.assertTrue(stored > 0, "the basin received nothing; the tank kept " + left.getAmount()
                + " of 2000 and reports " + fluidLevelReport(stack));
        h.succeed();
    }

    private static String fluidLevelReport(ItemStack stack) {
        return "level " + modelIndex(stack);
    }

    /**
     * The one thing a tank must never do.
     *
     * <p>Both fluids a player is most likely to try are checked, and the assertion is about the
     * world, not about the item: aiming at plain stone leaves no fluid and no change of block.
     */
    @GameTest(template = "empty", timeoutTicks = 40)
    public static void pouringNeverPlacesASourceBlock(GameTestHelper h) {
        var level = h.getLevel();
        BlockPos floor = h.absolutePos(new BlockPos(2, 2, 2));
        level.setBlockAndUpdate(floor, Blocks.STONE.defaultBlockState());

        var player = h.makeMockPlayer(GameType.SURVIVAL);
        for (Fluid content : List.of(Fluids.WATER, Fluids.LAVA)) {
            ItemStack stack = tank(PortableFluidTankItem.Tier.COPPER);
            handler(stack).fill(new FluidStack(content, 1000), IFluidHandler.FluidAction.EXECUTE);
            player.setItemInHand(InteractionHand.MAIN_HAND, stack);
            player.setPos(floor.getX() + 0.5, floor.getY() + 2, floor.getZ() + 0.5);
            player.setXRot(90f);
            player.setYRot(0f);

            stack.getItem().use(level, player, InteractionHand.MAIN_HAND);

            h.assertTrue(level.getFluidState(floor).isEmpty()
                            && level.getBlockState(floor).is(Blocks.STONE),
                    "a tank of " + BuiltInRegistries.FLUID.getKey(content)
                            + " changed the world at " + floor);
            h.assertTrue(PortableFluidTankItem.fluid(stack).getAmount() == 1000,
                    "the click destroyed fluid without pouring it anywhere");
        }
        h.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 40)
    public static void drinkingTakesExactlyOneSip(GameTestHelper h) {
        var level = h.getLevel();
        var player = h.makeMockPlayer(GameType.SURVIVAL);
        ItemStack stack = tank(PortableFluidTankItem.Tier.COPPER);
        handler(stack).fill(new FluidStack(Fluids.WATER, 1000), IFluidHandler.FluidAction.EXECUTE);

        ItemStack after = stack.getItem().finishUsingItem(stack, level, player);
        h.assertTrue(after == stack, "drinking swapped the item for another one");
        h.assertTrue(PortableFluidTankItem.fluid(stack).getAmount() == 1000 - PortableFluidTankItem.DRINK_AMOUNT,
                "one sip took " + (1000 - PortableFluidTankItem.fluid(stack).getAmount()) + " mB");
        h.assertTrue(handler(stack).getFluidInTank(0).getAmount() == 750, "the capability disagrees");

        // A tank holding less than a sip is left alone rather than emptied for nothing.
        ItemStack nearlyEmpty = tank(PortableFluidTankItem.Tier.COPPER);
        handler(nearlyEmpty).fill(new FluidStack(Fluids.WATER, 100), IFluidHandler.FluidAction.EXECUTE);
        nearlyEmpty.getItem().finishUsingItem(nearlyEmpty, level, player);
        h.assertTrue(PortableFluidTankItem.fluid(nearlyEmpty).getAmount() == 100,
                "a tank with less than a sip in it was drained anyway");
        h.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 40)
    public static void anUndrinkableFluidRefusesTheDrink(GameTestHelper h) {
        var level = h.getLevel();
        BlockPos air = h.absolutePos(new BlockPos(2, 4, 2));
        var player = h.makeMockPlayer(GameType.SURVIVAL);
        ItemStack stack = tank(PortableFluidTankItem.Tier.COPPER);
        handler(stack).fill(new FluidStack(Fluids.LAVA, 1000), IFluidHandler.FluidAction.EXECUTE);
        player.setItemInHand(InteractionHand.MAIN_HAND, stack);
        player.setPos(air.getX() + 0.5, air.getY(), air.getZ() + 0.5);
        player.setXRot(-90f);

        var result = stack.getItem().use(level, player, InteractionHand.MAIN_HAND);
        h.assertTrue(result.getResult() == InteractionResult.FAIL,
                "lava started a drink: " + result.getResult());
        h.assertTrue(!player.isUsingItem(), "an undrinkable fluid put the player into the drink pose");
        h.succeed();
    }

    /**
     * A drink keeps what it does when it moves into a tank.
     *
     * <p>Three drinks, three different ways for the effect to be carried, and all three have to
     * arrive: a potion carries its effects as a component on the fluid stack, honey is defined by
     * what it cures, and Create's tea only says what it does through the item it fills into.
     */
    @GameTest(template = "empty", timeoutTicks = 40)
    public static void drinksKeepTheirEffectsInATank(GameTestHelper h) {
        var level = h.getLevel();

        // A potion's effects ride on the fluid. Storing it and drinking it must not lose them.
        var player = h.makeMockPlayer(GameType.SURVIVAL);
        ItemStack potionTank = tank(PortableFluidTankItem.Tier.COPPER);
        FluidStack potion = new FluidStack(fluid("create:potion"), 250);
        potion.set(DataComponents.POTION_CONTENTS,
                new net.minecraft.world.item.alchemy.PotionContents(net.minecraft.world.item.alchemy.Potions.NIGHT_VISION));
        handler(potionTank).fill(potion, IFluidHandler.FluidAction.EXECUTE);
        h.assertTrue(PortableFluidTankItem.fluid(potionTank).has(DataComponents.POTION_CONTENTS),
                "the tank dropped the potion's contents on the way in");
        potionTank.getItem().finishUsingItem(potionTank, level, player);
        h.assertTrue(player.hasEffect(net.minecraft.world.effect.MobEffects.NIGHT_VISION),
                "a potion drunk from a tank did nothing");

        // Honey is what it cures, and it is food besides.
        var honeyDrinker = h.makeMockPlayer(GameType.SURVIVAL);
        honeyDrinker.addEffect(new net.minecraft.world.effect.MobEffectInstance(
                net.minecraft.world.effect.MobEffects.POISON, 200));
        honeyDrinker.getFoodData().setFoodLevel(10);
        ItemStack honey = tank(PortableFluidTankItem.Tier.COPPER);
        handler(honey).fill(new FluidStack(fluid("create:honey"), 250), IFluidHandler.FluidAction.EXECUTE);
        honey.getItem().finishUsingItem(honey, level, honeyDrinker);
        h.assertTrue(!honeyDrinker.hasEffect(net.minecraft.world.effect.MobEffects.POISON),
                "honey drunk from a tank did not cure poison");
        h.assertTrue(honeyDrinker.getFoodData().getFoodLevel() > 10,
                "honey drunk from a tank did not feed anyone");

        // Tea says nothing about itself on the stack; its meaning is the item it fills into, which
        // is why the tank asks Create's filling recipes.
        var teaDrinker = h.makeMockPlayer(GameType.SURVIVAL);
        ItemStack tea = tank(PortableFluidTankItem.Tier.COPPER);
        handler(tea).fill(new FluidStack(fluid("create:tea"), 250), IFluidHandler.FluidAction.EXECUTE);
        tea.getItem().finishUsingItem(tea, level, teaDrinker);
        h.assertTrue(teaDrinker.hasEffect(net.minecraft.world.effect.MobEffects.DIG_SPEED),
                "tea drunk from a tank was just coloured water");
        h.succeed();
    }

    /** The icon's level follows the contents, which is what selects the model. */
    @GameTest(template = "empty", timeoutTicks = 40)
    public static void theIconLevelFollowsTheContents(GameTestHelper h) {
        for (PortableFluidTankItem.Tier tier : TIERS) {
            int capacity = tier.capacity();
            ItemStack stack = tank(tier);
            IFluidHandlerItem handler = handler(stack);
            // A fresh stack carries no component at all, which draws the root model -- and the root
            // model is the empty one. There is no reason to write a zero onto every tank ever
            // crafted just to say so, so absent and 0 are the same answer here.
            h.assertTrue(modelIndex(stack) == 0, tier + ": a fresh tank is not drawn empty");

            handler.fill(new FluidStack(Fluids.WATER, capacity / 4), IFluidHandler.FluidAction.EXECUTE);
            h.assertTrue(modelIndex(stack) == 1, tier + ": a quarter full reads " + modelIndex(stack));

            handler.fill(new FluidStack(Fluids.WATER, capacity), IFluidHandler.FluidAction.EXECUTE);
            h.assertTrue(modelIndex(stack) == 2, tier + ": full reads " + modelIndex(stack));

            handler.drain(capacity, IFluidHandler.FluidAction.EXECUTE);
            h.assertTrue(modelIndex(stack) == 0, tier + ": emptied reads " + modelIndex(stack));
            h.assertTrue(stack.has(DataComponents.CUSTOM_MODEL_DATA),
                    tier + ": emptying did not go through the capability's hook");
        }
        h.succeed();
    }

    /** 0 empty, 1 up to half, 2 more than half; absent counts as empty. */
    private static int modelIndex(ItemStack stack) {
        var data = stack.get(DataComponents.CUSTOM_MODEL_DATA);
        return data == null ? 0 : data.value();
    }

    /**
     * Every tier feeds the suit, and only the two media do.
     *
     * <p>The tanks used to be one item and the armour asked for it by id; now the feed keys off a tag,
     * and a tier missing from that tag would be a tank the suit silently ignores. Rather than trust
     * the tag, this runs the armour's own tick handler, so what is being tested is the wiring.
     */
    @GameTest(template = "empty", timeoutTicks = 40)
    public static void everyTierFeedsTheSuit(GameTestHelper h) {
        for (PortableFluidTankItem.Tier tier : TIERS) {
            ItemStack chest = new ItemStack(ModItems.ETHER_CASING_CHESTPLATE.get());
            ItemStack stack = tank(tier);
            handler(stack).fill(new FluidStack(dev.distantstock.fluid.ModFluids.ETHER.get(), 1000),
                    IFluidHandler.FluidAction.EXECUTE);

            String fed = dev.distantstock.item.EtherCasingMediumServer
                    .refillFrom(chest, List.of(stack));

            h.assertTrue(fed != null, tier + ": the suit did not recognise the tank");
            h.assertTrue(EtherCasingArmorItem.reserve(chest) > 0,
                    tier + ": the suit was not fed");
            h.assertTrue(PortableFluidTankItem.fluid(stack).getAmount() < 1000,
                    tier + ": the suit fed without taking anything from the tank");
        }

        // A tank of something the suit cannot run on is left alone -- the feed never drains it into
        // nothing just because it happens to be a tank.
        ItemStack chest = new ItemStack(ModItems.ETHER_CASING_CHESTPLATE.get());
        ItemStack water = tank(PortableFluidTankItem.Tier.COPPER);
        handler(water).fill(new FluidStack(Fluids.WATER, 1000), IFluidHandler.FluidAction.EXECUTE);
        h.assertTrue(dev.distantstock.item.EtherCasingMediumServer
                        .refillFrom(chest, List.of(water)) == null,
                "the suit accepted water as a working medium");
        h.assertTrue(PortableFluidTankItem.fluid(water).getAmount() == 1000,
                "the suit drank water out of a tank");

        // ...and a piece already running on molten amethyst is not topped up with ether, which is
        // the one case where the medium matters rather than just being present.
        ItemStack moltenChest = new ItemStack(ModItems.ETHER_CASING_CHESTPLATE.get());
        EtherCasingArmorItem.addReserve(moltenChest, 10, EtherCasingArmorItem.MOLTEN_AMETHYST_MEDIUM);
        ItemStack ether = tank(PortableFluidTankItem.Tier.COPPER);
        handler(ether).fill(new FluidStack(dev.distantstock.fluid.ModFluids.ETHER.get(), 1000),
                IFluidHandler.FluidAction.EXECUTE);
        h.assertTrue(dev.distantstock.item.EtherCasingMediumServer
                        .refillFrom(moltenChest, List.of(ether)) == null,
                "an amethyst-fed piece was topped up with ether");
        h.succeed();
    }

    /**
     * The data files behind the family: three recipes that load, one tag that covers them.
     *
     * <p>A tag whose contents are misspelled loads perfectly and is simply empty, which turns the
     * armour's feed off without a single log line; and a recipe that fails to parse never reaches the
     * recipe manager at all.
     */
    @GameTest(template = "empty", timeoutTicks = 40)
    public static void theFamilyIsWiredTogether(GameTestHelper h) {
        for (String id : List.of("copper_fluid_tank", "sturdy_fluid_tank", "resonant_canister")) {
            var key = ResourceLocation.fromNamespaceAndPath(DistantStock.MODID, id);
            h.assertTrue(h.getLevel().getRecipeManager().byKey(key).isPresent(),
                    id + " has no recipe, or it failed to parse");
        }
        for (var holder : ModItems.FLUID_TANKS) {
            ItemStack stack = new ItemStack(holder.get());
            h.assertTrue(stack.is(ModTags.Items.FLUID_TANKS),
                    holder.get() + " is not in distantstock:fluid_tanks");
        }
        h.assertTrue(!new ItemStack(Items.BUCKET).is(ModTags.Items.FLUID_TANKS),
                "the tank tag is matching things that are not tanks");
        h.assertTrue(!new ItemStack(ModBlocks.DOCK.get()).is(ModTags.Items.FLUID_TANKS),
                "the tank tag is matching a block item");
        h.succeed();
    }

    private FluidTankGameTests() {
    }
}
