package dev.distantstock.routing;

import dev.distantstock.block.LoadedTowers;
import dev.distantstock.block.TowerCoreBlockEntity;
import dev.distantstock.config.StockConfig;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;

/**
 * What a parcel costs, and who pays for it.
 *
 * <p>The tower pays, not the dock. A dock is a door; the tower is what carries the parcel through
 * it, and a bill attached to the door would have to be settled by a block that has no tank, no
 * tier and no way to explain the charge.
 *
 * <p>Everything here is off unless {@code tower.chargeParcels} is on, which it is not by default.
 * A disabled charge must not leave a trace: no lookup, no log line, no tank read, exactly the
 * behaviour of a build that never grew this class.
 *
 * <p>The two methods are a matched pair and are always called in the same tick by the same thread:
 * {@link #charge} takes the ether before a parcel is handed over, and {@link #refund} puts it back
 * when the handover then turns out not to happen. Refusing to send because the tower is empty is
 * the point; taking the ether and not sending is not allowed, and neither is sending without
 * taking it.
 */
public final class TowerBilling {
    /**
     * Test seam.
     *
     * <p>The alternative is a game test writing to the global config, which the test runner shares
     * between cases running in parallel: a case that switched charging on would switch it on for
     * every other case in the same batch. A field with a lifetime of one test method does not.
     */
    private static volatile Boolean chargeOverride;
    private static volatile Integer costOverride;

    /** Sets the switch and the price for a test, in place of the config. */
    public static void overrideForTesting(Boolean charge, Integer cost) {
        chargeOverride = charge;
        costOverride = cost;
    }

    /** Returns to reading the config. */
    public static void clearOverride() {
        chargeOverride = null;
        costOverride = null;
    }

    public static boolean enabled() {
        Boolean override = chargeOverride;
        return override != null ? override : StockConfig.towerChargeParcels();
    }

    public static int parcelCost() {
        Integer override = costOverride;
        int cost = override != null ? override : StockConfig.towerParcelCost();
        return Math.max(0, cost);
    }

    /**
     * Takes one parcel's worth of ether from the tower that carries this device.
     *
     * <p>True when the parcel may leave. A device nothing carries pays nothing — that is the
     * no-towers case, and it is free on purpose: a world that never built a tower must behave
     * exactly as it did before towers existed.
     */
    public static boolean charge(Level level, BlockPos device) {
        if (!enabled() || level == null || level.isClientSide) {
            return true;
        }
        int cost = parcelCost();
        if (cost <= 0) {
            return true;
        }
        TowerCoreBlockEntity tower = carrier(level, device);
        if (tower == null) {
            return true;
        }
        return tower.drawEther(cost) >= cost;
    }

    /** Gives back exactly what {@link #charge} took, for a transfer that did not happen after all. */
    public static void refund(Level level, BlockPos device) {
        if (!enabled() || level == null || level.isClientSide) {
            return;
        }
        int cost = parcelCost();
        if (cost <= 0) {
            return;
        }
        TowerCoreBlockEntity tower = carrier(level, device);
        if (tower != null) {
            tower.storeEther(cost);
        }
    }

    /** The block entity of the tower the snapshot says carries this device, if it is still loaded. */
    private static TowerCoreBlockEntity carrier(Level level, BlockPos device) {
        TowerSystem.TowerId id = TowerActivation.carrier(level, device);
        return id == null ? null : LoadedTowers.at(level, BlockPos.of(id.packedPos()));
    }

    private TowerBilling() {
    }
}
