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

/**
 * The two addresses on the terminal, written to whatever the screen is standing on.
 *
 * <p>Both travel in one payload because both fields are on one screen and either may be edited at
 * any moment: one packet per field would be two writers of one record, and the second to arrive
 * would be the truth regardless of which was typed last.
 *
 * <p>{@code address} is the one the parcel is packed with — the address the <em>other</em> server
 * sorts it by. {@code homeAddress} is the one it wears after crossing, which is the address this
 * server sorts it by when it comes home. Blank means "do not change it on arrival".
 */
public record SetAddressC2S(String address, String homeAddress) implements CustomPacketPayload {
    public static final Type<SetAddressC2S> TYPE = new Type<>(
            ResourceLocation.fromNamespaceAndPath(DistantStock.MODID, "set_address"));
    public static final StreamCodec<RegistryFriendlyByteBuf, SetAddressC2S> STREAM_CODEC = StreamCodec.composite(
            ByteBufCodecs.STRING_UTF8, SetAddressC2S::address,
            ByteBufCodecs.STRING_UTF8, SetAddressC2S::homeAddress,
            SetAddressC2S::new);

    /** One address, for anything that still writes only the first. */
    public SetAddressC2S(String address) {
        this(address, "");
    }

    @Override
    public Type<SetAddressC2S> type() {
        return TYPE;
    }

    public static void handle(SetAddressC2S msg, IPayloadContext ctx) {
        ctx.enqueueWork(() -> {
            Player p = ctx.player();
            if (p.containerMenu instanceof RequesterMenu menu) {
                menu.writeAddress(p, msg.address);
                menu.writeHomeAddress(p, msg.homeAddress);
            }
        });
    }
}
