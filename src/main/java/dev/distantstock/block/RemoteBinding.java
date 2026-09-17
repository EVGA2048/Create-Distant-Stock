package dev.distantstock.block;

import dev.distantstock.routing.RemoteNetworkId;
import net.minecraft.nbt.CompoundTag;

import java.util.UUID;

/**
 * One device's answer to "where do my goods come from": a warehouse, a dock group, and the two
 * addresses a parcel needs — the one it is packed with, and the one it wears once it is home.
 *
 * <p>Shared by the panels that order from another server and by the redstone requester that does
 * the same on a pulse. It is one value in both places because it is one decision: fields that only
 * mean anything together, and a device holding some of them would be a device that orders into
 * nowhere.
 *
 * @param address     the address the parcels are packed with, read by the far side's own logistics
 * @param homeAddress the address they wear after the crossing, applied on the way in. Blank is the
 *                    ordinary case: goods that stay on the server they were packed on never need a
 *                    second one — see {@code RemoteRouteData.applyHomeAddress}.
 */
public record RemoteBinding(RemoteNetworkId network, UUID receivingGroup, String address,
                            String homeAddress) {
    public RemoteBinding {
        address = address == null ? "" : address;
        homeAddress = homeAddress == null ? "" : homeAddress;
    }

    /** A binding from before there were two addresses: its goods come home wearing what they left with. */
    public RemoteBinding(RemoteNetworkId network, UUID receivingGroup, String address) {
        this(network, receivingGroup, address, "");
    }

    public CompoundTag save() {
        CompoundTag tag = new CompoundTag();
        tag.put("Network", network.save());
        if (receivingGroup != null) {
            tag.putUUID("Group", receivingGroup);
        }
        tag.putString("Address", address);
        // 只在有东西可写的时候写：老存档读回来仍是"没有第二个地址"，而不是空字符串。
        if (!homeAddress.isEmpty()) {
            tag.putString("HomeAddress", homeAddress);
        }
        return tag;
    }

    /** Reads a binding, or null for a tag that does not hold one. */
    public static RemoteBinding read(CompoundTag tag) {
        if (tag == null || !tag.contains("Network")) {
            return null;
        }
        RemoteNetworkId network = RemoteNetworkId.read(tag.getCompound("Network")).orElse(null);
        if (network == null) {
            return null;
        }
        return new RemoteBinding(network,
                tag.hasUUID("Group") ? tag.getUUID("Group") : null, tag.getString("Address"),
                tag.getString("HomeAddress"));
    }
}
