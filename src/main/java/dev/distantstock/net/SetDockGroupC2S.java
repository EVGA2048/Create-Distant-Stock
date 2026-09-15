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
 * Names the receiving dock group a requester points at.
 *
 * <p>A name, not an id. The design has players seeing a renameable display name and never a UUID,
 * so the name is what travels and the server is what turns it into an identity. A name nobody has
 * used yet becomes a new group: that is the whole of "creating a system", and it is deliberately
 * the same gesture as selecting one, because a player who has to first create a system and then
 * select it is being asked to do one thing twice.
 *
 * <p>The world half of the pairing is in {@link dev.distantstock.block.DockBlock}: sneak to make a
 * dock join the carried group, plain click to make it send there.
 */
public record SetDockGroupC2S(String name, boolean rename) implements CustomPacketPayload {
    public static final Type<SetDockGroupC2S> TYPE = new Type<>(
            ResourceLocation.fromNamespaceAndPath(DistantStock.MODID, "set_dock_group"));
    public static final StreamCodec<RegistryFriendlyByteBuf, SetDockGroupC2S> STREAM_CODEC =
            StreamCodec.composite(
                    ByteBufCodecs.STRING_UTF8, SetDockGroupC2S::name,
                    ByteBufCodecs.BOOL, SetDockGroupC2S::rename,
                    SetDockGroupC2S::new);

    @Override
    public Type<SetDockGroupC2S> type() {
        return TYPE;
    }

    public static void handle(SetDockGroupC2S msg, IPayloadContext ctx) {
        ctx.enqueueWork(() -> {
            Player player = ctx.player();
            if (player.containerMenu instanceof RequesterMenu menu) {
                menu.writeDockGroup(player, msg.name, msg.rename);
            }
        });
    }
}
