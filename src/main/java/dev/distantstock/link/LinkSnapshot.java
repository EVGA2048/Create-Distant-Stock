package dev.distantstock.link;

import dev.distantstock.config.StockConfig;
import dev.distantstock.routing.TowerReadout;
import net.minecraft.server.MinecraftServer;

/** 主线程写本端，io 线程写对端。HTTP / GUI / 护目镜只读。 */
public final class LinkSnapshot {
    public static volatile double localTps = 20;
    public static volatile double localMspt = 50;
    public static volatile int orderDepth;
    public static volatile int packageDepth;
    public static volatile int inFlight;
    public static volatile boolean peerUp;
    public static volatile double peerTps;
    public static volatile double peerMspt;
    public static volatile double peerRttMs = -1;
    public static volatile int peerFails;
    public static volatile String peerId = "";
    public static volatile long peerSeenMs;
    public static volatile int peersUp;
    public static volatile int peersTotal;
    public static volatile boolean transerverAttached;
    public static volatile boolean transerverUp;
    public static volatile String transerverNodeId = "";
    public static volatile String transerverAlias = "";
    public static volatile String transerverFailure = "";
    public static volatile int transerverOutbox;
    public static volatile int transerverInbox;
    public static volatile int transerverCompleted;
    public static volatile int transerverDeadLetters;

    private static long prevNano = System.nanoTime();
    private static double ewmaMspt = 50;

    public static void tickLocal(MinecraftServer server) {
        long now = System.nanoTime();
        double dt = (now - prevNano) / 1_000_000.0;
        prevNano = now;
        if (dt > 0 && dt < 5000) {
            ewmaMspt = ewmaMspt * 0.85 + dt * 0.15;
        }
        try {
            var m = server.getClass().getMethod("getAverageTickTimeNanos");
            Object raw = m.invoke(server);
            if (raw instanceof Number n && n.doubleValue() > 0) {
                ewmaMspt = n.doubleValue() / 1_000_000.0;
            }
        } catch (Throwable ignored) {
        }
        localMspt = ewmaMspt;
        localTps = ewmaMspt <= 0 ? 20 : Math.min(20.0, 1000.0 / ewmaMspt);
        orderDepth = LinkQueues.orderDepth();
        packageDepth = LinkQueues.packageDepth() + transerverOutbox + transerverInbox;
        inFlight = LinkQueues.inFlight() + ParcelEscrow.get(server).size();
    }

    /**
     * How long a peer's numbers stay believable without a fresh word from it.
     *
     * <p>Two announcement periods: one lost message must not blank the reading, and a peer that has
     * said nothing for two minutes is a peer whose numbers this server does not know. The screen
     * shows a dash then rather than a zero — zero is a real measurement, and a link that is up but
     * silent is not one.
     */
    public static final long PEER_STALE_MS = 120_000;

    /** Whether anything has been heard from a peer recently enough to draw its numbers. */
    public static boolean peerFresh() {
        return peerSeenMs > 0 && System.currentTimeMillis() - peerSeenMs < PEER_STALE_MS;
    }

    /**
     * A peer's own numbers, from the announcement it just sent.
     *
     * <p>The one source of a peer's TPS in Transerver mode. There is no HTTP polling on that
     * transport — the announce is the only regular message that crosses — so a peer's metrics ride
     * along with the network list rather than being asked for, which would need a second channel and
     * a second round trip to say the same thing.
     */
    public static void peerMetrics(String node, double tps, double mspt) {
        peerId = node == null ? "" : node;
        peerTps = tps;
        peerMspt = mspt;
        peerSeenMs = System.currentTimeMillis();
        peerUp = true;
        peersUp = Math.max(peersUp, 1);
        peersTotal = Math.max(peersTotal, 1);
    }

    public static void peersOk(int up, int total, String id, double tps, double mspt, long rttMs) {
        peerUp = up > 0;
        peersUp = up;
        peersTotal = total;
        peerId = id == null ? "" : id;
        peerTps = tps;
        peerMspt = mspt;
        peerRttMs = rttMs;
        peerSeenMs = System.currentTimeMillis();
    }

    public static void peersTotal(int total) {
        peersTotal = total;
        peersUp = 0;
    }

    public static void peerFail() {
        peerFails++;
        if (peerSeenMs == 0 || System.currentTimeMillis() - peerSeenMs > 4000) {
            peerUp = false;
            peerRttMs = -1;
            peersUp = 0;
        }
    }

    public static void transerver(String nodeId, String alias, boolean transportUp, String failure,
                                  int outbox, int inbox, int completed, int deadLetters) {
        transerverAttached = true;
        transerverUp = transportUp;
        transerverNodeId = nodeId == null ? "" : nodeId;
        transerverAlias = alias == null ? transerverNodeId : alias;
        transerverFailure = failure == null ? "" : failure;
        transerverOutbox = outbox;
        transerverInbox = inbox;
        transerverCompleted = completed;
        transerverDeadLetters = deadLetters;
    }

    public static void transerverUnavailable() {
        transerverAttached = false;
        transerverUp = false;
        transerverNodeId = "";
        transerverAlias = "";
        transerverFailure = "";
        transerverOutbox = 0;
        transerverInbox = 0;
        transerverCompleted = 0;
        transerverDeadLetters = 0;
    }

    public static String selfId() {
        return StockConfig.selfId();
    }

    public static String linkLabel() {
        return view().linkLabel();
    }

    /**
     * The link half of the dashboard, for the callers that are not a monitor.
     *
     * <p>The tower readout is absent rather than looked up: it belongs to one monitor, and a process
     * wide field for it would show the wrong tower for a tick every time two monitors were open.
     */
    public static View view() {
        return view(TowerReadout.NONE);
    }

    /** The same view with the readout of the monitor that is asking. */
    public static View view(TowerReadout tower) {
        return new View(
                selfId(),
                peerId == null ? "" : peerId,
                localTps,
                localMspt,
                orderDepth,
                packageDepth,
                inFlight,
                peerUp,
                peerTps,
                peerMspt,
                peerFresh(),
                peerRttMs,
                peerFails,
                peersUp,
                peersTotal,
                transerverAttached,
                transerverUp,
                transerverNodeId,
                transerverAlias,
                transerverFailure,
                transerverOutbox,
                transerverInbox,
                transerverCompleted,
                transerverDeadLetters,
                tower == null ? TowerReadout.NONE : tower
        );
    }

    /**
     * Everything a monitor screen draws.
     *
     * <p>The order of the components is the order of the wire; the last one was added as a whole
     * record for that reason. See {@link dev.distantstock.routing.TowerReadout}.
     */
    public record View(
            String selfId,
            String peerId,
            double localTps,
            double localMspt,
            int orderDepth,
            int packageDepth,
            int inFlight,
            boolean peerUp,
            double peerTps,
            double peerMspt,
            boolean peerFresh,
            double peerRttMs,
            int peerFails,
            int peersUp,
            int peersTotal,
            boolean transerverAttached,
            boolean transerverUp,
            String transerverNodeId,
            String transerverAlias,
            String transerverFailure,
            int transerverOutbox,
            int transerverInbox,
            int transerverCompleted,
            int transerverDeadLetters,
            TowerReadout tower
    ) {
        public boolean linkUp() {
            return transerverAttached ? transerverUp : peerUp;
        }

        public String linkLabel() {
            if (transerverAttached) {
                return (transerverAlias == null || transerverAlias.isBlank()
                        ? transerverNodeId : transerverAlias) + (transerverUp ? " · online" : " · offline");
            }
            if (peersTotal > 1) {
                return selfId + " · " + peersUp + "/" + peersTotal;
            }
            String peer = peerId == null || peerId.isBlank() ? "—" : peerId;
            return selfId + " \u2194 " + peer;
        }
    }

    private LinkSnapshot() {
    }
}
