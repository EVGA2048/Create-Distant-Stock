package dev.distantstock.net;

import dev.distantstock.DistantStock;
import dev.distantstock.block.DockBlockEntity;
import dev.distantstock.menu.DockMenu;
import dev.distantstock.routing.DockGroupDirectory;
import dev.distantstock.routing.DockMode;
import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/** Atomic edit of the one-page Distant Dock form. */
public record ConfigureDockC2S(BlockPos pos, String name, String address, int mode, int priority)
        implements CustomPacketPayload {
    public static final Type<ConfigureDockC2S> TYPE = new Type<>(
            ResourceLocation.fromNamespaceAndPath(DistantStock.MODID, "configure_dock"));
    public static final StreamCodec<RegistryFriendlyByteBuf, ConfigureDockC2S> STREAM_CODEC =
            StreamCodec.composite(
                    BlockPos.STREAM_CODEC, ConfigureDockC2S::pos,
                    ByteBufCodecs.STRING_UTF8, ConfigureDockC2S::name,
                    ByteBufCodecs.STRING_UTF8, ConfigureDockC2S::address,
                    ByteBufCodecs.VAR_INT, ConfigureDockC2S::mode,
                    ByteBufCodecs.VAR_INT, ConfigureDockC2S::priority,
                    ConfigureDockC2S::new);

    @Override public Type<ConfigureDockC2S> type() { return TYPE; }

    public static void handle(ConfigureDockC2S msg, IPayloadContext ctx) {
        ctx.enqueueWork(() -> {
            if (!(ctx.player().containerMenu instanceof DockMenu menu) || !menu.dockPos.equals(msg.pos)
                    || ctx.player().distanceToSqr(msg.pos.getCenter()) > 64
                    || !(ctx.player().level().getBlockEntity(msg.pos) instanceof DockBlockEntity dock)) {
                return;
            }
            int modeIndex = Math.max(0, Math.min(DockMode.values().length - 1, msg.mode));
            DockMode nextMode = DockMode.values()[modeIndex];
            String name = clean(msg.name, 48);
            String address = clean(msg.address, 64);

            java.util.UUID scope = dock.distantNetworkScope();
            if (!address.isBlank()) {
                if (scope == null) {
                    ctx.player().displayClientMessage(Component.translatable(
                            "message.distantstock.dock.bind_network_first"), true);
                    return;
                }
                var group = DockGroupDirectory.get(ctx.player().getServer())
                        .resolveAddress(scope, address);
                dock.setGroupId(group.id());
            } else if (nextMode != DockMode.SEND) {
                ctx.player().displayClientMessage(Component.translatable(
                        "message.distantstock.dock.address_required"), true);
                return;
            }

            dock.setCustomName(name);
            dock.setMode(nextMode);
            dock.setPriority(msg.priority);
            ctx.player().displayClientMessage(Component.translatable(
                    "message.distantstock.dock.config_saved"), true);
        });
    }

    private static String clean(String value, int max) {
        String clean = value == null ? "" : value.trim();
        return clean.length() <= max ? clean : clean.substring(0, max);
    }
}
