package dev.distantstock.block;

import java.util.Optional;

/**
 * What a tower's height buys it.
 *
 * <p>Seven steps, entered at {@link #I} with the minimum mast and topped out at {@link #VII}. The
 * thresholds are odd numbers five apart, which is the art's rule rather than a balance one: a
 * cross-frame is drawn at every seam, so a mast of only a couple of segments is mostly frame with
 * nothing between the frames.
 *
 * <p>Chunk loading deliberately climbs slower than everything else. The step from III to IV doubles
 * the device count and widens the radius by a third, but the loaded area stays 3x3: a tower that
 * grew its footprint on every tier would make the last steps cost more server than any build is
 * worth. It moves at III, V and VII.
 *
 * <p>These are code, not config. A config entry per number would be thirty-five of them, and the
 * two that actually want tuning — whether a parcel costs ether at all, and the ceiling on loaded
 * chunks — belong in {@link dev.distantstock.config.StockConfig} where they can be found.
 */
public enum TowerTier {
    //            couplers  devices  radius  chunkSide  stress
    I(5, 8, 32, 1, 256),
    II(7, 16, 40, 3, 512),
    III(9, 32, 48, 3, 1024),
    IV(11, 48, 64, 5, 2048),
    V(13, 64, 80, 5, 4096),
    VI(15, 96, 112, 7, 8192),
    VII(17, 128, 144, 7, 16384);

    private final int couplers;
    private final int devices;
    private final int radius;
    private final int chunkSide;
    private final float stress;

    TowerTier(int couplers, int devices, int radius, int chunkSide, float stress) {
        this.couplers = couplers;
        this.devices = devices;
        this.radius = radius;
        this.chunkSide = chunkSide;
        this.stress = stress;
    }

    /** Couplers the mast must reach to enter this tier; the first one is also where a tower starts. */
    public int couplers() {
        return couplers;
    }

    /** Distant devices this tower may carry. Anything past the count simply is not activated. */
    public int devices() {
        return devices;
    }

    /** How far from the base a device can stand and still be carried, in blocks. */
    public int radius() {
        return radius;
    }

    /** The side of the square of chunks a tower may keep loaded: 1, 3, 5 or 7. */
    public int chunkSide() {
        return chunkSide;
    }

    /**
     * How far, in chunks, this tier's square reaches out from the tower's own chunk: 0 to 3.
     *
     * <p>The number an operator sets on a monitor is this one, because it is the one that means
     * something at the block level: radius zero is "my own chunk and nothing else", radius two is
     * "two chunks out in every direction". The side is what the table holds and what the loader
     * calculates with, so the two are converted in exactly one place — {@link #sideForRadius} —
     * rather than being worked out again on the screen and in the loader, where they could drift.
     */
    public int chunkRadius() {
        return (chunkSide - 1) / 2;
    }

    /** The square's side a radius asks for: {@code 2r + 1}, the inverse of {@link #chunkRadius()}. */
    public static int sideForRadius(int radius) {
        return 2 * Math.max(0, radius) + 1;
    }

    /**
     * Rotation this tower draws from the shaft under its base, in Create's stress units.
     *
     * <p>This is the impact, not the total: Create multiplies it by the absolute speed, so a tower
     * on a fast network costs proportionally more. Every tier roughly doubles the draw, which keeps
     * a tower near the edge of what one network can carry at every height.
     */
    public float stress() {
        return stress;
    }

    /**
     * The tier a mast of this height has reached, or empty when it is not a tower yet.
     *
     * <p>Past the top threshold the tier stops climbing but nothing refuses the extra couplers — a
     * tower taller than the table covers still works, it just reads as capped. Refusing them would
     * mean a player who stacked one segment too many has to take the mast apart to find which one
     * was one too many.
     */
    public static Optional<TowerTier> forCouplers(int couplers) {
        TowerTier reached = null;
        for (TowerTier tier : values()) {
            if (couplers >= tier.couplers) {
                reached = tier;
            }
        }
        return Optional.ofNullable(reached);
    }

    /** Whether a mast this tall is past the last tier's threshold. */
    public static boolean capped(int couplers) {
        return couplers > VII.couplers;
    }

    /** The next tier up, for the readout that tells a player how much taller to build. */
    public Optional<TowerTier> next() {
        int ordinal = ordinal() + 1;
        return ordinal < values().length ? Optional.of(values()[ordinal]) : Optional.empty();
    }
}
