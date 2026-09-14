package dev.distantstock.stock;

import net.minecraft.core.BlockPos;

import java.util.List;

/**
 * A bound logistics network's health, cheap enough to sample on a timer.
 *
 * Everything here comes straight from Create's {@code LogisticsNetwork}, so a lamp bound to a
 * frequency needs no gauges and no wiring at all: the network itself is the sensor.
 *
 * @param known       false when the frequency has no network loaded on this server
 * @param loadedLinks links that are actually loaded right now
 * @param totalLinks  links ever registered, loaded or not
 * @param idle        true when no package promise is outstanding
 * @param locked      true while the network is administratively locked
 * @param missing     up to N registered links that are not loaded, sorted for stable comparison
 */
public record NetworkHealth(boolean known, int loadedLinks, int totalLinks, boolean idle, boolean locked,
                            List<BlockPos> missing) {
    public static final NetworkHealth UNKNOWN = new NetworkHealth(false, 0, 0, true, false, List.of());

    /** Links that should be online but are not. */
    public int offline() {
        return Math.max(0, totalLinks - loadedLinks);
    }
}
