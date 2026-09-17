package dev.distantstock.item;

import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;

import java.util.UUID;
import java.util.Optional;
import dev.distantstock.routing.RemoteNetworkId;

public final class RequesterData {
    public static final String FREQ = "Freq";
    public static final String ADDRESS = "Address";
    /**
     * 货回到本端以后要穿的地址。空白 = 只有上面那一个地址，也就是这件货不需要换门牌。
     */
    public static final String HOME_ADDRESS = "HomeAddress";
    public static final String NETWORK = "RemoteNetwork";
    public static final String RECEIVING_GROUP = "ReceivingDockGroup";
    /** The carried system's name, cached on the item so the screen can draw it offline. */
    public static final String RECEIVING_GROUP_NAME = "ReceivingDockGroupName";

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
        update(stack, tag -> {
            tag.remove(NETWORK);
            tag.putUUID(FREQ, freq);
        });
    }

    /** Clear only network-dependent fields; preserve the user's address and other item data. */
    public static void clearBinding(ItemStack stack) {
        update(stack, RequesterData::clearBindingTag);
    }

    static void clearBindingTag(CompoundTag tag) {
        // The name goes with the id. Leaving it behind made "a name with no id" a legal state, and
        // the next dock click would write that stale name back onto the item alongside the default
        // group's id — a label that looks like an answer and is not one.
        tag.remove(FREQ);
        tag.remove(NETWORK);
        tag.remove(RECEIVING_GROUP);
        tag.remove(RECEIVING_GROUP_NAME);
    }

    public static void setAddress(ItemStack stack, String address) {
        update(stack, tag -> tag.putString(ADDRESS, address == null ? "" : address));
    }

    /**
     * The address the goods wear once they are back on this side, or "".
     *
     * <p>Stored on the requester rather than sent with each order so the setting survives closing the
     * screen, the way the address beside it does: a player who has to retype both every time they
     * open a terminal would stop using the second one.
     */
    public static String homeAddress(ItemStack stack) {
        return tag(stack).getString(HOME_ADDRESS);
    }

    public static void setHomeAddress(ItemStack stack, String homeAddress) {
        String value = homeAddress == null ? "" : homeAddress;
        update(stack, tag -> {
            if (value.isEmpty()) {
                tag.remove(HOME_ADDRESS);
            } else {
                tag.putString(HOME_ADDRESS, value);
            }
        });
    }

    public static Optional<RemoteNetworkId> network(ItemStack stack) {
        CompoundTag root = tag(stack);
        return root.contains(NETWORK) ? RemoteNetworkId.read(root.getCompound(NETWORK)) : Optional.empty();
    }

    public static void setNetwork(ItemStack stack, RemoteNetworkId network) {
        update(stack, tag -> {
            tag.put(NETWORK, network.save());
            tag.putUUID(FREQ, network.createFrequency());
        });
    }

    public static Optional<UUID> receivingGroup(ItemStack stack) {
        CompoundTag root = tag(stack);
        return root.hasUUID(RECEIVING_GROUP) ? Optional.of(root.getUUID(RECEIVING_GROUP)) : Optional.empty();
    }

    /**
     * The system this requester points at, by name as well as by id.
     *
     * <p>The name rides along on the item rather than being looked up when the screen opens. Names
     * live in a server-side file and the screen is drawn on the client, so without the copy the
     * field would have to be blank until a packet arrived, and a requester in a chest would show
     * nothing at all.
     */
    public static Optional<String> receivingGroupName(ItemStack stack) {
        CompoundTag root = tag(stack);
        String name = root.getString(RECEIVING_GROUP_NAME);
        return name.isBlank() ? Optional.empty() : Optional.of(name);
    }

    public static void setReceivingGroup(ItemStack stack, UUID groupId, String name) {
        update(stack, tag -> {
            if (groupId == null) {
                tag.remove(RECEIVING_GROUP);
            } else {
                tag.putUUID(RECEIVING_GROUP, groupId);
            }
            if (name == null || name.isBlank()) {
                tag.remove(RECEIVING_GROUP_NAME);
            } else {
                tag.putString(RECEIVING_GROUP_NAME, name);
            }
        });
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
