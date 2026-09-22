package dev.distantstock.client;

import dev.distantstock.item.ModItems;
import dev.distantstock.item.RequesterData;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

/** Client helper for Create interactions that otherwise recognise only its exact Frogport item. */
public final class SpecialFrogportSelection {
    private static final ResourceLocation CREATE_FROGPORT =
            ResourceLocation.fromNamespaceAndPath("create", "package_frogport");

    public static boolean isSpecialFrogport(ItemStack stack) {
        return (stack.is(ModItems.DIAGNOSTIC_FROGPORT.get()) && RequesterData.tuned(stack))
                || stack.is(ModItems.CACHE_FROGPORT.get());
    }

    /**
     * Only used as the argument to Create's BlockEntry.isIn() checks. The player's real stack is
     * never changed.
     */
    public static ItemStack normalizeForCreateCheck(ItemStack stack) {
        if (!isSpecialFrogport(stack)) return stack;
        Item vanillaFrogport = BuiltInRegistries.ITEM.get(CREATE_FROGPORT);
        return new ItemStack(vanillaFrogport);
    }

    private SpecialFrogportSelection() {
    }
}
