package dev.distantstock.event;

import dev.distantstock.block.LoadedTowers;
import dev.distantstock.block.TowerCoreBlockEntity;
import dev.distantstock.link.LinkSnapshot;
import dev.distantstock.routing.TowerActivation;
import dev.distantstock.routing.TowerBilling;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.Level;

/**
 * Samples persistent infrastructure conditions once a second and projects them into EventRegistry.
 *
 * <p>Unlike parcel faults, these are level-triggered states. A tower that stays stopped for ten
 * minutes is still one incident, not six hundred repeats, so an already-active condition is not
 * raised again on every sample.
 */
public final class AlarmSampler {
    private static boolean linkEverAttached;

    public static void tick(MinecraftServer server, boolean transerverEnabled) {
        if (server == null) return;
        EventRegistry events = EventRegistry.get(server);
        long now = System.currentTimeMillis();

        reportLink(events, transerverEnabled, LinkSnapshot.transerverAttached,
                LinkSnapshot.transerverUp, LinkSnapshot.transerverFailure, now);

        for (TowerCoreBlockEntity tower : LoadedTowers.all()) {
            Level level = tower.getLevel();
            if (level == null || level.isClientSide) continue;
            TowerActivation.Usage usage = tower.id() == null ? null : TowerActivation.usage(tower.id());
            int carried = usage == null ? 0 : usage.carried();
            int parcelCost = TowerBilling.enabled() ? TowerBilling.parcelCost() : 0;
            reportTower(events, EventRegistry.blockSource(level, tower.getBlockPos()),
                    tower.tier() != null, tower.isRunning(), tower.overstressed(),
                    tower.ether(), parcelCost, carried, now);
        }
    }

    public static void stop() {
        linkEverAttached = false;
    }

    public static void clearTower(MinecraftServer server, Level level, BlockPos pos) {
        if (server == null || level == null || pos == null) return;
        EventRegistry events = EventRegistry.get(server);
        String source = EventRegistry.blockSource(level, pos);
        long now = System.currentTimeMillis();
        clear(events, EventRegistry.Codes.TOWER_STOPPED, "tower", source, now);
        clear(events, EventRegistry.Codes.TOWER_OVERSTRESSED, "tower", source, now);
        clear(events, EventRegistry.Codes.TOWER_NO_ETHER, "tower", source, now);
    }

    /** Pure state transition used by the live sampler and regression tests. */
    public static void reportTower(EventRegistry events, String source,
                                   boolean built, boolean running, boolean overstressed,
                                   int ether, int parcelCost, int carried, long now) {
        if (events == null || source == null) return;
        if (!built) {
            clear(events, EventRegistry.Codes.TOWER_STOPPED, "tower", source, now);
            clear(events, EventRegistry.Codes.TOWER_OVERSTRESSED, "tower", source, now);
            clear(events, EventRegistry.Codes.TOWER_NO_ETHER, "tower", source, now);
            return;
        }

        if (overstressed) {
            ensure(events, EventRegistry.Severity.ERROR, EventRegistry.Codes.TOWER_OVERSTRESSED,
                    "tower", source, "goggle.distantstock.tower.stalled", now);
            clear(events, EventRegistry.Codes.TOWER_STOPPED, "tower", source, now);
        } else {
            clear(events, EventRegistry.Codes.TOWER_OVERSTRESSED, "tower", source, now);
            if (!running) {
                ensure(events, EventRegistry.Severity.ERROR, EventRegistry.Codes.TOWER_STOPPED,
                        "tower", source, "goggle.distantstock.tower.not_turning", now);
            } else {
                clear(events, EventRegistry.Codes.TOWER_STOPPED, "tower", source, now);
            }
        }

        boolean cannotPay = running && carried > 0 && parcelCost > 0 && ether < parcelCost;
        if (cannotPay) {
            ensure(events, EventRegistry.Severity.ERROR, EventRegistry.Codes.TOWER_NO_ETHER,
                    "tower", source, "goggle.distantstock.send.no_ether", now);
        } else {
            clear(events, EventRegistry.Codes.TOWER_NO_ETHER, "tower", source, now);
        }
    }

    /** Pure link transition used by the live sampler and regression tests. */
    public static void reportLink(EventRegistry events, boolean enabled, boolean attached,
                                  boolean up, String detail, long now) {
        if (events == null) return;
        if (attached) linkEverAttached = true;
        String source = "transerver";
        if (!enabled || (!attached && !linkEverAttached)) {
            clear(events, EventRegistry.Codes.LINK_OFFLINE, "link", source, now);
            return;
        }
        if (!attached || !up) {
            ensure(events, EventRegistry.Severity.ERROR, EventRegistry.Codes.LINK_OFFLINE,
                    "link", source, detail == null ? "" : detail, now);
        } else {
            clear(events, EventRegistry.Codes.LINK_OFFLINE, "link", source, now);
        }
    }

    private static void ensure(EventRegistry events, EventRegistry.Severity severity,
                               String code, String sourceType, String sourceId,
                               String detail, long now) {
        if (events.active(code, sourceType, sourceId).isEmpty()) {
            events.raise(severity, code, sourceType, sourceId, detail, now);
        }
    }

    private static void clear(EventRegistry events, String code, String sourceType,
                              String sourceId, long now) {
        events.clear(code, sourceType, sourceId, now);
    }

    private AlarmSampler() {
    }
}
