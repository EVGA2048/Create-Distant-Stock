package dev.distantstock.block;

/**
 * Andon severity shown by a brass signal lamp, in the vocabulary of a machine stack light.
 *
 * Existing constants keep their original ordinals because panel state is persisted by ordinal in
 * old saves. New states therefore cannot rely on declaration order for urgency; {@link #worst}
 * uses an explicit rank.
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
    FATAL,
    /** An ERROR alarm that an operator has acknowledged but that is still active. */
    FATAL_ACK;

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
        return rank(a) >= rank(b) ? a : b;
    }

    private static int rank(LampState state) {
        return switch (state) {
            case IDLE -> 0;
            case ALL_GOOD -> 1;
            case ACT -> 2;
            case WARN -> 3;
            case WARN_URGENT -> 4;
            case FATAL_ACK -> 5;
            case FATAL -> 6;
        };
    }
}
