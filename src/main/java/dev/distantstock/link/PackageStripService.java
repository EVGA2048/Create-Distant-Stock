package dev.distantstock.link;

import dev.distantstock.routing.RoutingChannels;
import dev.transerver.api.DeliveryResult;
import dev.transerver.api.ReceivedMessage;
import net.minecraft.server.MinecraftServer;

import java.io.IOException;
import java.util.concurrent.CompletableFuture;

/**
 * Source-side intake for strip notices. The destination reports which registry entries it lacks; the
 * parcel itself stays in the escrow and is stripped by {@link ParcelEscrowPump} when its dock is loaded.
 */
public final class PackageStripService {
    public static void register() {
        TranserverBridge.handler(RoutingChannels.PACKAGE_STRIP, PackageStripService::receive);
    }

    private static CompletableFuture<DeliveryResult> receive(ReceivedMessage message) {
        final PackageStripCodec.Notice notice;
        try {
            notice = PackageStripCodec.decode(message.payload());
        } catch (IOException exception) {
            return CompletableFuture.completedFuture(DeliveryResult.REJECTED);
        }
        MinecraftServer server = TranserverBridge.server();
        if (server == null || !server.isRunning()) {
            return CompletableFuture.completedFuture(DeliveryResult.RETRY);
        }
        CompletableFuture<DeliveryResult> result = new CompletableFuture<>();
        server.execute(() -> result.complete(accept(server, notice)));
        return result;
    }

    private static DeliveryResult accept(MinecraftServer server, PackageStripCodec.Notice notice) {
        ParcelEscrow escrow = ParcelEscrow.get(server);
        if (escrow.find(notice.parcelId()).isEmpty()) {
            // The parcel already left the escrow, so this notice is stale. Acknowledge it to stop retries.
            return DeliveryResult.APPLIED;
        }
        if (!escrow.attachStrip(notice.parcelId(), notice.missingIds(), notice.detail())) {
            return DeliveryResult.APPLIED;
        }
        escrow.flush(server);
        return DeliveryResult.APPLIED;
    }

    private PackageStripService() {
    }
}
