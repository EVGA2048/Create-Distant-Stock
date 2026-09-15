package dev.distantstock.link;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Custody checks for rejected parcels, runnable with ./gradlew verifyParcelOwnership.
 *
 * <p>Every scenario answers one question: who owns the parcel right now? The only valid answers are the
 * escrow, the return inbox or the quarantine library, and exactly one of them may hold it at a time.
 */
public final class ParcelOwnershipCheck {
    private static final String PAYLOAD = "H4sIAAAAAAAA/test-payload";
    private static final String ADDRESS = "工厂 A / 收货";
    private static final String DIMENSION = "minecraft:overworld";
    private static final UUID GROUP = UUID.fromString("00000000-0000-0000-0000-00000000d0c1");
    private static final String NODE = "11111111-2222-3333-4444-555555555555";

    public static void main(String[] args) {
        handOverMovesOwnership();
        repeatedHandOverKeepsOneRecord();
        failedFlushKeepsTheEscrowAsOwner();
        crashWindowResolvesToASingleOwner();
        quarantineTakesOverWithRawBytes();
        quarantineDetectsTamperedPayload();
        recordsSurviveReload();
    }

    private static void handOverMovesOwnership() {
        ParcelEscrow escrow = new ParcelEscrow();
        ParcelReturnInbox inbox = new ParcelReturnInbox();
        ParcelQuarantine quarantine = new ParcelQuarantine();
        UUID parcelId = hold(escrow);
        escrow.rejected(parcelId, "target_missing_mod");
        require(owners(parcelId, escrow, inbox, quarantine) == 1, "rejection duplicated the parcel");

        AtomicInteger flushes = new AtomicInteger();
        ParcelReturnInbox.Record adopted = inbox.handOver(escrow, record(escrow, parcelId),
                "origin_dock_unavailable", flushes::incrementAndGet);
        require(flushes.get() == 1, "hand-over must flush the new owner exactly once");
        require(escrow.find(parcelId).isEmpty(), "escrow must release the parcel after a durable hand-over");
        require(owners(parcelId, escrow, inbox, quarantine) == 1, "hand-over duplicated or lost the parcel");
        require(adopted.parcelId().equals(parcelId), "hand-over changed the parcel identity");
        require(adopted.encodedPackage().equals(PAYLOAD), "hand-over changed the payload");
        require(adopted.detail().equals("target_missing_mod"), "hand-over lost the rejection reason");
        require(adopted.attempts() == 0, "a fresh return record must start with no delivery attempts");
    }

    private static void repeatedHandOverKeepsOneRecord() {
        ParcelEscrow escrow = new ParcelEscrow();
        ParcelReturnInbox inbox = new ParcelReturnInbox();
        UUID parcelId = hold(escrow);
        escrow.rejected(parcelId, "origin_dock_unavailable");
        ParcelEscrow.Record original = record(escrow, parcelId);
        inbox.handOver(escrow, original, "origin_dock_unavailable", () -> {
        });
        inbox.adopt(original, "origin_dock_unavailable");
        require(inbox.size() == 1, "a repeated hand-over created a second custody record");
        require(inbox.find(parcelId).isPresent(), "the parcel disappeared from the return inbox");
    }

    private static void failedFlushKeepsTheEscrowAsOwner() {
        ParcelEscrow escrow = new ParcelEscrow();
        ParcelReturnInbox inbox = new ParcelReturnInbox();
        UUID parcelId = hold(escrow);
        escrow.rejected(parcelId, "target_missing_mod");
        boolean failed = false;
        try {
            inbox.handOver(escrow, record(escrow, parcelId), "origin_dock_unavailable", () -> {
                throw new IllegalStateException("simulated disk failure");
            });
        } catch (IllegalStateException expected) {
            failed = true;
        }
        require(failed, "a failed flush must abort the hand-over");
        require(escrow.find(parcelId).isPresent(), "the escrow must stay the owner when the flush fails");
        require(inbox.find(parcelId).isEmpty(), "a failed flush must not leave a copy behind");
        require(owners(parcelId, escrow, inbox, new ParcelQuarantine()) == 1, "a failed flush lost or duplicated the parcel");
    }

    private static void crashWindowResolvesToASingleOwner() {
        ParcelEscrow escrow = new ParcelEscrow();
        ParcelReturnInbox inbox = new ParcelReturnInbox();
        ParcelQuarantine quarantine = new ParcelQuarantine();
        UUID parcelId = hold(escrow);
        escrow.rejected(parcelId, "target_missing_mod");
        // A crash between the durable hand-over and the escrow delete leaves both copies on disk.
        inbox.adopt(record(escrow, parcelId), "origin_dock_unavailable");
        require(owners(parcelId, escrow, inbox, quarantine) == 2, "the crash window should leave two copies on disk");

        inbox.reconcile(escrow, quarantine);
        require(escrow.find(parcelId).isEmpty(), "reconcile must drop the stale escrow copy");
        require(inbox.find(parcelId).isPresent(), "reconcile must keep the newer owner");
        require(owners(parcelId, escrow, inbox, quarantine) == 1, "reconcile left the parcel owned twice");

        ParcelEscrow quarantined = new ParcelEscrow();
        UUID quarantinedId = hold(quarantined);
        quarantine.store(ParcelQuarantine.Intake.fromEscrow(record(quarantined, quarantinedId)),
                "source_decode_failed", "payload_bytes=31");
        require(owners(quarantinedId, quarantined, inbox, quarantine) == 2, "the quarantine crash window lost a copy");
        inbox.reconcile(quarantined, quarantine);
        require(quarantined.find(quarantinedId).isEmpty(), "reconcile must drop an escrow copy owned by quarantine");
        require(quarantine.size() == 1, "reconcile must keep the quarantine record");
        require(owners(quarantinedId, quarantined, inbox, quarantine) == 1, "reconcile left the quarantined parcel owned twice");
    }

    private static void quarantineTakesOverWithRawBytes() {
        ParcelEscrow escrow = new ParcelEscrow();
        ParcelQuarantine quarantine = new ParcelQuarantine();
        UUID parcelId = hold(escrow);
        ParcelEscrow.Record original = record(escrow, parcelId);
        AtomicInteger flushes = new AtomicInteger();
        ParcelQuarantine.Record stored = quarantine.transfer(escrow, original, "source_decode_failed",
                "payload_bytes=" + PAYLOAD.length(), flushes::incrementAndGet);
        require(flushes.get() == 1, "quarantine must flush before releasing the escrow copy");
        require(escrow.find(parcelId).isEmpty(), "quarantine must take over the escrow copy");
        require(stored.parcelId().equals(parcelId), "quarantine lost the parcel identity");
        require(stored.encodedPackage().equals(PAYLOAD), "quarantine changed the raw payload");
        require(stored.sha256().equals(ParcelQuarantine.hashOf(PAYLOAD)), "quarantine digest mismatch");
        require(stored.address().equals(ADDRESS), "quarantine lost the address");
        require(stored.destinationNode().equals(NODE), "quarantine lost the destination node");
        require(stored.receivingDockGroupId().equals(GROUP), "quarantine lost the dock group");
        require(stored.originDimension().equals(DIMENSION), "quarantine lost the origin dimension");
        require(stored.originPos() == original.originPos(), "quarantine lost the origin position");
        require(stored.reason().equals("source_decode_failed"), "quarantine lost the failure reason");
        require(stored.detail().equals("payload_bytes=" + PAYLOAD.length()), "quarantine lost the failure detail");
        require(stored.intact(), "a freshly stored payload must verify against its digest");
    }

    private static void quarantineDetectsTamperedPayload() {
        ParcelQuarantine quarantine = new ParcelQuarantine();
        quarantine.store(new ParcelQuarantine.Intake(UUID.randomUUID(), PAYLOAD, ADDRESS, NODE, GROUP,
                DIMENSION, 0L), "source_decode_failed", "");
        CompoundTag tag = quarantine.save(new CompoundTag(), null);
        ListTag rows = tag.getList("Quarantine", Tag.TAG_COMPOUND);
        require(rows.size() == 1, "quarantine did not persist the record");
        rows.getCompound(0).putString("Package", PAYLOAD + "tampered");
        ParcelQuarantine reloaded = ParcelQuarantine.load(tag, null);
        require(!reloaded.records().getFirst().intact(), "a tampered payload must fail its digest check");
    }

    private static void recordsSurviveReload() {
        ParcelEscrow escrow = new ParcelEscrow();
        UUID parcelId = hold(escrow);
        UUID messageId = UUID.randomUUID();
        escrow.submitted(parcelId, messageId);
        escrow.rejected(parcelId, "target_missing_mod");
        ParcelEscrow reloadedEscrow = ParcelEscrow.load(escrow.save(new CompoundTag(), null), null);
        ParcelEscrow.Record record = reloadedEscrow.find(parcelId).orElseThrow();
        require(record.state() == ParcelEscrow.State.REJECTED, "escrow state did not survive a reload");
        require(record.detail().equals("target_missing_mod"), "escrow detail did not survive a reload");
        require(messageId.equals(record.messageId()), "escrow message id did not survive a reload");
        require(record.encodedPackage().equals(PAYLOAD), "escrow payload did not survive a reload");
        require(record.address().equals(ADDRESS), "escrow address did not survive a reload");

        ParcelReturnInbox inbox = new ParcelReturnInbox();
        inbox.adopt(record, "origin_dock_unavailable");
        inbox.noteAttempt(parcelId, 42L);
        ParcelReturnInbox reloadedInbox = ParcelReturnInbox.load(inbox.save(new CompoundTag(), null), null);
        ParcelReturnInbox.Record returned = reloadedInbox.find(parcelId).orElseThrow();
        require(returned.attempts() == 1, "return attempts did not survive a reload");
        require(returned.lastAttemptAt() == 42L, "the return retry clock did not survive a reload");
        require(returned.createdAt() == record.createdAt(), "hand-over changed the original creation time");
        require(returned.destinationNode().equals(NODE), "the return record lost the destination node");
        require(returned.receivingDockGroupId().equals(GROUP), "the return record lost the dock group");
        require(returned.originPos() == record.originPos(), "the return record lost the origin position");

        ParcelQuarantine quarantine = new ParcelQuarantine();
        reloadedInbox.reconcile(reloadedEscrow, quarantine);
        require(owners(parcelId, reloadedEscrow, reloadedInbox, quarantine) == 1,
                "a reloaded crash window did not settle on one owner");
    }

    private static UUID hold(ParcelEscrow escrow) {
        // 200 ticks of game time: the escrow stamps parcels on the level clock, and this check runs
        // without a level, so the number only has to be a plausible game time rather than a real one.
        return escrow.holdEncoded(PAYLOAD, ADDRESS, NODE, GROUP, DIMENSION, new BlockPos(12, 64, -8),
                200L);
    }

    private static ParcelEscrow.Record record(ParcelEscrow escrow, UUID parcelId) {
        return escrow.find(parcelId).orElseThrow();
    }

    /** How many stores currently hold this parcel. Exactly one store may own a parcel at any moment. */
    private static int owners(UUID parcelId, ParcelEscrow escrow, ParcelReturnInbox inbox,
                              ParcelQuarantine quarantine) {
        int owners = escrow.find(parcelId).isPresent() ? 1 : 0;
        owners += inbox.find(parcelId).isPresent() ? 1 : 0;
        owners += quarantine.records().stream()
                .anyMatch(record -> parcelId.equals(record.parcelId())) ? 1 : 0;
        return owners;
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }

    private ParcelOwnershipCheck() {
    }
}
