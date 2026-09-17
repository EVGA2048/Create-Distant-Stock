package dev.distantstock.link;

import com.simibubi.create.AllDataComponents;
import com.simibubi.create.content.logistics.box.PackageItem;
import dev.distantstock.block.DockBlockEntity;
import dev.distantstock.block.LoadedDocks;
import dev.distantstock.routing.RemoteRouteData;
import dev.distantstock.routing.RoutingChannels;
import dev.transerver.api.CompletedSend;
import dev.transerver.api.DeliveryResult;
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
    /**
     * How long a parcel may sit held before the dock stops waiting and gives it back.
     *
     * <p>A parcel held this long is not slow, it is undeliverable: an address that matches no dock,
     * a destination group with nothing loaded, a receiver that is switched off. Every one of those
     * reports RETRY, and RETRY on its own is indistinguishable from "not yet" — so without a
     * deadline the parcel leaves the dock, counts as in flight for ever, and is never seen again.
     * That is the worst of the three outcomes, because there is nothing to look at and nothing to
     * notice; a parcel handed back through the fallback face is a player being told.
     *
     * <p>The routing design says a group that is temporarily full reports RETRY and its parcel
     * waits, and a group whose docks are merely unloaded is the same kind of temporary. Neither can
     * be told apart from "will never arrive" with what the server knows: {@link
     * dev.distantstock.block.LoadedDocks} holds only docks that are loaded, so a group with no docks
     * and a group whose docks are all unloaded look identical from here. An earlier attempt to
     * answer at once by asking the group directly got that wrong — it returned parcels that were
     * waiting for a chunk to load.
     *
     * <p>So there is one deadline and it has to serve both. Thirty minutes, because "temporarily
     * full" has to be allowed to mean something: a parcel held that long is not waiting for a dock
     * to empty, and handing it back is worth more than holding it where nobody can find it.
     */
    public static final long HOLD_TIMEOUT_TICKS = 36000;

    private static final Map<UUID, Long> REJECTED_SINCE = new ConcurrentHashMap<>();

    public static void tick(MinecraftServer server) {
        ParcelEscrow escrow = ParcelEscrow.get(server);
        ParcelReturnInbox returns = ParcelReturnInbox.get(server);
        ParcelQuarantine quarantine = ParcelQuarantine.get(server);
        returns.reconcile(escrow, quarantine);
        resolveCompleted(escrow);
        expireHeld(server, escrow);
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

    /**
     * Turns parcels nobody has been able to take into rejections, so the return path picks them up.
     *
     * <p>Marked rather than returned here, because returning is what {@link #handleRejected} already
     * does and does completely: it knows about the origin dock being gone, about the fallback face
     * having no room, and about the return inbox. A second copy of that would be a second set of
     * those decisions to keep in step.
     *
     * <p>Only records still held. A record that was handed to a transport is the transport's to
     * finish or to lose, and calling it back after a timeout could return a parcel that is about to
     * be applied at the other end — the ledger would drop the duplicate, but the player would have
     * the goods twice.
     */
    private static void expireHeld(MinecraftServer server, ParcelEscrow escrow) {
        long gameTime = server.overworld().getGameTime();
        for (ParcelEscrow.Record record : escrow.records()) {
            if (record.state() != ParcelEscrow.State.HELD) {
                continue;
            }
            if (record.createdAt() > gameTime) {
                // Stamped by a version that wrote a wall clock here, so there is no age to compare.
                // Left alone rather than re-stamped: the record is either already delivered or about
                // to be, and starting its clock again would only delay the one outcome it can have.
                continue;
            }
            if (!pastDeadline(gameTime, record.createdAt())) {
                continue;
            }
            LOG.warn("[DistantStock/Parcel] held too long, returning parcel={} target={} group={} age={}",
                    record.parcelId(), record.destinationNode(), record.receivingDockGroupId(),
                    gameTime - record.createdAt());
            escrow.rejected(record.parcelId(), "delivery_timeout");
        }
    }

    /**
     * Whether a parcel held at {@code createdAt} has been waiting longer than the window.
     *
     * <p>A named function rather than an inline subtraction because it is the one part of the
     * deadline that can be checked without waiting: the rest needs a level, a running clock and half
     * an hour. It is also where a wrong clock would show up — comparing a level's game time against
     * a wall clock makes this false for every parcel, which is a bug that looks exactly like an
     * empty deadline.
     */
    public static boolean pastDeadline(long gameTime, long createdAt) {
        return gameTime - createdAt >= HOLD_TIMEOUT_TICKS;
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
                if (TranserverBridge.isLocal(record.destinationNode())) {
                    // 计费不在这里：这是同一次传输的下半段，邮包离开港时就由塔付过费了（DockBlockEntity.ship）。
                    // 在这里再收一次等于对同一段路收两遍，所以本机投递只是把已经付费的包裹送到目的地。
                    // Billing is not repeated here. A parcel reaches this branch only after leaving a
                    // dock, and the tower that carries that dock has already paid for it — charging
                    // again would bill one transfer twice.
                    // 目的地就是本机：没有 Transerver 也要送到，所以直接在同一条服务器线程上跑接收侧校验。
                    // The destination is this very node, so there is no transport to hand the parcel to.
                    // Without this branch an unattached bridge makes TranserverBridge.send return null and
                    // the record silently stays HELD forever: the parcel has left the dock, the goggles
                    // count it as in flight, and it never arrives. Running the receiver's own apply() here
                    // is what makes "send to my other dock group" work on a save with no Transerver at all.
                    DeliveryResult result = TranserverPackageService.apply(server, dispatch,
                            TranserverBridge.localNodeId());
                    if (result == DeliveryResult.APPLIED) {
                        // The parcel is in the target dock now. Remove the escrow record first and flush,
                        // because a crash between the two leaves a record the ledger will recognise as a
                        // duplicate (apply() short-circuits on it) rather than a second parcel.
                        escrow.remove(record.parcelId());
                        escrow.flush(server);
                        LOG.info("[DistantStock/Parcel] delivered parcel={} target=local group={} strips={}",
                                record.parcelId(), record.receivingDockGroupId(), record.strips());
                        sent++;
                    } else if (result == DeliveryResult.REJECTED) {
                        // 永久拒绝：本机不会收这个包裹 —— 清单缺条目、包裹解不开，或者**这一侧根本没有
                        // 它要去的那个收货港组**（那个组等多久都不会出现，见 TranserverPackageService）。
                        // 交给既有的退件流程。
                        escrow.rejected(record.parcelId(), "target_refused");
                    }
                    // RETRY 不是失败：目标港所在区块还没加载、港满了或在忙，下一个 tick 会再试。
                    // RETRY is not a failure: it is the same "not yet" the transport path reports, and the
                    // record deliberately stays HELD so a later tick can finish the delivery.
                    continue;
                }
                UUID messageId = TranserverBridge.send(record.destinationNode(), RoutingChannels.PACKAGE_DISPATCH,
                        PackageDispatchCodec.encode(dispatch), record.parcelId().toString());
                if (messageId != null) {
                    escrow.submitted(record.parcelId(), messageId);
                    LOG.info("[DistantStock/Parcel] submitted parcel={} message={} target={} group={} strips={}",
                            record.parcelId(), messageId, record.destinationNode(),
                            record.receivingDockGroupId(), record.strips());
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
                        LOG.info("[DistantStock/Parcel] completed parcel={} message={} result=APPLIED",
                                parcelId, completed.messageId());
                        escrow.remove(parcelId);
                    } else if (completed.state() == DeliveryState.REJECTED) {
                        LOG.warn("[DistantStock/Parcel] completed parcel={} message={} result=REJECTED detail={}",
                                parcelId, completed.messageId(), completed.detail());
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
            LOG.error("[DistantStock/Parcel] quarantined parcel={} source=escrow reason={} detail={}",
                    record.parcelId(), reason, detail);
        } catch (IllegalStateException full) {
            // Quarantine is full: the escrow record stays authoritative instead of being discarded.
        }
    }

    private static void moveToQuarantine(MinecraftServer server, ParcelReturnInbox returns,
                                         ParcelQuarantine quarantine, ParcelReturnInbox.Record record,
                                         String reason, String detail) {
        try {
            quarantine.transfer(returns, record, reason, detail, () -> quarantine.flush(server));
            LOG.error("[DistantStock/Parcel] quarantined parcel={} source=return_inbox reason={} detail={}",
                    record.parcelId(), reason, detail);
        } catch (IllegalStateException full) {
            // Quarantine is full: the return inbox record stays authoritative instead of being discarded.
        }
    }

    private ParcelEscrowPump() {
    }
}
