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

    private static final boolean DEPLOYER = net.neoforged.fml.ModList.get().isLoaded("deployer");

    /**
     * A panel that answers true or false instead of counting stock — a logic gauge from Extra
     * Gauges — or null for a stock gauge. Only Deployer's panels can be one, and naming them is left
     * to the panel package so a pack without Deployer never loads them.
     */
    private static Boolean logicOutput(FactoryPanelBehaviour source) {
        if (!DEPLOYER || source.getClass() == FactoryPanelBehaviour.class) {
            return null;
        }
        return dev.distantstock.panel.DeployerPanels.redstoneOutput(source);
    }

    /** The rung one gauge reports. */
    public static LampState ofGauge(FactoryPanelBehaviour gauge) {
        Boolean logic = logicOutput(gauge);
        if (logic != null) {
            // A gate has no stock, no address and nothing on order: true is "fine", false is the
            // same "not satisfied" an unfilled gauge reports.
            return logic ? LampState.ALL_GOOD : LampState.WARN;
        }
        if (gauge.isMissingAddress() || gauge.redstonePowered) {
            return LampState.FATAL;
        }
        LampState diagnosed = dev.distantstock.diagnostics.ChainDiagnostics.lampState(
                gauge.panelBE().getLevel(), gauge.getFrogAddress());
        if (diagnosed != null) {
            return diagnosed;
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

    /**
     * Whether an andesite lamp wired to gauges is lit.
     *
     * <p>Normal: lit while any gauge pointing at it is satisfied or powered. Inverted, it is a
     * shortage alarm: lit while it is wired and none of them are.
     */
    public static boolean wiredLit(Level level, FactoryPanelBehaviour lamp, boolean inverted) {
        if (level == null) {
            return false;
        }
        boolean connected = false;
        boolean satisfied = false;
        for (FactoryPanelConnection connection : lamp.targetedBy.values()) {
            FactoryPanelBehaviour source = FactoryPanelBehaviour.at(level, connection);
            if (source == null) {
                continue;
            }
            connected = true;
            Boolean logic = logicOutput(source);
            if (logic != null ? logic : source.satisfied || source.redstonePowered) {
                satisfied = true;
            }
        }
        return inverted ? connected && !satisfied : satisfied;
    }

    /** The colour a lamp shows for a rung. The andesite lamps keep their own colour instead. */
    public static SignalLampPanelItem.Color colorFor(LampState level) {
        return switch (level) {
            case IDLE, ALL_GOOD -> SignalLampPanelItem.Color.GREEN;
            case ACT -> SignalLampPanelItem.Color.CYAN;
            case WARN, WARN_URGENT -> SignalLampPanelItem.Color.ORANGE;
            case FATAL, FATAL_ACK -> SignalLampPanelItem.Color.RED;
        };
    }
}
