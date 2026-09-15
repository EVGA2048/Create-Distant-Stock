package dev.distantstock.net;

import dev.distantstock.DistantStock;
import dev.distantstock.routing.DockGroup;
import dev.distantstock.routing.DockGroupDirectory;
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
 * The systems this player may point a requester at.
 *
 * <p>Sent rather than guessed. The field on the requester's screen used to be free text, which meant
 * a player had to remember a name to select a system and could not tell whether one existed — and a
 * new player had no way at all of learning that "默认收货港组" was there.
 *
 * <p>What the client is told is filtered to groups the player may actually use. A private group
 * belonging to somebody else is not sent at all: a list of names that refuse to be picked is worse
 * than a shorter list.
 */
public record DockGroupsS2C(List<Entry> groups, UUID carried) implements CustomPacketPayload {
    /** How many groups one screen is offered. The list scrolls in nothing; it has to fit. */
    public static final int MAX_ENTRIES = 24;

    /** One row: what it is called, and whether this player may pick it or open it. */
    public record Entry(UUID id, String name, boolean open, boolean mine, int docks) {
    }

    public static final Type<DockGroupsS2C> TYPE = new Type<>(
            ResourceLocation.fromNamespaceAndPath(DistantStock.MODID, "dock_groups"));
    public static final StreamCodec<RegistryFriendlyByteBuf, DockGroupsS2C> STREAM_CODEC =
            StreamCodec.of(DockGroupsS2C::write, DockGroupsS2C::read);

    @Override
    public Type<DockGroupsS2C> type() {
        return TYPE;
    }

    private static void write(RegistryFriendlyByteBuf buf, DockGroupsS2C msg) {
        buf.writeVarInt(msg.groups.size());
        for (Entry entry : msg.groups) {
            buf.writeUUID(entry.id());
            buf.writeUtf(entry.name(), DockGroup.MAX_NAME_LENGTH);
            buf.writeBoolean(entry.open());
            buf.writeBoolean(entry.mine());
            buf.writeVarInt(entry.docks());
        }
        buf.writeBoolean(msg.carried != null);
        if (msg.carried != null) {
            buf.writeUUID(msg.carried);
        }
    }

    private static DockGroupsS2C read(RegistryFriendlyByteBuf buf) {
        int count = buf.readVarInt();
        if (count < 0 || count > MAX_ENTRIES) {
            // A count is the one thing a malformed packet can turn into an allocation, so it is
            // checked before a list is reserved for it.
            throw new io.netty.handler.codec.DecoderException("dock group count " + count);
        }
        List<Entry> groups = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            groups.add(new Entry(buf.readUUID(), buf.readUtf(DockGroup.MAX_NAME_LENGTH),
                    buf.readBoolean(), buf.readBoolean(), buf.readVarInt()));
        }
        UUID carried = buf.readBoolean() ? buf.readUUID() : null;
        return new DockGroupsS2C(groups, carried);
    }

    public static void handle(DockGroupsS2C msg, IPayloadContext ctx) {
        // Straight to the screen that asked, the way the other client payloads do: the list is
        // only meaningful to a requester screen, and there is at most one open.
        ctx.enqueueWork(() -> {
            if (net.minecraft.client.Minecraft.getInstance().screen
                    instanceof dev.distantstock.client.RequesterScreen screen) {
                screen.applyGroups(msg);
            }
        });
    }

    /** Builds the list one player may see, capped. Server side. */
    public static DockGroupsS2C of(DockGroupDirectory directory, UUID player, UUID carried,
                                   java.util.function.ToIntFunction<UUID> dockCount) {
        List<Entry> out = new ArrayList<>();
        for (DockGroup group : directory.all()) {
            if (!group.admits(player)) {
                continue;
            }
            out.add(new Entry(group.id(), group.name(), group.open(),
                    group.ownedBy(player), dockCount.applyAsInt(group.id())));
            if (out.size() >= MAX_ENTRIES) {
                break;
            }
        }
        return new DockGroupsS2C(List.copyOf(out), carried);
    }
}
