package dev.distantstock.net;

import com.simibubi.create.content.logistics.factoryBoard.FactoryPanelBlock;
import dev.distantstock.DistantStock;
import dev.distantstock.block.RemoteGaugeBlockEntity;
import dev.distantstock.block.RemoteRedstoneRequesterBlockEntity;
import dev.distantstock.block.SignalPanelBlockEntity;
import dev.distantstock.link.DistantNetworkJoinService;
import dev.distantstock.routing.DistantNetworkDirectory;
import dev.distantstock.routing.RemoteNetworkId;
import dev.distantstock.stock.NetworkDirectory;
import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.handling.IPayloadContext;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** Network context and visible warehouse members for one distant device. */
public record DistantDeviceStateS2C(BlockPos pos, int slot, UUID scope, String networkName,
                                    RemoteNetworkId selected, List<Member> members)
        implements CustomPacketPayload {
    public record Member(RemoteNetworkId network, String warehouseName, String server,
                         boolean local, boolean packable) {
    }

    public static final Type<DistantDeviceStateS2C> TYPE = new Type<>(
            ResourceLocation.fromNamespaceAndPath(DistantStock.MODID, "distant_device_state"));
    public static final net.minecraft.network.codec.StreamCodec<RegistryFriendlyByteBuf, DistantDeviceStateS2C>
            STREAM_CODEC = net.minecraft.network.codec.StreamCodec.of(
            DistantDeviceStateS2C::write, DistantDeviceStateS2C::read);

    @Override
    public Type<DistantDeviceStateS2C> type() {
        return TYPE;
    }

    public boolean joined() {
        return DistantNetworkDirectory.isFormalId(scope);
    }

    public static void send(ServerPlayer player, DistantNetworkJoinService.DeviceTarget target) {
        if (player == null || player.getServer() == null || target == null) return;
        var level = player.getServer().getLevel(target.dimension());
        if (level == null) return;
        var blockEntity = level.getBlockEntity(target.pos());
        UUID scope = null;
        RemoteNetworkId selected = null;
        UUID receivingGroup = null;
        if (blockEntity instanceof RemoteRedstoneRequesterBlockEntity requester && target.slot() < 0) {
            scope = requester.distantNetworkScope();
            selected = requester.binding() == null ? null : requester.binding().network();
            receivingGroup = requester.binding() == null ? null : requester.binding().receivingGroup();
        } else {
            FactoryPanelBlock.PanelSlot[] slots = FactoryPanelBlock.PanelSlot.values();
            if (target.slot() < 0 || target.slot() >= slots.length) return;
            FactoryPanelBlock.PanelSlot panel = slots[target.slot()];
            if (blockEntity instanceof RemoteGaugeBlockEntity gauge) {
                scope = gauge.distantNetworkScope(panel);
                selected = gauge.binding(panel) == null ? null : gauge.binding(panel).network();
                receivingGroup = gauge.binding(panel) == null ? null : gauge.binding(panel).receivingGroup();
            } else if (blockEntity instanceof SignalPanelBlockEntity signal && signal.isRemoteGauge(panel)) {
                scope = signal.distantNetworkScope(panel);
                selected = signal.binding(panel) == null ? null : signal.binding(panel).network();
                receivingGroup = signal.binding(panel) == null ? null : signal.binding(panel).receivingGroup();
            } else if (blockEntity instanceof com.simibubi.create.content.logistics.factoryBoard.FactoryPanelBlockEntity board
                    && net.neoforged.fml.ModList.get().isLoaded("deployer")
                    && dev.distantstock.panel.DeployerPanels.holdsRemoteGauge(board, panel)) {
                scope = dev.distantstock.panel.DeployerPanels.distantNetworkScope(board, panel);
                var binding = dev.distantstock.panel.DeployerPanels.bindingOf(board, panel);
                selected = binding == null ? null : binding.network();
                receivingGroup = binding == null ? null : binding.receivingGroup();
            } else {
                return;
            }
        }

        String name = "";
        List<Member> members = new ArrayList<>();
        if (DistantNetworkDirectory.isFormalId(scope)) {
            name = DistantNetworkDirectory.get(player.getServer()).find(scope)
                    .map(DistantNetworkDirectory.Network::name).orElse("");
            for (NetworkDirectory.Entry entry : NetworkDirectory.visible(true)) {
                if (!scope.equals(entry.distantNetworkId()) || entry.networkId() == null) continue;
                members.add(new Member(entry.networkId(), entry.warehouseName(), entry.server(),
                        entry.local(), entry.packable()));
                if (members.size() >= 128) break;
            }
        }
        PacketDistributor.sendToPlayer(player,
                new DistantDeviceStateS2C(target.pos(), target.slot(), scope, name,
                        selected, List.copyOf(members)));
        if (DistantNetworkDirectory.isFormalId(scope)) {
            dev.distantstock.menu.RequesterMenu.sendGroupList(player, scope, receivingGroup);
        }
    }

    public static void handle(DistantDeviceStateS2C msg, IPayloadContext ctx) {
        ctx.enqueueWork(() -> {
            var screen = net.minecraft.client.Minecraft.getInstance().screen;
            if (screen instanceof dev.distantstock.client.RemoteGaugeScreen gauge) {
                gauge.applyDistantNetworkState(msg);
            } else if (screen instanceof dev.distantstock.client.RemoteRedstoneRequesterScreen requester) {
                requester.applyDistantNetworkState(msg);
            }
        });
    }

    private static void write(RegistryFriendlyByteBuf buf, DistantDeviceStateS2C msg) {
        buf.writeBlockPos(msg.pos());
        buf.writeVarInt(msg.slot());
        buf.writeBoolean(msg.scope() != null);
        if (msg.scope() != null) buf.writeUUID(msg.scope());
        buf.writeUtf(msg.networkName() == null ? "" : msg.networkName(), DistantNetworkDirectory.MAX_NAME_LENGTH);
        buf.writeBoolean(msg.selected() != null);
        if (msg.selected() != null) buf.writeNbt(msg.selected().save());
        int count = Math.min(128, msg.members() == null ? 0 : msg.members().size());
        buf.writeVarInt(count);
        for (int i = 0; i < count; i++) {
            Member member = msg.members().get(i);
            buf.writeNbt(member.network().save());
            buf.writeUtf(member.warehouseName() == null ? "" : member.warehouseName(), 64);
            buf.writeUtf(member.server() == null ? "" : member.server(), 64);
            buf.writeBoolean(member.local());
            buf.writeBoolean(member.packable());
        }
    }

    private static DistantDeviceStateS2C read(RegistryFriendlyByteBuf buf) {
        BlockPos pos = buf.readBlockPos();
        int slot = buf.readVarInt();
        UUID scope = buf.readBoolean() ? buf.readUUID() : null;
        String name = buf.readUtf(DistantNetworkDirectory.MAX_NAME_LENGTH);
        RemoteNetworkId selected = buf.readBoolean()
                ? RemoteNetworkId.read(buf.readNbt()).orElse(null) : null;
        int count = Math.min(128, Math.max(0, buf.readVarInt()));
        List<Member> members = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            RemoteNetworkId network = RemoteNetworkId.read(buf.readNbt()).orElse(null);
            String warehouseName = buf.readUtf(64);
            String server = buf.readUtf(64);
            boolean local = buf.readBoolean();
            boolean packable = buf.readBoolean();
            if (network != null) members.add(new Member(network, warehouseName, server, local, packable));
        }
        return new DistantDeviceStateS2C(pos, slot, scope, name, selected, List.copyOf(members));
    }
}
