package dev.distantstock.block;

/**
 * Andon severity shown by a brass signal lamp, in the vocabulary of a machine stack light.
 *
 * The constant order is the severity order, so {@link #worst} is a plain ordinal comparison and a
 * board with several inputs reports its most urgent one. Colour is deliberately not part of this
 * enum; the renderer owns that mapping.
 */
public enum LampState {
    /** Stocked and nothing on order: the line is ready but has no work. */
    IDLE,
    /** Stocked and still working through promises. */
    ALL_GOOD,
    /** Short right now, but the network has promised enough to cover it. */
    ACT,
    /** Short and the network is refilling. */
    WARN,
    /** Short and the network is not answering, so someone has to look at it. */
    WARN_URGENT,
    /** Misconfigured, or forced from outside. */
    FATAL;

    /** How loudly a state asks to be noticed. */
    public enum Blink {
        NONE,
        /** Standby: ready but idle. */
        SLOW,
        /** Emergency: a stopped or unattended line. */
        FAST
    }

    public Blink blink() {
        return switch (this) {
            case IDLE -> Blink.SLOW;
            case WARN_URGENT, FATAL -> Blink.FAST;
            default -> Blink.NONE;
        };
    }

    /** The more urgent of two states; null means "no input attached". */
    public static LampState worst(LampState a, LampState b) {
        if (a == null) {
            return b;
        }
        if (b == null) {
            return a;
        }
        return a.ordinal() >= b.ordinal() ? a : b;
    }
}
