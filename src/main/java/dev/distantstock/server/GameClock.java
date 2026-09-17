package dev.distantstock.server;

import dev.distantstock.DistantStock;
import dev.distantstock.block.LoadedDocks;
import dev.distantstock.config.StockConfig;
import dev.distantstock.link.LinkQueues;
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
import dev.distantstock.routing.TowerActivation;
import dev.distantstock.routing.TowerChunkLoader;
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
        // The tower state is per-world: a level reference left behind here would outlive the world
        // it points at, and the next world in the same client would inherit its ticket bookkeeping.
        TowerActivation.unpinDevices();
        TowerChunkLoader.reset();
        // Legacy queues live in static fields and hold whole orders and parcels. A world left with
        // entries still in them hands them to the next world opened in this client, where the
        // default group has the same id and the parcel is delivered as if it had been sent there.
        LinkQueues.clear();
        // The scanner's suppliers are registered once per world start; without this they pile up,
        // and every scan after the second world entry walks the same docks twice.
        StockScanner.clearExtra();
        LOG.info("[DistantStock] transport stopped");
    }

    /**
     * A level is going away: forget everything registered under it.
     *
     * <p>On the server this is belt and braces — a level being unloaded takes its block entities
     * through {@code setRemoved} and each one deregisters itself. On the client it is the only
     * chance there is: leaving a world does not unload a single chunk, so the client's own copies
     * of every loaded dock, tower and monitor would otherwise stay in the static registries for the
     * life of the process, holding their level and everything under it in memory, and answering the
     * delivery paths of whatever save is opened next.
     */
    @SubscribeEvent
    public static void unloaded(net.neoforged.neoforge.event.level.LevelEvent.Unload e) {
        if (e.getLevel() instanceof net.minecraft.world.level.Level level) {
            dev.distantstock.block.LoadedDocks.forget(level);
            dev.distantstock.block.LoadedTowers.forget(level);
            dev.distantstock.block.LoadedDevices.forget(level);
        }
    }

    @SubscribeEvent
    public static void tick(ServerTickEvent.Post e) {
        ticks++;
        LinkSnapshot.tickLocal(e.getServer());
        // 塔的地基：激活快照每秒重算一次（港每 tick 都要读），区块票每 tick 处理队列、每 20 tick 对齐。
        // The activation snapshot is what canSend/canReceive read, and the ticket queue is drained
        // every tick so a broken tower releases its chunks promptly rather than at the next beat.
        TowerActivation.tick(e.getServer());
        TowerChunkLoader.tick(e.getServer());

        if (transerverActive) {
            TranserverBridge.tick();
            if (ticks % 10 == 0) {
                ParcelEscrowPump.tick(e.getServer());
                TranserverOrderService.tick(e.getServer());
            }
            if (ticks % 20 == 0) {
                NetworkAnnouncementService.publish();
            }
            if (ticks % 40 == 0) {
                TranserverStockService.tick();
            }
        }

        // 一秒一次，和 Create 自己那份近似汇总的刷新节拍一致（见 CreateStock.summary）：
        // 更快没有意义（读到的还是同一份缓存），更慢则让终端里的库存显得迟钝。
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
