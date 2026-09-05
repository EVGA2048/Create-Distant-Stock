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
        PayloadRegistrar r = e.registrar("2");
        r.playToServer(SetAddressC2S.TYPE, SetAddressC2S.STREAM_CODEC, SetAddressC2S::handle);
        r.playToServer(PlaceOrderC2S.TYPE, PlaceOrderC2S.STREAM_CODEC, PlaceOrderC2S::handle);
        r.playToServer(OpenRequesterC2S.TYPE, OpenRequesterC2S.STREAM_CODEC, OpenRequesterC2S::handle);
        r.playToClient(LinkSnapshotS2C.TYPE, LinkSnapshotS2C.STREAM_CODEC, LinkSnapshotS2C::handle);
    }

    private ModNet() {
    }
}
