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

    /**
     * One row: what it is called, and whether this player may pick it or open it.
     *
     * <p>{@code owner} and {@code members} are names, because that is the only thing that answers
     * the question the list is read with — "whose warehouse is this?" A uuid answers nothing a
     * player can act on, and the short form of one answers even less. The owner is the player who
     * made the system; the members are the players they let in.
     */
    /**
     * One row: what it is called, and whether this player may pick it or open it.
     *
     * <p>{@code owner} and {@code members} are names, because that is the only thing that answers
     * the question the list is read with — "whose warehouse is this?" A uuid answers nothing a
     * player can act on, and the short form of one answers even less. The owner is the player who
     * made the system; the members are the players they let in.
     *
     * <p>{@code admitted} is the group's own answer to "may this player use it", and it is sent
     * separately from {@code open} because the two are not the same question: an open group admits
     * everybody and a closed one admits its list, so a row can be open and still not admit this
     * player (they simply have not joined yet) — which is exactly the row that needs a way in.
     */
    public record Entry(UUID id, String name, boolean open, boolean mine, int docks,
                        String owner, List<String> members, boolean admitted) {
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
            buf.writeBoolean(entry.admitted());
            buf.writeVarInt(entry.docks());
            buf.writeUtf(entry.owner() == null ? "" : entry.owner(), 64);
            buf.writeVarInt(entry.members().size());
            for (String member : entry.members()) {
                buf.writeUtf(member, 64);
            }
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
            UUID id = buf.readUUID();
            String name = buf.readUtf(DockGroup.MAX_NAME_LENGTH);
            boolean open = buf.readBoolean();
            boolean mine = buf.readBoolean();
            boolean admitted = buf.readBoolean();
            int docks = buf.readVarInt();
            String owner = buf.readUtf(64);
            int memberCount = buf.readVarInt();
            // Members are capped on the way in as well as on the way out. The cap is the group's
            // own, so a well-behaved server never hits it and a malformed packet cannot reserve a
            // list of whatever length it likes.
            if (memberCount < 0 || memberCount > DockGroup.MAX_MEMBERS) {
                throw new io.netty.handler.codec.DecoderException("dock group member count " + memberCount);
            }
            List<String> members = new ArrayList<>(memberCount);
            for (int m = 0; m < memberCount; m++) {
                members.add(buf.readUtf(64));
            }
            groups.add(new Entry(id, name, open, mine, docks, owner, List.copyOf(members), admitted));
        }
        UUID carried = buf.readBoolean() ? buf.readUUID() : null;
        return new DockGroupsS2C(groups, carried);
    }

    public static void handle(DockGroupsS2C msg, IPayloadContext ctx) {
        // Straight to the screen that asked, the way the other client payloads do: the list is
        // only meaningful to a requester screen, and there is at most one open.
        ctx.enqueueWork(() -> {
            // Four screens draw this list now: the terminal's dropdown, the page opened from it, and
            // the two device screens that pick a receiving group (远程红石请求器 / 远仓仪表). Each is
            // sent whichever part it draws — only one is open at a time.
            if (net.minecraft.client.Minecraft.getInstance().screen
                    instanceof dev.distantstock.client.GroupListSink sink) {
                sink.acceptGroups(msg);
            } else if (net.minecraft.client.Minecraft.getInstance().screen
                    instanceof dev.distantstock.client.RequesterScreen screen) {
                screen.applyGroups(msg);
            } else if (net.minecraft.client.Minecraft.getInstance().screen
                    instanceof dev.distantstock.client.DockGroupScreen page) {
                page.applyGroups(msg);
            }
        });
    }

    /**
     * Builds the list one player may see, capped. Server side.
     *
     * <p>{@code nameOf} turns the owner's account into a name to show. It is passed in rather than
     * looked up here so the one place that knows how this server names its players — the profile
     * cache, with the online list in front of it — is the one place that answers.
     */
    public static DockGroupsS2C of(DockGroupDirectory directory, UUID player, UUID carried,
                                   java.util.function.ToIntFunction<UUID> dockCount,
                                   java.util.function.Function<UUID, String> nameOf) {
        List<Entry> out = new ArrayList<>();
        for (DockGroup group : directory.all()) {
            if (group.id().equals(DockGroupDirectory.DEFAULT_GROUP_ID)) {
                // 默认组不进列表。玩家 2026-09-18：「默认港组容易出事，发的东西都进虚空了 / 改为必须
                // 新建或加入一个港组而不是默认的」。它不是一间仓库，是"这个港还没加入任何组"那个占位，
                // 选中它等于没选 —— 而列表里有一行、点得下去，就是在请玩家选它。
                continue;
            }
            boolean admitted = group.admits(player);
            if (!admitted && !group.open()) {
                // 锁着的、又不是给我的：画一行点不动的名字比不画更糟。
                //
                // An open group this player is not in is a different case and does get sent, because
                // there is something to do with it — that is the one they can join. A closed one
                // they are not in has nothing on it for them but the fact that it exists, and a list
                // of names that refuse to be picked is a list a player learns to stop reading.
                continue;
            }
            out.add(new Entry(group.id(), group.name(), group.open(),
                    group.ownedBy(player), dockCount.applyAsInt(group.id()),
                    group.owner() == null ? "" : nameOf.apply(group.owner()),
                    List.copyOf(group.members().values()), admitted));
            if (out.size() >= MAX_ENTRIES) {
                break;
            }
        }
        return new DockGroupsS2C(List.copyOf(out), carried);
    }
}
