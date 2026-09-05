package dev.distantstock.item;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;

import java.util.UUID;

/** 不编译依赖 Create。从 BE NBT 里摸 Freq。 */
public final class CreateFreq {
    public static UUID fromBlockEntity(BlockEntity be) {
        if (be == null) {
            return null;
        }
        Level level = be.getLevel();
        if (level == null) {
            return null;
        }
        CompoundTag tag = be.saveWithFullMetadata(level.registryAccess());
        return findFreq(tag);
    }

    private static UUID findFreq(CompoundTag tag) {
        if (tag == null) {
            return null;
        }
        if (tag.hasUUID("Freq")) {
            return tag.getUUID("Freq");
        }
        if (tag.hasUUID("freq")) {
            return tag.getUUID("freq");
        }
        for (String key : tag.getAllKeys()) {
            if (tag.contains(key, Tag.TAG_COMPOUND)) {
                UUID nested = findFreq(tag.getCompound(key));
                if (nested != null) {
                    return nested;
                }
            }
        }
        return null;
    }

    private CreateFreq() {
    }
}
