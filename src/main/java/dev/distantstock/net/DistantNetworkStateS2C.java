package dev.distantstock.net;

import dev.distantstock.DistantStock;
import dev.distantstock.routing.DistantNetworkDirectory;
import dev.distantstock.routing.RemoteNetworkId;
import dev.distantstock.stock.NetworkDirectory;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.handling.IPayloadContext;

import java.util.UUID;

/** Current Distant Stock network membership for the warehouse selected by an open terminal. */
public record DistantNetworkStateS2C(boolean localWarehouse, UUID networkId, String networkName,
                                     boolean owner, String joinCode) implements CustomPacketPayload {
    public static final Type<DistantNetworkStateS2C> TYPE = new Type<>(
            ResourceLocation.fromNamespaceAndPath(DistantStock.MODID, "distant_network_state"));
    public static final StreamCodec<RegistryFriendlyByteBuf, DistantNetworkStateS2C> STREAM_CODEC =
            StreamCodec.of(DistantNetworkStateS2C::write, DistantNetworkStateS2C::read);

    @Override
    public Type<DistantNetworkStateS2C> type() {
        return TYPE;
    }

    public boolean joined() {
        return networkId != null;
    }

    public static void send(ServerPlayer player, RemoteNetworkId member) {
        send(player, member, null);
    }

    public static void send(ServerPlayer player, RemoteNetworkId member, UUID scopeHint) {
        if (player == null || player.getServer() == null) return;
        boolean local = member != null && NetworkDirectory.local().stream()
                .anyMatch(entry -> member.equals(entry.networkId()));
        if (member == null) {
            PacketDistributor.sendToPlayer(player,
                    new DistantNetworkStateS2C(false, null, "", false, ""));
            return;
        }
        DistantNetworkDirectory directory = DistantNetworkDirectory.get(player.getServer());
        // A remote warehouse may belong to this Distant Stock network without having a local
        // membership row in our SavedData. Its current announcement is authoritative for the
        // visible scope, so prefer NetworkDirectory before falling back to locally persisted data.
        UUID scope = scopeHint != null ? scopeHint
                : NetworkDirectory.find(member)
                .map(NetworkDirectory.Entry::distantNetworkId)
                .orElseGet(() -> directory.scopeOf(member));
        var network = directory.find(scope).orElse(null);
        if (network == null || network.legacy()) {
            PacketDistributor.sendToPlayer(player,
                    new DistantNetworkStateS2C(local, null, "", false, ""));
            return;
        }
        boolean owner = network.ownedBy(player.getUUID())
                && network.authoritativeOn(dev.distantstock.link.TranserverBridge.nodeId());
        PacketDistributor.sendToPlayer(player, new DistantNetworkStateS2C(
                local, network.id(), network.name(), owner, owner ? network.joinCode() : ""));
    }

    public static void handle(DistantNetworkStateS2C msg, IPayloadContext ctx) {
        ctx.enqueueWork(() -> {
            if (net.minecraft.client.Minecraft.getInstance().screen
                    instanceof dev.distantstock.client.DistantNetworkScreen screen) {
                screen.apply(msg);
            }
        });
    }

    private static void write(RegistryFriendlyByteBuf buf, DistantNetworkStateS2C msg) {
        buf.writeBoolean(msg.localWarehouse);
        buf.writeBoolean(msg.networkId != null);
        if (msg.networkId != null) buf.writeUUID(msg.networkId);
        buf.writeUtf(msg.networkName == null ? "" : msg.networkName, DistantNetworkDirectory.MAX_NAME_LENGTH);
        buf.writeBoolean(msg.owner);
        buf.writeUtf(msg.joinCode == null ? "" : msg.joinCode, 9);
    }

    private static DistantNetworkStateS2C read(RegistryFriendlyByteBuf buf) {
        boolean local = buf.readBoolean();
        UUID id = buf.readBoolean() ? buf.readUUID() : null;
        String name = buf.readUtf(DistantNetworkDirectory.MAX_NAME_LENGTH);
        boolean owner = buf.readBoolean();
        String code = buf.readUtf(9);
        return new DistantNetworkStateS2C(local, id, name, owner, code);
    }
}
