package dev.distantstock.link;

import dev.distantstock.config.StockConfig;
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
        packageDepth = LinkQueues.packageDepth();
        inFlight = LinkQueues.inFlight();
    }

    public static void peerOk(String id, double tps, double mspt, long rttMs) {
        peerUp = true;
        peerId = id == null ? "" : id;
        peerTps = tps;
        peerMspt = mspt;
        peerRttMs = rttMs;
        peerSeenMs = System.currentTimeMillis();
    }

    public static void peerFail() {
        peerFails++;
        if (peerSeenMs == 0 || System.currentTimeMillis() - peerSeenMs > 4000) {
            peerUp = false;
            peerRttMs = -1;
        }
    }

    public static String selfId() {
        return StockConfig.selfId();
    }

    public static String linkLabel() {
        String peer = peerId == null || peerId.isBlank() ? "—" : peerId;
        return selfId() + " \u2194 " + peer;
    }

    public static View view() {
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
                peerRttMs,
                peerFails
        );
    }

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
            double peerRttMs,
            int peerFails
    ) {
        public String linkLabel() {
            String peer = peerId == null || peerId.isBlank() ? "—" : peerId;
            return selfId + " \u2194 " + peer;
        }
    }

    private LinkSnapshot() {
    }
}
