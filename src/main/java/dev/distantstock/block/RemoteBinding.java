package dev.distantstock.block;

import dev.distantstock.routing.RemoteNetworkId;
import net.minecraft.nbt.CompoundTag;

import java.util.UUID;

/**
 * One device's answer to "where do my goods come from": a warehouse, a dock group, an address.
 *
 * <p>Shared by the panels that order from another server and by the redstone requester that does
 * the same on a pulse. It is one value in both places because it is one decision: three fields that
 * only mean anything together, and a device holding two of them would be a device that orders into
 * nowhere.
 */
public record RemoteBinding(RemoteNetworkId network, UUID receivingGroup, String address) {
    public RemoteBinding {
        address = address == null ? "" : address;
    }

    public CompoundTag save() {
        CompoundTag tag = new CompoundTag();
        tag.put("Network", network.save());
        if (receivingGroup != null) {
            tag.putUUID("Group", receivingGroup);
        }
        tag.putString("Address", address);
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
                tag.hasUUID("Group") ? tag.getUUID("Group") : null, tag.getString("Address"));
    }
}
