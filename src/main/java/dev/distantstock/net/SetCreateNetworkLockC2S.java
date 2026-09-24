package dev.distantstock.net;

import com.simibubi.create.Create;
import dev.distantstock.DistantStock;
import dev.distantstock.menu.RequesterMenu;
import dev.distantstock.stock.CreateNetworkAccess;
import dev.distantstock.stock.NetworkDirectory;
import dev.distantstock.event.EventRegistry;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/** Toggle or refresh Create's native logistics-network lock for the selected local warehouse. */
public record SetCreateNetworkLockC2S(boolean change, boolean locked) implements CustomPacketPayload {
    public static final Type<SetCreateNetworkLockC2S> TYPE = new Type<>(
            ResourceLocation.fromNamespaceAndPath(DistantStock.MODID, "set_create_network_lock"));
    public static final StreamCodec<RegistryFriendlyByteBuf, SetCreateNetworkLockC2S> STREAM_CODEC =
            StreamCodec.composite(
                    ByteBufCodecs.BOOL, SetCreateNetworkLockC2S::change,
                    ByteBufCodecs.BOOL, SetCreateNetworkLockC2S::locked,
                    SetCreateNetworkLockC2S::new);

    @Override
    public Type<SetCreateNetworkLockC2S> type() {
        return TYPE;
    }

    public static void handle(SetCreateNetworkLockC2S msg, IPayloadContext ctx) {
        ctx.enqueueWork(() -> {
            if (!(ctx.player() instanceof ServerPlayer player)
                    || !(player.containerMenu instanceof RequesterMenu menu)) {
                return;
            }
            if (!menu.isGauge()) {
                CreateNetworkLockS2C.send(player, menu);
                return;
            }
            var freq = menu.freq(player);
            var row = freq == null ? null : NetworkDirectory.findByFreq(freq).orElse(null);
            if (row == null || !row.local()) {
                CreateNetworkLockS2C.send(player, menu);
                return;
            }
            if (msg.change()) {
                if (!CreateNetworkAccess.mayAdministrate(row.networkId(), freq, player)) {
                    player.displayClientMessage(net.minecraft.network.chat.Component.translatable(
                            "message.distantstock.network.create_admin_required"), true);
                    CreateNetworkLockS2C.send(player, menu);
                    return;
                }
                var logistics = Create.LOGISTICS.logisticsNetworks.get(freq);
                if (logistics != null) {
                    boolean changed = logistics.locked != msg.locked();
                    logistics.locked = msg.locked();
                    Create.LOGISTICS.markDirty();
                    if (changed && msg.locked()) {
                        // Locking a warehouse is an operator action, not a fault. Keep one completed
                        // INFO row for the control room and let bound loggers chime once when they
                        // observe it. Because the record is cleared immediately it can never enter
                        // the alarm/ACK/printing path or consume incident paper.
                        long now = System.currentTimeMillis();
                        EventRegistry events = EventRegistry.get(player.getServer());
                        String source = "network:" + freq;
                        String detail = player.getGameProfile().getName()
                                + " locked the Create logistics network";
                        events.raise(EventRegistry.Severity.INFO, EventRegistry.Codes.NETWORK_LOCKED,
                                "network", source, detail, freq, null, now);
                        events.clear(EventRegistry.Codes.NETWORK_LOCKED, "network", source, now);
                    }
                }
            }
            CreateNetworkLockS2C.send(player, menu);
        });
    }
}
