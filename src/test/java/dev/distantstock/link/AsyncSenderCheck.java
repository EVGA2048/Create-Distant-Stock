package dev.distantstock.link;

import dev.transerver.api.*;
import dev.distantstock.routing.RoutingChannels;
import java.util.*;
import java.util.concurrent.*;

/** Exercises the actual bounded sender without a Minecraft main thread or real disk. */
public final class AsyncSenderCheck {
    static final class Api implements TranserverApi {
        final CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        final List<byte[]> payloads = new CopyOnWriteArrayList<>();
        final List<String> channels = new CopyOnWriteArrayList<>();
        final List<CompletableFuture<SendReceipt>> receipts = new CopyOnWriteArrayList<>();
        volatile boolean block;
        volatile String thread;
        public SendHandle send(String destination, String channel, byte[] payload, SendOptions options) {
            thread = Thread.currentThread().getName(); entered.countDown();
            if (block) try { release.await(); } catch (InterruptedException e) { throw new RuntimeException(e); }
            payloads.add(payload);
            channels.add(channel);
            var receipt = new CompletableFuture<SendReceipt>(); receipts.add(receipt);
            return new SendHandle(UUID.randomUUID(), receipt);
        }
        public void registerHandler(String channel, MessageHandler handler) {}
        public NodeStatus status() { return null; }
        public List<CompletedSend> completedSends(int limit) { return List.of(); }
        public void acknowledgeCompletedSend(UUID id) {}
    }
    public static void main(String[] args) throws Exception {
        var api = new Api(); api.block = true;
        try (var sender = new AsyncSender()) {
            byte[] payload = {7};
            var sent = sender.submit(api, "offline", "hello", payload,
                    new SendOptions("generation-a", "application/octet-stream", null), true);
            check(api.entered.await(2, TimeUnit.SECONDS), "worker started");
            check(!sent.isDone(), "acceptance waits for durable API send");
            payload[0] = 9;
            check(sender.submit(api, "offline", "hello", payload,
                    new SendOptions("generation-a", "application/octet-stream", null), true) == sent,
                    "same heartbeat generation coalesced");
            var upgraded = sender.submit(api, "offline", "hello", payload,
                    new SendOptions("generation-b", "application/octet-stream", null), true);
            check(upgraded != sent, "new wire generation is not hidden behind an older heartbeat");
            api.release.countDown();
            sent.get(2, TimeUnit.SECONDS);
            upgraded.get(2, TimeUnit.SECONDS);
            check(api.payloads.getFirst()[0] == 7, "payload copied before returning");
            check(api.thread.equals("distantstock-send-io"), "send runs off main thread");
            // The two control-plane generations above also occupy this peer's outstanding budget.
            for (int i = 0; i < 62; i++) sender.submit(api, "offline", "query", new byte[1], SendOptions.defaults(), false).get(2, TimeUnit.SECONDS);
            check(sender.submit(api, "offline", "query", new byte[1], SendOptions.defaults(), false).isCompletedExceptionally(), "offline quota bounded");
            sender.submit(api, "online", "query", new byte[1], SendOptions.defaults(), false).get(2, TimeUnit.SECONDS);
            sender.submit(api, "offline", "announce", new byte[1], SendOptions.defaults(), true).get(2, TimeUnit.SECONDS);
            check(sender.submit(api, "online", "query", new byte[2 * 1024 * 1024 + 1], SendOptions.defaults(), false).isCompletedExceptionally(), "oversized payload refused");
        }
        var blocked = new Api(); blocked.block = true;
        var sender = new AsyncSender();
        var active = sender.submit(blocked, "one", "query", new byte[1], SendOptions.defaults(), false);
        check(blocked.entered.await(2, TimeUnit.SECONDS), "blocked worker started");
        var queued = sender.submit(blocked, "two", "query", new byte[1], SendOptions.defaults(), false);
        sender.close();
        check(queued.isCompletedExceptionally(), "shutdown resolves queued futures");
        try { active.get(2, TimeUnit.SECONDS); throw new AssertionError("active interrupted"); } catch (ExecutionException expected) {}
        var priorityApi = new Api(); priorityApi.block = true;
        try (var prioritySender = new AsyncSender()) {
            var first = prioritySender.submit(priorityApi, "node", "busy", new byte[1],
                    SendOptions.defaults(), false);
            check(priorityApi.entered.await(2, TimeUnit.SECONDS), "priority worker entered");
            var normal = prioritySender.submit(priorityApi, "node", "bulk", new byte[1],
                    SendOptions.defaults(), false);
            var join = prioritySender.submit(priorityApi, "node", RoutingChannels.DISTANT_NETWORK_JOIN_REQUEST,
                    new byte[1], SendOptions.defaults(), false);
            priorityApi.release.countDown();
            first.get(2, TimeUnit.SECONDS);
            join.get(2, TimeUnit.SECONDS);
            normal.get(2, TimeUnit.SECONDS);
            check(priorityApi.channels.equals(List.of("busy", RoutingChannels.DISTANT_NETWORK_JOIN_REQUEST,
                            "bulk")), "join handshake was stuck behind an ordinary outbound backlog");
        }
        System.out.println("AsyncSender checks passed: durability, thread, copy, coalescing, peer fairness, size, urgent join, shutdown");
    }
    static void check(boolean value, String label) { if (!value) throw new AssertionError(label); }
}
