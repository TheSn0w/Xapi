package com.botwithus.bot.scripts.eliteclue.scan;

import com.botwithus.bot.api.log.BotLogger;
import com.botwithus.bot.api.log.LoggerFactory;

import java.util.*;

/**
 * Pure-logic navigation solver for scan clues. Computes probe targets based on
 * color observations and transitions — does NOT call any navigation/walk APIs.
 * <p>
 * The caller reads {@link #computeNextTarget} output and decides how to walk there.
 * <p>
 * <h3>State Machine Phases</h3>
 * <pre>
 * INITIAL_APPROACH   →  no observations yet, target = candidate centroid
 * DIRECTIONAL_PROBE  →  have observations, probing in a direction
 * BACKTRACK          →  last move worsened; return to last good position
 * PROXIMITY_EXPLORE  →  tunnel detected; targets specific candidates instead of compass probes
 * CONVERGE           →  ≤3 candidates remain; walk to nearest to trigger hint arrow
 * </pre>
 * <p>
 * Also implements a <b>negative validation</b> subsystem: if we dwell inside a
 * candidate's base-distance zone and observe non-RED colors, that candidate
 * (and all others in range) are eliminated via cascade.
 */
public final class ScanNavigationSolver {

    private static final BotLogger log = LoggerFactory.getLogger(ScanNavigationSolver.class);

    // ── Constants ──

    /** Minimum probe step (must exceed ScanClueTracker.MIN_MOVE_DISTANCE = 3). */
    private static final int MIN_STEP = 4;

    /** Consecutive SAME transitions before rotating 90°. */
    private static final int SAME_ROTATION_THRESHOLD = 3;

    /** Non-NONE observations required inside a candidate's radius for negative validation. */
    private static final int REQUIRED_CONFIRMATIONS = 3;

    /** Backtracks to the same anchor (or exhausted compass headings) before entering PROXIMITY_EXPLORE. */
    private static final int TUNNEL_THRESHOLD = 2;

    /** Consecutive observations without player movement before marking a candidate unreachable. */
    private static final int STUCK_THRESHOLD = 3;

    // ── 8 compass directions as unit vectors ──
    // Index 0=N, 1=NE, 2=E, 3=SE, 4=S, 5=SW, 6=W, 7=NW
    private static final double[][] COMPASS = {
            { 0,  1}, // N
            { 1,  1}, // NE
            { 1,  0}, // E
            { 1, -1}, // SE
            { 0, -1}, // S
            {-1, -1}, // SW
            {-1,  0}, // W
            {-1,  1}, // NW
    };

    private static final String[] COMPASS_LABELS = {
            "N", "NE", "E", "SE", "S", "SW", "W", "NW"
    };

    // ── Solver Phase ──

    public enum SolverPhase {
        INITIAL_APPROACH("Initial Approach"),
        DIRECTIONAL_PROBE("Directional Probe"),
        BACKTRACK("Backtrack"),
        CONVERGE("Converge"),
        /** Tunnel-aware exploration: targets specific candidates instead of compass probes. */
        PROXIMITY_EXPLORE("Proximity Explore");

        private final String label;
        SolverPhase(String label) { this.label = label; }
        public String label() { return label; }
    }

    // ── Solver State ──

    private SolverPhase phase = SolverPhase.INITIAL_APPROACH;
    private int baseDistance = ScanClueTracker.DEFAULT_BASE_DISTANCE;
    private int probeStepSize;

    /** Last position where we saw an improved or best color. */
    private int[] lastGoodPosition;
    private ScanPulseColor lastGoodColor = ScanPulseColor.NONE;

    /** Current probe direction as unit vector [dx, dy]. */
    private double[] probeDirection;

    /** Quantized compass heading index (0-7) of current direction. */
    private int currentHeadingIndex = -1;

    /** Directions already tried from the current anchor without improvement. */
    private final Set<Integer> triedDirections = new HashSet<>();

    /** Counter for consecutive SAME transitions. */
    private int consecutiveSameCount = 0;

    /**
     * Candidates we've already visited (been within MIN_STEP of) during CONVERGE.
     * Only used as fallback if hint arrow hasn't appeared yet.
     */
    private final Set<ScanCoordinate> visitedCandidates = new HashSet<>();

    // ── Tunnel Detection / PROXIMITY_EXPLORE State ──

    /** How many times we've arrived back at the same anchor without improvement. */
    private int anchorRevisitCount = 0;

    /** Last observed player position for stuck detection. */
    private int[] lastObservedPosition;

    /** Consecutive observations where player hasn't moved (for stuck detection). */
    private int stuckCount = 0;

    /** Ordered queue of candidates to visit during PROXIMITY_EXPLORE. */
    private final List<ScanCoordinate> explorationQueue = new ArrayList<>();

    /** Current index into the exploration queue. */
    private int explorationIndex = 0;

    // ── Negative Validation State ──

    private ScanCoordinate negValTarget;
    private int[] negValEntryPosition;
    private long negValEntryTimestamp;
    private int negValEntryObsIndex;
    private boolean negValInsideRadius;
    private int negValConfirmations;
    private boolean negValSawRed;
    private boolean negValSawAnyColor;

    public ScanNavigationSolver() {
        this.probeStepSize = ScanClueTracker.DEFAULT_BASE_DISTANCE;
    }

    /**
     * Update the base distance (called when tracker parses it from scanner text).
     */
    public void setBaseDistance(int baseDistance) {
        if (baseDistance > 0 && baseDistance != this.baseDistance) {
            this.baseDistance = baseDistance;
            if (phase == SolverPhase.INITIAL_APPROACH) {
                this.probeStepSize = baseDistance;
            }
            log.info("[ScanSolver] Base distance updated to {}", baseDistance);
        }
    }

    /**
     * Called after each new observation. Updates solver state and runs negative
     * validation checks.
     *
     * @param obsLog  the observation log (already has the new observation appended)
     * @param tracker the tracker (for negative validation eliminations)
     */
    public void onObservation(ScanObservationLog obsLog, ScanClueTracker tracker) {
        ScanObservationLog.ScanObservation latest = obsLog.getLatest();
        if (latest == null || latest.color() == ScanPulseColor.NONE) return;

        ScanObservationLog.ColorTransition transition = obsLog.getTransition();
        updateBaseDistance(tracker.getBaseDistance());

        log.info("[ScanSolver] Obs #{}: {} at ({},{}) — {} (phase={})",
                obsLog.size(), latest.color().label(), latest.x(), latest.y(),
                transition, phase.label());

        // Update last good position on improvement
        if (transition == ScanObservationLog.ColorTransition.IMPROVED
                || lastGoodColor == ScanPulseColor.NONE
                || latest.color().stateId() > lastGoodColor.stateId()) {
            int[] oldGood = lastGoodPosition;
            lastGoodPosition = new int[]{latest.x(), latest.y()};
            lastGoodColor = latest.color();
            log.info("[ScanSolver] New best: {} at ({},{})",
                    lastGoodColor.label(), latest.x(), latest.y());

            // Reset tunnel detection if anchor moved significantly
            if (oldGood != null) {
                int anchorMoved = Math.max(Math.abs(latest.x() - oldGood[0]),
                        Math.abs(latest.y() - oldGood[1]));
                if (anchorMoved > baseDistance) {
                    anchorRevisitCount = 0;
                    triedDirections.clear();
                }
            }
        }

        // Phase transitions based on observation.
        //
        // RED = hint arrow visible in the real game. ScanClueTask.handleSolved()
        // takes over navigation to the dig spot. The solver doesn't need to act
        // on RED — it means we're done.
        //
        // CONVERGE is only entered when ≤3 candidates remain, to walk close
        // enough to trigger RED (and thus the hint arrow).
        switch (phase) {
            case INITIAL_APPROACH -> {
                if (tracker.getCandidateCount() <= 3) {
                    transitionTo(SolverPhase.CONVERGE, latest);
                } else {
                    transitionTo(SolverPhase.DIRECTIONAL_PROBE, latest);
                }
            }
            case DIRECTIONAL_PROBE -> {
                if (tracker.getCandidateCount() <= 3) {
                    transitionTo(SolverPhase.CONVERGE, latest);
                } else if (transition == ScanObservationLog.ColorTransition.DEGRADED) {
                    transitionTo(SolverPhase.BACKTRACK, latest);
                } else if (transition == ScanObservationLog.ColorTransition.SAME) {
                    consecutiveSameCount++;
                    if (consecutiveSameCount >= SAME_ROTATION_THRESHOLD) {
                        consecutiveSameCount = 0;
                        // Check for tunnel behavior before rotating
                        if (shouldEnterProximityExplore(tracker)) {
                            enterProximityExplore(latest, tracker);
                        } else {
                            rotate90();
                        }
                    }
                } else if (transition == ScanObservationLog.ColorTransition.IMPROVED) {
                    consecutiveSameCount = 0;
                    // Reduce step size when entering ORANGE zone
                    if (latest.color() == ScanPulseColor.ORANGE) {
                        int orangeStep = Math.max(baseDistance / 2, MIN_STEP);
                        if (orangeStep < probeStepSize) {
                            probeStepSize = orangeStep;
                            log.info("[ScanSolver] Step size: {} (entered ORANGE zone)", probeStepSize);
                        }
                    }
                }
            }
            case BACKTRACK -> {
                if (tracker.getCandidateCount() <= 3) {
                    transitionTo(SolverPhase.CONVERGE, latest);
                }
                // Stay in BACKTRACK until we reach lastGoodPosition (handled in computeNextTarget)
            }
            case CONVERGE -> {
                // Stay in CONVERGE — walk toward nearest candidate to trigger RED/hint arrow
            }
            case PROXIMITY_EXPLORE -> {
                if (tracker.getCandidateCount() <= 3) {
                    transitionTo(SolverPhase.CONVERGE, latest);
                }
                // Do NOT enter BACKTRACK on degradation — tunnel paths curve away temporarily.
                // The pathfinder routes through corridors; color may fluctuate en route.
                // Arrival and stuck detection handled below.
            }
        }

        // Run negative validation check
        processNegativeValidation(latest.x(), latest.y(), latest.color(), obsLog, tracker);

        // Proximity exploration: stuck detection and candidate arrival
        if (phase == SolverPhase.PROXIMITY_EXPLORE) {
            handleProximityExploreObservation(latest, tracker);
        }
    }

    /**
     * Compute the next target coordinate to walk toward.
     * Returns null if no target can be computed (e.g. no candidates).
     */
    public int[] computeNextTarget(int playerX, int playerY,
                                   ScanObservationLog obsLog,
                                   ScanClueTracker tracker) {
        if (tracker.getCandidateCount() == 0) return null;

        ScanRegion region = tracker.getActiveRegion();
        if (region == null) return null;

        int[] target = switch (phase) {
            case INITIAL_APPROACH -> computeInitialTarget(tracker);
            case DIRECTIONAL_PROBE -> computeProbeTarget(playerX, playerY, tracker, region);
            case BACKTRACK -> computeBacktrackTarget(playerX, playerY, obsLog, tracker, region);
            case CONVERGE -> computeConvergeTarget(playerX, playerY, tracker);
            case PROXIMITY_EXPLORE -> computeProximityExploreTarget(playerX, playerY, tracker);
        };

        if (target != null && region != null) {
            target = clampToBounds(target, region);
        }

        // Update negative validation target candidate (nearest to walk target)
        if (target != null) {
            updateNegValTarget(target, tracker);
        }

        return target;
    }

    /**
     * Reset all solver state. Called when scanner closes or clue changes.
     */
    public void reset() {
        log.info("[ScanSolver] Reset");
        phase = SolverPhase.INITIAL_APPROACH;
        baseDistance = ScanClueTracker.DEFAULT_BASE_DISTANCE;
        probeStepSize = ScanClueTracker.DEFAULT_BASE_DISTANCE;
        lastGoodPosition = null;
        lastGoodColor = ScanPulseColor.NONE;
        probeDirection = null;
        currentHeadingIndex = -1;
        triedDirections.clear();
        consecutiveSameCount = 0;
        visitedCandidates.clear();
        anchorRevisitCount = 0;
        lastObservedPosition = null;
        stuckCount = 0;
        explorationQueue.clear();
        explorationIndex = 0;
        resetNegativeValidation();
    }

    // ── Phase computation methods ──

    private int[] computeInitialTarget(ScanClueTracker tracker) {
        return computeOptimalSplitTarget(tracker);
    }

    /**
     * Find the observation position that maximizes the minimum elimination across
     * all possible color outcomes (minimax over {BLUE, ORANGE, RED}).
     * <p>
     * For each candidate as a hypothetical observation point, count how many
     * candidates fall in each color zone. The best point is where the smallest
     * zone is maximized — guaranteeing high elimination regardless of color.
     * <p>
     * This is O(n²) where n = candidate count, which is fine for n ≤ 52.
     * Falls back to centroid if computation fails.
     */
    private int[] computeOptimalSplitTarget(ScanClueTracker tracker) {
        List<ScanCoordinate> candidates = tracker.getCandidates();
        if (candidates.size() <= 3) {
            return tracker.getCentroid();
        }

        int total = candidates.size();
        int bestMinElim = -1;
        int[] bestPos = null;

        // Evaluate each candidate position as a potential observation point
        for (ScanCoordinate probe : candidates) {
            int redCount = 0, orangeCount = 0, blueCount = 0;

            for (ScanCoordinate c : candidates) {
                int dist = c.chebyshevDistance(probe.x(), probe.y());
                if (dist <= baseDistance) {
                    redCount++;
                } else if (dist <= (baseDistance * 2) + 1) {
                    orangeCount++;
                } else {
                    blueCount++;
                }
            }

            // If we observe RED at this point, we eliminate (total - redCount) candidates.
            // If ORANGE, we eliminate (total - orangeCount). If BLUE, (total - blueCount).
            // The worst case (minimum elimination) determines this point's quality.
            int elimIfRed = total - redCount;
            int elimIfOrange = total - orangeCount;
            int elimIfBlue = total - blueCount;
            int minElim = Math.min(elimIfRed, Math.min(elimIfOrange, elimIfBlue));

            if (minElim > bestMinElim) {
                bestMinElim = minElim;
                bestPos = new int[]{probe.x(), probe.y()};
            }
        }

        if (bestPos != null && bestMinElim > 0) {
            log.info("[ScanSolver] Optimal split: ({},{}) guarantees ≥{}/{} elimination",
                    bestPos[0], bestPos[1], bestMinElim, total);
            return bestPos;
        }

        return tracker.getCentroid();
    }

    private int[] computeProbeTarget(int playerX, int playerY,
                                     ScanClueTracker tracker, ScanRegion region) {
        // If best color is still BLUE (haven't entered orange zone yet),
        // use larger steps scaled to actually reach candidates
        if (lastGoodColor == ScanPulseColor.BLUE || lastGoodColor == ScanPulseColor.NONE) {
            ScanCoordinate nearest = tracker.getNearestCandidate(playerX, playerY);
            if (nearest != null) {
                int nearestDist = nearest.chebyshevDistance(playerX, playerY);
                // In BLUE zone, step at least 1/3 of the way to nearest candidate
                int scaledStep = Math.max(nearestDist / 3, baseDistance);
                if (scaledStep > probeStepSize) {
                    probeStepSize = scaledStep;
                    log.info("[ScanSolver] BLUE zone step scaled to {} (nearest cand dist={})",
                            probeStepSize, nearestDist);
                }
            }
        }

        if (probeDirection == null) {
            // Pick initial direction toward nearest candidate (not centroid —
            // centroid may be at player pos if player started at center)
            ScanCoordinate nearest = tracker.getNearestCandidate(playerX, playerY);
            if (nearest != null && nearest.chebyshevDistance(playerX, playerY) > MIN_STEP) {
                setDirectionToward(playerX, playerY, nearest.x(), nearest.y());
            } else {
                int[] centroid = tracker.getCentroid();
                if (centroid == null) return null;
                setDirectionToward(playerX, playerY, centroid[0], centroid[1]);
            }
        }

        // Step along current direction
        int targetX = playerX + (int) (probeDirection[0] * probeStepSize);
        int targetY = playerY + (int) (probeDirection[1] * probeStepSize);

        return new int[]{targetX, targetY};
    }

    private int[] computeBacktrackTarget(int playerX, int playerY,
                                         ScanObservationLog obsLog,
                                         ScanClueTracker tracker,
                                         ScanRegion region) {
        if (lastGoodPosition == null) {
            // No good position recorded — fall back to centroid
            transitionTo(SolverPhase.DIRECTIONAL_PROBE, obsLog.getLatest());
            return tracker.getCentroid();
        }

        // Check if we've arrived at the last good position
        int dist = Math.max(Math.abs(playerX - lastGoodPosition[0]),
                Math.abs(playerY - lastGoodPosition[1]));

        if (dist <= MIN_STEP) {
            anchorRevisitCount++;

            // Check for tunnel behavior before resuming compass probing.
            // If we've returned to this anchor multiple times or exhausted many
            // compass headings without improvement, switch to candidate-targeted
            // exploration — the pathfinder will handle routing through tunnels.
            if (shouldEnterProximityExplore(tracker)) {
                enterProximityExplore(obsLog.getLatest(), tracker);
                return computeProximityExploreTarget(playerX, playerY, tracker);
            }

            // Arrived at anchor — pick next probe direction.
            // Strategy: use optimal-split target if it's within reachable distance
            // (≤ 2× step size), otherwise fall back to filtered centroid for direction.
            int[] nextTarget = computeOptimalSplitTarget(tracker);
            int[] filteredCentroid = computeFilteredCentroid(
                    lastGoodPosition[0], lastGoodPosition[1], lastGoodColor, tracker);

            boolean usedOptimal = false;
            if (nextTarget != null && tracker.getCandidateCount() > 3) {
                int dToOpt = Math.max(Math.abs(lastGoodPosition[0] - nextTarget[0]),
                        Math.abs(lastGoodPosition[1] - nextTarget[1]));
                // Only use optimal-split if reasonably close — otherwise
                // the long walk wastes time vs. incremental probing
                if (dToOpt > MIN_STEP && dToOpt <= probeStepSize * 2) {
                    setDirectionToward(lastGoodPosition[0], lastGoodPosition[1],
                            nextTarget[0], nextTarget[1]);
                    usedOptimal = true;
                }
            }

            if (!usedOptimal) {
                if (filteredCentroid != null) {
                    setDirectionToward(lastGoodPosition[0], lastGoodPosition[1],
                            filteredCentroid[0], filteredCentroid[1]);
                } else {
                    pickUntriedDirection(tracker, lastGoodPosition[0], lastGoodPosition[1]);
                }
            }

            consecutiveSameCount = 0;
            transitionTo(SolverPhase.DIRECTIONAL_PROBE, obsLog.getLatest());

            // Return a probe step from anchor
            return new int[]{
                    lastGoodPosition[0] + (int) (probeDirection[0] * probeStepSize),
                    lastGoodPosition[1] + (int) (probeDirection[1] * probeStepSize)
            };
        }

        // Still walking back to anchor
        return lastGoodPosition.clone();
    }

    private int[] computeConvergeTarget(int playerX, int playerY, ScanClueTracker tracker) {
        List<ScanCoordinate> candidates = tracker.getCandidates();
        if (candidates.isEmpty()) return null;

        if (candidates.size() == 1) {
            ScanCoordinate only = candidates.getFirst();
            return new int[]{only.x(), only.y()};
        }

        // Mark candidates we're currently standing on as visited
        for (ScanCoordinate c : candidates) {
            if (c.chebyshevDistance(playerX, playerY) <= MIN_STEP) {
                visitedCandidates.add(c);
            }
        }

        // First: try unvisited candidates (nearest first)
        ScanCoordinate bestUnvisited = null;
        int bestDist = Integer.MAX_VALUE;
        for (ScanCoordinate c : candidates) {
            if (visitedCandidates.contains(c)) continue;
            int dist = c.chebyshevDistance(playerX, playerY);
            if (dist < bestDist) {
                bestDist = dist;
                bestUnvisited = c;
            }
        }

        if (bestUnvisited != null) {
            log.info("[ScanSolver] CONVERGE: targeting unvisited {} (dist={}, visited {}/{})",
                    bestUnvisited, bestDist, visitedCandidates.size(), candidates.size());
            return new int[]{bestUnvisited.x(), bestUnvisited.y()};
        }

        // All candidates visited — reset and try again with the farthest
        // (to avoid oscillating between two nearby ones)
        log.info("[ScanSolver] CONVERGE: all {} candidates visited, resetting visited set",
                candidates.size());
        visitedCandidates.clear();

        ScanCoordinate farthest = null;
        int farthestDist = -1;
        for (ScanCoordinate c : candidates) {
            int dist = c.chebyshevDistance(playerX, playerY);
            if (dist > farthestDist) {
                farthestDist = dist;
                farthest = c;
            }
        }

        return farthest != null ? new int[]{farthest.x(), farthest.y()} : null;
    }

    // ── Direction management ──

    /**
     * Set probe direction toward a target point, quantized to 8 compass headings.
     */
    private void setDirectionToward(int fromX, int fromY, int toX, int toY) {
        double dx = toX - fromX;
        double dy = toY - fromY;
        double len = Math.sqrt(dx * dx + dy * dy);

        if (len < 1.0) {
            // Too close — pick arbitrary direction
            pickUntriedDirection();
            return;
        }

        // Normalize
        dx /= len;
        dy /= len;

        // Quantize to nearest compass heading
        int heading = quantizeToHeading(dx, dy);
        setHeading(heading);
    }

    /**
     * Rotate the current probe direction 90° clockwise.
     */
    private void rotate90() {
        if (currentHeadingIndex < 0) {
            pickUntriedDirection();
            return;
        }

        int newHeading = (currentHeadingIndex + 2) % 8; // +2 = 90° clockwise
        // If that's already tried, try counter-clockwise
        if (triedDirections.contains(newHeading)) {
            newHeading = (currentHeadingIndex + 6) % 8; // -2 = 90° counter-clockwise
        }

        if (triedDirections.contains(newHeading)) {
            pickUntriedDirection();
        } else {
            setHeading(newHeading);
            log.info("[ScanSolver] Rotated 90° to {} (3x SAME)", COMPASS_LABELS[newHeading]);
        }
    }

    /**
     * Pick an untried compass direction. If all 8 are tried, halve step size
     * and reset tried set.
     */
    /**
     * Pick the untried compass direction that maximizes expected candidate
     * elimination at the hypothetical probe target. Falls back to sequential
     * direction selection if no candidates are available for scoring.
     */
    private void pickUntriedDirection() {
        pickUntriedDirection(null, 0, 0);
    }

    private void pickUntriedDirection(ScanClueTracker tracker, int fromX, int fromY) {
        if (tracker != null && tracker.getCandidateCount() > 3) {
            int bestHeading = -1;
            int bestMinElim = -1;
            List<ScanCoordinate> candidates = tracker.getCandidates();
            int total = candidates.size();

            for (int i = 0; i < 8; i++) {
                if (triedDirections.contains(i)) continue;

                int probeX = fromX + (int) (COMPASS[i][0] * probeStepSize);
                int probeY = fromY + (int) (COMPASS[i][1] * probeStepSize);

                int redCount = 0, orangeCount = 0, blueCount = 0;
                for (ScanCoordinate c : candidates) {
                    int dist = c.chebyshevDistance(probeX, probeY);
                    if (dist <= baseDistance) redCount++;
                    else if (dist <= (baseDistance * 2) + 1) orangeCount++;
                    else blueCount++;
                }

                int elimIfRed = total - redCount;
                int elimIfOrange = total - orangeCount;
                int elimIfBlue = total - blueCount;
                int minElim = Math.min(elimIfRed, Math.min(elimIfOrange, elimIfBlue));

                if (minElim > bestMinElim) {
                    bestMinElim = minElim;
                    bestHeading = i;
                }
            }

            if (bestHeading >= 0) {
                log.info("[ScanSolver] Scored direction: {} (guarantees ≥{}/{} elim)",
                        COMPASS_LABELS[bestHeading], bestMinElim, total);
                setHeading(bestHeading);
                return;
            }
        }

        // Fallback: sequential untried
        for (int i = 0; i < 8; i++) {
            if (!triedDirections.contains(i)) {
                setHeading(i);
                return;
            }
        }

        // All directions tried — halve step and retry
        probeStepSize = Math.max(probeStepSize / 2, MIN_STEP);
        triedDirections.clear();
        log.info("[ScanSolver] All 8 directions exhausted — step halved to {}, directions reset",
                probeStepSize);

        // Default to N
        setHeading(0);
    }

    private void setHeading(int headingIndex) {
        currentHeadingIndex = headingIndex;
        probeDirection = COMPASS[headingIndex];
        triedDirections.add(headingIndex);
        log.info("[ScanSolver] Direction: {} from anchor, tried: {}",
                COMPASS_LABELS[headingIndex], formatTriedDirections());
    }

    /**
     * Quantize a direction vector to the nearest of 8 compass headings.
     */
    private static int quantizeToHeading(double dx, double dy) {
        double angle = Math.atan2(dy, dx); // radians, 0 = east
        // Convert to 0-7 where 0=N: rotate by 90° so N=0
        double degrees = Math.toDegrees(angle);
        // atan2 gives: E=0, N=90, W=180/-180, S=-90
        // We want: N=0, NE=1, E=2, SE=3, S=4, SW=5, W=6, NW=7
        // So: heading = (90 - degrees) / 45, adjusted to 0-7
        double heading = (90.0 - degrees) / 45.0;
        int quantized = ((int) Math.round(heading) % 8 + 8) % 8;
        return quantized;
    }

    // ── Filtered centroid (candidates consistent with best observation) ──

    private int[] computeFilteredCentroid(int anchorX, int anchorY,
                                          ScanPulseColor color,
                                          ScanClueTracker tracker) {
        List<ScanCoordinate> candidates = tracker.getCandidates();
        if (candidates.isEmpty()) return null;

        long sumX = 0, sumY = 0;
        int count = 0;

        for (ScanCoordinate c : candidates) {
            int dist = c.chebyshevDistance(anchorX, anchorY);
            if (color.isConsistentWith(dist, baseDistance)) {
                sumX += c.x();
                sumY += c.y();
                count++;
            }
        }

        if (count == 0) return null;
        return new int[]{(int) (sumX / count), (int) (sumY / count)};
    }

    // ── Tunnel Detection / Proximity Exploration ──

    /**
     * Check whether the solver should enter PROXIMITY_EXPLORE.
     * Conditions: we have an ORANGE+ anchor AND multiple failed compass attempts
     * from it, suggesting constrained/tunnel-like terrain where compass probing
     * is ineffective.
     */
    private boolean shouldEnterProximityExplore(ScanClueTracker tracker) {
        // Need evidence of proximity (ORANGE or better observation)
        if (lastGoodColor.stateId() < ScanPulseColor.ORANGE.stateId()) return false;
        // Don't use for tiny candidate sets (CONVERGE handles that)
        if (tracker.getCandidateCount() <= 3) return false;
        // Need evidence of tunnel behavior
        return anchorRevisitCount >= TUNNEL_THRESHOLD || triedDirections.size() >= 6;
    }

    /**
     * Transition to PROXIMITY_EXPLORE: build candidate queue and reset exploration state.
     */
    private void enterProximityExplore(ScanObservationLog.ScanObservation obs,
                                       ScanClueTracker tracker) {
        buildExplorationQueue(tracker);
        stuckCount = 0;
        lastObservedPosition = null;
        transitionTo(SolverPhase.PROXIMITY_EXPLORE, obs);
    }

    /**
     * Build the exploration queue: remaining candidates sorted by distance from
     * the proximity anchor (lastGoodPosition), nearest first.
     */
    private void buildExplorationQueue(ScanClueTracker tracker) {
        explorationQueue.clear();
        explorationQueue.addAll(tracker.getCandidates());
        explorationIndex = 0;

        if (lastGoodPosition != null) {
            int ax = lastGoodPosition[0], ay = lastGoodPosition[1];
            explorationQueue.sort((a, b) -> {
                int da = a.chebyshevDistance(ax, ay);
                int db = b.chebyshevDistance(ax, ay);
                return Integer.compare(da, db);
            });
        }

        log.info("[ScanSolver] PROXIMITY_EXPLORE: built queue with {} candidates from anchor ({},{})",
                explorationQueue.size(),
                lastGoodPosition != null ? lastGoodPosition[0] : "?",
                lastGoodPosition != null ? lastGoodPosition[1] : "?");
    }

    /**
     * Compute the target for PROXIMITY_EXPLORE: the current candidate in the queue.
     * If the queue is exhausted, rebuild or switch to DIRECTIONAL_PROBE.
     */
    private int[] computeProximityExploreTarget(int playerX, int playerY,
                                                 ScanClueTracker tracker) {
        // Remove eliminated candidates
        explorationQueue.retainAll(tracker.getCandidates());
        if (explorationIndex >= explorationQueue.size()) {
            explorationIndex = 0;
        }

        if (explorationQueue.isEmpty()) {
            // All candidates eliminated during exploration — rebuild from tracker
            buildExplorationQueue(tracker);
            if (explorationQueue.isEmpty()) return null;
        }

        ScanCoordinate target = explorationQueue.get(explorationIndex);
        return new int[]{target.x(), target.y()};
    }

    /**
     * Handle observation while in PROXIMITY_EXPLORE: stuck detection and
     * candidate arrival tracking.
     */
    private void handleProximityExploreObservation(ScanObservationLog.ScanObservation latest,
                                                    ScanClueTracker tracker) {
        // Stuck detection: if player hasn't moved, they may be blocked by walls
        if (lastObservedPosition != null) {
            int moved = Math.max(Math.abs(latest.x() - lastObservedPosition[0]),
                    Math.abs(latest.y() - lastObservedPosition[1]));
            if (moved <= 1) {
                stuckCount++;
                if (stuckCount >= STUCK_THRESHOLD && !explorationQueue.isEmpty()
                        && explorationIndex < explorationQueue.size()) {
                    ScanCoordinate skipped = explorationQueue.get(explorationIndex);
                    log.info("[ScanSolver] PROXIMITY_EXPLORE: stuck at ({},{}) for {} obs, " +
                                    "skipping candidate {} (unreachable from current approach)",
                            latest.x(), latest.y(), stuckCount, skipped);
                    advanceExplorationQueue(latest, tracker);
                }
            } else {
                stuckCount = 0;
            }
        }
        lastObservedPosition = new int[]{latest.x(), latest.y()};

        // Arrival detection: check if we've reached the current target candidate
        if (!explorationQueue.isEmpty() && explorationIndex < explorationQueue.size()) {
            ScanCoordinate target = explorationQueue.get(explorationIndex);
            int distToTarget = target.chebyshevDistance(latest.x(), latest.y());
            if (distToTarget <= MIN_STEP) {
                log.info("[ScanSolver] PROXIMITY_EXPLORE: arrived at candidate {} (dist={}, color={})",
                        target, distToTarget, latest.color().label());
                stuckCount = 0;
                advanceExplorationQueue(latest, tracker);
            }
        }
    }

    /**
     * Advance to the next candidate in the exploration queue. If the queue is
     * exhausted, exit PROXIMITY_EXPLORE and resume directional probing from
     * the current position.
     */
    private void advanceExplorationQueue(ScanObservationLog.ScanObservation latest,
                                          ScanClueTracker tracker) {
        explorationIndex++;
        stuckCount = 0;

        // Remove eliminated candidates (tracker may have eliminated some)
        explorationQueue.retainAll(tracker.getCandidates());
        if (explorationIndex >= explorationQueue.size()) {
            explorationIndex = 0;
        }

        if (explorationQueue.isEmpty()) {
            log.info("[ScanSolver] PROXIMITY_EXPLORE: queue exhausted, resuming directional probe");
            exitProximityExplore(latest);
        } else {
            ScanCoordinate next = explorationQueue.get(explorationIndex);
            log.info("[ScanSolver] PROXIMITY_EXPLORE: next target {} ({}/{})",
                    next, explorationIndex + 1, explorationQueue.size());
        }
    }

    /**
     * Exit PROXIMITY_EXPLORE: reset tunnel detection state and resume
     * DIRECTIONAL_PROBE from current position.
     */
    private void exitProximityExplore(ScanObservationLog.ScanObservation latest) {
        triedDirections.clear();
        anchorRevisitCount = 0;
        explorationQueue.clear();
        explorationIndex = 0;
        stuckCount = 0;
        lastObservedPosition = null;
        transitionTo(SolverPhase.DIRECTIONAL_PROBE, latest);
    }

    // ── Negative Validation ──

    /**
     * Process negative validation for the current observation.
     * If we've been inside a candidate's base-distance zone long enough without
     * seeing RED, eliminate that candidate and all others in range.
     */
    private void processNegativeValidation(int playerX, int playerY,
                                            ScanPulseColor color,
                                            ScanObservationLog obsLog,
                                            ScanClueTracker tracker) {
        if (negValTarget == null) return;

        int distToTarget = negValTarget.chebyshevDistance(playerX, playerY);

        // Check for entry into radius
        if (!negValInsideRadius && distToTarget <= baseDistance) {
            negValInsideRadius = true;
            negValEntryPosition = new int[]{playerX, playerY};
            negValEntryTimestamp = System.currentTimeMillis();
            negValEntryObsIndex = obsLog.size();
            negValConfirmations = 0;
            negValSawRed = false;
            negValSawAnyColor = false;
            log.info("[NegVal] Entered base-distance zone of {}, dist={}",
                    negValTarget, distToTarget);
        }

        if (!negValInsideRadius) return;

        // Check for exit from radius
        if (distToTarget > baseDistance) {
            log.info("[NegVal] Exited {} zone before confirmation ({} observations)",
                    negValTarget, negValConfirmations);
            negValInsideRadius = false;
            return;
        }

        // Process the color observation while inside
        if (color != ScanPulseColor.NONE) {
            negValConfirmations++;
            negValSawAnyColor = true;

            if (color == ScanPulseColor.RED) {
                negValSawRed = true;
                log.info("[NegVal] RED seen inside {} zone — not a negative validation",
                        negValTarget);
                negValInsideRadius = false;
                return;
            }
        }

        // Check confirmation window
        if (negValConfirmations >= REQUIRED_CONFIRMATIONS) {
            if (!negValSawRed && negValSawAnyColor) {
                // Trigger cascade elimination
                triggerNegativeElimination(playerX, playerY, color, tracker);
                negValInsideRadius = false;
            } else if (!negValSawAnyColor) {
                log.info("[NegVal] {} NONE-only ticks inside {} zone — inconclusive",
                        negValConfirmations, negValTarget);
            }
        }
    }

    /**
     * Cascade elimination: remove ALL candidates within baseDistance of the
     * current player position. Mathematical proof: if any of them were the
     * real dig spot, we'd have seen RED. We didn't.
     */
    private void triggerNegativeElimination(int playerX, int playerY,
                                            ScanPulseColor lastColor,
                                            ScanClueTracker tracker) {
        List<ScanCoordinate> toEliminate = new ArrayList<>();

        for (ScanCoordinate c : tracker.getCandidates()) {
            int dist = c.chebyshevDistance(playerX, playerY);
            if (dist <= baseDistance) {
                toEliminate.add(c);
                log.info("[NegVal] CASCADE ELIMINATE {}: dist={} <= {} from ({},{}), " +
                                "observed {} for {} ticks, never saw RED",
                        c, dist, baseDistance, playerX, playerY,
                        lastColor.label(), negValConfirmations);
            }
        }

        if (!toEliminate.isEmpty()) {
            String reason = String.format("negative validation at (%d,%d): observed %s for %d non-NONE ticks, never RED",
                    playerX, playerY, lastColor.label(), negValConfirmations);
            int count = tracker.eliminateCandidates(toEliminate, reason);
            log.info("[NegVal] Eliminated {} candidates via negative validation", count);
        }
    }

    /**
     * Update the negative validation target to the candidate nearest to our walk target.
     */
    private void updateNegValTarget(int[] walkTarget, ScanClueTracker tracker) {
        ScanCoordinate nearest = null;
        int nearestDist = Integer.MAX_VALUE;

        for (ScanCoordinate c : tracker.getCandidates()) {
            int dist = c.chebyshevDistance(walkTarget[0], walkTarget[1]);
            if (dist < nearestDist) {
                nearestDist = dist;
                nearest = c;
            }
        }

        if (nearest != negValTarget) {
            // Target changed — reset negative validation state
            if (nearest != null) {
                log.info("[NegVal] New target candidate: {} (dist {} from walk target)",
                        nearest, nearestDist);
            }
            resetNegativeValidation();
            negValTarget = nearest;
        }
    }

    private void resetNegativeValidation() {
        negValTarget = null;
        negValEntryPosition = null;
        negValEntryTimestamp = 0;
        negValEntryObsIndex = 0;
        negValInsideRadius = false;
        negValConfirmations = 0;
        negValSawRed = false;
        negValSawAnyColor = false;
    }

    // ── Utility ──

    private void transitionTo(SolverPhase newPhase, ScanObservationLog.ScanObservation obs) {
        if (newPhase == phase) return;
        String reason = obs != null
                ? String.format("%s at (%d,%d)", obs.color().label(), obs.x(), obs.y())
                : "unknown";
        log.info("[ScanSolver] Phase: {} -> {} ({})", phase.label(), newPhase.label(), reason);
        phase = newPhase;

        if (newPhase == SolverPhase.DIRECTIONAL_PROBE) {
            // Set step size based on current color zone
            if (lastGoodColor == ScanPulseColor.ORANGE) {
                probeStepSize = Math.max(baseDistance / 2, MIN_STEP);
            } else {
                probeStepSize = Math.max(baseDistance, MIN_STEP);
            }
            log.info("[ScanSolver] Step size: {} (zone={})", probeStepSize, lastGoodColor.label());
        }
    }

    private void updateBaseDistance(int newBase) {
        if (newBase > 0 && newBase != baseDistance) {
            baseDistance = newBase;
        }
    }

    /**
     * Clamp a target position to the region's bounding box.
     */
    private static int[] clampToBounds(int[] target, ScanRegion region) {
        int x = Math.max(region.minX(), Math.min(region.maxX(), target[0]));
        int y = Math.max(region.minY(), Math.min(region.maxY(), target[1]));
        return new int[]{x, y};
    }

    private String formatTriedDirections() {
        if (triedDirections.isEmpty()) return "[]";
        StringJoiner sj = new StringJoiner(", ", "[", "]");
        for (int h : triedDirections) {
            sj.add(COMPASS_LABELS[h]);
        }
        return sj.toString();
    }

    // ── Accessors for UI ──

    public SolverPhase getPhase() { return phase; }

    public int getProbeStepSize() { return probeStepSize; }

    public int[] getLastGoodPosition() {
        return lastGoodPosition != null ? lastGoodPosition.clone() : null;
    }

    public ScanPulseColor getLastGoodColor() { return lastGoodColor; }

    public double[] getProbeDirection() {
        return probeDirection != null ? probeDirection.clone() : null;
    }

    public int getCurrentHeadingIndex() { return currentHeadingIndex; }

    public String getCurrentHeadingLabel() {
        return currentHeadingIndex >= 0 ? COMPASS_LABELS[currentHeadingIndex] : "—";
    }

    public int getConsecutiveSameCount() { return consecutiveSameCount; }

    public int getBaseDistance() { return baseDistance; }

    // Negative validation accessors
    public ScanCoordinate getNegValTarget() { return negValTarget; }
    public boolean isNegValInsideRadius() { return negValInsideRadius; }
    public int getNegValConfirmations() { return negValConfirmations; }
    public boolean isNegValSawRed() { return negValSawRed; }
    public boolean isNegValSawAnyColor() { return negValSawAnyColor; }

    // Proximity exploration accessors
    public int getAnchorRevisitCount() { return anchorRevisitCount; }
    public int getStuckCount() { return stuckCount; }
    public int getExplorationQueueSize() { return explorationQueue.size(); }
    public int getExplorationIndex() { return explorationIndex; }

    public ScanCoordinate getCurrentExploreTarget() {
        if (explorationQueue.isEmpty() || explorationIndex >= explorationQueue.size()) return null;
        return explorationQueue.get(explorationIndex);
    }
}
