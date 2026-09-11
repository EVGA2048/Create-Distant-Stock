package dev.distantstock.link;

import com.simibubi.create.AllDataComponents;
import com.simibubi.create.content.logistics.box.PackageItem;
import dev.distantstock.block.DockBlockEntity;
import dev.distantstock.block.LoadedDocks;
import dev.distantstock.routing.RemoteRouteData;
import dev.distantstock.routing.RoutingChannels;
import dev.transerver.api.CompletedSend;
import dev.transerver.api.DeliveryState;
import dev.transerver.api.TranserverApi;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.ItemContainerContents;
import net.neoforged.neoforge.items.ItemStackHandler;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/** Moves durable escrow records through Transerver and resolves final receipts. */
public final class ParcelEscrowPump {
    private static final Logger LOG = LogManager.getLogger();
    /** Time the escrow keeps a rejected parcel before the return inbox takes ownership of it. */
    private static final long RETURN_GRACE_TICKS = 600;
    /** Retry interval for parcels parked in the return inbox. */
    private static final long RETURN_RETRY_TICKS = 100;
    /** How often one parcel may be stripped and re-sent before it is handed back to the player. */
    public static final int STRIP_LIMIT = 3;

    private static final Map<UUID, Long> REJECTED_SINCE = new ConcurrentHashMap<>();

    public static void tick(MinecraftServer server) {
        ParcelEscrow escrow = ParcelEscrow.get(server);
        ParcelReturnInbox returns = ParcelReturnInbox.get(server);
        ParcelQuarantine quarantine = ParcelQuarantine.get(server);
        returns.reconcile(escrow, quarantine);
        resolveCompleted(escrow);
        handleRejected(server, escrow, returns, quarantine);
        deliverReturns(server, returns, quarantine);
        submitHeld(server, escrow);
    }

    /**
     * True when a parcel should be stripped and sent again. Nothing removed means the manifest did not
     * cover the rejection, and the strip limit keeps two nodes from stripping and resending forever.
     */
    public static boolean shouldResend(int strips, boolean strippedAnything, int limit) {
        return strippedAnything && strips < limit;
    }

    private static void submitHeld(MinecraftServer server, ParcelEscrow escrow) {
        int sent = 0;
        for (ParcelEscrow.Record record : escrow.records()) {
            if (sent >= 8 || record.state() != ParcelEscrow.State.HELD) {
                continue;
            }
            try {
                ItemStack parcel = PackageCodec.decode(record.encodedPackage(), server.registryAccess());
                if (parcel.isEmpty()) {
                    escrow.rejected(record.parcelId(), "source_decode_failed");
                    continue;
                }
                PackageDispatchCodec.Dispatch dispatch = PackageDispatchCodec.create(
                        record.parcelId(), record.receivingDockGroupId(), record.address(),
                        PayloadManifest.fromPackage(parcel), record.encodedPackage());
                UUID messageId = TranserverBridge.send(record.destinationNode(), RoutingChannels.PACKAGE_DISPATCH,
                        PackageDispatchCodec.encode(dispatch), record.parcelId().toString());
                if (messageId != null) {
                    escrow.submitted(record.parcelId(), messageId);
                    sent++;
                }
            } catch (RuntimeException ignored) {
                break;
            }
        }
    }

    private static void resolveCompleted(ParcelEscrow escrow) {
        TranserverApi api = TranserverBridge.attachedApi();
        if (api == null) {
            return;
        }
        for (CompletedSend completed : api.completedSends(64)) {
            if (!RoutingChannels.PACKAGE_DISPATCH.equals(completed.channel())) {
                continue;
            }
            try {
                UUID parcelId = PackageDispatchCodec.decode(completed.payload()).parcelId();
                ParcelEscrow.Record record = escrow.find(parcelId).orElse(null);
                // A stripped parcel is re-sent under the same parcel ID, so only the receipt that matches
                // the message currently in flight may change its state.
                if (record != null && completed.messageId().equals(record.messageId())) {
                    if (completed.state() == DeliveryState.APPLIED) {
                        escrow.remove(parcelId);
                    } else if (completed.state() == DeliveryState.REJECTED) {
                        escrow.rejected(parcelId, completed.detail());
                    }
                }
                api.acknowledgeCompletedSend(completed.messageId());
            } catch (IOException | RuntimeException ignored) {
                // Leave an undecodable completion visible for administrator diagnosis.
            }
        }
    }

    /**
     * Handles rejected parcels. A parcel the destination described precisely is stripped of the offending
     * items and sent again; everything else falls out of the dock's fallback face to the player who owns
     * that dock. Ownership only moves to the server return inbox when the origin dock is gone.
     */
    private static void handleRejected(MinecraftServer server, ParcelEscrow escrow, ParcelReturnInbox returns,
                                       ParcelQuarantine quarantine) {
        long gameTime = server.overworld().getGameTime();
        for (ParcelEscrow.Record record : escrow.records()) {
            boolean wantsStrip = !record.stripIds().isEmpty();
            if (record.state() != ParcelEscrow.State.REJECTED && !wantsStrip) {
                REJECTED_SINCE.remove(record.parcelId());
                continue;
            }
            ItemStack parcel = PackageCodec.decode(record.encodedPackage(), server.registryAccess());
            if (parcel.isEmpty()) {
                DockBlockEntity origin = LoadedDocks.at(record.originDimension(), record.originPos());
                if (origin != null) {
                    origin.noteFault("goggle.distantstock.fault.payload_unreadable");
                }
                moveToQuarantine(server, escrow, quarantine, record, "source_decode_failed",
                        "payload_bytes=" + record.encodedPackage().length());
                REJECTED_SINCE.remove(record.parcelId());
                continue;
            }
            DockBlockEntity origin = LoadedDocks.at(record.originDimension(), record.originPos());
            if (wantsStrip && strip(server, escrow, origin, record, parcel)) {
                REJECTED_SINCE.remove(record.parcelId());
                continue;
            }
            if (origin == null) {
                long since = REJECTED_SINCE.computeIfAbsent(record.parcelId(), key -> gameTime);
                if (gameTime - since >= RETURN_GRACE_TICKS) {
                    try {
                        returns.handOver(escrow, record, "origin_dock_unavailable", () -> returns.flush(server));
                    } catch (IllegalStateException full) {
                        // The return inbox is full: the escrow stays the single owner and /distantstock
                        // status reports the backlog until an administrator frees space.
                    }
                    REJECTED_SINCE.remove(record.parcelId());
                }
                continue;
            }
            if (!origin.hasFallbackRoom(1)) {
                origin.noteReleaseRefused();
                continue;
            }
            // Clear remote route data so the parcel is not automatically re-sent by the dock.
            RemoteRouteData.clear(parcel);
            if (!origin.offerFallback(parcel)) {
                origin.noteReleaseRefused();
                continue;
            }
            LOG.info("[DistantStock] Rejected parcel {} returned to origin dock (route cleared)", record.parcelId());
            origin.noteFallback(wantsStrip
                    ? "goggle.distantstock.fallback.strip_unmatched" : "goggle.distantstock.fallback.parcel");
            if (wantsStrip) {
                origin.noteFault("goggle.distantstock.fault.strip_unmatched");
            }
            if (record.strips() >= STRIP_LIMIT) {
                origin.noteFault("goggle.distantstock.fault.strip_limit");
            }
            escrow.remove(record.parcelId());
            escrow.flush(server);
            REJECTED_SINCE.remove(record.parcelId());
        }
    }

    /**
     * Removes the registry entries the destination reported missing from the parcel contents and queues the
     * remainder for another attempt. Returns true when the parcel was rewritten.
     */
    private static boolean strip(MinecraftServer server, ParcelEscrow escrow, DockBlockEntity origin,
                                 ParcelEscrow.Record record, ItemStack parcel) {
        if (origin == null || record.strips() >= STRIP_LIMIT) {
            return false;
        }
        ItemStackHandler contents = PackageItem.getContents(parcel);
        Set<String> missing = Set.copyOf(record.stripIds());
        List<ItemStack> kept = new ArrayList<>();
        List<ItemStack> stripped = new ArrayList<>();
        for (int slot = 0; slot < contents.getSlots(); slot++) {
            ItemStack stack = contents.getStackInSlot(slot);
            if (stack.isEmpty()) {
                continue;
            }
            boolean rejected = PayloadManifest.entriesOf(stack).stream().anyMatch(missing::contains);
            (rejected ? stripped : kept).add(stack.copy());
        }
        if (!shouldResend(record.strips(), !stripped.isEmpty(), STRIP_LIMIT)) {
            return false;
        }
        if (!origin.hasFallbackRoom(stripped.size())) {
            origin.noteReleaseRefused();
            return false;
        }
        parcel.set(AllDataComponents.PACKAGE_CONTENTS, ItemContainerContents.fromItems(kept));
        String encoded = PackageCodec.encode(parcel, server.registryAccess());
        if (encoded.isBlank() || !escrow.replacePayload(record.parcelId(), encoded, record.strips() + 1)) {
            return false;
        }
        for (ItemStack stack : stripped) {
            if (!origin.offerFallback(stack)) {
                origin.noteReleaseRefused();
            }
        }
        escrow.flush(server);
        origin.noteFallback("goggle.distantstock.fallback.stripped");
        origin.clearFault();
        return true;
    }

    /** Releases parcels the return inbox owns back to the dock's fallback face once that dock is loaded. */
    private static void deliverReturns(MinecraftServer server, ParcelReturnInbox returns,
                                       ParcelQuarantine quarantine) {
        long gameTime = server.overworld().getGameTime();
        for (ParcelReturnInbox.Record record : returns.records()) {
            if (record.attempts() > 0 && gameTime - record.lastAttemptAt() < RETURN_RETRY_TICKS) {
                continue;
            }
            ItemStack parcel = PackageCodec.decode(record.encodedPackage(), server.registryAccess());
            if (parcel.isEmpty()) {
                moveToQuarantine(server, returns, quarantine, record, "source_decode_failed",
                        "payload_bytes=" + record.encodedPackage().length());
                continue;
            }
            DockBlockEntity origin = LoadedDocks.at(record.originDimension(), record.originPos());
            if (origin == null || !origin.hasFallbackRoom(1)) {
                if (origin != null) {
                    origin.noteReleaseRefused();
                }
                returns.noteAttempt(record.parcelId(), gameTime);
                continue;
            }
            // Clear remote route data so the parcel is not automatically re-sent by the dock.
            RemoteRouteData.clear(parcel);
            if (!origin.offerFallback(parcel)) {
                origin.noteReleaseRefused();
                returns.noteAttempt(record.parcelId(), gameTime);
                continue;
            }
            LOG.info("[DistantStock] Return inbox parcel {} delivered to origin dock (route cleared)", record.parcelId());
            origin.noteFallback("goggle.distantstock.fallback.parcel");
            returns.remove(record.parcelId());
            returns.flush(server);
        }
    }

    private static void moveToQuarantine(MinecraftServer server, ParcelEscrow escrow,
                                         ParcelQuarantine quarantine, ParcelEscrow.Record record,
                                         String reason, String detail) {
        try {
            quarantine.transfer(escrow, record, reason, detail, () -> quarantine.flush(server));
        } catch (IllegalStateException full) {
            // Quarantine is full: the escrow record stays authoritative instead of being discarded.
        }
    }

    private static void moveToQuarantine(MinecraftServer server, ParcelReturnInbox returns,
                                         ParcelQuarantine quarantine, ParcelReturnInbox.Record record,
                                         String reason, String detail) {
        try {
            quarantine.transfer(returns, record, reason, detail, () -> quarantine.flush(server));
        } catch (IllegalStateException full) {
            // Quarantine is full: the return inbox record stays authoritative instead of being discarded.
        }
    }

    private ParcelEscrowPump() {
    }
}
