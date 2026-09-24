package dev.distantstock.net;

import dev.distantstock.DistantStock;
import dev.distantstock.block.ModBlocks;
import dev.distantstock.link.LinkSnapshot;
import dev.distantstock.routing.TowerReadout;
import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/** Refreshes an already-open tower-only control screen. */
public record RequestTowerSnapshotC2S(BlockPos core) implements CustomPacketPayload {
    public static final Type<RequestTowerSnapshotC2S> TYPE = new Type<>(
            ResourceLocation.fromNamespaceAndPath(DistantStock.MODID, "request_tower_snapshot"));
    public static final StreamCodec<RegistryFriendlyByteBuf, RequestTowerSnapshotC2S> STREAM_CODEC =
            StreamCodec.composite(BlockPos.STREAM_CODEC, RequestTowerSnapshotC2S::core,
                    RequestTowerSnapshotC2S::new);
    private static final double REACH = 64 * 64;

    @Override public Type<RequestTowerSnapshotC2S> type() { return TYPE; }

    public static void handle(RequestTowerSnapshotC2S msg, IPayloadContext ctx) {
        ctx.enqueueWork(() -> {
            if (!(ctx.player() instanceof ServerPlayer player) || msg.core == null
                    || player.distanceToSqr(msg.core.getCenter()) > REACH
                    || !player.level().getBlockState(msg.core).is(ModBlocks.TOWER_CORE.get())) {
                return;
            }
            PacketDistributor.sendToPlayer(player, new LinkSnapshotS2C(msg.core,
                    LinkSnapshot.view(TowerReadout.survey(player.level(), msg.core))));
        });
    }
}
