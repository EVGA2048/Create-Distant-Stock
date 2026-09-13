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
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/** Target-side parcel validation, de-duplication and insertion. */
public final class TranserverPackageService {
    private static final Logger LOG = LogManager.getLogger();

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
            LOG.info("[DistantStock/Parcel] duplicate already applied parcel={} source={} group={}",
                    dispatch.parcelId(), sourceNode, dispatch.receivingDockGroupId());
            return DeliveryResult.APPLIED;
        }
        // Tell the source exactly what this server lacks. Only the source owns those mods, so it is the only
        // node that can take the offending items out of the parcel and hand them back to its own logistics.
        List<String> missing = dispatch.manifest().missingRegistryEntries();
        if (!missing.isEmpty()) {
            LOG.warn("[DistantStock/Parcel] rejected incompatible parcel={} source={} group={} missing={}",
                    dispatch.parcelId(), sourceNode, dispatch.receivingDockGroupId(), missing);
            requestStrip(sourceNode, dispatch.parcelId(), missing);
            return DeliveryResult.REJECTED;
        }
        ItemStack parcel = PackageCodec.decode(dispatch.encodedPackage(), server.registryAccess());
        if (parcel.isEmpty() || !PackageItem.isPackage(parcel)) {
            LOG.warn("[DistantStock/Parcel] rejected unreadable parcel={} source={} group={}",
                    dispatch.parcelId(), sourceNode, dispatch.receivingDockGroupId());
            return DeliveryResult.REJECTED;
        }
        DockBlockEntity dock = LoadedDocks.importFor(parcel, dispatch.receivingDockGroupId());
        if (dock == null || dock.isFull()) {
            LOG.debug("[DistantStock/Parcel] waiting parcel={} source={} group={} reason={}",
                    dispatch.parcelId(), sourceNode, dispatch.receivingDockGroupId(),
                    dock == null ? "no_loaded_dock" : "dock_full");
            return DeliveryResult.RETRY;
        }
        if (!dock.insert(parcel)) {
            LOG.debug("[DistantStock/Parcel] waiting parcel={} source={} group={} reason=insert_refused",
                    dispatch.parcelId(), sourceNode, dispatch.receivingDockGroupId());
            return DeliveryResult.RETRY;
        }
        ledger.markApplied(dispatch.parcelId());
        LOG.info("[DistantStock/Parcel] applied parcel={} source={} group={} dock={} dimension={}",
                dispatch.parcelId(), sourceNode, dispatch.receivingDockGroupId(), dock.getBlockPos(),
                dock.getLevel() == null ? "unknown" : dock.getLevel().dimension().location());
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
