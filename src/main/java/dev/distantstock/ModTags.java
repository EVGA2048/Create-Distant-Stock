package dev.distantstock;

import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.material.Fluid;

/**
 * The tag keys this mod ships data files for.
 *
 * <p>Two of them, for two different reasons. {@link Items#FLUID_TANKS} exists because the three
 * portable tanks are one family in code but three registrations, and every place that treats them
 * alike -- the armour's medium feed, the fluid capability, the tests -- would otherwise carry its
 * own list that a fourth tier would quietly miss. {@link Fluids#DRINKABLE} exists to be extended by
 * other people: it is the explicit half of the drinkability test, the half a pack author is meant
 * to write to, and it has to be a tag rather than code for exactly that reason.
 */
public final class ModTags {
    public static final class Items {
        /** Every portable fluid tank. See {@link #FLUID_TANKS}. */
        public static final TagKey<Item> FLUID_TANKS = TagKey.create(Registries.ITEM,
                ResourceLocation.fromNamespaceAndPath(DistantStock.MODID, "fluid_tanks"));

        private Items() {
        }
    }

    public static final class Fluids {
        /**
         * Fluids a tank will let its owner drink.
         *
         * <p>This is the first of four layers, and the only one anybody has to know about: a pack
         * that adds a beverage only has to add it here. The other three exist so that a mod which
         * has never heard of this one still works -- see {@code DrinkableFluids}.
         */
        public static final TagKey<Fluid> DRINKABLE = TagKey.create(Registries.FLUID,
                ResourceLocation.fromNamespaceAndPath(DistantStock.MODID, "drinkable"));

        private Fluids() {
        }
    }

    private ModTags() {
    }
}
