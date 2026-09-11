package dev.distantstock.routing;

import java.util.List;

/**
 * Selection rule for receiving docks. Priority decides the tier; every dock in the winning tier that has
 * room takes turns. The rule is kept free of game types so the rotation can be checked without a server.
 */
public final class DockSelection {
    /** {@code available} means the dock currently has room to take a parcel. */
    public record Candidate(int priority, boolean available) {
    }

    /**
     * @param ordered eligible docks in a stable order
     * @param sequence rotating counter, normally one per dock group
     * @return index into {@code ordered}, or -1 when nothing is eligible
     */
    public static int select(List<Candidate> ordered, int sequence) {
        if (ordered.isEmpty()) {
            return -1;
        }
        int best = Integer.MIN_VALUE;
        for (Candidate candidate : ordered) {
            if (candidate.available()) {
                best = Math.max(best, candidate.priority());
            }
        }
        if (best == Integer.MIN_VALUE) {
            // Everything in the group is full: report the first one so the caller can show a clear reason.
            return 0;
        }
        int tier = 0;
        for (Candidate candidate : ordered) {
            if (candidate.available() && candidate.priority() == best) {
                tier++;
            }
        }
        int pick = Math.floorMod(sequence, tier);
        for (int index = 0; index < ordered.size(); index++) {
            Candidate candidate = ordered.get(index);
            if (!candidate.available() || candidate.priority() != best) {
                continue;
            }
            if (pick-- == 0) {
                return index;
            }
        }
        return 0;
    }

    private DockSelection() {
    }
}
