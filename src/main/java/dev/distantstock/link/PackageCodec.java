package dev.distantstock.link;

import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.world.item.ItemStack;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.Base64;

public final class PackageCodec {
    private static final long MAX_DECOMPRESSED_NBT_BYTES = 16L * 1024 * 1024;
    public static String encode(ItemStack stack, HolderLookup.Provider regs) {
        if (stack == null || stack.isEmpty()) {
            return "";
        }
        try {
            CompoundTag tag = (CompoundTag) stack.save(regs);
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            NbtIo.writeCompressed(tag, bos);
            return Base64.getEncoder().encodeToString(bos.toByteArray());
        } catch (Exception e) {
            return "";
        }
    }

    public static ItemStack decode(String b64, HolderLookup.Provider regs) {
        if (b64 == null || b64.isEmpty()) {
            return ItemStack.EMPTY;
        }
        try {
            byte[] raw = Base64.getDecoder().decode(b64);
            CompoundTag tag = NbtIo.readCompressed(new ByteArrayInputStream(raw),
                    NbtAccounter.create(MAX_DECOMPRESSED_NBT_BYTES));
            return ItemStack.parseOptional(regs, tag);
        } catch (Exception e) {
            return ItemStack.EMPTY;
        }
    }

    private PackageCodec() {
    }
}
