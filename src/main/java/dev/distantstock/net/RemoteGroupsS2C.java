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
    /**
     * One row: what to call it, and the id the screen sends back when it is picked.
     *
     * <p>{@code admitted} is the question the row is really asking. The far server sends its member
     * list along with the group and this server judges the player asking against it — the only place
     * that judgement can be made, because an order arrives over there with no player on it. A row
     * that is not admitted is drawn greyed and refuses to be picked rather than being left out: a
     * player who was let into a group and later removed should be able to see that happened, and
     * "the destination vanished" tells them nothing.
     *
     * @param docks how many receiving docks the group has over there. Zero is a destination nothing
     *              will arrive at, which is worth seeing before the order rather than after.
     */
    public record Entry(UUID group, String name, String label, boolean admitted, int docks) {
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
            buf.writeBoolean(entry.admitted());
            buf.writeVarInt(Math.max(0, entry.docks()));
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
            groups.add(new Entry(buf.readUUID(), buf.readUtf(DockGroup.MAX_NAME_LENGTH), buf.readUtf(64),
                    buf.readBoolean(), Math.max(0, buf.readVarInt())));
        }
        return new RemoteGroupsS2C(List.copyOf(groups));
    }

    public static void handle(RemoteGroupsS2C msg, IPayloadContext ctx) {
        ctx.enqueueWork(() -> {
            // 和本服那份清单一样，谁开着界面就给谁：终端、以及两张要选接收港组的设备界面。
            if (net.minecraft.client.Minecraft.getInstance().screen
                    instanceof dev.distantstock.client.GroupListSink sink) {
                sink.acceptRemotes(msg);
            } else if (net.minecraft.client.Minecraft.getInstance().screen
                    instanceof dev.distantstock.client.RequesterScreen screen) {
                screen.applyRemoteGroups(msg);
            }
        });
    }

    /**
     * The remote destinations this server remembers.
     *
     * <p>The label is resolved now rather than read from the stored row: a group redeemed before
     * this server had ever heard from that node kept a uuid prefix in its row for good, so the
     * destination list went on saying "5d2a90b4" long after the node had introduced itself. The
     * stored label stays as the fallback for a node that is quiet.
     */
    public static RemoteGroupsS2C of(RemoteGroups directory, net.minecraft.server.MinecraftServer server,
                                     java.util.UUID player) {
        List<Entry> out = new ArrayList<>();
        for (RemoteGroups.Entry entry : directory.all()) {
            String live = dev.distantstock.routing.PeerNames.label(server, entry.node());
            out.add(new Entry(entry.group(), entry.name(),
                    live == null || live.isEmpty() ? entry.label() : live,
                    entry.admits(player), entry.docks()));
        }
        return new RemoteGroupsS2C(List.copyOf(out));
    }
}
