package dev.distantstock.link;

import dev.transerver.api.SendOptions;
import dev.transerver.api.TranserverApi;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

/** Admission is bounded in both message count and bytes. Completion means durable acceptance. */
final class AsyncSender implements AutoCloseable {
    private static final int MAX_PAYLOAD = 2 * 1024 * 1024;
    private static final long MAX_BYTES = 8L * 1024 * 1024;
    private final ThreadPoolExecutor worker = new ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS,
            new ArrayBlockingQueue<>(128), runnable -> {
                Thread thread = new Thread(runnable, "distantstock-send-io");
                thread.setDaemon(true);
                return thread;
            }, new ThreadPoolExecutor.AbortPolicy());
    private final ConcurrentMap<String, CompletableFuture<UUID>> recurring = new ConcurrentHashMap<>();
    private long queuedBytes;
    private long outstandingBytes;
    private int outstandingCount;
    private final java.util.Map<String, Integer> peerCounts = new java.util.HashMap<>();
    private final java.util.Map<String, Long> peerBytes = new java.util.HashMap<>();

    synchronized CompletableFuture<UUID> submit(TranserverApi api, String destination, String channel,
                                                 byte[] payload, SendOptions options, boolean coalesce) {
        // A channel can have distinct control-plane generations in flight. In particular, startup
        // sends a legacy network announcement before the capability hello arrives, then must send
        // the catalog-bearing announcement immediately afterwards. Coalesce repetitions of the
        // same generation, never two wire formats that happen to share a channel.
        String key = destination + "\0" + channel + "\0"
                + (options == null || options.correlationId() == null ? "" : options.correlationId());
        if (coalesce) {
            var previous = recurring.get(key);
            if (previous != null) return previous;
        }
        if (payload == null || payload.length > MAX_PAYLOAD || queuedBytes + payload.length > MAX_BYTES
                || outstandingCount >= 512 || outstandingBytes + payload.length > 32L * 1024 * 1024
                || (!coalesce && (peerCounts.getOrDefault(destination, 0) >= 64
                || peerBytes.getOrDefault(destination, 0L) + payload.length > 8L * 1024 * 1024))) {
            return CompletableFuture.failedFuture(new RejectedExecutionException("Transport byte budget exhausted"));
        }
        var result = new CompletableFuture<UUID>();
        byte[] copy = payload.clone();
        queuedBytes += copy.length;
        outstandingBytes += copy.length;
        outstandingCount++;
        peerCounts.merge(destination, 1, Integer::sum);
        peerBytes.merge(destination, (long) copy.length, Long::sum);
        AtomicBoolean released = new AtomicBoolean();
        Runnable releaseOutstanding = () -> {
            if (released.compareAndSet(false, true)) synchronized (AsyncSender.this) {
                outstandingBytes -= copy.length;
                outstandingCount--;
                peerCounts.computeIfPresent(destination, (peer, count) -> count == 1 ? null : count - 1);
                peerBytes.computeIfPresent(destination, (peer, bytes) -> bytes == copy.length ? null : bytes - copy.length);
            }
        };
        result.whenComplete((id, error) -> { if (error != null) releaseOutstanding.run(); });
        if (coalesce) recurring.put(key, result);
        Task task = new Task(copy.length, result, () -> {
            try {
                var handle = api.send(destination, channel, copy, options);
                handle.completion().whenComplete((receipt, error) -> {
                    if (coalesce) recurring.remove(key, result);
                    releaseOutstanding.run();
                });
                result.complete(handle.messageId());
            } catch (RuntimeException error) {
                if (coalesce) recurring.remove(key, result);
                result.completeExceptionally(error);
            }
        });
        try { worker.execute(task); }
        catch (RejectedExecutionException full) {
            if (coalesce) recurring.remove(key, result);
            task.cancel(full);
        }
        return result;
    }

    private final class Task implements Runnable {
        private final int bytes;
        private final CompletableFuture<UUID> result;
        private final Runnable action;
        private final AtomicBoolean claimed = new AtomicBoolean();
        Task(int bytes, CompletableFuture<UUID> result, Runnable action) {
            this.bytes = bytes; this.result = result; this.action = action;
        }
        private void release() { synchronized (AsyncSender.this) { queuedBytes -= bytes; } }
        public void run() {
            if (!claimed.compareAndSet(false, true)) return;
            try { action.run(); } finally { release(); }
        }
        void cancel(Throwable error) {
            if (!claimed.compareAndSet(false, true)) return;
            result.completeExceptionally(error);
            release();
        }
    }

    public void close() {
        for (Runnable task : worker.shutdownNow()) {
            ((Task) task).cancel(new RejectedExecutionException("Server is stopping"));
        }
        recurring.clear();
    }
}
