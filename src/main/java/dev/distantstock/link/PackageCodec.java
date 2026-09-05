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
            CompoundTag tag = NbtIo.readCompressed(new ByteArrayInputStream(raw), NbtAccounter.unlimitedHeap());
            return ItemStack.parseOptional(regs, tag);
        } catch (Exception e) {
            return ItemStack.EMPTY;
        }
    }

    private PackageCodec() {
    }
}
