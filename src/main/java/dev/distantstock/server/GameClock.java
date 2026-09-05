package dev.distantstock.server;

import dev.distantstock.DistantStock;
import dev.distantstock.block.LoadedLinkers;
import dev.distantstock.link.LinkServer;
import dev.distantstock.link.LinkSnapshot;
import dev.distantstock.link.OrderService;
import dev.distantstock.link.PackagePump;
import dev.distantstock.stock.StockScanner;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

@EventBusSubscriber(modid = DistantStock.MODID)
public final class GameClock {
    private static int ticks;

    @SubscribeEvent
    public static void started(ServerStartedEvent e) {
        StockScanner.extra(LoadedLinkers::watched);
        LinkServer.start();
    }

    @SubscribeEvent
    public static void stopping(ServerStoppingEvent e) {
        LinkServer.stop();
    }

    @SubscribeEvent
    public static void tick(ServerTickEvent.Post e) {
        ticks++;
        LinkSnapshot.tickLocal(e.getServer());
        if (ticks % 20 == 0) {
            StockScanner.scan();
        }
        OrderService.drainInbound();
        PackagePump.drain(e.getServer());
    }

    private GameClock() {
    }
}
