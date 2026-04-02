package com.botwithus.bot.scripts.eliteclue.scan;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Transition-aware observation history for scan clue navigation.
 * <p>
 * Separate from {@link ScanClueTracker}'s elimination-focused observation list —
 * this one is focused on navigation signals (color transitions, best-seen color,
 * backtracking anchors).
 * <p>
 * Thread-safe via {@link CopyOnWriteArrayList} so the ImGui render thread can
 * read observations safely.
 */
public final class ScanObservationLog {

    /**
     * A single observation: player position + pulse color at that position.
     */
    public record ScanObservation(int x, int y, ScanPulseColor color, long timestampMs,
                                  int candidatesRemaining) {
        @Override
        public String toString() {
            return String.format("(%d,%d) %s [%d cands]", x, y, color.label(), candidatesRemaining);
        }
    }

    /**
     * Describes the color transition between two consecutive observations.
     */
    public enum ColorTransition {
        /** No previous observation to compare. */
        NONE,
        /** Color improved (closer): BLUE→ORANGE, BLUE→RED, ORANGE→RED. */
        IMPROVED,
        /** Color degraded (farther): RED→ORANGE, ORANGE→BLUE, RED→BLUE. */
        DEGRADED,
        /** Same color band as previous observation. */
        SAME;

        /**
         * Compute the transition from previousColor to currentColor.
         */
        public static ColorTransition compute(ScanPulseColor previous, ScanPulseColor current) {
            if (previous == ScanPulseColor.NONE || current == ScanPulseColor.NONE) {
                return NONE;
            }
            int diff = current.stateId() - previous.stateId();
            if (diff > 0) return IMPROVED;
            if (diff < 0) return DEGRADED;
            return SAME;
        }
    }

    private final CopyOnWriteArrayList<ScanObservation> observations = new CopyOnWriteArrayList<>();

    /**
     * Record a new observation.
     */
    public void record(int x, int y, ScanPulseColor color, int candidatesRemaining) {
        observations.add(new ScanObservation(x, y, color, System.currentTimeMillis(), candidatesRemaining));
    }

    /**
     * Get the most recent observation, or null if none recorded.
     */
    public ScanObservation getLatest() {
        if (observations.isEmpty()) return null;
        return observations.getLast();
    }

    /**
     * Get the second-most-recent observation, or null if fewer than 2 recorded.
     */
    public ScanObservation getPrevious() {
        int size = observations.size();
        if (size < 2) return null;
        return observations.get(size - 2);
    }

    /**
     * Compute the color transition between the two most recent observations.
     */
    public ColorTransition getTransition() {
        ScanObservation latest = getLatest();
        ScanObservation previous = getPrevious();
        if (latest == null || previous == null) return ColorTransition.NONE;
        return ColorTransition.compute(previous.color(), latest.color());
    }

    /**
     * Find the observation with the highest-rank color ever seen (RED > ORANGE > BLUE).
     * Returns null if no non-NONE observations exist.
     */
    public ScanObservation getBestObservation() {
        ScanObservation best = null;
        for (ScanObservation obs : observations) {
            if (obs.color() == ScanPulseColor.NONE) continue;
            if (best == null || obs.color().stateId() > best.color().stateId()) {
                best = obs;
            }
        }
        return best;
    }

    /**
     * Find the most recent observation with the given color.
     * Useful for backtracking to the last-known-good position.
     */
    public ScanObservation getLastPositionWithColor(ScanPulseColor color) {
        for (int i = observations.size() - 1; i >= 0; i--) {
            ScanObservation obs = observations.get(i);
            if (obs.color() == color) return obs;
        }
        return null;
    }

    /**
     * Get all observations (thread-safe snapshot).
     */
    public List<ScanObservation> getAll() {
        return List.copyOf(observations);
    }

    /**
     * Get the number of observations recorded.
     */
    public int size() {
        return observations.size();
    }

    /**
     * Check if any observations have been recorded.
     */
    public boolean isEmpty() {
        return observations.isEmpty();
    }

    /**
     * Clear all observations. Called when scanner closes or clue changes.
     */
    public void clear() {
        observations.clear();
    }
}
