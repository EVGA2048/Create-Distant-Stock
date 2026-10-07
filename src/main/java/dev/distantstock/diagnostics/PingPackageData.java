package dev.distantstock.diagnostics;

import com.simibubi.create.content.logistics.box.PackageItem;
import dev.distantstock.item.ModItems;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;

import java.util.UUID;

/** Small probe envelope carried inside the ping PackageItem's custom data. */
public final class PingPackageData {
    private static final String ROOT = "DistantStockPing";

    public record Data(UUID probeId, String dimension, BlockPos controllerPos, BlockPos targetPos,
                       String originalAddress, long issuedTick, long deadlineTick, boolean valid) {
    }

    public static ItemStack create(UUID probeId, String dimension, BlockPos controllerPos,
                                   BlockPos targetPos, String originalAddress,
                                   String routingAddress, long issuedTick, long deadlineTick) {
        ItemStack stack = new ItemStack(ModItems.PING_PACKAGE.get());
        write(stack, new Data(probeId, dimension, controllerPos, targetPos,
                clean(originalAddress), issuedTick, deadlineTick, true));
        PackageItem.clearAddress(stack);
        PackageItem.addAddress(stack, routingAddress);
        return stack;
    }

    public static boolean isPing(ItemStack stack) {
        return stack != null && !stack.isEmpty() && stack.getItem() == ModItems.PING_PACKAGE.get();
    }

    public static Data read(ItemStack stack) {
        if (!isPing(stack)) return null;
        CompoundTag all = stack.getOrDefault(DataComponents.CUSTOM_DATA, CustomData.EMPTY).copyTag();
        if (!all.contains(ROOT)) return null;
        CompoundTag tag = all.getCompound(ROOT);
        if (!tag.hasUUID("Probe") || !tag.contains("Dimension")
                || !tag.contains("Controller") || !tag.contains("Target")) return null;
        return new Data(tag.getUUID("Probe"), tag.getString("Dimension"),
                BlockPos.of(tag.getLong("Controller")), BlockPos.of(tag.getLong("Target")),
                tag.getString("Address"), tag.getLong("Issued"), tag.getLong("Deadline"),
                !tag.contains("Valid") || tag.getBoolean("Valid"));
    }

    public static void invalidateForPlayer(ItemStack stack) {
        Data data = read(stack);
        if (data == null) return;
        write(stack, new Data(data.probeId(), data.dimension(), data.controllerPos(), data.targetPos(),
                data.originalAddress(), data.issuedTick(), data.deadlineTick(), false));
        PackageItem.clearAddress(stack);
        PackageItem.addAddress(stack, ChainDiagnostics.diagnosticAddress(data.controllerPos()));
    }

    private static void write(ItemStack stack, Data data) {
        stack.update(DataComponents.CUSTOM_DATA, CustomData.EMPTY, cur -> {
            CompoundTag all = cur.copyTag();
            CompoundTag tag = new CompoundTag();
            tag.putUUID("Probe", data.probeId());
            tag.putString("Dimension", data.dimension());
            tag.putLong("Controller", data.controllerPos().asLong());
            tag.putLong("Target", data.targetPos().asLong());
            tag.putString("Address", clean(data.originalAddress()));
            tag.putLong("Issued", data.issuedTick());
            tag.putLong("Deadline", data.deadlineTick());
            tag.putBoolean("Valid", data.valid());
            all.put(ROOT, tag);
            return CustomData.of(all);
        });
    }

    private static String clean(String value) {
        if (value == null) return "";
        return value.length() > 192 ? value.substring(0, 192) : value;
    }

    private PingPackageData() {
    }
}
