package dev.distantstock.link;

import com.simibubi.create.content.logistics.box.PackageItem;
import dev.distantstock.block.DockBlockEntity;
import dev.distantstock.block.LoadedDocks;
import dev.distantstock.routing.RoutingChannels;
import dev.transerver.api.DeliveryResult;
import dev.transerver.api.ReceivedMessage;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.item.ItemStack;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/** Target-side parcel validation, de-duplication and insertion. */
public final class TranserverPackageService {
    public static void register() {
        TranserverBridge.handler(RoutingChannels.PACKAGE_DISPATCH, TranserverPackageService::receive);
    }

    private static CompletableFuture<DeliveryResult> receive(ReceivedMessage message) {
        final PackageDispatchCodec.Dispatch dispatch;
        try {
            dispatch = PackageDispatchCodec.decode(message.payload());
        } catch (IOException exception) {
            return CompletableFuture.completedFuture(DeliveryResult.REJECTED);
        }
        MinecraftServer server = TranserverBridge.server();
        if (server == null || !server.isRunning()) {
            return CompletableFuture.completedFuture(DeliveryResult.RETRY);
        }
        String sourceNode = message.source();
        CompletableFuture<DeliveryResult> result = new CompletableFuture<>();
        server.execute(() -> result.complete(apply(server, dispatch, sourceNode)));
        return result;
    }

    private static DeliveryResult apply(MinecraftServer server, PackageDispatchCodec.Dispatch dispatch,
                                        String sourceNode) {
        ParcelLedger ledger = ParcelLedger.get(server);
        if (ledger.contains(dispatch.parcelId())) {
            return DeliveryResult.APPLIED;
        }
        // Tell the source exactly what this server lacks. Only the source owns those mods, so it is the only
        // node that can take the offending items out of the parcel and hand them back to its own logistics.
        List<String> missing = dispatch.manifest().missingRegistryEntries();
        if (!missing.isEmpty()) {
            requestStrip(sourceNode, dispatch.parcelId(), missing);
            return DeliveryResult.REJECTED;
        }
        ItemStack parcel = PackageCodec.decode(dispatch.encodedPackage(), server.registryAccess());
        if (parcel.isEmpty() || !PackageItem.isPackage(parcel)) {
            return DeliveryResult.REJECTED;
        }
        DockBlockEntity dock = LoadedDocks.importFor(parcel, dispatch.receivingDockGroupId());
        if (dock == null || dock.isFull()) {
            return DeliveryResult.RETRY;
        }
        if (!dock.insert(parcel)) {
            return DeliveryResult.RETRY;
        }
        ledger.markApplied(dispatch.parcelId());
        return DeliveryResult.APPLIED;
    }

    private static void requestStrip(String sourceNode, java.util.UUID parcelId, List<String> missing) {
        if (sourceNode == null || sourceNode.isBlank()) {
            return;
        }
        try {
            PackageStripCodec.Notice notice = new PackageStripCodec.Notice(parcelId, missing,
                    "missing_registry_entries");
            TranserverBridge.send(sourceNode, RoutingChannels.PACKAGE_STRIP,
                    PackageStripCodec.encode(notice), parcelId.toString());
        } catch (IOException ignored) {
            // Without the notice the source falls back to returning the whole parcel to its own dock.
        }
    }

    private TranserverPackageService() {
    }
}
