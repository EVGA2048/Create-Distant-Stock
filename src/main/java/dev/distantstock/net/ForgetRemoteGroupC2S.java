package dev.distantstock.net;

import dev.distantstock.DistantStock;
import dev.distantstock.routing.RemoteGroups;
import net.minecraft.core.UUIDUtil;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.network.handling.IPayloadContext;

import java.util.UUID;

/**
 * "I am not interested in this destination any more."
 *
 * <p>One group id and nothing else, because it is the whole of what a player can do to a row that
 * came from another server: the group is not this server's to rename, open, close or delete, and
 * the identity of it — the id — is only known here because the other server announced it.
 *
 * <p>The row is hidden rather than deleted, and the hide holds only while the far side says the
 * same thing about the group. See {@link RemoteGroups#forget} for why: the peer re-announces its
 * whole list every 45 seconds, so a delete would be undone before the player had closed the screen
 * and the button would look broken.
 *
 * <p>Resolved by id and never by name. Two servers can both have a group called 仓库, and a command
 * that acted on the nearer match would sometimes hide the local one — which is not even in this
 * list.
 */
public record ForgetRemoteGroupC2S(UUID group) implements CustomPacketPayload {
    public static final Type<ForgetRemoteGroupC2S> TYPE = new Type<>(
            ResourceLocation.fromNamespaceAndPath(DistantStock.MODID, "forget_remote_group"));
    public static final StreamCodec<RegistryFriendlyByteBuf, ForgetRemoteGroupC2S> STREAM_CODEC =
            StreamCodec.composite(UUIDUtil.STREAM_CODEC, ForgetRemoteGroupC2S::group,
                    ForgetRemoteGroupC2S::new);

    @Override
    public Type<ForgetRemoteGroupC2S> type() {
        return TYPE;
    }

    public static void handle(ForgetRemoteGroupC2S msg, IPayloadContext ctx) {
        ctx.enqueueWork(() -> {
            var server = ctx.player().getServer();
            if (server == null || msg.group() == null) {
                return;
            }
            RemoteGroups.get(server).forget(msg.group());
        });
    }
}
