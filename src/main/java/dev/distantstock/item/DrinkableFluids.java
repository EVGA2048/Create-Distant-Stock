package dev.distantstock.item;

import dev.distantstock.ModTags;
import dev.distantstock.config.StockConfig;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;
import com.simibubi.create.AllRecipeTypes;
import com.simibubi.create.api.effect.OpenPipeEffectHandler;
import com.simibubi.create.content.fluids.transfer.FillingRecipe;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.food.FoodProperties;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.alchemy.PotionContents;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.material.Fluid;
import net.neoforged.neoforge.common.EffectCures;
import net.neoforged.neoforge.common.Tags;
import net.neoforged.neoforge.fluids.FluidStack;
import net.neoforged.neoforge.fluids.crafting.SizedFluidIngredient;

import java.util.List;
import java.util.Locale;

/**
 * Which fluids a tank will let you drink, and what drinking them does.
 *
 * <p>Drinkability is deliberately over-determined, because the interesting case is a fluid from a mod
 * that has never heard of this one. Four layers, in order of confidence:
 *
 * <ol>
 *   <li><b>{@code distantstock:drinkable}</b> -- explicit, shipped as data, and the only layer a pack
 *       author needs to know about.</li>
 *   <li><b>The conventions</b> -- the {@code c:} tags NeoForge and Create already publish
 *       ({@code c:water}, {@code c:milk}, {@code c:tea}, {@code c:chocolate}, ...). A mod that tags
 *       its drinks the way the ecosystem expects gets picked up without knowing we exist.</li>
 *   <li><b>A purity component</b> -- a fluid carrying Thirst Was Taken's {@code thirst:purity} is
 *       water-family by construction, because that is what stamped it there.</li>
 *   <li><b>Keywords</b> -- a guess, and the only layer that is one. It is what catches the long tail
 *       (a drink nobody tagged), so it is on by default, and it is off-switchable because a guess
 *       that is wrong about a fluid is worse than a fluid that cannot be drunk.</li>
 * </ol>
 *
 * <p>Layers 3 and the effects of layer 1/2's water branch need Thirst Was Taken, which is a mod this
 * one may not be loaded with. That is what {@link WaterBridge} is: a single seam that the compat
 * package installs, with an inert default. Nothing here names a Thirst type, so the class loads
 * either way, and with the mod absent the water branch simply plays its sound.
 */
public final class DrinkableFluids {
    /** The conventions other mods publish. Anything they tag the usual way is drinkable here. */
    private static final List<TagKey<Fluid>> CONVENTION_TAGS = List.of(
            Tags.Fluids.WATER, Tags.Fluids.MILK, Tags.Fluids.HONEY, Tags.Fluids.POTION,
            convention("tea"), convention("chocolate"), convention("coffee"), convention("juice"),
            convention("drinks"), convention("drinks/water"), convention("drinks/watery"),
            convention("drinks/milk"), convention("drinks/juice"), convention("drinks/honey"),
            convention("drinks/tea"));

    /**
     * The long tail. Matched against the fluid's registry id and its type's description id, never
     * against a localized name -- the answer must not depend on the player's language.
     */
    private static final List<String> KEYWORDS = List.of(
            "water", "tea", "juice", "milk", "potion", "honey", "coffee", "cocoa", "chocolate",
            "soup", "stew", "soda", "wine", "beer", "cider", "smoothie", "drink", "beverage");

    /** Installed by the Thirst compat, null when that mod is absent. */
    private static volatile WaterBridge bridge;

    private DrinkableFluids() {
    }

    /**
     * Thirst Was Taken, reduced to the five questions this mod asks it.
     *
     * <p>An interface rather than five separate hooks because they are one decision: a bridge is
     * either fully installed or not installed at all, and half of one would be a bug that only shows
     * up in a pack that has the mod.
     */
    public interface WaterBridge {
        /** True when the fluid carries a purity -- i.e. it is water-family. Read-only. */
        boolean hasPurity(FluidStack fluid);

        /** Purity 0..3, or -1 when there is none. Read-only, never writes a default. */
        int purity(FluidStack fluid);

        /** Thirst's own nausea/poison roll for that purity. */
        void applyPurityEffects(Player player, int purity);

        /** Add one sip's worth of hydration for that purity. */
        void hydrate(Player player, int purity);

        /** The tooltip line for this fluid's purity, or null when it has none. */
        Component describePurity(FluidStack fluid);
    }

    /** Called once at mod construction by the compat package, on both sides. */
    public static void installWaterBridge(WaterBridge installed) {
        bridge = installed;
    }

    /** The installed bridge, or null. */
    public static WaterBridge waterBridge() {
        return bridge;
    }

    public static boolean isDrinkable(FluidStack fluid) {
        if (fluid.isEmpty()) return false;
        if (fluid.is(ModTags.Fluids.DRINKABLE)) return true;
        for (TagKey<Fluid> tag : CONVENTION_TAGS) {
            if (fluid.is(tag)) return true;
        }
        WaterBridge installed = bridge;
        if (installed != null && installed.hasPurity(fluid)) return true;
        return StockConfig.fluidTankKeywordDrink() && keywordMatches(fluid);
    }

    /**
     * One mouthful's worth of whatever this fluid does.
     *
     * <p>Server side only; the caller plays the sound. The order below is by how much each branch
     * knows: a potion carries its own effects, milk and honey are defined by what they take away,
     * water is the one drink whose effect depends on where it came from, and anything else is handed
     * to whoever authored it -- first the item a bucket of it would be drunk from, then the effect
     * registry Create publishes for fluids.
     *
     * <p>That last pair is the answer to "does moving a drink into a tank lose what it does". The
     * data half never had anything to lose: effects ride on the fluid stack as components, and both
     * this mod's tank and Create's own keep them, on disk and over the wire. The half that could be
     * lost is the code that fires when a particular item is drunk, and it is not lost by accident
     * here: a fluid whose container is a food is drunk as that food, and a fluid Create has an
     * effect handler for gets that handler run on the drinker. What remains out of reach is a mod
     * that gives its drink an effect only inside its own item's code, with no data and no handler;
     * there is nothing to read, so nothing here can know.
     */
    public static void applyDrink(Level level, LivingEntity entity, FluidStack fluid) {
        if (level.isClientSide || fluid.isEmpty()) return;

        // A potion is defined by carrying effects, which is exactly how Create bottles them: the
        // contents ride on the fluid stack. A c:potion-tagged fluid with nothing in it is water
        // wearing a label, and falls through to the water branch where it belongs.
        PotionContents potion = fluid.getOrDefault(DataComponents.POTION_CONTENTS, PotionContents.EMPTY);
        if (!PotionContents.EMPTY.equals(potion)) {
            applyPotion(entity, potion);
            return;
        }

        if (fluid.is(Tags.Fluids.MILK)) {
            entity.removeEffectsCuredBy(EffectCures.MILK);
            return;
        }
        // Honey cures what honey cures and, unlike milk, is food as well -- so this one is not a
        // return: it falls through to the container branch that feeds the drinker.
        boolean honey = fluid.is(Tags.Fluids.HONEY);
        if (honey) {
            entity.removeEffectsCuredBy(EffectCures.HONEY);
        }

        // Water: the only drink that is interesting for where it came from rather than what it is.
        if (!honey && isWaterFamily(fluid)) {
            WaterBridge installed = bridge;
            if (installed != null && entity instanceof Player player) {
                int purity = installed.purity(fluid);
                if (purity >= 0) {
                    installed.applyPurityEffects(player, purity);
                    installed.hydrate(player, purity);
                }
            }
            return;
        }

        // Drink it the way its own container would be drunk: same effects, same food value, rolled
        // the same way. Create's tea is the case this exists for, and so is any mod's soup.
        if (drinkAsItsItemForm(level, entity, fluid)) return;

        // No container to copy: ask Create's registry of "what this fluid does to an entity", which
        // is how it describes its own tea and any fluid another mod has registered an effect for.
        OpenPipeEffectHandler handler = OpenPipeEffectHandler.REGISTRY.get(fluid.getFluid());
        if (handler != null) handler.apply(level, entity.getBoundingBox(), fluid);
    }

    private static boolean isWaterFamily(FluidStack fluid) {
        if (fluid.is(Tags.Fluids.WATER)) return true;
        WaterBridge installed = bridge;
        return installed != null && installed.hasPurity(fluid);
    }

    /**
     * What a container of this fluid would be as an item, if anything edible is.
     *
     * <p>Two ways to find it, because one is not enough. The fluid's bucket is the conventional
     * answer and the one a mod declaring its drink properly will have. Create's own tea has no
     * bucket -- it is drunk as a bottled tea, made by a filling recipe -- so the filling recipes are
     * asked next: whatever a spout turns this fluid into is the item that says what drinking it
     * means. That covers Create's tea with its own data (dig speed for three minutes, not the
     * twenty ticks its pipe-effect handler gives) and any mod that expresses its drinks the same
     * way, without this mod having to know the item.
     *
     * <p>The bucket is asked first but not trusted: several fluids have a non-food bucket, and
     * stopping at the first item found would then be worse than looking further.
     */
    private static FoodProperties foodFormOf(Level level, FluidStack fluid) {
        FoodProperties fromBucket = fluid.getFluidType().getBucket(fluid).get(DataComponents.FOOD);
        if (fromBucket != null) return fromBucket;
        // Create's recipe type arrives through an erased generic, so its elements are typed as plain
        // Recipes and both an instanceof and a direct cast are rejected as inconvertible: the check
        // has to be against the class and the cast has to go through Object. The list is only walked
        // when somebody actually drinks, which is at most once every thirty-two ticks per player, so
        // it is not worth caching against the recipe reload it would then have to track.
        for (var holder : level.getRecipeManager().getAllRecipesFor(AllRecipeTypes.FILLING.getType())) {
            if (!FillingRecipe.class.isInstance(holder.value())) continue;
            FillingRecipe recipe = (FillingRecipe) (Object) holder.value();
            SizedFluidIngredient required = recipe.getRequiredFluid();
            if (required == null) continue;
            // The ingredient also compares amounts, and a tank part-way through a sip may hold less
            // than one recipe's worth; the question here is what the fluid makes, not whether there
            // is a batch of it.
            if (!required.test(fluid.copyWithAmount(Math.max(fluid.getAmount(), required.amount())))) {
                continue;
            }
            FoodProperties food = recipe.getResultItem(level.registryAccess()).get(DataComponents.FOOD);
            if (food != null) return food;
        }
        return null;
    }

    private static boolean drinkAsItsItemForm(Level level, LivingEntity entity, FluidStack fluid) {
        FoodProperties food = foodFormOf(level, fluid);
        if (food == null) return false;
        if (entity instanceof Player player) player.getFoodData().eat(food);
        for (FoodProperties.PossibleEffect possible : food.effects()) {
            if (entity.getRandom().nextFloat() < possible.probability()) {
                entity.addEffect(new MobEffectInstance(possible.effect()));
            }
        }
        return true;
    }

    /** Vanilla's own potion drinking, minus the bottle: instant effects fire, the rest are applied. */
    private static void applyPotion(LivingEntity entity, PotionContents contents) {
        Player player = entity instanceof Player p ? p : null;
        contents.forEachEffect(effect -> {
            if (effect.getEffect().value().isInstantenous()) {
                effect.getEffect().value().applyInstantenousEffect(player, player, entity,
                        effect.getAmplifier(), 1.0);
            } else {
                entity.addEffect(new MobEffectInstance(effect));
            }
        });
    }

    private static boolean keywordMatches(FluidStack fluid) {
        ResourceLocation id = BuiltInRegistries.FLUID.getKey(fluid.getFluid());
        String description = fluid.getFluidType().getDescriptionId().toLowerCase(Locale.ROOT);
        String path = id == null ? "" : id.getPath().toLowerCase(Locale.ROOT);
        for (String keyword : KEYWORDS) {
            if (path.contains(keyword) || description.contains(keyword)) return true;
        }
        return false;
    }

    private static TagKey<Fluid> convention(String path) {
        return TagKey.create(Registries.FLUID,
                ResourceLocation.fromNamespaceAndPath("c", path));
    }
}
