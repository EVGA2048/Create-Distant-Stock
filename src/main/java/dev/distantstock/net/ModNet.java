package dev.distantstock.net;

import dev.distantstock.DistantStock;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;

@EventBusSubscriber(modid = DistantStock.MODID, bus = EventBusSubscriber.Bus.MOD)
public final class ModNet {
    @SubscribeEvent
    public static void register(RegisterPayloadHandlersEvent e) {
        PayloadRegistrar r = e.registrar("10");
        r.playToServer(SetAddressC2S.TYPE, SetAddressC2S.STREAM_CODEC, SetAddressC2S::handle);
        r.playToServer(PlaceOrderC2S.TYPE, PlaceOrderC2S.STREAM_CODEC, PlaceOrderC2S::handle);
        r.playToServer(JoinNetworkC2S.TYPE, JoinNetworkC2S.STREAM_CODEC, JoinNetworkC2S::handle);
        r.playToServer(OpenRequesterC2S.TYPE, OpenRequesterC2S.STREAM_CODEC, OpenRequesterC2S::handle);
        r.playToServer(SaveAdminC2S.TYPE, SaveAdminC2S.STREAM_CODEC, SaveAdminC2S::handle);
        r.playToServer(SetTowerSettingsC2S.TYPE, SetTowerSettingsC2S.STREAM_CODEC, SetTowerSettingsC2S::handle);
        r.playToServer(SetDockGroupC2S.TYPE, SetDockGroupC2S.STREAM_CODEC, SetDockGroupC2S::handle);
        r.playToServer(BindGaugePanelC2S.TYPE, BindGaugePanelC2S.STREAM_CODEC, BindGaugePanelC2S::handle);
        r.playToServer(BindPanelFromTerminalC2S.TYPE, BindPanelFromTerminalC2S.STREAM_CODEC,
                BindPanelFromTerminalC2S::handle);
        r.playToServer(ForgetRemoteGroupC2S.TYPE, ForgetRemoteGroupC2S.STREAM_CODEC,
                ForgetRemoteGroupC2S::handle);
        r.playToServer(SetRequesterTargetC2S.TYPE, SetRequesterTargetC2S.STREAM_CODEC,
                SetRequesterTargetC2S::handle);
        r.playToServer(GroupMemberC2S.TYPE, GroupMemberC2S.STREAM_CODEC, GroupMemberC2S::handle);
        r.playToServer(DistantNetworkActionC2S.TYPE, DistantNetworkActionC2S.STREAM_CODEC,
                DistantNetworkActionC2S::handle);
        r.playToServer(DistantDeviceActionC2S.TYPE, DistantDeviceActionC2S.STREAM_CODEC,
                DistantDeviceActionC2S::handle);
        r.playToServer(SetCreateNetworkLockC2S.TYPE, SetCreateNetworkLockC2S.STREAM_CODEC,
                SetCreateNetworkLockC2S::handle);
        r.playToServer(LoggerActionC2S.TYPE, LoggerActionC2S.STREAM_CODEC, LoggerActionC2S::handle);
        r.playToClient(RemoteGroupsS2C.TYPE, RemoteGroupsS2C.STREAM_CODEC, RemoteGroupsS2C::handle);
        r.playToClient(OpenMonitorS2C.TYPE, OpenMonitorS2C.STREAM_CODEC, OpenMonitorS2C::handle);
        r.playToClient(LinkSnapshotS2C.TYPE, LinkSnapshotS2C.STREAM_CODEC, LinkSnapshotS2C::handle);
        r.playToClient(StockSyncS2C.TYPE, StockSyncS2C.STREAM_CODEC, StockSyncS2C::handle);
        r.playToClient(AdminConfigS2C.TYPE, AdminConfigS2C.STREAM_CODEC, AdminConfigS2C::handle);
        r.playToClient(DockGroupsS2C.TYPE, DockGroupsS2C.STREAM_CODEC, DockGroupsS2C::handle);
        r.playToClient(DistantNetworkStateS2C.TYPE, DistantNetworkStateS2C.STREAM_CODEC,
                DistantNetworkStateS2C::handle);
        r.playToClient(DistantDeviceStateS2C.TYPE, DistantDeviceStateS2C.STREAM_CODEC,
                DistantDeviceStateS2C::handle);
        r.playToClient(CreateNetworkLockS2C.TYPE, CreateNetworkLockS2C.STREAM_CODEC,
                CreateNetworkLockS2C::handle);
        r.playToClient(OpenLoggerS2C.TYPE, OpenLoggerS2C.STREAM_CODEC, OpenLoggerS2C::handle);
    }

    private ModNet() {
    }
}
