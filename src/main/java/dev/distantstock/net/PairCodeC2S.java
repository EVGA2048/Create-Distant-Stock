package dev.distantstock.net;

import dev.distantstock.DistantStock;
import dev.distantstock.link.PairingService;
import dev.distantstock.routing.DockGroup;
import dev.distantstock.routing.DockGroupDirectory;
import dev.distantstock.routing.PairingCodes;
import dev.distantstock.routing.RemoteGroups;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.entity.player.Player;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.handling.IPayloadContext;

import java.util.UUID;

/**
 * What the requester's screen asks about pairing codes: mint one, spend one, forget one.
 *
 * <p>One payload with an action rather than three, because all three are the same two strings and
 * the alternative is three near-identical registrations. The action travels as a short name and is
 * checked against a closed set here — an unknown action is ignored rather than guessed at.
 *
 * <p><b>Minting is checked against ownership on this side.</b> The screen decides which groups to
 * offer the button for, but a screen is not a lock: the group's owner is looked up from the
 * directory before a code exists, or the first player to open a screen could mint access to
 * somebody else's warehouse.
 */
public record PairCodeC2S(String action, String value, int minutes) implements CustomPacketPayload {
    public static final String ISSUE = "issue";
    public static final String REDEEM = "redeem";
    public static final String FORGET = "forget";

    public static final Type<PairCodeC2S> TYPE = new Type<>(
            ResourceLocation.fromNamespaceAndPath(DistantStock.MODID, "pair_code"));
    public static final StreamCodec<RegistryFriendlyByteBuf, PairCodeC2S> STREAM_CODEC =
            StreamCodec.composite(
                    ByteBufCodecs.STRING_UTF8, PairCodeC2S::action,
                    ByteBufCodecs.STRING_UTF8, PairCodeC2S::value,
                    ByteBufCodecs.VAR_INT, PairCodeC2S::minutes,
                    PairCodeC2S::new);

    @Override
    public Type<PairCodeC2S> type() {
        return TYPE;
    }

    public static void handle(PairCodeC2S msg, IPayloadContext ctx) {
        ctx.enqueueWork(() -> {
            Player player = ctx.player();
            MinecraftServer server = player.level().getServer();
            if (server == null) {
                return;
            }
            switch (msg.action == null ? "" : msg.action) {
                case ISSUE -> issue(server, player, msg.value, msg.minutes);
                case REDEEM -> PairingService.redeem(server, player, msg.value);
                case FORGET -> forget(server, player, msg.value);
                default -> {
                }
            }
        });
    }

    /**
     * Mints a code for a group the player owns and reads it back to them.
     *
     * <p>In chat rather than in the screen: a code is meant to leave this server — it goes into
     * Discord, into a voice call, into whatever the two players already use — and chat is the one
     * place in the game a line of text can be selected and copied from.
     */
    private static void issue(MinecraftServer server, Player player, String name, int minutes) {
        DockGroupDirectory directory = DockGroupDirectory.get(server);
        DockGroup group = directory.findByName(name).orElse(null);
        if (group == null) {
            player.displayClientMessage(Component.translatable("gui.distantstock.pair.no_group", name), false);
            return;
        }
        if (!group.ownedBy(player.getUUID())) {
            player.displayClientMessage(Component.translatable("gui.distantstock.pair.not_owner"), false);
            return;
        }
        PairingCodes.Code code = PairingCodes.get(server).issue(group.id(), player.getUUID(),
                minutes <= 0 ? PairingCodes.DEFAULT_MINUTES : minutes, System.currentTimeMillis());
        player.displayClientMessage(Component.translatable("gui.distantstock.pair.minted", code.code(),
                group.name(), Math.max(1, Math.min(minutes <= 0 ? PairingCodes.DEFAULT_MINUTES : minutes,
                        PairingCodes.MAX_MINUTES))), false);
        sendList(server, player);
    }

    /** Drops a remote destination. The parcels already on their way still land; this is a memory. */
    private static void forget(MinecraftServer server, Player player, String id) {
        UUID group;
        try {
            group = UUID.fromString(id);
        } catch (IllegalArgumentException malformed) {
            return;
        }
        if (RemoteGroups.get(server).forget(group)) {
            player.displayClientMessage(Component.translatable("gui.distantstock.pair.forgotten"), false);
        }
        sendList(server, player);
    }

    /** Pushes the remote destinations to whoever asked, in the screen's own shape. */
    public static void sendList(MinecraftServer server, Player player) {
        if (player instanceof net.minecraft.server.level.ServerPlayer online) {
            PacketDistributor.sendToPlayer(online, RemoteGroupsS2C.of(RemoteGroups.get(server)));
        }
    }
}
