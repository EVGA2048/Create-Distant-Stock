package dev.distantstock.item;

import net.minecraft.nbt.CompoundTag;
import java.util.UUID;

public final class RequesterUnbindCheck {
    public static void main(String[] args) {
        CompoundTag tag = new CompoundTag();
        tag.putUUID(RequesterData.FREQ, UUID.randomUUID());
        tag.put(RequesterData.NETWORK, new CompoundTag());
        tag.putUUID(RequesterData.RECEIVING_GROUP, UUID.randomUUID());
        tag.putString(RequesterData.ADDRESS, "车间一号");
        tag.putString("UnrelatedData", "preserve");
        RequesterData.clearBindingTag(tag);
        require(!tag.contains(RequesterData.FREQ) && !tag.contains(RequesterData.NETWORK)
                && !tag.contains(RequesterData.RECEIVING_GROUP), "stale binding remains");
        require(tag.getString(RequesterData.ADDRESS).equals("车间一号")
                && tag.getString("UnrelatedData").equals("preserve"), "unrelated data was lost");
        CompoundTag once = tag.copy();
        RequesterData.clearBindingTag(tag);
        require(once.equals(tag), "unbind must be idempotent");
        RequesterData.clearBindingTag(new CompoundTag());
        System.out.println("Requester unbind checks PASSED: binding cleared, address retained, repeated unbind safe.");
    }
    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
