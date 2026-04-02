package com.botwithus.bot.scripts.eliteclue.scan;

/**
 * The three scan proximity feedback states, mapped from spot animation IDs.
 * <p>
 * Distance thresholds are relative to a per-clue {@code baseDistance} (R) value
 * parsed from the scanner interface text:
 * <ul>
 *   <li><b>BLUE</b>:   player is farther than {@code 2R+1} tiles</li>
 *   <li><b>ORANGE</b>: player is within {@code 2R+1} tiles but farther than R</li>
 *   <li><b>RED</b>:    player is within {@code R} tiles — hint arrow appears, dig spot revealed</li>
 * </ul>
 * The RS3 wiki states thresholds at R and 2R. We use 2R+1 as a conservative safety
 * margin (+1 tile) to avoid wrongly eliminating the true dig spot at boundary
 * distances. Can be tightened to 2R after in-game verification.
 * <p>
 * Note: ORANGE does NOT exclude RED — a candidate at red range is also consistent
 * with orange. The server shows red when available, but for elimination purposes
 * orange means "at most 2R+1 away".
 */
public enum ScanPulseColor {

    /** No pulse detected — scanner not triggered or player is moving. */
    NONE(0, 0, "None"),

    /** Blue pulse (anim 6841) — far away: distance > (baseDistance * 2) + 1. */
    BLUE(1, 6841, "Blue (far)"),

    /** Orange pulse (anim 6842) — getting closer: distance <= (baseDistance * 2) + 1
     *  but also means we didn't get red, so distance > baseDistance. */
    ORANGE(2, 6842, "Orange (warm)"),

    /** Red pulse (anim 6843) — very close: distance <= baseDistance. */
    RED(3, 6843, "Red (hot)");

    private final int stateId;
    private final int spotAnimId;
    private final String label;

    ScanPulseColor(int stateId, int spotAnimId, String label) {
        this.stateId = stateId;
        this.spotAnimId = spotAnimId;
        this.label = label;
    }

    public int stateId() { return stateId; }
    public int spotAnimId() { return spotAnimId; }
    public String label() { return label; }

    /**
     * Check whether a Chebyshev distance is consistent with this pulse color
     * given a specific baseDistance.
     * <p>
     * Thresholds: BLUE > 2R+1, ORANGE in (R, 2R+1], RED <= R.
     * The +1 is a conservative safety margin over the wiki's 2R boundary.
     */
    public boolean isConsistentWith(int chebyshevDistance, int baseDistance) {
        int orangeThreshold = (baseDistance * 2) + 1;
        return switch (this) {
            case NONE -> true;
            case BLUE -> chebyshevDistance > orangeThreshold;
            case ORANGE -> chebyshevDistance > baseDistance && chebyshevDistance <= orangeThreshold;
            case RED -> chebyshevDistance <= baseDistance;
        };
    }

    /**
     * Map from the spotAnimState integer (0-3) used in ClueContext.
     */
    public static ScanPulseColor fromStateId(int stateId) {
        return switch (stateId) {
            case 1 -> BLUE;
            case 2 -> ORANGE;
            case 3 -> RED;
            default -> NONE;
        };
    }
}
