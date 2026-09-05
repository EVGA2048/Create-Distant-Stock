package dev.distantstock.server;

import dev.distantstock.DistantStock;
import dev.distantstock.block.LoadedDocks;
import dev.distantstock.link.LinkServer;
import dev.distantstock.link.LinkSnapshot;
import dev.distantstock.link.OrderService;
import dev.distantstock.link.PackagePump;
import dev.distantstock.menu.RequesterMenu;
import dev.distantstock.net.StockSyncS2C;
import dev.distantstock.stock.StockScanner;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.network.PacketDistributor;

@EventBusSubscriber(modid = DistantStock.MODID)
public final class GameClock {
    private static int ticks;

    @SubscribeEvent
    public static void started(ServerStartedEvent e) {
        StockScanner.extra(LoadedDocks::watched);
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
        if (ticks % 40 == 0) {
            for (ServerPlayer player : e.getServer().getPlayerList().getPlayers()) {
                if (player.containerMenu instanceof RequesterMenu menu) {
                    menu.refresh(player);
                    PacketDistributor.sendToPlayer(player, StockSyncS2C.of(menu.demo, menu.stock));
                }
            }
        }
        OrderService.drainInbound();
        PackagePump.drain(e.getServer());
    }

    private GameClock() {
    }
}
