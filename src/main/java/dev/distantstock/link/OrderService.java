package dev.distantstock.link;

import dev.distantstock.config.StockConfig;
import dev.distantstock.stock.CreateStock;
import dev.distantstock.stock.StockCache;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public final class OrderService {
    public enum Result {
        QUEUED, FAIL, EMPTY, NO_PEER
    }

    public static Result place(UUID freq, String address, List<LinkQueues.Line> lines) {
        if (lines == null || lines.isEmpty()) {
            return Result.EMPTY;
        }
        if (CreateStock.hasNetwork(freq)) {
            List<StockCache.Entry> items = new ArrayList<>();
            for (LinkQueues.Line line : lines) {
                items.add(new StockCache.Entry(line.itemId(), line.count()));
            }
            boolean ok = CreateStock.request(freq, items, address);
            LinkQueues.lastPack(freq, ok ? LinkQueues.PackResult.SUCCESS : LinkQueues.PackResult.NO_STOCK);
            return ok ? Result.QUEUED : Result.FAIL;
        }
        if (!StockConfig.hasPeer()) {
            LinkQueues.lastPack(freq, LinkQueues.PackResult.UNLOADED);
            return Result.NO_PEER;
        }
        boolean ok = LinkQueues.offerOutboundOrder(new LinkQueues.Order(freq, address, lines, StockConfig.selfId()));
        if (ok) {
            LinkClient.wake();
            return Result.QUEUED;
        }
        return Result.FAIL;
    }

    public static void drainInbound() {
        LinkQueues.Order order;
        int n = 0;
        while (n++ < 8 && (order = LinkQueues.pollInboundOrder()) != null) {
            List<StockCache.Entry> items = new ArrayList<>();
            for (LinkQueues.Line line : order.items) {
                items.add(new StockCache.Entry(line.itemId(), line.count()));
            }
            if (!CreateStock.hasNetwork(order.freq)) {
                LinkQueues.lastPack(order.freq, LinkQueues.PackResult.UNLOADED);
                continue;
            }
            ReturnRoute.remember(order.address, order.from);
            boolean ok = CreateStock.request(order.freq, items, order.address);
            LinkQueues.lastPack(order.freq, ok ? LinkQueues.PackResult.SUCCESS : LinkQueues.PackResult.NO_STOCK);
        }
    }

    private OrderService() {
    }
}
