package dev.distantstock.link;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;

public final class LinkQueues {
    public static final class Order {
        public final UUID freq;
        public final String address;
        public final List<Line> items;

        public Order(UUID freq, String address, List<Line> items) {
            this.freq = freq;
            this.address = address == null ? "" : address;
            this.items = List.copyOf(items);
        }
    }

    public record Line(String itemId, int count) {
    }

    public static final class Parcel {
        public final String nbt;
        public final String address;
        public int tries;

        public Parcel(String nbt, String address) {
            this.nbt = nbt;
            this.address = address == null ? "" : address;
        }
    }

    public enum PackResult {
        SUCCESS, NO_STOCK, UNLOADED
    }

    private static final ConcurrentLinkedQueue<Order> IN_ORDERS = new ConcurrentLinkedQueue<>();
    private static final ConcurrentLinkedQueue<Order> OUT_ORDERS = new ConcurrentLinkedQueue<>();
    private static final ConcurrentLinkedQueue<Parcel> IN_PACK = new ConcurrentLinkedQueue<>();
    private static final ConcurrentLinkedQueue<Parcel> OUT_PACK = new ConcurrentLinkedQueue<>();
    private static final AtomicInteger IN_FLIGHT = new AtomicInteger();
    private static final java.util.concurrent.ConcurrentHashMap<UUID, PackResult> LAST_PACK =
            new java.util.concurrent.ConcurrentHashMap<>();

    public static final int CAP = 64;

    public static boolean offerInboundOrder(Order order) {
        if (IN_ORDERS.size() >= CAP) {
            return false;
        }
        IN_ORDERS.add(order);
        return true;
    }

    public static boolean offerOutboundOrder(Order order) {
        if (OUT_ORDERS.size() >= CAP) {
            return false;
        }
        OUT_ORDERS.add(order);
        return true;
    }

    public static boolean offerInboundPackage(Parcel p) {
        if (IN_PACK.size() >= CAP) {
            return false;
        }
        IN_PACK.add(p);
        return true;
    }

    public static boolean offerOutboundPackage(Parcel p) {
        if (OUT_PACK.size() >= CAP) {
            return false;
        }
        OUT_PACK.add(p);
        IN_FLIGHT.incrementAndGet();
        return true;
    }

    public static Order pollInboundOrder() {
        return IN_ORDERS.poll();
    }

    public static Order pollOutboundOrder() {
        return OUT_ORDERS.poll();
    }

    public static Parcel pollInboundPackage() {
        return IN_PACK.poll();
    }

    public static Parcel pollOutboundPackage() {
        return OUT_PACK.poll();
    }

    public static void requeueInbound(Parcel p) {
        if (IN_PACK.size() < CAP) {
            IN_PACK.add(p);
        }
    }

    public static void requeueOutbound(Parcel p) {
        p.tries++;
        if (p.tries < 8 && OUT_PACK.size() < CAP) {
            OUT_PACK.add(p);
        } else {
            landed();
        }
    }

    public static void landed() {
        IN_FLIGHT.updateAndGet(n -> Math.max(0, n - 1));
    }

    public static int orderDepth() {
        return IN_ORDERS.size() + OUT_ORDERS.size();
    }

    public static int packageDepth() {
        return IN_PACK.size() + OUT_PACK.size();
    }

    public static int inFlight() {
        return IN_FLIGHT.get();
    }

    public static void lastPack(UUID freq, PackResult result) {
        if (freq != null) {
            LAST_PACK.put(freq, result);
        }
    }

    public static PackResult lastPack(UUID freq) {
        return freq == null ? null : LAST_PACK.get(freq);
    }

    public static List<Order> peekOutboundOrders() {
        return new ArrayList<>(OUT_ORDERS);
    }

    private LinkQueues() {
    }
}
