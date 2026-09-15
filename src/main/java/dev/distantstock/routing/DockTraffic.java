package dev.distantstock.routing;

/**
 * How many parcels one dock has sent and received over the last ten minutes.
 *
 * <p>Ten one-minute buckets on a ring, rather than a list of timestamps: the question the monitor
 * asks is "how busy has this dock been", and a bucket per minute answers it at a fixed cost however
 * busy the dock gets. A timestamp ring would grow with the traffic it is measuring, which is exactly
 * backwards.
 *
 * <p>Not persisted. The window is a live reading of the last ten minutes, and a window that spanned
 * a restart would be claiming to know about a stretch of time the server spent switched off. A
 * reload therefore starts the count from zero, and the screen says "since the server started" rather
 * than pretending otherwise.
 *
 * <p>All of it is driven by the caller passing the game time in. Nothing here reads a clock, so the
 * window advances on the server's beat and not on whatever thread happens to ask.
 */
public final class DockTraffic {
    /** Minutes the window covers, and therefore how many buckets it keeps. */
    private static final int WINDOW = 10;
    private static final long BUCKET_TICKS = 1200L;

    private final long[] sent = new long[WINDOW];
    private final long[] received = new long[WINDOW];
    private long lastRotation = -1;
    private int head;

    /** Counts one parcel leaving this dock. */
    public void noteSent(long gameTime) {
        rotate(gameTime);
        sent[head]++;
    }

    /** Counts one parcel arriving at this dock. */
    public void noteReceived(long gameTime) {
        rotate(gameTime);
        received[head]++;
    }

    /** The window as it stands, with the rotation up to {@code gameTime} applied first. */
    public TowerActivation.Traffic window(long gameTime) {
        rotate(gameTime);
        long sentTotal = 0;
        long receivedTotal = 0;
        for (int i = 0; i < WINDOW; i++) {
            sentTotal += sent[i];
            receivedTotal += received[i];
        }
        return new TowerActivation.Traffic(sentTotal, receivedTotal);
    }

    /**
     * Moves the head forward to the bucket {@code gameTime} belongs to, clearing the ones in
     * between.
     *
     * <p>A gap longer than the whole window clears everything, which is the honest answer: a dock
     * whose chunk was unloaded for an hour has no traffic in the last ten minutes, and stepping the
     * head one bucket at a time over that gap would either stop short or clear the wrong cells.
     */
    private void rotate(long gameTime) {
        if (lastRotation < 0) {
            lastRotation = gameTime;
            return;
        }
        long elapsed = gameTime - lastRotation;
        if (elapsed < BUCKET_TICKS) {
            return;
        }
        int steps = (int) Math.min(WINDOW, elapsed / BUCKET_TICKS);
        for (int i = 0; i < steps; i++) {
            head = (head + 1) % WINDOW;
            sent[head] = 0;
            received[head] = 0;
        }
        lastRotation += (long) steps * BUCKET_TICKS;
    }
}
