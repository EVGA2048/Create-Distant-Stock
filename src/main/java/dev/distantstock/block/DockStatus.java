package dev.distantstock.block;

import net.minecraft.util.StringRepresentable;

import java.util.Locale;

/**
 * Lamp state of a remote dock. The dock has no screen, so the 2x2 lamp is the only always-visible
 * status report and the Ponder scene refers to it by name.
 */
public enum DockStatus implements StringRepresentable {
    /** Not bound to a network frequency, or the link to the other nodes is down. */
    INACTIVE,
    /** Bound and connected, nothing to do. */
    STANDBY,
    /** A parcel is rising into the ether surface right now. */
    SENDING,
    /** Needs a human: a returned parcel could not be handled at all. */
    FAULT,
    /** Fallback face has something to hand over but no room below it. */
    BLOCKED;

    @Override
    public String getSerializedName() {
        return name().toLowerCase(Locale.ROOT);
    }
}
