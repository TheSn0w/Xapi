package com.botwithus.bot.scripts.eliteclue.scan;

import com.botwithus.bot.api.log.BotLogger;
import com.botwithus.bot.api.log.LoggerFactory;

import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Tracks scan clue candidates and eliminates impossible ones based on
 * observed scan pulse colors at known player positions.
 * <p>
 * Thread safety: the candidate and elimination lists use CopyOnWriteArrayList
 * so the ImGui render thread can read them safely.
 * <p>
 * <h3>Elimination Rules (Chebyshev distance, relative to baseDistance)</h3>
 * The scanner text tells us the baseDistance for this clue (e.g., 14, 11, 27).
 * From the cache scripts:
 * <ul>
 *   <li><b>BLUE</b>:   candidate is valid only if {@code dist > (baseDistance * 2) + 1}</li>
 *   <li><b>ORANGE</b>: candidate is valid only if {@code dist > baseDistance AND dist <= (baseDistance * 2) + 1}</li>
 *   <li><b>RED</b>:    candidate is valid only if {@code dist <= baseDistance}</li>
 *   <li><b>NONE</b>:   no elimination (scanner hasn't pulsed yet)</li>
 * </ul>
 */
public final class ScanClueTracker {

    private static final BotLogger log = LoggerFactory.getLogger(ScanClueTracker.class);

    /** Default base scan range if we can't parse it from the text. */
    public static final int DEFAULT_BASE_DISTANCE = 14;

    /** Meerkat bonus range (extends the hint-arrow reveal range, not the color thresholds). */
    public static final int MEERKAT_BONUS = 5;

    /** Minimum tiles the player must move before we re-evaluate (prevents redundant checks). */
    private static final int MIN_MOVE_DISTANCE = 3;

    /** Pattern to extract the paces number from scanner text. Matches "14 paces", "11 paces", etc. */
    private static final Pattern PACES_PATTERN = Pattern.compile("(\\d+)\\s*paces?", Pattern.CASE_INSENSITIVE);

    // ── State ──

    private volatile ScanRegion activeRegion;

    /** The base distance for the current scan clue, parsed from the scanner text. */
    private volatile int baseDistance = DEFAULT_BASE_DISTANCE;

    /** Remaining valid candidates. Thread-safe for UI reads. */
    private final CopyOnWriteArrayList<ScanCoordinate> candidates = new CopyOnWriteArrayList<>();

    /** All elimination records for debugging. Thread-safe for UI reads. */
    private final CopyOnWriteArrayList<EliminationRecord> eliminations = new CopyOnWriteArrayList<>();

    /** Observations collected so far: position + color. */
    private final List<Observation> observations = new ArrayList<>();

    /** Last position where we ran elimination. */
    private int lastEvalX = Integer.MIN_VALUE;
    private int lastEvalY = Integer.MIN_VALUE;

    /** Whether the scan is solved (hint arrow found). */
    private volatile boolean solved = false;

    /** The solved coordinate (hint arrow target). */
    private volatile ScanCoordinate solvedCoordinate = null;

    /**
     * Initialize tracking for a new scan clue region.
     * Resets all state and populates candidates from the region.
     */
    public void startTracking(ScanRegion region) {
        this.activeRegion = region;
        this.baseDistance = DEFAULT_BASE_DISTANCE;
        this.candidates.clear();
        this.eliminations.clear();
        this.observations.clear();
        this.lastEvalX = Integer.MIN_VALUE;
        this.lastEvalY = Integer.MIN_VALUE;
        this.solved = false;
        this.solvedCoordinate = null;

        candidates.addAll(region.coords());

        log.info("[ScanTracker] Started tracking region '{}' with {} candidates, baseDistance={}",
                region.name(), candidates.size(), baseDistance);
    }

    /**
     * Parse and set the base distance from the scanner interface text.
     * The text typically contains something like "within 14 paces" or "11 paces".
     *
     * @param scannerText the full text from interface 1752 component 3
     * @return the parsed base distance, or the default if parsing fails
     */
    public int parseBaseDistance(String scannerText) {
        if (scannerText == null || scannerText.isBlank()) {
            log.warn("[ScanTracker] Empty scanner text, using default baseDistance={}",
                    DEFAULT_BASE_DISTANCE);
            return baseDistance;
        }

        Matcher matcher = PACES_PATTERN.matcher(scannerText);
        if (matcher.find()) {
            int parsed = Integer.parseInt(matcher.group(1));
            if (parsed > 0 && parsed < 200) {  // Sanity check
                if (parsed != baseDistance) {
                    log.info("[ScanTracker] Parsed baseDistance={} from text: '{}'",
                            parsed, scannerText);
                    this.baseDistance = parsed;
                }
                return parsed;
            }
        }

        log.warn("[ScanTracker] Could not parse paces from text: '{}', using default={}",
                scannerText, DEFAULT_BASE_DISTANCE);
        return baseDistance;
    }

    /**
     * Reset all tracking state. Called when the scanner closes or clue changes.
     */
    public void reset() {
        log.info("[ScanTracker] Reset — clearing {} candidates, {} eliminations",
                candidates.size(), eliminations.size());
        activeRegion = null;
        baseDistance = DEFAULT_BASE_DISTANCE;
        candidates.clear();
        eliminations.clear();
        observations.clear();
        lastEvalX = Integer.MIN_VALUE;
        lastEvalY = Integer.MIN_VALUE;
        solved = false;
        solvedCoordinate = null;
    }

    /**
     * Process a scan observation at the player's current position.
     * Eliminates any candidate whose Chebyshev distance to the player
     * is inconsistent with the observed pulse color and baseDistance.
     *
     * @param playerX player world tile X
     * @param playerY player world tile Y
     * @param color   the observed scan pulse color
     * @return number of candidates eliminated this call
     */
    public int processObservation(int playerX, int playerY, ScanPulseColor color) {
        if (color == ScanPulseColor.NONE || candidates.isEmpty()) {
            return 0;
        }

        // Skip if we haven't moved far enough since last evaluation
        int moveDist = Math.max(Math.abs(playerX - lastEvalX), Math.abs(playerY - lastEvalY));
        if (moveDist < MIN_MOVE_DISTANCE && !observations.isEmpty()) {
            return 0;
        }

        lastEvalX = playerX;
        lastEvalY = playerY;

        int orangeThreshold = (baseDistance * 2) + 1;

        // Record the observation
        Observation obs = new Observation(playerX, playerY, color);
        observations.add(obs);

        log.info("[ScanTracker] Observation #{}: player=({},{}) color={} baseDistance={} orangeThreshold={} — {} candidates",
                observations.size(), playerX, playerY, color.label(),
                baseDistance, orangeThreshold, candidates.size());

        // Eliminate inconsistent candidates
        List<ScanCoordinate> toRemove = new ArrayList<>();

        for (ScanCoordinate candidate : candidates) {
            int dist = candidate.chebyshevDistance(playerX, playerY);
            if (!color.isConsistentWith(dist, baseDistance)) {
                String reason = buildEliminationReason(candidate, playerX, playerY, color, dist);
                EliminationRecord record = new EliminationRecord(
                        candidate, reason, playerX, playerY, color, dist, System.currentTimeMillis());
                eliminations.add(record);
                toRemove.add(candidate);

                log.info("[ScanTracker] ELIMINATED {} — {} (dist={}, base={}, orangeMax={})",
                        candidate, reason, dist, baseDistance, orangeThreshold);
            }
        }

        if (!toRemove.isEmpty()) {
            candidates.removeAll(toRemove);
            log.info("[ScanTracker] Eliminated {} candidates, {} remaining",
                    toRemove.size(), candidates.size());
        }

        // Also retroactively verify against ALL previous observations
        int retroEliminated = retroactiveElimination();
        if (retroEliminated > 0) {
            log.info("[ScanTracker] Retroactive pass eliminated {} more, {} remaining",
                    retroEliminated, candidates.size());
        }

        return toRemove.size() + retroEliminated;
    }

    /**
     * Re-check all remaining candidates against ALL collected observations.
     * This catches candidates that survive one observation but are inconsistent
     * with the combination of multiple observations.
     */
    private int retroactiveElimination() {
        List<ScanCoordinate> toRemove = new ArrayList<>();

        for (ScanCoordinate candidate : candidates) {
            for (Observation obs : observations) {
                int dist = candidate.chebyshevDistance(obs.x, obs.y);
                if (!obs.color.isConsistentWith(dist, baseDistance)) {
                    String reason = String.format("retroactive: dist=%d from (%d,%d) incompatible with %s (base=%d)",
                            dist, obs.x, obs.y, obs.color.label(), baseDistance);
                    EliminationRecord record = new EliminationRecord(
                            candidate, reason, obs.x, obs.y, obs.color, dist, System.currentTimeMillis());
                    eliminations.add(record);
                    toRemove.add(candidate);
                    break; // One failed observation is enough
                }
            }
        }

        if (!toRemove.isEmpty()) {
            candidates.removeAll(toRemove);
        }
        return toRemove.size();
    }

    /**
     * Mark the scan as solved when a hint arrow is detected.
     */
    public void markSolved(int hintX, int hintY, int hintPlane) {
        this.solved = true;

        // Find the matching candidate (if any)
        for (ScanCoordinate c : candidates) {
            if (c.x() == hintX && c.y() == hintY) {
                this.solvedCoordinate = c;
                log.info("[ScanTracker] SOLVED — hint arrow matches candidate {}", c);
                return;
            }
        }

        // Hint arrow doesn't match any remaining candidate — might be a coord we eliminated
        this.solvedCoordinate = new ScanCoordinate(hintX, hintY, hintPlane, 0);
        log.warn("[ScanTracker] SOLVED at ({},{},{}) but no matching candidate in remaining list!",
                hintX, hintY, hintPlane);
    }

    /**
     * Compute the centroid (average position) of remaining candidates.
     * Useful as a navigation target when candidates are spread out.
     */
    public int[] getCentroid() {
        if (candidates.isEmpty()) return null;

        long sumX = 0, sumY = 0;
        for (ScanCoordinate c : candidates) {
            sumX += c.x();
            sumY += c.y();
        }
        return new int[]{
                (int) (sumX / candidates.size()),
                (int) (sumY / candidates.size())
        };
    }

    /**
     * Find the candidate nearest to the given position.
     */
    public ScanCoordinate getNearestCandidate(int playerX, int playerY) {
        ScanCoordinate nearest = null;
        int nearestDist = Integer.MAX_VALUE;

        for (ScanCoordinate c : candidates) {
            int dist = c.chebyshevDistance(playerX, playerY);
            if (dist < nearestDist) {
                nearestDist = dist;
                nearest = c;
            }
        }
        return nearest;
    }

    /**
     * Suggest the best position to walk to for maximum elimination.
     * Strategy: walk toward the centroid of remaining candidates to get
     * into orange/red range of the most candidates at once.
     */
    public int[] suggestWalkTarget(int playerX, int playerY) {
        if (candidates.isEmpty()) return null;
        if (candidates.size() == 1) {
            ScanCoordinate only = candidates.getFirst();
            return new int[]{only.x(), only.y()};
        }

        return getCentroid();
    }

    /**
     * Bulk-eliminate candidates by external request (e.g., negative validation).
     * Creates EliminationRecords for each removed candidate.
     *
     * @param toEliminate candidates to remove
     * @param reason      human-readable reason for all eliminations
     * @return number of candidates actually removed
     */
    public int eliminateCandidates(List<ScanCoordinate> toEliminate, String reason) {
        List<ScanCoordinate> actuallyRemoved = new ArrayList<>();

        for (ScanCoordinate c : toEliminate) {
            if (candidates.remove(c)) {
                actuallyRemoved.add(c);
                EliminationRecord record = new EliminationRecord(
                        c, reason, lastEvalX, lastEvalY, ScanPulseColor.NONE, 0,
                        System.currentTimeMillis());
                eliminations.add(record);
            }
        }

        if (!actuallyRemoved.isEmpty()) {
            log.info("[ScanTracker] Bulk eliminated {} candidates (reason: {}), {} remaining",
                    actuallyRemoved.size(), reason, candidates.size());
        }
        return actuallyRemoved.size();
    }

    // ── Accessors (thread-safe reads) ──

    public List<ScanCoordinate> getCandidates() {
        return List.copyOf(candidates);
    }

    public List<EliminationRecord> getEliminations() {
        return List.copyOf(eliminations);
    }

    public int getCandidateCount() {
        return candidates.size();
    }

    public int getEliminationCount() {
        return eliminations.size();
    }

    public ScanRegion getActiveRegion() {
        return activeRegion;
    }

    public boolean isTracking() {
        return activeRegion != null;
    }

    public boolean isSolved() {
        return solved;
    }

    public ScanCoordinate getSolvedCoordinate() {
        return solvedCoordinate;
    }

    public int getObservationCount() {
        return observations.size();
    }

    public int getBaseDistance() {
        return baseDistance;
    }

    public int getOrangeThreshold() {
        return (baseDistance * 2) + 1;
    }

    // ── Internal ──

    private String buildEliminationReason(ScanCoordinate candidate, int px, int py,
                                           ScanPulseColor color, int dist) {
        int orangeMax = (baseDistance * 2) + 1;
        return switch (color) {
            case BLUE -> String.format("saw BLUE but dist=%d <= %d (should be >%d)",
                    dist, orangeMax, orangeMax);
            case ORANGE -> {
                if (dist <= baseDistance)
                    yield String.format("saw ORANGE but dist=%d <= %d (too close, should be red)",
                            dist, baseDistance);
                else
                    yield String.format("saw ORANGE but dist=%d > %d (too far, should be blue)",
                            dist, orangeMax);
            }
            case RED -> String.format("saw RED but dist=%d > %d (too far, should be orange/blue)",
                    dist, baseDistance);
            default -> "unknown";
        };
    }

    /**
     * An observation: player position + pulse color at that position.
     */
    private record Observation(int x, int y, ScanPulseColor color) {}
}
