package com.botwithus.bot.scripts.eliteclue.task;

import com.botwithus.bot.api.log.BotLogger;
import com.botwithus.bot.api.log.LoggerFactory;
import com.botwithus.bot.api.script.Task;
import com.botwithus.bot.scripts.eliteclue.ClueActivity;
import com.botwithus.bot.scripts.eliteclue.ClueContext;
import com.botwithus.bot.scripts.eliteclue.scan.*;
import com.botwithus.bot.scripts.eliteclue.scan.ScanObservationLog.ColorTransition;

/**
 * Task that handles the scanner clue step (interface 1752).
 * <p>
 * Lifecycle:
 * <ol>
 *   <li>IDENTIFY — detect which scan region we're solving from the clue item or player position</li>
 *   <li>OBSERVE — read pulse color, eliminate impossible candidates</li>
 *   <li>NAVIGATE — walk toward the centroid of remaining candidates</li>
 *   <li>SOLVED — hint arrow detected, walk to dig spot</li>
 * </ol>
 */
public final class ScanClueTask implements Task {

    private static final BotLogger log = LoggerFactory.getLogger(ScanClueTask.class);

    private final ClueContext ctx;
    private final ScanClueData data;
    private final ScanClueTracker tracker;
    private final ScanObservationLog observationLog;
    private final ScanNavigationSolver solver;

    /** The solver's output — readable by future pathfinder. */
    private volatile int[] pendingWalkTarget;

    /** Cooldown between walk commands to avoid spamming. */
    private long lastWalkTime = 0;
    private static final long WALK_COOLDOWN_MS = 1200;

    /** Cooldown between observations to let spot anim settle. */
    private long lastObservationTime = 0;
    private static final long OBSERVATION_COOLDOWN_MS = 1200;

    /** Track if we've attempted region identification this session. */
    private boolean regionIdentified = false;

    public ScanClueTask(ClueContext ctx, ScanClueData data, ScanClueTracker tracker) {
        this.ctx = ctx;
        this.data = data;
        this.tracker = tracker;
        this.observationLog = new ScanObservationLog();
        this.solver = new ScanNavigationSolver();
    }

    @Override
    public String name() {
        return "ScanClue";
    }

    @Override
    public int priority() {
        return 80;
    }

    @Override
    public boolean validate() {
        return ctx.activity == ClueActivity.SCANNER && ctx.scannerOpen;
    }

    @Override
    public int execute() {
        long now = System.currentTimeMillis();

        // ── Step 1: Ensure we have a region identified ──
        if (!tracker.isTracking()) {
            return identifyRegion();
        }

        // ── Step 1b: Parse base distance from scanner text if not yet done ──
        // The text says e.g. "within 14 paces" — this sets the elimination thresholds
        if (ctx.scannerOpen && ctx.scannerDistanceText != null && !ctx.scannerDistanceText.isBlank()) {
            tracker.parseBaseDistance(ctx.scannerDistanceText);
        }

        // ── Step 2: Check if solved (hint arrow appeared) ──
        if (ctx.hasHintArrow) {
            return handleSolved();
        }

        // ── Step 3: Process scan observation if we have a pulse color ──
        ScanPulseColor color = ScanPulseColor.fromStateId(ctx.spotAnimState);
        if (color != ScanPulseColor.NONE && !ctx.playerMoving) {
            if (now - lastObservationTime >= OBSERVATION_COOLDOWN_MS) {
                lastObservationTime = now;
                int eliminated = tracker.processObservation(ctx.playerX, ctx.playerY, color);

                // Record to observation log and notify solver
                observationLog.record(ctx.playerX, ctx.playerY, color, tracker.getCandidateCount());
                solver.setBaseDistance(tracker.getBaseDistance());
                solver.onObservation(observationLog, tracker);

                ColorTransition transition = observationLog.getTransition();

                if (eliminated > 0) {
                    ctx.logAction(String.format("SCAN: %s at (%d,%d) — %s — eliminated %d, %d remaining",
                            color.label(), ctx.playerX, ctx.playerY, transition,
                            eliminated, tracker.getCandidateCount()));
                }

                // If only 1 candidate left, log it prominently
                if (tracker.getCandidateCount() == 1) {
                    ScanCoordinate last = tracker.getCandidates().getFirst();
                    ctx.logAction("SCAN: Only 1 candidate remaining: " + last
                            + " — walking to it");
                }

                // If 0 candidates, something went wrong
                if (tracker.getCandidateCount() == 0) {
                    ctx.logAction("SCAN ERROR: All candidates eliminated! Resetting.");
                    log.warn("[ScanTask] All candidates eliminated — logic error or stale data. Resetting.");
                    tracker.reset();
                    solver.reset();
                    observationLog.clear();
                    regionIdentified = false;
                    return 600;
                }
            }
        }

        // ── Step 4: Navigate toward remaining candidates ──
        if (!ctx.playerMoving && now - lastWalkTime >= WALK_COOLDOWN_MS) {
            return navigateTowardCandidates(now);
        }

        return 600; // Wait a tick
    }

    /**
     * Identify which scan region we're solving.
     * Priority: (1) scanner text, (2) clue item ID, (3) player position.
     */
    private int identifyRegion() {
        if (!data.isLoaded()) {
            ctx.logAction("SCAN: Data not loaded yet, waiting...");
            log.warn("[ScanTask] ScanClueData not loaded");
            return 1200;
        }

        ScanRegion region = null;
        String method = "unknown";

        // Method 1 (best): Match by scanner interface text
        // The text on interface 1752 comp 3 tells us the region name directly
        String scanText = ctx.scannerDistanceText;
        if (scanText != null && !scanText.isBlank()) {
            region = data.identifyRegionByText(scanText);
            if (region != null) {
                method = "scanner text '" + scanText + "'";
                log.info("[ScanTask] Identified region '{}' from scanner text: '{}'",
                        region.name(), scanText);
            }
        }

        // Method 2: Match by clue item ID (param 235)
        if (region == null && ctx.clueItemId > 0) {
            region = data.getRegionForItem(ctx.clueItemId);
            if (region != null) {
                method = "clue item " + ctx.clueItemId;
                log.info("[ScanTask] Identified region '{}' from clue item {}",
                        region.name(), ctx.clueItemId);
            }
        }

        // Method 3 (fallback): Match by player position proximity
        if (region == null) {
            region = data.identifyRegionByPosition(ctx.playerX, ctx.playerY, ctx.playerPlane);
            if (region != null) {
                method = "player position (" + ctx.playerX + "," + ctx.playerY + "," + ctx.playerPlane + ")";
                log.info("[ScanTask] Identified region '{}' from player position ({},{},{})",
                        region.name(), ctx.playerX, ctx.playerY, ctx.playerPlane);
            }
        }

        if (region == null) {
            ctx.logAction("SCAN: Could not identify scan region! text='" + scanText
                    + "' item=" + ctx.clueItemId
                    + " pos=(" + ctx.playerX + "," + ctx.playerY + "," + ctx.playerPlane + ")");
            log.warn("[ScanTask] Failed to identify region — text='{}' item={} pos=({},{},{})",
                    scanText, ctx.clueItemId, ctx.playerX, ctx.playerY, ctx.playerPlane);
            regionIdentified = true; // Don't spam
            return 2000;
        }

        tracker.startTracking(region);
        regionIdentified = true;
        ctx.logAction(String.format("SCAN: Tracking '%s' — %d candidates (via %s)",
                region.name(), region.coords().size(), method));

        return 600;
    }

    /**
     * Hint arrow detected — the scan is solved. Walk to the dig spot.
     * Handles plane transitions: if the dig spot is on a different plane,
     * logs a warning (pathfinder plane support is TODO).
     */
    private int handleSolved() {
        if (!tracker.isSolved()) {
            tracker.markSolved(ctx.hintArrowX, ctx.hintArrowY, ctx.hintArrowPlane);
            ctx.logAction(String.format("SCAN SOLVED: Dig at (%d, %d, %d) — %d observations, %d eliminations",
                    ctx.hintArrowX, ctx.hintArrowY, ctx.hintArrowPlane,
                    tracker.getObservationCount(), tracker.getEliminationCount()));

            // Warn if the dig spot is on a different plane (e.g. Dorgesh-Kaan)
            if (ctx.hintArrowPlane != ctx.playerPlane) {
                log.warn("[ScanTask] Dig spot on plane {} but player on plane {} — " +
                        "manual plane transition may be needed",
                        ctx.hintArrowPlane, ctx.playerPlane);
                ctx.logAction(String.format("SCAN WARNING: Dig spot on plane %d, you are on plane %d",
                        ctx.hintArrowPlane, ctx.playerPlane));
            }
        }

        // Navigate to the hint arrow
        int dist = Math.max(Math.abs(ctx.playerX - ctx.hintArrowX),
                Math.abs(ctx.playerY - ctx.hintArrowY));

        if (dist <= 1 && !ctx.playerMoving) {
            if (ctx.hintArrowPlane != ctx.playerPlane) {
                ctx.logAction(String.format("SCAN: At dig XY but wrong plane (%d vs %d) — find stairs/ladder",
                        ctx.playerPlane, ctx.hintArrowPlane));
                return 1200;
            }
            // We're at the dig spot — the activity system should switch to DIG
            ctx.logAction("SCAN: At dig spot, waiting for DIG activity");
            return 600;
        }

        if (!ctx.playerMoving) {
            long now = System.currentTimeMillis();
            if (now - lastWalkTime >= WALK_COOLDOWN_MS) {
                lastWalkTime = now;
                log.info("[ScanTask] Walking to dig spot ({},{},{}) dist={}",
                        ctx.hintArrowX, ctx.hintArrowY, ctx.hintArrowPlane, dist);
                ctx.nav.walkTo(ctx.hintArrowX, ctx.hintArrowY);
            }
        }

        return 600;
    }

    /**
     * Walk toward the best position to narrow down candidates.
     * Uses the solver to compute probe targets with directional reasoning.
     */
    private int navigateTowardCandidates(long now) {
        int candidateCount = tracker.getCandidateCount();
        if (candidateCount == 0) return 600;

        int[] target = solver.computeNextTarget(ctx.playerX, ctx.playerY, observationLog, tracker);
        if (target == null) return 600;

        int dist = Math.max(Math.abs(ctx.playerX - target[0]), Math.abs(ctx.playerY - target[1]));
        if (dist <= 3) {
            // Already near target — wait for more observations
            return 600;
        }

        // Store for future pathfinder consumption
        pendingWalkTarget = target;
        lastWalkTime = now;

        log.info("[ScanTask] Navigating to ({},{}) — phase={}, heading={}, {} candidates, dist={}",
                target[0], target[1], solver.getPhase().label(),
                solver.getCurrentHeadingLabel(), candidateCount, dist);
        ctx.logAction(String.format("SCAN [%s %s]: Walking to (%d,%d) — %d candidates, dist=%d",
                solver.getPhase().label(), solver.getCurrentHeadingLabel(),
                target[0], target[1], candidateCount, dist));

        // TODO: Replace with custom non-blocking pathfinder from E:\Desktop\Projects\Tools
        ctx.nav.walkTo(target[0], target[1]);

        return (int) ctx.pace.delay("walk");
    }

    /**
     * Called when the scanner interface closes. Reset state for next scan.
     */
    public void onScannerClosed() {
        if (tracker.isTracking()) {
            ctx.logAction("SCAN: Scanner closed — resetting tracker");
            tracker.reset();
            solver.reset();
            observationLog.clear();
            pendingWalkTarget = null;
            regionIdentified = false;
        }
    }

    /** Expose solver for UI rendering. */
    public ScanNavigationSolver getSolver() { return solver; }

    /** Expose observation log for UI rendering. */
    public ScanObservationLog getObservationLog() { return observationLog; }

    /** Get the pending walk target (for future pathfinder integration). */
    public int[] getPendingWalkTarget() { return pendingWalkTarget; }
}
