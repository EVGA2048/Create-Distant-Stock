package dev.distantstock.net;

import com.simibubi.create.content.logistics.factoryBoard.FactoryPanelBlock;
import dev.distantstock.DistantStock;
import dev.distantstock.block.RemoteBinding;
import dev.distantstock.block.RemoteGaugeBlockEntity;
import dev.distantstock.block.RemoteRedstoneRequesterBlockEntity;
import dev.distantstock.block.SignalPanelBlockEntity;
import dev.distantstock.link.DistantNetworkJoinService;
import dev.distantstock.routing.DistantNetworkDirectory;
import dev.distantstock.routing.RemoteNetworkId;
import dev.distantstock.stock.CreateNetworkAccess;
import dev.distantstock.stock.NetworkDirectory;
import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/** Network setup actions shared by distant gauges and distant redstone requesters. */
public record DistantDeviceActionC2S(BlockPos pos, int slot, int action, String value,
                                     RemoteNetworkId network) implements CustomPacketPayload {
    public static final int REFRESH = 0;
    public static final int JOIN = 1;
    public static final int SELECT = 2;
    public static final int CLEAR_SOURCE = 3;

    public static final Type<DistantDeviceActionC2S> TYPE = new Type<>(
            ResourceLocation.fromNamespaceAndPath(DistantStock.MODID, "distant_device_action"));
    public static final StreamCodec<RegistryFriendlyByteBuf, DistantDeviceActionC2S> STREAM_CODEC =
            StreamCodec.of(DistantDeviceActionC2S::write, DistantDeviceActionC2S::read);

    @Override
    public Type<DistantDeviceActionC2S> type() {
        return TYPE;
    }

    public static void handle(DistantDeviceActionC2S msg, IPayloadContext ctx) {
        ctx.enqueueWork(() -> {
            if (!(ctx.player() instanceof ServerPlayer player)
                    || player.level().getBlockEntity(msg.pos()) == null
                    || player.distanceToSqr(msg.pos().getCenter()) > 64) {
                return;
            }
            DistantNetworkJoinService.DeviceTarget target = new DistantNetworkJoinService.DeviceTarget(
                    player.level().dimension(), msg.pos(), msg.slot());
            if (!validTarget(player, msg.pos(), msg.slot())) return;

            if (msg.action() == REFRESH) {
                DistantDeviceStateS2C.send(player, target);
                return;
            }
            if (msg.action() == JOIN) {
                if (!DistantNetworkJoinService.requestDevice(player.getServer(), player,
                        player.level().dimension(), msg.pos(), msg.slot(), msg.value())) {
                    player.displayClientMessage(net.minecraft.network.chat.Component.translatable(
                            "message.distantstock.network.join_send_failed"), true);
                }
                return;
            }

            java.util.UUID scope = scope(player, msg.pos(), msg.slot());
            if (!DistantNetworkDirectory.isFormalId(scope)) return;
            if (msg.action() == CLEAR_SOURCE) {
                clearSource(player, msg.pos(), msg.slot());
                DistantDeviceStateS2C.send(player, target);
                return;
            }
            if (msg.action() != SELECT || msg.network() == null) return;

            NetworkDirectory.Entry entry = NetworkDirectory.find(msg.network()).orElse(null);
            if (entry == null || !scope.equals(entry.distantNetworkId())) return;
            if (entry.local() && !CreateNetworkAccess.mayInteract(entry.networkId(), entry.freq(), player)) {
                player.displayClientMessage(net.minecraft.network.chat.Component.translatable(
                        "message.distantstock.network.interact_denied"), true);
                return;
            }
            bindSource(player, msg.pos(), msg.slot(), entry.networkId(), scope);
            DistantDeviceStateS2C.send(player, target);
        });
    }

    private static boolean validTarget(ServerPlayer player, BlockPos pos, int slotIndex) {
        var be = player.level().getBlockEntity(pos);
        if (be instanceof RemoteRedstoneRequesterBlockEntity) return slotIndex < 0;
        FactoryPanelBlock.PanelSlot[] slots = FactoryPanelBlock.PanelSlot.values();
        if (slotIndex < 0 || slotIndex >= slots.length) return false;
        FactoryPanelBlock.PanelSlot slot = slots[slotIndex];
        if (be instanceof RemoteGaugeBlockEntity gauge) return gauge.panels.get(slot).isActive();
        if (be instanceof SignalPanelBlockEntity signal) return signal.isRemoteGauge(slot);
        return be instanceof com.simibubi.create.content.logistics.factoryBoard.FactoryPanelBlockEntity board
                && net.neoforged.fml.ModList.get().isLoaded("deployer")
                && dev.distantstock.panel.DeployerPanels.holdsRemoteGauge(board, slot);
    }

    private static java.util.UUID scope(ServerPlayer player, BlockPos pos, int slotIndex) {
        var be = player.level().getBlockEntity(pos);
        if (be instanceof RemoteRedstoneRequesterBlockEntity requester) {
            return requester.distantNetworkScope();
        }
        FactoryPanelBlock.PanelSlot slot = FactoryPanelBlock.PanelSlot.values()[slotIndex];
        if (be instanceof RemoteGaugeBlockEntity gauge) return gauge.distantNetworkScope(slot);
        if (be instanceof SignalPanelBlockEntity signal) return signal.distantNetworkScope(slot);
        if (be instanceof com.simibubi.create.content.logistics.factoryBoard.FactoryPanelBlockEntity board
                && net.neoforged.fml.ModList.get().isLoaded("deployer")) {
            return dev.distantstock.panel.DeployerPanels.distantNetworkScope(board, slot);
        }
        return null;
    }

    private static void clearSource(ServerPlayer player, BlockPos pos, int slotIndex) {
        var be = player.level().getBlockEntity(pos);
        if (be instanceof RemoteRedstoneRequesterBlockEntity requester) {
            requester.bind(null);
            return;
        }
        FactoryPanelBlock.PanelSlot slot = FactoryPanelBlock.PanelSlot.values()[slotIndex];
        if (be instanceof RemoteGaugeBlockEntity gauge) gauge.unbind(slot);
        else if (be instanceof SignalPanelBlockEntity signal) signal.unbind(slot);
        else if (be instanceof com.simibubi.create.content.logistics.factoryBoard.FactoryPanelBlockEntity board
                && net.neoforged.fml.ModList.get().isLoaded("deployer")) {
            dev.distantstock.panel.DeployerPanels.unbind(board, slot);
        }
    }

    private static void bindSource(ServerPlayer player, BlockPos pos, int slotIndex,
                                   RemoteNetworkId network, java.util.UUID scope) {
        var be = player.level().getBlockEntity(pos);
        if (be instanceof RemoteRedstoneRequesterBlockEntity requester) {
            RemoteBinding old = requester.binding();
            requester.bind(new RemoteBinding(network, scope,
                    old == null ? null : old.receivingGroup(),
                    old == null ? "" : old.address(),
                    old == null ? "" : old.homeAddress()));
            return;
        }
        FactoryPanelBlock.PanelSlot slot = FactoryPanelBlock.PanelSlot.values()[slotIndex];
        RemoteBinding old = be instanceof RemoteGaugeBlockEntity gauge ? gauge.binding(slot)
                : be instanceof SignalPanelBlockEntity signal ? signal.binding(slot)
                : be instanceof com.simibubi.create.content.logistics.factoryBoard.FactoryPanelBlockEntity board
                && net.neoforged.fml.ModList.get().isLoaded("deployer")
                ? dev.distantstock.panel.DeployerPanels.bindingOf(board, slot) : null;
        RemoteBinding next = new RemoteBinding(network, scope,
                old == null ? null : old.receivingGroup(),
                old == null ? "" : old.address(),
                old == null ? "" : old.homeAddress());
        if (be instanceof RemoteGaugeBlockEntity gauge) gauge.bind(slot, next);
        else if (be instanceof SignalPanelBlockEntity signal) signal.bind(slot, next);
        else if (be instanceof com.simibubi.create.content.logistics.factoryBoard.FactoryPanelBlockEntity board
                && net.neoforged.fml.ModList.get().isLoaded("deployer")) {
            dev.distantstock.panel.DeployerPanels.bind(board, slot, next);
        }
    }

    private static void write(RegistryFriendlyByteBuf buf, DistantDeviceActionC2S msg) {
        buf.writeBlockPos(msg.pos());
        buf.writeVarInt(msg.slot());
        buf.writeVarInt(msg.action());
        buf.writeUtf(msg.value() == null ? "" : msg.value(), 64);
        buf.writeBoolean(msg.network() != null);
        if (msg.network() != null) buf.writeNbt(msg.network().save());
    }

    private static DistantDeviceActionC2S read(RegistryFriendlyByteBuf buf) {
        BlockPos pos = buf.readBlockPos();
        int slot = buf.readVarInt();
        int action = buf.readVarInt();
        String value = buf.readUtf(64);
        RemoteNetworkId network = buf.readBoolean()
                ? RemoteNetworkId.read(buf.readNbt()).orElse(null) : null;
        return new DistantDeviceActionC2S(pos, slot, action, value, network);
    }
}
