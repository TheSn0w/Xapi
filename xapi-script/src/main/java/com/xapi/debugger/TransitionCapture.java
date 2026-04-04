package com.xapi.debugger;

import com.botwithus.bot.api.inventory.ActionTypes;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Locale;

/**
 * State machine that captures transitions from live gameplay.
 *
 * <pre>
 * IDLE → WAITING_MOVE → WAITING_SETTLE → (creates TransitionData) → IDLE
 * </pre>
 *
 * Called from two threads:
 * <ul>
 *   <li>{@link #onInteraction} — from event thread (onActionExecuted)</li>
 *   <li>{@link #pollPosition} — from onLoop thread (~600ms)</li>
 * </ul>
 */
final class TransitionCapture {

    private static final Logger log = LoggerFactory.getLogger(TransitionCapture.class);

    enum State { IDLE, WAITING_MOVE, WAITING_SETTLE }

    private final XapiState state;
    private final MapDebuggerClient client;

    // ── Capture state (accessed from onLoop only after initial set) ──────
    private volatile State captureState = State.IDLE;
    private volatile String pendingEntityName = "";
    private volatile String pendingOptionName = "";
    private volatile int pendingActionId;

    // Source position at time of interaction
    private int srcX, srcY, srcP;

    // For settle detection: last polled position and settle counter
    private int lastPollX, lastPollY, lastPollP;
    private int settleCount;

    // Manual override type (set by startManualCapture, null = auto-classify)
    private String manualOverrideType;

    // Timeout
    private long captureStartTime;
    private static final long CAPTURE_TIMEOUT_MS = 15_000;

    // Consecutive same-position polls needed to consider "settled"
    private static final int SETTLE_THRESHOLD = 2;

    TransitionCapture(XapiState state, MapDebuggerClient client) {
        this.state = state;
        this.client = client;
    }

    // ── Event thread entry point ──���──────────────────────────────────────

    /**
     * Called from onActionExecuted when the player interacts with something.
     * Only records if the action looks like a scene object or NPC interaction.
     */
    void onInteraction(int actionId, int param1, String entityName, String optionName,
                       int playerX, int playerY, int playerPlane) {
        // Skip walk actions and pure item/inventory actions
        if (actionId == 23) return; // WALK
        if (actionId == 1 || actionId == 2) return; // item-on-item, item-use

        // Accept all object (3-6), NPC (7-12), and component (57+) interactions.
        // The state machine will time out harmlessly if no movement occurs.
        if (actionId < 3) return;

        // If already capturing, discard the old one
        if (captureState != State.IDLE) {
            log.debug("Overwriting pending capture (was {} for '{}')", captureState, pendingEntityName);
        }

        this.pendingEntityName = entityName != null ? entityName : "";
        this.pendingOptionName = optionName != null ? optionName : "";
        this.pendingActionId = actionId;
        this.manualOverrideType = null;
        this.srcX = playerX;
        this.srcY = playerY;
        this.srcP = playerPlane;
        this.settleCount = 0;
        this.captureStartTime = System.currentTimeMillis();
        this.captureState = State.WAITING_MOVE;

        log.info("Capture started: '{}' '{}' at ({},{},{})", pendingOptionName, pendingEntityName, srcX, srcY, srcP);
    }

    // ── onLoop thread entry point ────────��───────────────────────────────

    /**
     * Called every onLoop iteration (~600ms) with current player position.
     */
    void pollPosition(int playerX, int playerY, int playerPlane, boolean isMoving) {
        if (captureState == State.IDLE) return;

        long elapsed = System.currentTimeMillis() - captureStartTime;

        // Timeout check
        if (elapsed > CAPTURE_TIMEOUT_MS) {
            log.debug("Capture timed out after {}ms for '{}'", elapsed, pendingEntityName);
            captureState = State.IDLE;
            return;
        }

        switch (captureState) {
            case WAITING_MOVE -> {
                // Check if position changed from source
                if (playerX != srcX || playerY != srcY || playerPlane != srcP) {
                    lastPollX = playerX;
                    lastPollY = playerY;
                    lastPollP = playerPlane;
                    settleCount = isMoving ? 0 : 1;
                    captureState = State.WAITING_SETTLE;
                    log.debug("Position changed, waiting to settle: ({},{},{}) moving={}", playerX, playerY, playerPlane, isMoving);
                }
            }
            case WAITING_SETTLE -> {
                if (playerX == lastPollX && playerY == lastPollY && playerPlane == lastPollP) {
                    if (!isMoving) settleCount++;
                    if (settleCount >= SETTLE_THRESHOLD) {
                        // Player has settled — capture complete
                        completeCapture(playerX, playerY, playerPlane);
                    }
                } else {
                    // Still moving, update last position
                    lastPollX = playerX;
                    lastPollY = playerY;
                    lastPollP = playerPlane;
                    settleCount = isMoving ? 0 : 1;
                }
            }
            default -> {} // IDLE handled above
        }
    }

    // ── Capture completion ────────────��──────────────────────────────────

    private void completeCapture(int dstX, int dstY, int dstP) {
        int dist = Math.abs(dstX - srcX) + Math.abs(dstY - srcY);
        int planeDelta = dstP - srcP;

        String type = (manualOverrideType != null) ? manualOverrideType
                : classifyType(pendingEntityName, pendingOptionName, dist, planeDelta, pendingActionId);
        manualOverrideType = null;
        boolean bidir = isBidirectional(type);
        int cost = (type.equals("DOOR") || type.equals("WALL_PASSAGE")) ? 1 : 2;

        // Destination-only types — source is irrelevant (can be cast from anywhere)
        int finalSrcX = srcX, finalSrcY = srcY, finalSrcP = srcP;
        if (isDstOnly(type)) {
            finalSrcX = 0; finalSrcY = 0; finalSrcP = 0;
        }

        XapiData.TransitionData transition = new XapiData.TransitionData(
                type, finalSrcX, finalSrcY, finalSrcP, dstX, dstY, dstP,
                pendingEntityName, pendingOptionName, cost, bidir,
                System.currentTimeMillis(), "pending"
        );

        log.info("Capture complete: {} '{}' ({},{},{}) -> ({},{},{}) dist={} dPlane={}",
                type, pendingEntityName, srcX, srcY, srcP, dstX, dstY, dstP, dist, planeDelta);

        state.transitionLog.add(transition);

        // Auto-send if enabled
        if (state.transitionAutoCapture) {
            boolean isDuplicate = false;
            try { isDuplicate = client.checkDuplicate(transition); } catch (Exception ignored) {}

            if (isDuplicate) {
                log.info("Duplicate transition skipped: {} at ({},{})", type, srcX, srcY);
                // Update status to "duplicate"
                replaceLastStatus("duplicate");
            } else {
                boolean sent = client.sendTransition(transition);
                replaceLastStatus(sent ? "sent" : "failed");
            }
        }

        captureState = State.IDLE;
    }

    /**
     * Replace the status of the last entry in the transition log.
     * Records are immutable so we swap the entry.
     */
    private void replaceLastStatus(String newStatus) {
        if (state.transitionLog.isEmpty()) return;
        int idx = state.transitionLog.size() - 1;
        XapiData.TransitionData old = state.transitionLog.get(idx);
        state.transitionLog.set(idx, new XapiData.TransitionData(
                old.type(), old.srcX(), old.srcY(), old.srcP(),
                old.dstX(), old.dstY(), old.dstP(),
                old.name(), old.option(), old.cost(), old.bidir(),
                old.timestamp(), newStatus
        ));
    }

    // ── Type classification ────────���─────────────────────────────────────

    static String classifyType(String entityName, String optionName, int manhattanDist, int planeDelta) {
        return classifyType(entityName, optionName, manhattanDist, planeDelta, 0);
    }

    static String classifyType(String entityName, String optionName, int manhattanDist, int planeDelta, int actionId) {
        String opt = optionName != null ? optionName.toLowerCase(Locale.ROOT) : "";
        String name = entityName != null ? entityName.toLowerCase(Locale.ROOT) : "";
        boolean isNpcAction = actionId >= 7 && actionId <= 12;

        // Specific object names first
        if (name.contains("fairy ring"))    return "FAIRY_RING";
        if (name.contains("spirit tree"))   return "SPIRIT_TREE";
        if (name.contains("lodestone"))     return "LODESTONE";
        if (name.contains("portal"))        return "PORTAL";

        // Option-based classification
        if (opt.contains("open") || opt.contains("close")) {
            if (manhattanDist <= 2 && planeDelta == 0 && !isNpcAction) return "DOOR";
        }
        if (opt.contains("climb"))          return "STAIRCASE";
        if (opt.contains("enter") || opt.contains("exit") || opt.contains("go-through") || opt.contains("pass-through") || opt.contains("go through") || opt.contains("pass through"))
            return "ENTRANCE";
        if (opt.contains("cross") || opt.contains("crawl") || opt.contains("jump") || opt.contains("squeeze") || opt.contains("balance"))
            return "AGILITY";
        if (opt.contains("teleport") || name.contains("teleport"))
            return "TELEPORT";
        if (opt.contains("board") || opt.contains("travel") || opt.contains("sail") || opt.contains("ride"))
            return "TRANSPORT";

        // NPC interactions that cause movement are transport, not doors/passages
        if (isNpcAction)                    return "NPC_TRANSPORT";

        // Component/interface actions (spellbook, lodestone map, item teleports)
        boolean isComponentAction = actionId >= 50;
        if (isComponentAction)              return "TELEPORT";

        // Fallbacks based on movement
        if (manhattanDist > 10)             return "TELEPORT";
        if (planeDelta != 0)                return "STAIRCASE";
        if (manhattanDist <= 2)             return "PASSAGE";

        return "PASSAGE";
    }

    /** Types where source position is irrelevant — only destination matters. */
    private static boolean isDstOnly(String type) {
        return switch (type) {
            case "TELEPORT", "LODESTONE", "FAIRY_RING", "SPIRIT_TREE", "ITEM_TELEPORT" -> true;
            default -> false;
        };
    }

    private static boolean isBidirectional(String type) {
        return switch (type) {
            case "DOOR", "WALL_PASSAGE", "STAIRCASE", "PASSAGE", "ENTRANCE", "AGILITY" -> true;
            case "TELEPORT", "LODESTONE", "FAIRY_RING", "SPIRIT_TREE", "PORTAL", "TRANSPORT", "NPC_TRANSPORT" -> false;
            default -> false;
        };
    }

    // ── Transition option/name heuristics ────────��───────────────────────

    private static boolean isTransitionOption(String opt) {
        return opt.contains("open") || opt.contains("close") || opt.contains("climb")
                || opt.contains("enter") || opt.contains("exit") || opt.contains("teleport")
                || opt.contains("cross") || opt.contains("crawl") || opt.contains("jump")
                || opt.contains("squeeze") || opt.contains("pass") || opt.contains("go-through")
                || opt.contains("go through") || opt.contains("board") || opt.contains("travel")
                || opt.contains("sail") || opt.contains("ride") || opt.contains("balance")
                || opt.contains("use") || opt.contains("activate");
    }

    private static boolean isTransitionName(String name) {
        return name.contains("door") || name.contains("gate") || name.contains("stair")
                || name.contains("ladder") || name.contains("portal") || name.contains("teleport")
                || name.contains("fairy ring") || name.contains("spirit tree")
                || name.contains("lodestone") || name.contains("trapdoor")
                || name.contains("agility") || name.contains("rope") || name.contains("bridge")
                || name.contains("tunnel") || name.contains("cave entrance")
                || name.contains("hole") || name.contains("shortcut");
    }

    // ── Accessors ────────────────────────────────────────────────────────

    State getCaptureState() { return captureState; }
    String getPendingEntityName() { return pendingEntityName; }
    String getPendingOptionName() { return pendingOptionName; }
    long getCaptureElapsedMs() {
        return captureState == State.IDLE ? 0 : System.currentTimeMillis() - captureStartTime;
    }

    /**
     * Send a pre-built transition directly (from the "Send" button in nearby objects).
     */
    void sendDirect(XapiData.TransitionData transition) {
        int idx = state.transitionLog.indexOf(transition);
        if (idx < 0) idx = state.transitionLog.size() - 1;
        if (idx < 0) return;

        boolean isDuplicate = false;
        try { isDuplicate = client.checkDuplicate(transition); } catch (Exception ignored) {}

        if (isDuplicate) {
            log.info("Duplicate transition skipped: {} at ({},{})", transition.type(), transition.srcX(), transition.srcY());
            replaceStatus(idx, "duplicate");
        } else {
            boolean sent = client.sendTransition(transition);
            replaceStatus(idx, sent ? "sent" : "failed");
        }
    }

    /**
     * Start a manual capture — user selected an object, now wait for them to interact.
     * The overrideType will be used instead of auto-classification when the capture completes.
     */
    void startManualCapture(String entityName, String optionName,
                            int playerX, int playerY, int playerPlane, String overrideType) {
        this.pendingEntityName = entityName;
        this.pendingOptionName = optionName;
        this.pendingActionId = 0;
        this.manualOverrideType = overrideType;
        this.srcX = playerX;
        this.srcY = playerY;
        this.srcP = playerPlane;
        this.settleCount = 0;
        this.captureStartTime = System.currentTimeMillis();
        this.captureState = State.WAITING_MOVE;
        log.info("Manual capture started: '{}' '{}' type={} at ({},{},{})",
                optionName, entityName, overrideType, srcX, srcY, srcP);
    }

    private void replaceStatus(int idx, String newStatus) {
        if (idx < 0 || idx >= state.transitionLog.size()) return;
        XapiData.TransitionData old = state.transitionLog.get(idx);
        state.transitionLog.set(idx, new XapiData.TransitionData(
                old.type(), old.srcX(), old.srcY(), old.srcP(),
                old.dstX(), old.dstY(), old.dstP(),
                old.name(), old.option(), old.cost(), old.bidir(),
                old.timestamp(), newStatus
        ));
    }

    /**
     * Manually trigger a resend for a specific log entry.
     */
    boolean resend(int logIndex) {
        if (logIndex < 0 || logIndex >= state.transitionLog.size()) return false;
        XapiData.TransitionData t = state.transitionLog.get(logIndex);
        boolean sent = client.sendTransition(t);
        state.transitionLog.set(logIndex, new XapiData.TransitionData(
                t.type(), t.srcX(), t.srcY(), t.srcP(),
                t.dstX(), t.dstY(), t.dstP(),
                t.name(), t.option(), t.cost(), t.bidir(),
                t.timestamp(), sent ? "sent" : "failed"
        ));
        return sent;
    }
}
