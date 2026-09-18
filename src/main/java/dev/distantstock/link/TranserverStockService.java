package dev.distantstock.link;

import dev.distantstock.routing.RemoteNetworkId;
import dev.distantstock.routing.RoutingChannels;
import dev.distantstock.routing.WorldIdentity;
import dev.distantstock.stock.CreateStock;
import dev.distantstock.stock.NetworkDirectory;
import dev.distantstock.stock.StockCache;
import dev.transerver.api.DeliveryResult;
import dev.transerver.api.ReceivedMessage;
import dev.transerver.api.TranserverApi;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.Items;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

public final class TranserverStockService {
    /** 一次还没等到回音的查询，以及它是什么时候发出去的。 */
    private record Pending(UUID queryId, long sentAt) {
    }

    /**
     * 发出去、还没等到回音的查询。
     *
     * <p><b>没有超时的时候这里是一道单向的门。</b>条目只在"回音到了"的时候才被删掉，于是丢一次回音
     * （对面正好在重启、消息在路由器里搁浅、服务器在关机途中）就等于把这个网络永久静音了 —— 界面上
     * 看是"库存一直空着"，界面重开多少次都一样，因为根本不再有人去问。玩家报的
     * 「重启服务器以后请求台看不见物品，要拆掉重新放重新设频率」就是它。
     *
     * <p>现在过期的条目会被丢掉、重新问一遍。代价是最坏情况白问一次；不这么做的代价是一条网络永远
     * 哑掉。这两者不对等。
     */
    private static final Map<RemoteNetworkId, Pending> OUTSTANDING = new ConcurrentHashMap<>();

    /** 多久没等到回音就把那次查询忘掉重问。比一次来回长得多，比人的耐心短。 */
    private static final long RETRY_AFTER_MS = 10_000L;

    /** 最近几次别人来问库存、我们答了什么。诊断用，见 {@code /distantstock stock}。 */
    private static final java.util.Deque<String> RECENT_QUERIES =
            new java.util.concurrent.ConcurrentLinkedDeque<>();
    private static final int RECENT_QUERIES_KEPT = 6;

    private static void noteQuery(String line) {
        RECENT_QUERIES.addFirst(line);
        while (RECENT_QUERIES.size() > RECENT_QUERIES_KEPT) {
            RECENT_QUERIES.removeLast();
        }
    }

    /** 诊断用：最近几次别人来问库存时我们是怎么答的（最新在前）。 */
    public static java.util.List<String> recentQueries() {
        return java.util.List.copyOf(RECENT_QUERIES);
    }

    /**
     * 这一次查询的回音到了，把"还没回音"那一格清掉。
     *
     * <p><b>只清自己那一次</b>：表里存的是一条 {@link Pending} 记录，不是那个查询 id —— 拿 UUID 去
     * {@code remove(key, value)} 永远比不上，于是这一格从来没有被清掉过，界面上就会一直显示"有一问没
     * 回音（N 秒）"，而那一次其实早就答完了。它顺带把"同一张网络十秒内不重复问"这条节流也一起兜住了，
     * 所以只是个谎话，不是个事故 —— 但诊断里的一句谎话比没有诊断更坏。
     */
    private static void settle(RemoteNetworkId network, UUID queryId) {
        Pending pending = OUTSTANDING.get(network);
        if (pending != null && pending.queryId().equals(queryId)) {
            OUTSTANDING.remove(network, pending);
        }
    }

    private static String shortId(RemoteNetworkId network) {
        return network == null ? "-" : network.createFrequency().toString().substring(0, 8);
    }

    public static void register() {
        TranserverBridge.handler(RoutingChannels.STOCK_QUERY, TranserverStockService::query);
        TranserverBridge.handler(RoutingChannels.STOCK_RESULT, TranserverStockService::result);
    }

    public static void tick() {
        acknowledgeCompleted();
        UUID local = TranserverBridge.nodeId();
        if (local == null) {
            return;
        }
        for (RemoteNetworkId network : StockCache.watchedNetworks(5 * 60_000L)) {
            if (network.nodeId().equals(local)) {
                continue;
            }
            // 对面刚说过"不认识它"就先别追问 —— 但只是**等一等**，不是永远不问。这条以前是永久的
            // （unwatch），于是对面重启一次就把这张网络在这边按死了，终端里永远是空的。
            if (StockCache.refusalWaitMs(network) > 0) {
                continue;
            }
            Pending pending = OUTSTANDING.get(network);
            if (pending != null && System.currentTimeMillis() - pending.sentAt() < RETRY_AFTER_MS) {
                continue;
            }
            long age = StockCache.ageMs(network);
            if (age >= 0 && age < 5_000L) {
                continue;
            }
            try {
                UUID queryId = UUID.randomUUID();
                UUID messageId = TranserverBridge.send(network.nodeId().toString(), RoutingChannels.STOCK_QUERY,
                        StockWireCodec.encodeQuery(new StockWireCodec.Query(queryId, network)), queryId.toString());
                if (messageId != null) {
                    OUTSTANDING.put(network, new Pending(queryId, System.currentTimeMillis()));
                }
            } catch (IOException ignored) {
            }
        }
    }

    /**
     * 诊断用：这张网络现在有没有一次发出去还没等到回音的查询，多久以前发的（没有就是 -1）。
     *
     * <p>{@code /distantstock stock} 用它把"这条链断在哪一节"一次说完 —— 玩家报的"重启后远程库存
     * 是空的"有三种完全不同的原因（没人在看、问了没人答、答了说没有），光看界面分不出来。
     */
    public static long pendingAgeMs(RemoteNetworkId network) {
        Pending pending = network == null ? null : OUTSTANDING.get(network);
        return pending == null ? -1L : System.currentTimeMillis() - pending.sentAt();
    }

    private static CompletableFuture<DeliveryResult> query(ReceivedMessage message) {
        final StockWireCodec.Query query;
        try {
            query = StockWireCodec.decodeQuery(message.payload());
        } catch (IOException | IllegalArgumentException exception) {
            noteQuery("报文读不出来 → REJECTED");
            return CompletableFuture.completedFuture(DeliveryResult.REJECTED);
        }
        MinecraftServer server = TranserverBridge.server();
        if (server == null || !server.isRunning()) {
            noteQuery(shortId(query.networkId()) + " 服务器没在跑 → RETRY");
            return CompletableFuture.completedFuture(DeliveryResult.RETRY);
        }
        CompletableFuture<DeliveryResult> applied = new CompletableFuture<>();
        server.execute(() -> {
            DeliveryResult valid = validateLocal(server, TranserverBridge.nodeId(), query.networkId());
            if (valid != DeliveryResult.APPLIED) {
                noteQuery(shortId(query.networkId()) + " " + valid);
                applied.complete(valid);
                return;
            }
            try {
                var lines = CreateStock.summary(query.networkId().createFrequency()).stream()
                        .map(item -> new LinkQueues.Line(item.itemId, item.count)).toList();
                StockWireCodec.Result result = new StockWireCodec.Result(query.queryId(), query.networkId(), lines);
                UUID sent = TranserverBridge.send(message.source(), RoutingChannels.STOCK_RESULT,
                        StockWireCodec.encodeResult(result), query.queryId().toString());
                noteQuery(shortId(query.networkId()) + " 答 " + lines.size() + " 条 → "
                        + (sent == null ? "发不出去 RETRY" : "APPLIED"));
                applied.complete(sent == null ? DeliveryResult.RETRY : DeliveryResult.APPLIED);
            } catch (IOException | RuntimeException exception) {
                noteQuery(shortId(query.networkId()) + " 出错 " + exception.getClass().getSimpleName()
                        + " → RETRY");
                applied.complete(DeliveryResult.RETRY);
            }
        });
        return applied;
    }

    private static CompletableFuture<DeliveryResult> result(ReceivedMessage message) {
        final StockWireCodec.Result result;
        try {
            result = StockWireCodec.decodeResult(message.payload());
            if (!result.networkId().nodeId().equals(UUID.fromString(message.source()))) {
                return CompletableFuture.completedFuture(DeliveryResult.REJECTED);
            }
        } catch (IOException | IllegalArgumentException exception) {
            return CompletableFuture.completedFuture(DeliveryResult.REJECTED);
        }
        MinecraftServer server = TranserverBridge.server();
        if (server == null || !server.isRunning()) {
            return CompletableFuture.completedFuture(DeliveryResult.RETRY);
        }
        CompletableFuture<DeliveryResult> applied = new CompletableFuture<>();
        server.execute(() -> {
            var entries = new ArrayList<StockCache.Entry>();
            for (LinkQueues.Line line : result.items()) {
                ResourceLocation id = ResourceLocation.tryParse(line.itemId());
                if (id == null || BuiltInRegistries.ITEM.get(id) == Items.AIR) {
                    continue;
                }
                entries.add(new StockCache.Entry(line.itemId(), line.count()));
            }
            StockCache.put(result.networkId(), entries, StockCache.Source.PEER);
            // 对面答上来了：这张网络是活的，之前那些"不认识它"的退避一笔勾销。
            StockCache.clearRefusal(result.networkId());
            settle(result.networkId(), result.queryId());
            applied.complete(DeliveryResult.APPLIED);
        });
        return applied;
    }

    /**
     * 别人问的这张网络，是不是就在本服、此刻看得见。
     *
     * <p>顺序是有讲究的：**先看频率在不在，再看世界 id 对不对**。频率是这张网络真正的名字，节点 id
     * 说明问的是我，维度说明问的是哪个世界 —— 三样都指向"就在这儿"的时候，那个世界 id 只是对面**记着
     * 的我**长什么样，它可以是旧的。玩家 2026-09-18 报的重启之后必须拆掉重放，根子就是这里以前先判
     * 世界 id：对面身上存的是上一次开机的 id，于是"这张网络明明就在你身上"被答成了"没有这张网络"。
     *
     * <p>世界 id 对不上仍有它的用处：网络**不在这儿**、世界又对不上，那就是这个世界被换掉了，那张网络
     * 真的没了 —— 这一档才回 REJECTED。世界对得上而网络看不见，只是还没加载（服务器刚起来、区块还没
     * 打开），回 RETRY，让对面过一会儿再问。
     *
     * @param localNode 本机节点 id；{@code null} 表示没挂上 Transerver，那这张网络不可能在这儿
     */
    public static DeliveryResult validateLocal(MinecraftServer server, UUID localNode,
                                               RemoteNetworkId network) {
        if (localNode == null || !localNode.equals(network.nodeId())) {
            return DeliveryResult.REJECTED;
        }
        ResourceLocation dimension = ResourceLocation.tryParse(network.dimensionId());
        if (dimension == null) {
            return DeliveryResult.REJECTED;
        }
        ServerLevel level = server.getLevel(ResourceKey.create(Registries.DIMENSION, dimension));
        if (level == null) {
            return DeliveryResult.RETRY;
        }
        if (CreateStock.hasNetwork(network.createFrequency())) {
            // 网络就在这儿。对面记的世界 id 是旧的说不了什么 —— 它记的是"上一次它看见的我"，
            // 而这一刻的我就在它问的这个频率上。
            return DeliveryResult.APPLIED;
        }
        // 世界是对的、节点也是我，但那张网络现在看不见 —— 最常见的原因是**它就是还没加载**：
        // 服务器刚起来、请求台所在的区块还没打开。这一档只能是 RETRY。
        //
        // 这里以前还会分成"我公告过它 → RETRY / 没公告过 → REJECTED"，那个 REJECTED 是给"世界已经
        // 被换掉、网络真的不存在了"准备的。剩下的"没公告过"判不出真假：刚重启那一秒本地目录就是空的，
        // 于是对面会收到一个像是终审的"没有这张网络" —— 玩家 2026-09-18 报的正是这个。宁可让它 RETRY：
        // 传输层自己会放弃，代价最多几次重投。
        return WorldIdentity.get(level).equals(network.worldId())
                ? DeliveryResult.RETRY : DeliveryResult.REJECTED;
    }

    private static void acknowledgeCompleted() {
        TranserverApi api = TranserverBridge.attachedApi();
        if (api == null) {
            return;
        }
        for (var completed : api.completedSends(64)) {
            if (RoutingChannels.STOCK_QUERY.equals(completed.channel())) {
                try {
                    StockWireCodec.Query query = StockWireCodec.decodeQuery(completed.payload());
                    if (completed.state() == dev.transerver.api.DeliveryState.REJECTED) {
                        settle(query.networkId(), query.queryId());
                        // 对面说没有这张网络：**退避**，不是永远不问。以前这里是 unwatch —— 而"对面
                        // 还没起来"和"对面真没有"在那一刻长得一模一样，于是服务器重启一次就把这张
                        // 网络在这边永久静音了。现在连着被拒最多也就五分钟问一次，而对面答上来或者
                        // 玩家重新指向它都会立刻恢复。
                        StockCache.refuse(query.networkId());
                    }
                } catch (IOException ignored) {
                }
                api.acknowledgeCompletedSend(completed.messageId());
            } else if (RoutingChannels.STOCK_RESULT.equals(completed.channel())) {
                api.acknowledgeCompletedSend(completed.messageId());
            }
        }
    }

    private TranserverStockService() {
    }
}
