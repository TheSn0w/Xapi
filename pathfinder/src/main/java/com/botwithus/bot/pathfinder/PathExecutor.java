package com.botwithus.bot.pathfinder;

import com.botwithus.bot.api.GameAPI;
import com.botwithus.bot.api.entities.SceneObjects;
import com.botwithus.bot.api.entities.SceneObject;
import com.botwithus.bot.api.model.GameAction;
import com.botwithus.bot.api.model.LocalPlayer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Non-blocking path executor with walk-ahead pipelining and early traversal probing.
 *
 * <h3>Key behaviours</h3>
 * <ul>
 *   <li>Queues walk actions ~18 tiles ahead along the path.</li>
 *   <li>Walk targets are NOT clamped at interact steps — the player keeps moving
 *       toward (and sometimes past) interaction points while the executor probes
 *       the object state from ~12 tiles away.</li>
 *   <li>Doors/gates: if their current option is already "Close" (meaning they are
 *       open), the interaction is skipped entirely and the player walks through.</li>
 *   <li>Stiles/stairs/agility: clicked as soon as the object is queryable (~10 tiles),
 *       the game auto-walks the player and plays the animation.</li>
 *   <li>After any interaction wait completes the next walk-ahead is queued immediately
 *       so the player never visibly pauses.</li>
 * </ul>
 *
 * <h3>States</h3>
 * <pre>
 * IDLE → WALKING → (probe/interact inline) → WAIT_INTERACTION → WALKING → ... → ARRIVED
 * </pre>
 */
public final class PathExecutor {

    private static final Logger log = LoggerFactory.getLogger(PathExecutor.class);

    private static final int WALK_ACTION = 23;

    // ── Tuning constants ─────────────────────────────────────────

    /** Walk-ahead range: randomised per click within [MIN, MAX]. */
    private static final int WALK_AHEAD_MIN = 14;
    private static final int WALK_AHEAD_MAX = 18;

    /** Probe range: randomised per interaction within [MIN, MAX]. */
    private static final int currentProbeRange_MIN = 9;
    private static final int currentProbeRange_MAX = 13;

    /** Arrival distance: randomised per path within [MIN, MAX]. */
    private static final int currentArrivalDist_MIN = 2;
    private static final int currentArrivalDist_MAX = 4;

    /** Manhattan distance used by advanceCursor to skip passed tiles. */
    private static final int CURSOR_SNAP_DIST = 5;

    /**
     * Re-queue range: the next walk click fires after the player has moved
     * a random number of tiles in [REQUEUE_MIN, REQUEUE_MAX] since the last
     * click. Varied per click to look human.
     */
    private static final int REQUEUE_MIN = 9;
    private static final int REQUEUE_MAX = 14;

    // ── Timeouts ─────────────────────────────────────────────────

    private static final long INTERACTION_TIMEOUT_MS = 15_000;
    private static final long WALK_TIMEOUT_MS = 30_000;
    /** Min ms between walk clicks: randomised per click within [MIN, MAX]. */
    private static final long WALK_CLICK_GAP_MIN_MS = 400;
    private static final long WALK_CLICK_GAP_MAX_MS = 800;

    // ── Types that use option-change detection instead of animation ──
    private static boolean isDoorLike(Transition.Type t) {
        return t == Transition.Type.DOOR || t == Transition.Type.GATE;
    }

    // ── State ────────────────────────────────────────────────────

    public enum State { IDLE, WALKING, WAIT_INTERACTION, ARRIVED, FAILED }

    private final java.util.concurrent.ThreadLocalRandom rng = java.util.concurrent.ThreadLocalRandom.current();

    private State state = State.IDLE;
    private GameAPI api;
    private long executionStartTime;
    private long stateStartTime;
    private long lastWalkQueueTime;
    private long currentClickGapMs;       // randomised per click
    private int currentRequeueThreshold;  // randomised per click
    private int currentWalkAhead;         // randomised per click
    private int currentProbeRange;        // randomised per interaction
    private int currentArrivalDist;       // randomised per path

    private List<PathStep> steps;
    private int cursor;
    private int walkTargetX, walkTargetY;
    private int nextInteractIdx;

    // Stuck detection: when the player repeatedly stops at the same tile,
    // switch to short walk-ahead (next 2-3 steps) to follow the path through walls.
    private int stuckX, stuckY;
    private int stuckCount;

    // Interaction counter — tracks total obj.interact() calls for debugging
    private int interactCallCount;

    // Position where the last walk click was issued — used to measure distance traveled
    private int walkClickX, walkClickY;

    // Interaction wait tracking
    private boolean animationSeen;
    private PathStep.Interact currentInteract;
    private String preInteractOption;  // option text before we clicked (for door-state detection)
    private int preInteractTypeId;     // typeId of the object we clicked (for door swap detection)
    private boolean doorSkipLogged;    // avoids spamming "already open" every tick
    private boolean approachedInteraction; // true once player has been near the interaction point
    private int preInteractPlane;         // player plane before interaction (for staircase detection)

    // Last interaction exit — used to prevent immediately probing the next object
    private int lastInteractExitX, lastInteractExitY;
    // Cooldown after plane-changing interactions — game needs time to load the new plane
    private static final long PLANE_CHANGE_COOLDOWN_MIN_MS = 1200;
    private static final long PLANE_CHANGE_COOLDOWN_MAX_MS = 2000;
    private long planeChangeCooldownUntil;

    // ── UI log buffer (thread-safe, read by render thread) ───────
    private static final int MAX_LOG_LINES = 200;
    private final CopyOnWriteArrayList<String> logBuffer = new CopyOnWriteArrayList<>();
    public List<String> getLogBuffer() { return logBuffer; }
    public void clearLog() { logBuffer.clear(); }

    private void emit(String level, String msg) {
        String line = ts() + " " + level + " " + msg;
        if ("WARN".equals(level)) log.warn(line); else log.info(line);
        logBuffer.add(line);
        while (logBuffer.size() > MAX_LOG_LINES) logBuffer.removeFirst();
    }
    private void info(String msg) { emit("INFO", msg); }
    private void warn(String msg) { emit("WARN", msg); }

    // ── Public API ───────────────────────────────────────────────

    public State getState() { return state; }
    public boolean isDone() { return state == State.ARRIVED || state == State.FAILED; }
    public boolean isActive() { return state != State.IDLE && !isDone(); }
    public int getCurrentSegmentIndex() { return cursor; }
    public int getTotalSegments() { return steps != null ? steps.size() : 0; }

    public void start(PathResult result, GameAPI api) {
        this.api = api;
        this.steps = result.steps();
        this.cursor = 0;
        this.executionStartTime = System.currentTimeMillis();
        this.currentInteract = null;
        this.lastWalkQueueTime = 0;
        this.doorSkipLogged = false;
        this.currentArrivalDist = rng.nextInt(currentArrivalDist_MIN, currentArrivalDist_MAX + 1);
        this.stuckCount = 0;
        this.interactCallCount = 0;
        rollClickVariation();
        logBuffer.clear();

        if (steps.isEmpty()) { state = State.ARRIVED; return; }

        LocalPlayer lp = getPlayer();
        PathStep last = steps.getLast();
        info(String.format("START: (%d,%d) -> (%d,%d) | %d steps, %d interactions",
                lp != null ? lp.tileX() : 0, lp != null ? lp.tileY() : 0,
                last.x(), last.y(),
                steps.size(), result.interactionCount()));

        nextInteractIdx = findNextInteract(0);
        // Log ALL interact steps so we can verify plane/option correctness
        for (int i = 0; i < steps.size(); i++) {
            if (steps.get(i) instanceof PathStep.Interact it) {
                info(String.format("  interact step %d: '%s' '%s' at (%d,%d,%d)->(%d,%d) [%s]%s",
                        i, it.objectName(), it.option(),
                        it.x(), it.y(), it.plane(), it.dstX(), it.dstY(), it.type(),
                        i == nextInteractIdx ? " <-- NEXT" : ""));
            }
        }

        queueWalkAhead();
        state = State.WALKING;
        stateStartTime = System.currentTimeMillis();
    }

    public void cancel() {
        info(String.format("CANCELLED step %d/%d", cursor, steps != null ? steps.size() : 0));
        state = State.IDLE; steps = null;
    }

    public int tick() {
        if (api == null || isDone() || state == State.IDLE) return 600;
        return switch (state) {
            case WALKING -> tickWalking();
            case WAIT_INTERACTION -> tickWaitInteraction();
            default -> 600;
        };
    }

    // ═════════════════════════════════════════════════════════════
    //  WALKING — the main movement loop
    // ═════════════════════════════════════════════════════════════

    private int tickWalking() {
        long now = System.currentTimeMillis();
        long stateElapsed = now - stateStartTime;
        LocalPlayer lp = getPlayer();
        if (lp == null) return 600;

        int px = lp.tileX(), py = lp.tileY();
        advanceCursor(px, py);

        // ── Done? ────────────────────────────────────────────────
        if (cursor >= steps.size()) {
            PathStep last = steps.getLast();
            int distToFinal = Math.abs(px - last.x()) + Math.abs(py - last.y());
            if (distToFinal <= currentArrivalDist) {
                info("ARRIVED at destination. Player: " + playerStr(lp));
                state = State.ARRIVED;
                return 300;
            }
            // Cursor skipped past remaining steps but player isn't near destination.
            // Walk to final target directly.
            info(String.format("WALK: cursor past end but distToFinal=%d, walking to (%d,%d). Player: %s",
                    distToFinal, last.x(), last.y(), playerStr(lp)));
            walkTargetX = last.x();
            walkTargetY = last.y();
            api.queueAction(new GameAction(WALK_ACTION, 0, walkTargetX, walkTargetY));
            cursor = steps.size() - 1; // back up so we keep ticking
        }

        // ── Probe upcoming interaction while still moving ────────
        if (nextInteractIdx >= 0 && nextInteractIdx < steps.size()) {
            var it = (PathStep.Interact) steps.get(nextInteractIdx);
            int distToObj = Math.abs(px - it.x()) + Math.abs(py - it.y());

            // Player is past the transition exit — they walked through, advance
            int distToDst = Math.abs(px - it.dstX()) + Math.abs(py - it.dstY());
            int distToSrc = Math.abs(px - it.x()) + Math.abs(py - it.y());
            if (distToDst < distToSrc && distToDst <= currentArrivalDist) {
                info(String.format("PROBE: player past '%s' at (%d,%d,%d), advancing",
                        it.objectName(), it.x(), it.y(), it.plane()));
                cursor = nextInteractIdx + 1;
                nextInteractIdx = findNextInteract(cursor);
            } else if (distToObj <= currentProbeRange
                    && now >= planeChangeCooldownUntil
                    && (cursor == nextInteractIdx || canProbeNextInteract(px, py))) {
                // Wait for plane-change cooldown before probing.
                // Bypass displacement check when the interact is the very next step
                // (back-to-back transitions like ladder→ladder with no walk between).
                // Only probe when the player has moved away from the last interaction
                // exit. Prevents firing the next stile/gate while still standing at
                // the previous one's exit tile.
                ProbeResult probe = probeObject(it);

                switch (probe) {
                    case ALREADY_OPEN -> {
                        // Door/gate is open — advance past it and walk through.
                        if (!doorSkipLogged) {
                            info(String.format("PROBE: '%s' at (%d,%d,%d) already open, walking through",
                                    it.objectName(), it.x(), it.y(), it.plane()));
                            doorSkipLogged = true;
                        }
                        cursor = nextInteractIdx + 1;
                        nextInteractIdx = findNextInteract(cursor);
                        doorSkipLogged = false;
                        queueWalkAhead();
                    }
                    case NEEDS_INTERACT -> {
                        doorSkipLogged = false;
                        // Object needs interaction — send it now, game auto-walks
                        sendInteract(it, lp);
                        return 300;
                    }
                    case NOT_VISIBLE -> {
                        // Object not loaded yet — keep walking closer
                        info(String.format("PROBE: '%s' '%s' at (%d,%d,%d) NOT_VISIBLE dist=%d",
                                it.objectName(), it.option(), it.x(), it.y(), it.plane(), distToObj));
                    }
                }
            }
        }

        // ── Keep walk target fresh ───────────────────────────────
        // Re-queue when the player has covered ~REQUEUE_AFTER_TILES since the
        // last click. This fires BEFORE the player reaches the old target, so
        // the game client seamlessly redirects and the player never stops.
        int distToTarget = Math.abs(px - walkTargetX) + Math.abs(py - walkTargetY);
        int tilesSinceClick = Math.abs(px - walkClickX) + Math.abs(py - walkClickY);
        boolean gapOk = (now - lastWalkQueueTime) > currentClickGapMs;

        if (gapOk) {
            if (tilesSinceClick >= currentRequeueThreshold && distToTarget > currentArrivalDist) {
                // Covered enough ground and target is still far — push further ahead
                stuckCount = 0; // player is making progress
                queueWalkAhead();
                stateStartTime = now; // reset timeout — player is making progress
            } else if (!lp.isMoving() && stateElapsed > 1200 && distToTarget > currentArrivalDist) {
                // Player stopped but hasn't reached target — blocked or misclick.
                // Track stuck position: if the player keeps stopping at the same tile,
                // the game client can't reach the walk-ahead target (walls in the way).
                // Switch to short walk-ahead (next few path steps) to follow the A*
                // route step-by-step around obstacles.
                if (px == stuckX && py == stuckY) {
                    stuckCount++;
                } else {
                    stuckX = px; stuckY = py; stuckCount = 1;
                }
                if (stuckCount >= 2) {
                    // Walk to the next 2-3 path steps instead of jumping far ahead
                    info(String.format("WALK: stuck at (%d,%d) x%d, short walk-ahead. Player: %s",
                            px, py, stuckCount, playerStr(lp)));
                    queueShortWalkAhead();
                } else {
                    info(String.format("WALK: stopped at (%d,%d) target=(%d,%d) dist=%d. Re-queue.",
                            px, py, walkTargetX, walkTargetY, distToTarget));
                    queueWalkAhead();
                }
                stateStartTime = now;
            } else if (!lp.isMoving() && distToTarget <= currentArrivalDist && cursor < steps.size()) {
                // Player arrived at walk target — advance cursor and push ahead
                advanceCursor(px, py);
                if (cursor >= steps.size()) {
                    info("ARRIVED at destination. Player: " + playerStr(lp));
                    state = State.ARRIVED;
                    return 300;
                }
                queueWalkAhead();
                stateStartTime = now;
            }
        }

        // ── Periodic progress ────────────────────────────────────
        if (stateElapsed > 3000 && stateElapsed % 5000 < 300) {
            info(String.format("WALK: step %d/%d target=(%d,%d) distTarget=%d sinceClick=%d Player: %s",
                    cursor, steps.size(), walkTargetX, walkTargetY, distToTarget, tilesSinceClick, playerStr(lp)));
        }

        if (stateElapsed > WALK_TIMEOUT_MS) {
            warn("WALK: TIMEOUT. Player: " + playerStr(lp));
            state = State.FAILED;
        }

        return 300; // tick at 300ms for responsive probing
    }

    // ═════════════════════════════════════════════════════════════
    //  Object probing — check state before committing to interact
    // ═════════════════════════════════════════════════════════════

    private enum ProbeResult { ALREADY_OPEN, NEEDS_INTERACT, NOT_VISIBLE }

    /**
     * Queries the scene for the interaction object and decides what to do.
     * For doors/gates: checks if the current option is "Close" (already open).
     * For everything else: returns NEEDS_INTERACT if the object is visible.
     */
    private ProbeResult probeObject(PathStep.Interact interact) {
        // Find by option — handles multiple objects with the same name but
        // different options (staircases up/down, gates open/close).
        SceneObject obj = findObjectByOption(interact);

        if (obj == null && isDoorLike(interact.type())) {
            // No object with the required option — gate/door is already open
            if (!doorSkipLogged) {
                info(String.format("PROBE: no object with '%s' near (%d,%d,%d) — already open",
                        interact.option(), interact.x(), interact.y(), interact.plane()));
            }
            return ProbeResult.ALREADY_OPEN;
        }

        if (obj == null) {
            // Fall back to name-based search
            obj = findObjectForInteract(interact);
        }

        if (obj == null) return ProbeResult.NOT_VISIBLE;

        return ProbeResult.NEEDS_INTERACT;
    }

    // ═════════════════════════════════════════════════════════════
    //  Interaction dispatch
    // ═════════════════════════════════════════════════════════════

    private void sendInteract(PathStep.Interact interact, LocalPlayer lp) {
        // Always find by option first — handles cases where multiple objects share
        // the same name but different options (e.g. staircase up vs down, gate open vs close).
        // Fall back to name-based search if no option match found.
        SceneObject obj = findObjectByOption(interact);
        if (obj == null) obj = findObjectForInteract(interact);

        if (obj == null) {
            warn(String.format("INTERACT: '%s' not visible near (%d,%d,%d), skipping",
                    interact.objectName(), interact.x(), interact.y(), interact.plane()));
            cursor = nextInteractIdx + 1;
            nextInteractIdx = findNextInteract(cursor);
            queueWalkAhead();
            return;
        }

        // Snapshot the object's typeId and live option before interacting
        preInteractTypeId = obj.typeId();
        List<String> opts = liveOptions(obj);
        preInteractOption = interact.option();
        if (opts != null && !opts.isEmpty()) {
            for (String o : opts) {
                if (o != null && !o.isEmpty()) { preInteractOption = o; break; }
            }
        }

        info(String.format("INTERACT: '%s' at (%d,%d) scenePlane=%d wantPlane=%d typeId=%d option='%s' player=(%d,%d,%d) dist=%d [%s]",
                obj.name(), obj.tileX(), obj.tileY(), obj.plane(), interact.plane(),
                obj.typeId(), preInteractOption,
                lp.tileX(), lp.tileY(), lp.plane(),
                Math.abs(lp.tileX() - obj.tileX()) + Math.abs(lp.tileY() - obj.tileY()),
                interact.type()));

        interactCallCount++;
        boolean success = obj.interact(interact.option());
        if (!success) {
            warn(String.format("INTERACT #%d: interact() returned false for '%s' '%s' typeId=%d opts=%s",
                    interactCallCount, interact.objectName(), interact.option(), obj.typeId(), liveOptions(obj)));
            state = State.FAILED;
            return;
        }

        currentInteract = interact;
        animationSeen = false;
        approachedInteraction = false;
        preInteractPlane = lp.plane();
        info(String.format("INTERACT #%d: sent. src=(%d,%d,%d) dst=(%d,%d) type=%s",
                interactCallCount, interact.x(), interact.y(), interact.plane(), interact.dstX(), interact.dstY(), interact.type()));

        state = State.WAIT_INTERACTION;
        stateStartTime = System.currentTimeMillis();
    }

    // ═════════════════════════════════════════════════════════════
    //  WAIT_INTERACTION — wait for traversal to complete
    // ═════════════════════════════════════════════════════════════

    private int tickWaitInteraction() {
        long stateElapsed = System.currentTimeMillis() - stateStartTime;
        LocalPlayer lp = getPlayer();
        if (lp == null) return 300;

        boolean idle = lp.animationId() == -1 && !lp.isMoving();

        // Track animation
        if (lp.animationId() != -1 && !animationSeen) {
            info(String.format("WAIT: anim=%d Player: %s", lp.animationId(), playerStr(lp)));
            animationSeen = true;
        }

        // ── Door/gate: multi-signal detection ────────────────────
        if (isDoorLike(currentInteract.type())) {
            // Query the EXACT tile — not a wide radius — so we don't
            // accidentally find a neighbouring closed gate
            SceneObject obj = findObjectAtTile(currentInteract);

            // Signal 1: object vanished entirely — door opened and despawned
            if (obj == null) {
                info("WAIT-DOOR: object vanished, treating as opened");
                onInteractionComplete(lp);
                return 300;
            }

            // Signal 2: the object at this tile swapped to a different typeId.
            // RS3 gates/doors: the closed-gate object despawns and an open-gate
            // object spawns with a different typeId but the same name "Gate".
            if (obj.typeId() != preInteractTypeId) {
                info(String.format("WAIT-DOOR: object swapped typeId %d -> %d, gate opened",
                        preInteractTypeId, obj.typeId()));
                onInteractionComplete(lp);
                return 300;
            }

            // Signal 3: live transform options changed (Open → Close)
            List<String> opts = liveOptions(obj);
            String currentOption = firstNonEmpty(opts);
            if (currentOption != null && !currentOption.equalsIgnoreCase(preInteractOption)) {
                info(String.format("WAIT-DOOR: option changed '%s' -> '%s'",
                        preInteractOption, currentOption));
                onInteractionComplete(lp);
                return 300;
            }

            // Signal 4: varbit transform resolved to a different ID
            if (obj.canTransform()) {
                int resolvedId = obj.resolveTransformId();
                if (resolvedId != obj.typeId()) {
                    info(String.format("WAIT-DOOR: varbit transform %d -> %d",
                            obj.typeId(), resolvedId));
                    onInteractionComplete(lp);
                    return 300;
                }
            }

            // Track whether the player has actually reached the gate.
            // This prevents Signals 5/6 from firing while still approaching from far away.
            {
                int srcDist = Math.abs(lp.tileX() - currentInteract.x())
                        + Math.abs(lp.tileY() - currentInteract.y());
                if (!approachedInteraction && srcDist <= currentArrivalDist + 1) {
                    approachedInteraction = true;
                }

                // Signal 5: player has moved well past the interaction point AFTER
                // having been near it. If the gate was already open, the game walked
                // us through it and we're now far past both src and dst.
                if (approachedInteraction && srcDist > currentArrivalDist + 2) {
                    info(String.format("WAIT-DOOR: player moved past gate srcDist=%d Player: %s",
                            srcDist, playerStr(lp)));
                    onInteractionComplete(lp);
                    return 300;
                }

                // Signal 6: player arrived at the gate, is idle, and we've been
                // waiting 2+ seconds. The interact was sent, the game walked us
                // to the gate — if the gate hadn't opened the player would still
                // be trying to path through. Object queries may return stale data
                // so we can't rely solely on typeId/option signals.
                if (approachedInteraction && idle && stateElapsed > 2000) {
                    info(String.format("WAIT-DOOR: player idle at gate srcDist=%d after %dms, treating as opened. Player: %s",
                            srcDist, stateElapsed, playerStr(lp)));
                    onInteractionComplete(lp);
                    return 300;
                }
            }

            // Diagnostic every ~3s
            if (stateElapsed > 2000 && stateElapsed % 3000 < 300) {
                int srcDist = Math.abs(lp.tileX() - currentInteract.x())
                        + Math.abs(lp.tileY() - currentInteract.y());
                info(String.format("WAIT-DOOR: preTypeId=%d curTypeId=%d preOpt='%s' curOpt='%s' srcDist=%d Player: %s",
                        preInteractTypeId, obj.typeId(), preInteractOption, currentOption, srcDist, playerStr(lp)));
            }

            // Player still walking TO the door (not past it) — reset timeout
            if (lp.isMoving() && !animationSeen) {
                int srcDist = Math.abs(lp.tileX() - currentInteract.x())
                        + Math.abs(lp.tileY() - currentInteract.y());
                // Only reset if player is still approaching (getting closer)
                if (srcDist > currentArrivalDist) {
                    stateStartTime = System.currentTimeMillis();
                }
            }
        }
        // ── Stile/stairs/agility: animation + plane change detection ──
        else {
            // Track approach for non-door interactions too
            if (!approachedInteraction) {
                int srcDist = Math.abs(lp.tileX() - currentInteract.x())
                        + Math.abs(lp.tileY() - currentInteract.y());
                if (srcDist <= currentArrivalDist + 1) approachedInteraction = true;
            }

            // Signal: player changed planes (staircase/ladder completion).
            // Stairs have no animation — the plane change IS the signal.
            if (lp.plane() != preInteractPlane) {
                long cooldown = rng.nextLong(PLANE_CHANGE_COOLDOWN_MIN_MS, PLANE_CHANGE_COOLDOWN_MAX_MS + 1);
                planeChangeCooldownUntil = System.currentTimeMillis() + cooldown;
                info(String.format("WAIT: plane changed %d -> %d, cooldown %dms. Player: %s",
                        preInteractPlane, lp.plane(), cooldown, playerStr(lp)));
                onInteractionComplete(lp);
                return 300;
            }

            if (animationSeen && idle) {
                info("WAIT: animation complete. Player: " + playerStr(lp));
                onInteractionComplete(lp);
                return 300;
            }

            // Player crossed to dst side without us catching the animation
            if (idle && currentInteract != null) {
                int dstDist = Math.abs(lp.tileX() - currentInteract.dstX())
                        + Math.abs(lp.tileY() - currentInteract.dstY());
                int srcDist = Math.abs(lp.tileX() - currentInteract.x())
                        + Math.abs(lp.tileY() - currentInteract.y());

                if (dstDist < srcDist && dstDist <= currentArrivalDist) {
                    info(String.format("WAIT: crossed to dst. dstDist=%d srcDist=%d Player: %s",
                            dstDist, srcDist, playerStr(lp)));
                    onInteractionComplete(lp);
                    return 300;
                }
            }

            // Player auto-walking to object — reset timeout
            if (lp.isMoving() && !animationSeen) {
                stateStartTime = System.currentTimeMillis();
            }
        }

        // Periodic log
        if (stateElapsed > 2000 && stateElapsed % 3000 < 300) {
            info(String.format("WAIT: %s animSeen=%s Player: %s",
                    currentInteract.type(), animationSeen, playerStr(lp)));
        }

        if (stateElapsed > INTERACTION_TIMEOUT_MS) {
            warn(String.format("WAIT: TIMEOUT %dms type=%s animSeen=%s Player: %s",
                    stateElapsed, currentInteract.type(), animationSeen, playerStr(lp)));
            onInteractionComplete(lp);
        }

        return 300;
    }

    private void onInteractionComplete(LocalPlayer lp) {
        info(String.format("DONE: '%s' complete. Player: %s",
                currentInteract.objectName(), playerStr(lp)));

        // Record exit position — probe won't fire next interaction until
        // the player has moved away from here
        lastInteractExitX = lp.tileX();
        lastInteractExitY = lp.tileY();

        int completedInteractIdx = nextInteractIdx;
        cursor = completedInteractIdx + 1;
        advanceCursor(lp.tileX(), lp.tileY());
        nextInteractIdx = findNextInteract(cursor);
        currentInteract = null;

        if (cursor >= steps.size()) {
            PathStep last = steps.getLast();
            int distToFinal = Math.abs(lp.tileX() - last.x()) + Math.abs(lp.tileY() - last.y());
            if (distToFinal == 0) {
                info("ARRIVED after interaction. Player: " + playerStr(lp));
                state = State.ARRIVED;
                return;
            }
            // Player stopped after interaction but still has distance to cover.
            // Walk to final destination — don't use arrival tolerance here because
            // the player has stopped and needs to actively walk the remaining tiles.
            info(String.format("POST-INTERACT: walking to final dest (%d,%d) distToFinal=%d. Player: %s",
                    last.x(), last.y(), distToFinal, playerStr(lp)));
            cursor = completedInteractIdx + 1;
        }

        if (nextInteractIdx >= 0) {
            var it = (PathStep.Interact) steps.get(nextInteractIdx);
            info(String.format("NEXT: interact step %d '%s' at (%d,%d,%d) [%s]",
                    nextInteractIdx, it.objectName(), it.x(), it.y(), it.plane(), it.type()));
        }

        queueWalkAhead();
        state = State.WALKING;
        stateStartTime = System.currentTimeMillis();
    }

    // ═════════════════════════════════════════════════════════════
    //  Walk-ahead: queue walk to a point well ahead on the path
    // ═════════════════════════════════════════════════════════════

    private void queueWalkAhead() {
        if (cursor >= steps.size()) return;

        // Walk up to currentWalkAhead along the path.
        // We do NOT stop at interact steps — the probe/interact logic handles them
        // while the player is still moving. The only exception is if the interact
        // step is the very next step (cursor == nextInteractIdx) and we haven't
        // probed it yet — in that case stopping is fine because tickWalking will
        // handle it next tick.
        int targetIdx = cursor;
        int tilesAhead = 0;

        LocalPlayer walkLp = getPlayer();
        int currentPlane = walkLp != null ? walkLp.plane() : 0;

        for (int i = cursor; i < steps.size() && tilesAhead < currentWalkAhead; i++) {
            PathStep step = steps.get(i);
            if (step instanceof PathStep.Interact) {
                // Stop before ALL interactions. The probe/interact logic handles
                // them while the player approaches — doors get opened (or skipped
                // if already open), stairs get clicked, etc. Walking past a closed
                // door causes the player to stop at the wall and look unnatural.
                break;
            }
            if (step instanceof PathStep.WalkTo wt) {
                // Stop if this walk step is on a different plane
                if (wt.plane() != currentPlane) break;
                targetIdx = i;
                tilesAhead++;
            }
        }

        PathStep target = steps.get(targetIdx);

        // Don't send a walk action to an interact step's coordinates — clicking on an
        // object tile (ladder, staircase) can trigger an unintended game interaction.
        // The probe/interact logic will handle it on the next tick.
        if (target instanceof PathStep.Interact) return;

        walkTargetX = target.x();
        walkTargetY = target.y();
        lastWalkQueueTime = System.currentTimeMillis();
        rollClickVariation(); // re-roll all per-click variation

        LocalPlayer lp = getPlayer();
        if (lp != null) {
            walkClickX = lp.tileX();
            walkClickY = lp.tileY();
        }
        info(String.format("WALK-AHEAD: (%d,%d) step %d/%d ahead=%d Player: %s",
                walkTargetX, walkTargetY, targetIdx, steps.size(), tilesAhead, playerStr(lp)));

        api.queueAction(new GameAction(WALK_ACTION, 0, walkTargetX, walkTargetY));
    }

    /**
     * Short walk-ahead: walk to the next 2-3 path steps only.
     * Used when the player is stuck — the game client can't reach the far-ahead
     * target because walls block the direct route. Short walk-ahead follows the
     * A* path step-by-step around obstacles.
     */
    private void queueShortWalkAhead() {
        if (cursor >= steps.size()) return;

        LocalPlayer walkLp = getPlayer();
        int currentPlane = walkLp != null ? walkLp.plane() : 0;

        int targetIdx = cursor;
        int tilesAhead = 0;
        int maxShort = 3; // just a few steps

        for (int i = cursor; i < steps.size() && tilesAhead < maxShort; i++) {
            PathStep step = steps.get(i);
            if (step instanceof PathStep.Interact) break;
            if (step instanceof PathStep.WalkTo wt) {
                if (wt.plane() != currentPlane) break;
                targetIdx = i;
                tilesAhead++;
            }
        }

        PathStep target = steps.get(targetIdx);
        walkTargetX = target.x();
        walkTargetY = target.y();
        lastWalkQueueTime = System.currentTimeMillis();

        LocalPlayer lp = getPlayer();
        if (lp != null) { walkClickX = lp.tileX(); walkClickY = lp.tileY(); }
        info(String.format("WALK-SHORT: (%d,%d) step %d/%d ahead=%d Player: %s",
                walkTargetX, walkTargetY, targetIdx, steps.size(), tilesAhead, playerStr(lp)));

        api.queueAction(new GameAction(WALK_ACTION, 0, walkTargetX, walkTargetY));
    }

    // ═════════════════════════════════════════════════════════════
    //  Object query helper — finds the closest matching object to
    //  the transition coordinates (not to the player)
    // ═════════════════════════════════════════════════════════════

    /**
     * Finds the object for initial approach — wide radius, closest to transition tile.
     * Used by probeObject and sendInteract when the player is still far away.
     */
    private SceneObject findObjectForInteract(PathStep.Interact interact) {
        SceneObjects objects = new SceneObjects(api);
        int tx = interact.x(), ty = interact.y();

        // Try plane-filtered first (avoids wrong-plane objects with same name)
        List<SceneObject> candidates = objects.query()
                .named(interact.objectName())
                .visible()
                .onPlane(interact.plane())
                .within(tx, ty, 12)
                .all();

        // Fallback: RS3 scene objects on upper floors often report plane 0
        if (candidates.isEmpty()) {
            candidates = objects.query()
                    .named(interact.objectName())
                    .visible()
                    .within(tx, ty, 12)
                    .all();
        }

        SceneObject best = null;
        int bestDist = Integer.MAX_VALUE;
        for (SceneObject c : candidates) {
            int dist = Math.abs(c.tileX() - tx) + Math.abs(c.tileY() - ty);
            if (dist < bestDist) { bestDist = dist; best = c; }
        }
        return best;
    }

    /**
     * Finds the object at the EXACT transition tile (radius 0).
     * Used by the wait loop to check if the specific object we interacted with
     * has changed, without accidentally finding a neighbouring gate/door.
     * Returns null if no object with that name is on that exact tile.
     */
    private SceneObject findObjectAtTile(PathStep.Interact interact) {
        SceneObjects objects = new SceneObjects(api);
        int tx = interact.x(), ty = interact.y();

        // Try plane-filtered first, fall back without (RS3 scene plane quirk)
        List<SceneObject> candidates = objects.query()
                .named(interact.objectName())
                .visible()
                .onPlane(interact.plane())
                .within(tx, ty, 1)
                .all();
        if (candidates.isEmpty()) {
            candidates = objects.query()
                    .named(interact.objectName())
                    .visible()
                    .within(tx, ty, 1)
                    .all();
        }

        for (SceneObject c : candidates) {
            if (c.tileX() == tx && c.tileY() == ty) return c;
        }
        // Fallback: closest within 1 tile (object might span multiple tiles)
        SceneObject best = null;
        int bestDist = Integer.MAX_VALUE;
        for (SceneObject c : candidates) {
            int dist = Math.abs(c.tileX() - tx) + Math.abs(c.tileY() - ty);
            if (dist < bestDist) { bestDist = dist; best = c; }
        }
        return best;
    }

    /**
     * Finds the closest object near the transition tile that has the expected
     * option (e.g. "Open" for a closed gate). Checks both base and live
     * (varbit-resolved) options. Returns null if no object with that option
     * exists — meaning the door/gate is already open.
     */
    private SceneObject findObjectByOption(PathStep.Interact interact) {
        SceneObjects objects = new SceneObjects(api);
        int tx = interact.x(), ty = interact.y();
        int dx = interact.dstX(), dy = interact.dstY();
        String wantedOption = interact.option();

        // Tight radius for doors/gates (cow pen has gates 1 tile apart).
        // Wider radius for staircases/ladders (transition coords may be slightly off,
        // and multiple staircase objects can share the same tile).
        // Option text ("Climb-up" vs "Climb-down", "Open" vs "Close") is the
        // primary discriminator. No plane filter here — RS3 scene objects on upper
        // floors often report plane 0 in the engine regardless of logical floor.
        int radius = isDoorLike(interact.type()) ? 1 : 5;
        List<SceneObject> candidates = objects.query()
                .visible()
                .within(tx, ty, radius)
                .all();

        // Two-pass: prefer objects on the correct scene plane, fall back to any plane.
        // At locations like stacked ladders, the same option exists on every plane —
        // interacting with the wrong plane's object does nothing.
        SceneObject best = null;
        int bestDist = Integer.MAX_VALUE;
        boolean bestOnPlane = false;
        int wantedPlane = interact.plane();

        for (SceneObject c : candidates) {
            boolean hasOption = hasOptionIn(c.getOptions(), wantedOption)
                    || hasOptionIn(liveOptions(c), wantedOption);
            if (!hasOption) continue;

            boolean onPlane = c.plane() == wantedPlane;
            int distSrc = Math.abs(c.tileX() - tx) + Math.abs(c.tileY() - ty);
            int distDst = Math.abs(c.tileX() - dx) + Math.abs(c.tileY() - dy);
            int dist = Math.min(distSrc, distDst);

            // Prefer correct plane, then closest distance
            if (onPlane && !bestOnPlane) {
                best = c; bestDist = dist; bestOnPlane = true;
            } else if (onPlane == bestOnPlane && dist < bestDist) {
                best = c; bestDist = dist; bestOnPlane = onPlane;
            }
        }
        return best;
    }

    // ═════════════════════════════════════════════════════════════
    //  Cursor management
    // ═════════════════════════════════════════════════════════════

    private void advanceCursor(int px, int py) {
        while (cursor < steps.size()) {
            PathStep step = steps.get(cursor);
            if (step instanceof PathStep.Interact) break;
            int dist = Math.abs(px - step.x()) + Math.abs(py - step.y());
            if (dist > CURSOR_SNAP_DIST) break;
            cursor++;
        }
    }

    private int findNextInteract(int fromIdx) {
        for (int i = fromIdx; i < steps.size(); i++) {
            if (steps.get(i) instanceof PathStep.Interact) return i;
        }
        return -1;
    }

    /** Minimum tiles the player must move from the last interaction exit before probing the next. */
    private static final int MIN_DISPLACEMENT_BEFORE_PROBE = 3;

    /**
     * Returns true if the player has moved far enough from the last interaction
     * exit to safely probe the next one. Prevents chaining interactions without
     * walking between them.
     */
    private boolean canProbeNextInteract(int px, int py) {
        int displacementFromExit = Math.abs(px - lastInteractExitX) + Math.abs(py - lastInteractExitY);
        return displacementFromExit >= MIN_DISPLACEMENT_BEFORE_PROBE;
    }

    /** Re-roll per-click variation: walk-ahead distance, click gap, requeue threshold, probe range. */
    private void rollClickVariation() {
        currentWalkAhead = rng.nextInt(WALK_AHEAD_MIN, WALK_AHEAD_MAX + 1);
        currentClickGapMs = rng.nextLong(WALK_CLICK_GAP_MIN_MS, WALK_CLICK_GAP_MAX_MS + 1);
        currentRequeueThreshold = rng.nextInt(REQUEUE_MIN, REQUEUE_MAX + 1);
        currentProbeRange = rng.nextInt(currentProbeRange_MIN, currentProbeRange_MAX + 1);
    }

    // ═════════════════════════════════════════════════════════════
    //  Helpers
    // ═════════════════════════════════════════════════════════════

    /**
     * Returns the LIVE options for a scene object by resolving its varbit/varp transform.
     * <p>
     * {@code SceneObject.getOptions()} caches the base LocationType and only checks
     * the transform if ALL base options are empty. For doors/gates, the base type
     * has options like "Open" baked in, so the transform is never consulted and the
     * options appear stale even after the door opens.
     * <p>
     * This method forces a fresh transform resolution via RPC, giving the real
     * current options (e.g. "Close" after opening).
     */
    private static List<String> liveOptions(SceneObject obj) {
        if (obj.canTransform()) {
            var resolved = obj.resolveTransform();
            if (resolved != null && resolved.options() != null) {
                return resolved.options();
            }
        }
        return obj.getOptions();
    }

    private static boolean hasOptionIn(List<String> opts, String option) {
        if (opts == null) return false;
        return opts.stream().anyMatch(o -> o != null && o.equalsIgnoreCase(option));
    }

    private static String firstNonEmpty(List<String> opts) {
        if (opts == null) return null;
        for (String o : opts) {
            if (o != null && !o.isEmpty()) return o;
        }
        return null;
    }

    private long elapsed() { return System.currentTimeMillis() - executionStartTime; }

    private String ts() {
        long ms = elapsed();
        return String.format("[%02d:%02d.%03d]", ms / 60000, (ms / 1000) % 60, ms % 1000);
    }

    private String playerStr(LocalPlayer lp) {
        if (lp == null) return "(null)";
        return String.format("(%d,%d,%d) anim=%d moving=%s",
                lp.tileX(), lp.tileY(), lp.plane(), lp.animationId(), lp.isMoving());
    }

    private LocalPlayer getPlayer() {
        try { return api.getLocalPlayer(); } catch (Exception e) { return null; }
    }
}
