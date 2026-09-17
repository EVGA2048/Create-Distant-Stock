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

    /**
     * Validates a dispatch against this server and inserts the parcel, returning what the caller
     * should do with the source-side record.
     *
     * <p>Public because the escrow pump delivers parcels addressed to this node in-process, with no
     * transport in between: the same validation, de-duplication and insertion must run for a local
     * delivery as for one that arrived over Transerver, or a parcel sent to "this node" would take a
     * second, weaker path through the code.
     */
    public static DeliveryResult apply(MinecraftServer server, PackageDispatchCodec.Dispatch dispatch,
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
        // 过海。包裹从对面来，身上穿的还是对面那台服务器的门牌 —— 在这边认不出任何一台港，
        // 所以先换上这一侧的地址，再去找港。这就是「两个地址」里交换的那一下，也是为什么
        // 包裹到了以后只剩一个地址：旧的那个指的是它不会再去的服务器。
        if (dev.distantstock.routing.RemoteRouteData.applyHomeAddress(parcel)) {
            LOG.info("[DistantStock/Parcel] home address applied parcel={} source={} address={}",
                    dispatch.parcelId(), sourceNode,
                    com.simibubi.create.content.logistics.box.PackageItem.getAddress(parcel));
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
        // 指名给某个玩家的包裹，落地时告诉那个人一声。他可能正在别处忙，而这件货只有他能取走 ——
        // 不说的话就是"寄了但没人知道到了"。
        String addressee = dev.distantstock.routing.ParcelAddressing.addressee(parcel);
        if (!addressee.isEmpty()) {
            net.minecraft.server.level.ServerPlayer target =
                    server.getPlayerList().getPlayerByName(addressee);
            if (target != null) {
                target.displayClientMessage(net.minecraft.network.chat.Component.translatable(
                        "message.distantstock.parcel.arrived",
                        dock.getBlockPos().getX() + ", " + dock.getBlockPos().getY()
                                + ", " + dock.getBlockPos().getZ()), false);
            }
        }
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
