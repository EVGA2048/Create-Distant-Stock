package dev.distantstock.net;

import com.simibubi.create.Create;
import dev.distantstock.DistantStock;
import dev.distantstock.menu.RequesterMenu;
import dev.distantstock.routing.RemoteNetworkId;
import dev.distantstock.stock.CreateNetworkAccess;
import dev.distantstock.stock.NetworkDirectory;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.handling.IPayloadContext;

import java.util.UUID;

/** Create's own logistics-network lock state for the local warehouse selected in a requester. */
public record CreateNetworkLockS2C(boolean visible, boolean admin, boolean locked)
        implements CustomPacketPayload {
    public static final Type<CreateNetworkLockS2C> TYPE = new Type<>(
            ResourceLocation.fromNamespaceAndPath(DistantStock.MODID, "create_network_lock"));
    public static final StreamCodec<RegistryFriendlyByteBuf, CreateNetworkLockS2C> STREAM_CODEC =
            StreamCodec.composite(
                    ByteBufCodecs.BOOL, CreateNetworkLockS2C::visible,
                    ByteBufCodecs.BOOL, CreateNetworkLockS2C::admin,
                    ByteBufCodecs.BOOL, CreateNetworkLockS2C::locked,
                    CreateNetworkLockS2C::new);

    @Override
    public Type<CreateNetworkLockS2C> type() {
        return TYPE;
    }

    public static void send(ServerPlayer player, RequesterMenu menu) {
        if (player == null || menu == null) return;
        // This mirrors Create's Stock Keeper control. A portable terminal must not become a
        // remote admin switch for a Create logistics network just because it can order from it.
        if (!menu.isGauge()) {
            PacketDistributor.sendToPlayer(player, new CreateNetworkLockS2C(false, false, false));
            return;
        }
        UUID freq = menu.freq(player);
        RemoteNetworkId network = menu.networkId(player);
        NetworkDirectory.Entry row = freq == null ? null
                : NetworkDirectory.findByFreq(freq).orElse(null);
        boolean local = row != null && row.local()
                && (network == null || row.networkId() == null || row.networkId().equals(network));
        if (!local) {
            PacketDistributor.sendToPlayer(player, new CreateNetworkLockS2C(false, false, false));
            return;
        }
        var logistics = Create.LOGISTICS.logisticsNetworks.get(freq);
        boolean admin = CreateNetworkAccess.mayAdministrate(row.networkId(), freq, player);
        PacketDistributor.sendToPlayer(player,
                new CreateNetworkLockS2C(true, admin, logistics != null && logistics.locked));
    }

    public static void handle(CreateNetworkLockS2C msg, IPayloadContext ctx) {
        ctx.enqueueWork(() -> {
            if (net.minecraft.client.Minecraft.getInstance().screen
                    instanceof dev.distantstock.client.RequesterScreen screen) {
                screen.applyCreateNetworkLock(msg);
            }
        });
    }
}
