package dev.distantstock.client;

import dev.distantstock.DistantStock;
import dev.distantstock.menu.ModMenus;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RegisterMenuScreensEvent;

@EventBusSubscriber(modid = DistantStock.MODID, bus = EventBusSubscriber.Bus.MOD, value = Dist.CLIENT)
public final class ClientSetup {
    @SubscribeEvent
    public static void screens(RegisterMenuScreensEvent e) {
        e.register(ModMenus.REQUESTER.get(), RequesterScreen::new);
    }

    private ClientSetup() {
    }
}
