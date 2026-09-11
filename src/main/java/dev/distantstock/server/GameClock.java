package dev.distantstock.server;

import dev.distantstock.DistantStock;
import dev.distantstock.block.LoadedDocks;
import dev.distantstock.config.StockConfig;
import dev.distantstock.link.LinkServer;
import dev.distantstock.link.LinkSnapshot;
import dev.distantstock.link.OrderService;
import dev.distantstock.link.PackagePump;
import dev.distantstock.link.PackageStripService;
import dev.distantstock.link.ParcelEscrowPump;
import dev.distantstock.link.TranserverBridge;
import dev.distantstock.link.TranserverPackageService;
import dev.distantstock.link.TranserverOrderService;
import dev.distantstock.menu.RequesterMenu;
import dev.distantstock.net.StockSyncS2C;
import dev.distantstock.stock.StockScanner;
import dev.distantstock.routing.WorldIdentity;
import dev.distantstock.link.NetworkAnnouncementService;
import dev.distantstock.link.TranserverStockService;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.network.PacketDistributor;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

@EventBusSubscriber(modid = DistantStock.MODID)
public final class GameClock {
    private static final Logger LOG = LogManager.getLogger();
    private static int ticks;
    private static boolean transerverActive;
    private static boolean legacyActive;

    @SubscribeEvent
    public static void started(ServerStartedEvent e) {
        String mode = StockConfig.transportMode();
        transerverActive = StockConfig.useTranserver();
        legacyActive = StockConfig.useLegacy();
        LOG.info("[DistantStock] transport.mode = '{}' | transerver={} legacy={}", mode, transerverActive, legacyActive);

        WorldIdentity.ensureUnique(e.getServer());
        StockScanner.extra(LoadedDocks::watched);

        if (transerverActive) {
            TranserverPackageService.register();
            TranserverOrderService.register();
            PackageStripService.register();
            NetworkAnnouncementService.register();
            TranserverStockService.register();
            TranserverBridge.start(e.getServer());
            LOG.info("[DistantStock] Transerver channels registered, bridge started");
        }

        if (legacyActive) {
            LinkServer.start();
            LOG.info("[DistantStock] Legacy HTTP LinkServer started on {}", StockConfig.BIND.get());
        }
    }

    @SubscribeEvent
    public static void stopping(ServerStoppingEvent e) {
        if (transerverActive) {
            TranserverBridge.stop();
        }
        if (legacyActive) {
            LinkServer.stop();
        }
        LOG.info("[DistantStock] transport stopped");
    }

    @SubscribeEvent
    public static void tick(ServerTickEvent.Post e) {
        ticks++;
        LinkSnapshot.tickLocal(e.getServer());

        if (transerverActive) {
            TranserverBridge.tick();
            if (ticks % 10 == 0) {
                ParcelEscrowPump.tick(e.getServer());
                TranserverOrderService.tick(e.getServer());
            }
            if (ticks % 20 == 0) {
                NetworkAnnouncementService.publishIfChanged();
            }
            if (ticks % 40 == 0) {
                TranserverStockService.tick();
            }
        }

        if (ticks % 20 == 0) {
            StockScanner.scan(e.getServer());
        }

        if (ticks % 40 == 0) {
            for (ServerPlayer player : e.getServer().getPlayerList().getPlayers()) {
                if (player.containerMenu instanceof RequesterMenu menu) {
                    menu.refresh(player);
                    PacketDistributor.sendToPlayer(player, StockSyncS2C.of(menu.demo, menu.stock));
                }
            }
        }

        if (legacyActive) {
            OrderService.drainInbound(e.getServer());
            PackagePump.drain(e.getServer());
        }
    }

    private GameClock() {
    }
}
