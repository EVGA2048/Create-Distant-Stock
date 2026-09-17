package dev.distantstock.net;

import com.mojang.authlib.GameProfile;
import dev.distantstock.DistantStock;
import dev.distantstock.menu.RequesterMenu;
import dev.distantstock.routing.DockGroup;
import dev.distantstock.routing.DockGroupDirectory;
import dev.distantstock.routing.PlayerNames;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.entity.player.Player;
import net.neoforged.neoforge.network.handling.IPayloadContext;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Naming somebody in a receiving-dock group, and taking the name back out.
 *
 * <p>The one thing this grants is what the group's own lock already guards: being named lets a
 * player use a closed group — add docks to it, point a sender at it, order into it. Nothing else.
 * Renaming it, opening and closing it, deleting it and naming anybody else all stay with the owner,
 * because a member who could name members is an owner with fewer words.
 *
 * <p><b>Ownership is checked here, not in the screen.</b> The list only draws the button on rows
 * the player owns, but a screen is not a lock: without this check the first player to write a
 * packet could hand themselves a key to somebody else's warehouse. The check is the same one
 * {@link SetDockGroupC2S} runs for opening and closing, on the same field.
 *
 * <p>Adding is by name because a player has a name and no uuid — the uuid is looked up on this side
 * from the player list and the profile cache. A name neither of those knows is refused with a
 * message rather than stored: a membership at an account nobody owns is worse than no membership,
 * because it looks like it worked.
 *
 * <p><b>Joining is the player naming themselves</b>, and it is the one thing here that does not
 * need the owner. Everything else in this file is the owner handing out a key; this is a player
 * picking up a key the owner left in the open, which is what an unlocked group is. The two are
 * checked against different fields — ownership for one, the lock for the other — and mixing them up
 * would either let anybody into a locked network or make an open one impossible to enter without
 * the owner being awake.
 */
public record GroupMemberC2S(String group, String player, int action) implements CustomPacketPayload {
    /** Name somebody in the group. */
    public static final int ADD = 0;
    /** Take the name back out. */
    public static final int REMOVE = 1;
    /**
     * Put yourself in. The one action here a player who owns nothing may take.
     *
     * <p>It exists because the alternative is worse: without it, the only way into somebody else's
     * network is for them to type your name, and a network whose owner is asleep cannot be joined at
     * all. An open group is one whose owner has already said yes to everybody — that yes is the
     * permission this checks, and it is checked here rather than in the screen.
     */
    public static final int JOIN = 2;
    /** Take yourself back out. */
    public static final int LEAVE = 3;

    public static final Type<GroupMemberC2S> TYPE = new Type<>(
            ResourceLocation.fromNamespaceAndPath(DistantStock.MODID, "group_member"));
    public static final StreamCodec<RegistryFriendlyByteBuf, GroupMemberC2S> STREAM_CODEC =
            StreamCodec.composite(
                    ByteBufCodecs.STRING_UTF8, GroupMemberC2S::group,
                    ByteBufCodecs.STRING_UTF8, GroupMemberC2S::player,
                    ByteBufCodecs.VAR_INT, GroupMemberC2S::action,
                    GroupMemberC2S::new);

    @Override
    public Type<GroupMemberC2S> type() {
        return TYPE;
    }

    public static void handle(GroupMemberC2S msg, IPayloadContext ctx) {
        ctx.enqueueWork(() -> {
            Player player = ctx.player();
            MinecraftServer server = player.level().getServer();
            if (server == null) {
                return;
            }
            DockGroupDirectory directory = DockGroupDirectory.get(server);
            DockGroup group = directory.findByName(msg.group).orElse(null);
            if (group == null) {
                player.displayClientMessage(
                        Component.translatable("gui.distantstock.group.unknown_name", msg.group), false);
                return;
            }
            // Joining and leaving are about the sender, so they are answered before the ownership
            // check that guards the other two: the whole point of them is that the player asking
            // owns nothing.
            if (msg.action == JOIN) {
                join(player, directory, group);
            } else if (msg.action == LEAVE) {
                leave(player, directory, group);
            } else if (!group.ownedBy(player.getUUID())) {
                player.displayClientMessage(
                        Component.translatable("gui.distantstock.member.not_owner"), false);
                return;
            } else if (msg.action == ADD) {
                add(server, player, directory, group, msg.player);
            } else if (msg.action == REMOVE) {
                remove(server, player, directory, group, msg.player);
            }
            // The list the screen is drawing is stale the moment a name changes, and the screen has
            // no way to ask. Pushed with the item in hand, which the menu ignores while it knows
            // the group the requester carries.
            RequesterMenu.sendGroupList(player, player.getMainHandItem());
        });
    }

    /**
     * The player adds themselves, which only an open group allows.
     *
     * <p>The owner is refused rather than quietly accepted: they are already in by definition, and a
     * "you joined" message for a network they made would read as though something had been granted.
     */
    private static void join(Player player, DockGroupDirectory directory, DockGroup group) {
        UUID id = player.getUUID();
        // One rule decides, and the messages below only say which half of it was not met: "nothing
        // happened" and "the owner has to let you in" look identical from a list of names, and the
        // second one is the answer the player needs.
        if (!group.joinableBy(id)) {
            if (group.ownedBy(id)) {
                player.displayClientMessage(
                        Component.translatable("gui.distantstock.member.join.owner", group.name()), false);
            } else if (group.hasMember(id)) {
                player.displayClientMessage(
                        Component.translatable("gui.distantstock.member.join.already", group.name()), false);
            } else {
                player.displayClientMessage(
                        Component.translatable("gui.distantstock.member.join.closed", group.name()), false);
            }
            return;
        }
        if (group.members().size() >= DockGroup.MAX_MEMBERS) {
            player.displayClientMessage(Component.translatable("gui.distantstock.member.full",
                    DockGroup.MAX_MEMBERS), false);
            return;
        }
        directory.addMember(group.id(), id, player.getGameProfile().getName());
        player.displayClientMessage(
                Component.translatable("gui.distantstock.member.joined", group.name()), false);
    }

    /**
     * The player takes themselves back out.
     *
     * <p>The owner cannot: they are in by definition and the way out of a network you own is to
     * delete it or to hand it over, neither of which is a membership change. Removing the owner from
     * their own list would leave a group whose owner is not in it, which is not a state any other
     * part of this mod has an answer for.
     */
    private static void leave(Player player, DockGroupDirectory directory, DockGroup group) {
        UUID id = player.getUUID();
        if (group.ownedBy(id)) {
            player.displayClientMessage(
                    Component.translatable("gui.distantstock.member.leave.owner", group.name()), false);
            return;
        }
        if (!group.hasMember(id)) {
            player.displayClientMessage(
                    Component.translatable("gui.distantstock.member.leave.not_member", group.name()), false);
            return;
        }
        directory.removeMember(group.id(), id);
        player.displayClientMessage(
                Component.translatable("gui.distantstock.member.left", group.name()), false);
    }

    private static void add(MinecraftServer server, Player player, DockGroupDirectory directory,
                            DockGroup group, String typed) {
        String name = typed == null ? "" : typed.trim();
        if (name.isEmpty()) {
            return;
        }
        if (group.members().size() >= DockGroup.MAX_MEMBERS) {
            player.displayClientMessage(Component.translatable("gui.distantstock.member.full",
                    DockGroup.MAX_MEMBERS), false);
            return;
        }
        Optional<GameProfile> account = PlayerNames.lookup(server, name);
        if (account.isEmpty()) {
            player.displayClientMessage(
                    Component.translatable("gui.distantstock.member.unknown", name), false);
            return;
        }
        UUID id = account.get().getId();
        String accountName = account.get().getName();
        if (group.ownedBy(id)) {
            player.displayClientMessage(Component.translatable("gui.distantstock.member.is_owner",
                    accountName), false);
            return;
        }
        if (group.hasMember(id)) {
            // Re-adding is how a name that went stale gets refreshed, so it still writes — but the
            // player is told it was not new, or the list would look like it ignored them.
            directory.addMember(group.id(), id, accountName);
            player.displayClientMessage(Component.translatable("gui.distantstock.member.already",
                    accountName), false);
            return;
        }
        directory.addMember(group.id(), id, accountName);
        player.displayClientMessage(Component.translatable("gui.distantstock.member.added",
                accountName, group.name()), false);
    }

    /**
     * Takes a name out of the list.
     *
     * <p>The row the player clicked shows a name, and that stored name is what is matched first —
     * it is the only handle the client has. A player who has since changed their account name is
     * matched by looking the typed name up as well, which is the same lookup adding runs.
     */
    private static void remove(MinecraftServer server, Player player, DockGroupDirectory directory,
                               DockGroup group, String typed) {
        String name = typed == null ? "" : typed.trim();
        if (name.isEmpty()) {
            return;
        }
        UUID id = null;
        for (Map.Entry<UUID, String> row : group.members().entrySet()) {
            if (row.getValue() != null && row.getValue().equalsIgnoreCase(name)) {
                id = row.getKey();
                break;
            }
        }
        if (id == null) {
            id = PlayerNames.lookup(server, name)
                    .filter(account -> group.hasMember(account.getId()))
                    .map(GameProfile::getId)
                    .orElse(null);
        }
        if (id == null) {
            player.displayClientMessage(
                    Component.translatable("gui.distantstock.member.not_member", name), false);
            return;
        }
        directory.removeMember(group.id(), id);
        player.displayClientMessage(
                Component.translatable("gui.distantstock.member.removed", name, group.name()), false);
    }
}
