package dev.distantstock.link;

import dev.distantstock.config.StockConfig;
import dev.distantstock.stock.StockCache;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/** 本服 io 线程拉对面。客户端零 HTTP。 */
public final class LinkClient {
    private static final Logger LOG = LogManager.getLogger();
    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofMillis(1500))
            .build();
    private static ScheduledExecutorService poll;

    static void start(ExecutorService io) {
        stop();
        poll = Executors.newSingleThreadScheduledExecutor(r -> LinkServer.daemon(r, "distantstock-poll"));
        poll.scheduleAtFixedRate(() -> {
            try {
                pump();
            } catch (Exception e) {
                LOG.debug("poll: {}", e.getMessage());
            }
        }, 1, 2, TimeUnit.SECONDS);
    }

    static void stop() {
        if (poll != null) {
            poll.shutdownNow();
            poll = null;
        }
    }

    public static void wake() {
        LinkServer.enqueue(() -> {
            try {
                pump();
            } catch (Exception ignored) {
            }
        });
    }

    private static void pump() {
        flushOrders();
        flushPackages();
        if (!StockConfig.hasPeer()) {
            return;
        }
        pullStatus();
        for (UUID freq : StockCache.watched(5 * 60_000L)) {
            if (StockCache.isLocal(freq) && StockCache.ageMs(freq) < 15_000L) {
                continue;
            }
            pullStock(freq);
        }
    }

    private static void flushOrders() {
        LinkQueues.Order order;
        while ((order = LinkQueues.pollOutboundOrder()) != null) {
            if (!post("/order", orderJson(order))) {
                LinkQueues.offerOutboundOrder(order);
                break;
            }
        }
    }

    private static void flushPackages() {
        LinkQueues.Parcel p;
        while ((p = LinkQueues.pollOutboundPackage()) != null) {
            String body = "{\"nbt\":\"" + LinkHttp.jsonEsc(p.nbt)
                    + "\",\"address\":\"" + LinkHttp.jsonEsc(p.address) + "\"}";
            if (post("/package", body)) {
                LinkQueues.landed();
            } else {
                LinkQueues.requeueOutbound(p);
                break;
            }
        }
    }

    private static void pullStatus() {
        long t0 = System.nanoTime();
        String json = get("/status");
        long rtt = (System.nanoTime() - t0) / 1_000_000L;
        if (json == null || !json.contains("\"ok\":true")) {
            LinkSnapshot.peerFail();
            return;
        }
        String id = LinkHttp.field(json, "self");
        double tps = num(LinkHttp.field(json, "tps"), 0);
        double mspt = num(LinkHttp.field(json, "mspt"), 0);
        LinkSnapshot.peerOk(id, tps, mspt, rtt);
    }

    private static void pullStock(UUID freq) {
        String json = get("/stock?freq=" + freq);
        if (json == null || !json.contains("\"ok\":true")) {
            return;
        }
        List<StockCache.Entry> items = new ArrayList<>();
        for (LinkQueues.Line line : OrderHandler.parseItems(json)) {
            items.add(new StockCache.Entry(line.itemId(), line.count()));
        }
        StockCache.put(freq, items, StockCache.Source.PEER);
    }

    private static boolean post(String path, String body) {
        if (!StockConfig.hasPeer()) {
            return false;
        }
        try {
            HttpRequest.Builder b = HttpRequest.newBuilder(uri(path))
                    .timeout(Duration.ofMillis(2500))
                    .header("Content-Type", "application/json; charset=UTF-8")
                    .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8));
            token(b);
            HttpResponse<String> res = HTTP.send(b.build(), HttpResponse.BodyHandlers.ofString());
            return res.statusCode() >= 200 && res.statusCode() < 300;
        } catch (Exception e) {
            LinkSnapshot.peerFail();
            return false;
        }
    }

    private static String get(String path) {
        if (!StockConfig.hasPeer()) {
            return null;
        }
        try {
            HttpRequest.Builder b = HttpRequest.newBuilder(uri(path))
                    .timeout(Duration.ofMillis(2500))
                    .GET();
            token(b);
            HttpResponse<String> res = HTTP.send(b.build(), HttpResponse.BodyHandlers.ofString());
            if (res.statusCode() >= 200 && res.statusCode() < 300) {
                return res.body();
            }
        } catch (Exception e) {
            LinkSnapshot.peerFail();
        }
        return null;
    }

    private static void token(HttpRequest.Builder b) {
        String token = StockConfig.TOKEN.get();
        if (token != null && !token.isEmpty()) {
            b.header("X-DistantStock-Token", token);
        }
    }

    private static URI uri(String path) {
        String host = StockConfig.PEER_HOST.get().trim();
        int port = StockConfig.PEER_PORT.get();
        return URI.create("http://" + host + ":" + port + path);
    }

    static String orderJson(LinkQueues.Order order) {
        StringBuilder sb = new StringBuilder("{\"freq\":\"")
                .append(order.freq).append("\",\"address\":\"")
                .append(LinkHttp.jsonEsc(order.address)).append("\",\"items\":[");
        for (int i = 0; i < order.items.size(); i++) {
            if (i > 0) {
                sb.append(',');
            }
            LinkQueues.Line line = order.items.get(i);
            sb.append("{\"id\":\"").append(LinkHttp.jsonEsc(line.itemId()))
                    .append("\",\"n\":").append(line.count()).append('}');
        }
        return sb.append("]}").toString();
    }

    private static double num(String s, double d) {
        try {
            return Double.parseDouble(s);
        } catch (Exception e) {
            return d;
        }
    }

    private LinkClient() {
    }
}
