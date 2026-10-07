package dev.distantstock.link;

import dev.distantstock.routing.RoutingChannels;
import dev.transerver.api.DeliveryResult;
import net.minecraft.server.MinecraftServer;

import java.io.IOException;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/** Cross-node parcel-status receipts. Trace delivery is diagnostic only and never owns cargo. */
public final class PackageTraceService {
    public static void register() {
        TranserverBridge.handler(RoutingChannels.PACKAGE_TRACE, message -> {
            final PackageTraceCodec.Notice notice;
            try {
                notice = PackageTraceCodec.decode(message.payload());
            } catch (IOException bad) {
                return CompletableFuture.completedFuture(DeliveryResult.REJECTED);
            }
            MinecraftServer server = TranserverBridge.server();
            if (server == null || !server.isRunning()) {
                return CompletableFuture.completedFuture(DeliveryResult.RETRY);
            }
            server.execute(() -> ParcelJournal.get(server).recordRemote(
                    notice.parcelId(), notice.at(), message.source(), notice.stage(), notice.detail()));
            return CompletableFuture.completedFuture(DeliveryResult.APPLIED);
        });
    }

    public static void report(MinecraftServer server, String sourceNode, UUID parcelId,
                              String stage, String detail) {
        if (server == null || parcelId == null || stage == null || stage.isBlank()) return;
        ParcelJournal.get(server).record(parcelId, stage, detail);
        UUID local = TranserverBridge.localNodeUuid();
        if (sourceNode == null || sourceNode.isBlank()
                || (local != null && local.toString().equals(sourceNode))) {
            return;
        }
        try {
            TranserverBridge.send(sourceNode, RoutingChannels.PACKAGE_TRACE,
                    PackageTraceCodec.encode(new PackageTraceCodec.Notice(
                            parcelId, System.currentTimeMillis(), stage, detail)), parcelId.toString());
        } catch (IOException ignored) {
        }
    }

    public static void acknowledgeCompleted() {
        for (var completed : TranserverBridge.completedSends(256)) {
            if (RoutingChannels.PACKAGE_TRACE.equals(completed.channel())) {
                TranserverBridge.acknowledgeCompletedSend(completed.messageId());
            }
        }
    }

    private PackageTraceService() {
    }
}
