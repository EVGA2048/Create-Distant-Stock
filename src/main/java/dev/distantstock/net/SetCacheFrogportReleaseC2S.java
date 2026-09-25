package dev.distantstock.net;

import dev.distantstock.DistantStock;
import dev.distantstock.block.CacheFrogportBlockEntity;
import dev.distantstock.menu.CacheFrogportMenu;
import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/** Changes the Cache Frogport replay mode from its inventory screen. */
public record SetCacheFrogportReleaseC2S(BlockPos pos, int delaySeconds) implements CustomPacketPayload {
    public static final Type<SetCacheFrogportReleaseC2S> TYPE = new Type<>(
            ResourceLocation.fromNamespaceAndPath(DistantStock.MODID, "set_cache_frogport_release"));

    public static final StreamCodec<RegistryFriendlyByteBuf, SetCacheFrogportReleaseC2S> STREAM_CODEC =
            StreamCodec.composite(
                    BlockPos.STREAM_CODEC, SetCacheFrogportReleaseC2S::pos,
                    ByteBufCodecs.VAR_INT, SetCacheFrogportReleaseC2S::delaySeconds,
                    SetCacheFrogportReleaseC2S::new);

    @Override
    public Type<SetCacheFrogportReleaseC2S> type() {
        return TYPE;
    }

    public static void handle(SetCacheFrogportReleaseC2S msg, IPayloadContext ctx) {
        ctx.enqueueWork(() -> {
            if (!(ctx.player().containerMenu instanceof CacheFrogportMenu menu)
                    || menu.contentHolder == null
                    || !menu.contentHolder.getBlockPos().equals(msg.pos)
                    || ctx.player().distanceToSqr(msg.pos.getCenter()) > 64
                    || !(ctx.player().level().getBlockEntity(msg.pos) instanceof CacheFrogportBlockEntity cache)) {
                return;
            }
            cache.setReleaseDelaySeconds(msg.delaySeconds);
        });
    }
}
