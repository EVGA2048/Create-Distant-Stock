package dev.distantstock.net;

import dev.distantstock.DistantStock;
import dev.distantstock.routing.DockGroup;
import dev.distantstock.routing.RemoteGroups;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.network.handling.IPayloadContext;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * The dock groups on other servers this one has been let into, for the requester's destination list.
 *
 * <p>Sent alongside {@link DockGroupsS2C} rather than merged into it because the two are different
 * things wearing the same clothes: one is a group this server can look up, count the docks of and
 * gate, and the other is a name and an id that only the far end can act on. The screen draws them
 * in one list and marks the difference; the server keeps them in separate files because only one of
 * them is a permission.
 *
 * <p>Every field here is display data. What an order needs is the group's id and the node it lives
 * on, and those are held server-side where the order is actually placed.
 */
public record RemoteGroupsS2C(List<Entry> groups) implements CustomPacketPayload {
    /** One row: what to call it, and the id the screen sends back when it is picked. */
    public record Entry(UUID group, String name, String label) {
        /** What the list draws. */
        public String display() {
            return label + "·" + name;
        }
    }

    public static final Type<RemoteGroupsS2C> TYPE = new Type<>(
            ResourceLocation.fromNamespaceAndPath(DistantStock.MODID, "remote_groups"));
    public static final StreamCodec<RegistryFriendlyByteBuf, RemoteGroupsS2C> STREAM_CODEC =
            StreamCodec.of(RemoteGroupsS2C::write, RemoteGroupsS2C::read);

    @Override
    public Type<RemoteGroupsS2C> type() {
        return TYPE;
    }

    private static void write(RegistryFriendlyByteBuf buf, RemoteGroupsS2C msg) {
        buf.writeVarInt(msg.groups.size());
        for (Entry entry : msg.groups) {
            buf.writeUUID(entry.group());
            buf.writeUtf(entry.name(), DockGroup.MAX_NAME_LENGTH);
            buf.writeUtf(entry.label(), 64);
        }
    }

    private static RemoteGroupsS2C read(RegistryFriendlyByteBuf buf) {
        int count = buf.readVarInt();
        if (count < 0 || count > RemoteGroups.MAX_ENTRIES) {
            // A count is the one thing a malformed packet can turn into an allocation, so it is
            // checked before a list is reserved for it.
            throw new io.netty.handler.codec.DecoderException("remote group count " + count);
        }
        List<Entry> groups = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            groups.add(new Entry(buf.readUUID(), buf.readUtf(DockGroup.MAX_NAME_LENGTH), buf.readUtf(64)));
        }
        return new RemoteGroupsS2C(List.copyOf(groups));
    }

    public static void handle(RemoteGroupsS2C msg, IPayloadContext ctx) {
        ctx.enqueueWork(() -> {
            if (net.minecraft.client.Minecraft.getInstance().screen
                    instanceof dev.distantstock.client.RequesterScreen screen) {
                screen.applyRemoteGroups(msg);
            }
        });
    }

    public static RemoteGroupsS2C of(RemoteGroups directory) {
        List<Entry> out = new ArrayList<>();
        for (RemoteGroups.Entry entry : directory.all()) {
            out.add(new Entry(entry.group(), entry.name(), entry.label()));
        }
        return new RemoteGroupsS2C(List.copyOf(out));
    }
}
