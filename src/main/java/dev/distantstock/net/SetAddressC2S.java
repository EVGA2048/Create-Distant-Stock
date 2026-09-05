package dev.distantstock.net;

import dev.distantstock.DistantStock;
import dev.distantstock.menu.RequesterMenu;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Player;
import net.neoforged.neoforge.network.handling.IPayloadContext;

public record SetAddressC2S(String address) implements CustomPacketPayload {
    public static final Type<SetAddressC2S> TYPE = new Type<>(
            ResourceLocation.fromNamespaceAndPath(DistantStock.MODID, "set_address"));
    public static final StreamCodec<RegistryFriendlyByteBuf, SetAddressC2S> STREAM_CODEC = StreamCodec.composite(
            ByteBufCodecs.STRING_UTF8, SetAddressC2S::address, SetAddressC2S::new);

    @Override
    public Type<SetAddressC2S> type() {
        return TYPE;
    }

    public static void handle(SetAddressC2S msg, IPayloadContext ctx) {
        ctx.enqueueWork(() -> {
            Player p = ctx.player();
            if (p.containerMenu instanceof RequesterMenu menu) {
                menu.writeAddress(p, msg.address);
            }
        });
    }
}
