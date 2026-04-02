package com.botwithus.bot.scripts.eliteclue.scan;

/**
 * Records why a candidate coordinate was eliminated.
 * Stored for debugging and UI display.
 *
 * @param coordinate    the eliminated coordinate
 * @param reason        human-readable elimination reason
 * @param playerX       player X when elimination occurred
 * @param playerY       player Y when elimination occurred
 * @param observedColor the pulse color that caused elimination
 * @param actualDistance the Chebyshev distance from player to candidate
 * @param timestamp     system time when eliminated
 */
public record EliminationRecord(
        ScanCoordinate coordinate,
        String reason,
        int playerX,
        int playerY,
        ScanPulseColor observedColor,
        int actualDistance,
        long timestamp
) {

    @Override
    public String toString() {
        return String.format("%s eliminated: %s (dist=%d from [%d,%d], saw %s)",
                coordinate, reason, actualDistance, playerX, playerY, observedColor.label());
    }
}
