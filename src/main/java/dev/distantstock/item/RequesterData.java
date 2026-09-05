package dev.distantstock.item;

import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;

import java.util.UUID;

public final class RequesterData {
    public static final String FREQ = "Freq";
    public static final String ADDRESS = "Address";

    public static boolean tuned(ItemStack stack) {
        return freq(stack) != null;
    }

    public static UUID freq(ItemStack stack) {
        CompoundTag tag = tag(stack);
        if (!tag.hasUUID(FREQ)) {
            return null;
        }
        return tag.getUUID(FREQ);
    }

    public static String address(ItemStack stack) {
        return tag(stack).getString(ADDRESS);
    }

    public static void setFreq(ItemStack stack, UUID freq) {
        update(stack, tag -> tag.putUUID(FREQ, freq));
    }

    public static void setAddress(ItemStack stack, String address) {
        update(stack, tag -> tag.putString(ADDRESS, address == null ? "" : address));
    }

    public static String shortFreq(UUID freq) {
        if (freq == null) {
            return "-";
        }
        return freq.toString().substring(0, 8);
    }

    private static CompoundTag tag(ItemStack stack) {
        return stack.getOrDefault(DataComponents.CUSTOM_DATA, CustomData.EMPTY).copyTag();
    }

    private static void update(ItemStack stack, java.util.function.Consumer<CompoundTag> fn) {
        stack.update(DataComponents.CUSTOM_DATA, CustomData.EMPTY, cur -> {
            CompoundTag t = cur.copyTag();
            fn.accept(t);
            return CustomData.of(t);
        });
    }

    private RequesterData() {
    }
}
