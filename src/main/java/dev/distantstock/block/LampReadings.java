package dev.distantstock.block;

import com.simibubi.create.content.logistics.factoryBoard.FactoryPanelBehaviour;
import com.simibubi.create.content.logistics.factoryBoard.FactoryPanelConnection;
import dev.distantstock.item.SignalLampPanelItem;
import dev.distantstock.stock.NetworkHealth;
import net.minecraft.world.level.Level;

/**
 * What a lamp makes of what it is looking at.
 *
 * <p>A lamp reads one of two things and reports both on the same ladder. Wired, it reads the gauges
 * pointing at it and reports the worst of them. Bound to a frequency, it reads the network itself —
 * no gauges, no wiring — through the same rungs, so a light means the same thing either way.
 *
 * <p>This lives on the common side because both readers need it and one of them is a panel type that
 * only exists when Create: Deployer does: a lamp on somebody else's board must light the same way as
 * a lamp on ours, and two copies of an andon ladder is how the two would start to disagree about
 * what orange means.
 */
public final class LampReadings {
    private LampReadings() {
    }

    /** The rung one gauge reports. */
    public static LampState ofGauge(FactoryPanelBehaviour gauge) {
        if (gauge.isMissingAddress() || gauge.redstonePowered) {
            return LampState.FATAL;
        }
        if (gauge.satisfied) {
            // Nothing on order means the line is ready but idle, not busy.
            return gauge.getPromised() > 0 ? LampState.ALL_GOOD : LampState.IDLE;
        }
        if (gauge.promisedSatisfied) {
            return LampState.ACT;
        }
        return gauge.waitingForNetwork ? LampState.WARN_URGENT : LampState.WARN;
    }

    /** The rung a bound network reports. */
    public static LampState ofNetwork(NetworkHealth net) {
        if (!net.known() || net.locked() || net.loadedLinks() == 0) {
            return LampState.FATAL;
        }
        if (net.loadedLinks() < net.totalLinks()) {
            return LampState.ACT;
        }
        return net.idle() ? LampState.IDLE : LampState.ALL_GOOD;
    }

    /**
     * The worst of the gauges pointing at this lamp, or null when none are.
     *
     * <p>Null rather than ALL_GOOD: a lamp wired to nothing is not a lamp reporting everything is
     * fine, and a green light on an unconnected panel is the one reading that would send someone
     * looking in the wrong place.
     */
    public static LampState worstFromGauges(Level level, FactoryPanelBehaviour lamp) {
        if (level == null) {
            return null;
        }
        LampState worst = null;
        for (FactoryPanelConnection connection : lamp.targetedBy.values()) {
            FactoryPanelBehaviour source = FactoryPanelBehaviour.at(level, connection);
            if (source != null) {
                worst = LampState.worst(worst, ofGauge(source));
            }
        }
        return worst;
    }

    /** The colour a lamp shows for a rung. The andesite lamps keep their own colour instead. */
    public static SignalLampPanelItem.Color colorFor(LampState level) {
        return switch (level) {
            case IDLE, ALL_GOOD -> SignalLampPanelItem.Color.GREEN;
            case ACT -> SignalLampPanelItem.Color.CYAN;
            case WARN, WARN_URGENT -> SignalLampPanelItem.Color.ORANGE;
            case FATAL -> SignalLampPanelItem.Color.RED;
        };
    }
}
