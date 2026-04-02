package com.botwithus.bot.scripts.eliteclue.task;

import com.botwithus.bot.api.log.BotLogger;
import com.botwithus.bot.api.log.LoggerFactory;
import com.botwithus.bot.api.model.OpenInterface;
import com.botwithus.bot.api.script.Task;
import com.botwithus.bot.scripts.eliteclue.ClueActivity;
import com.botwithus.bot.scripts.eliteclue.ClueContext;

import java.util.List;

/**
 * Diagnostic task that reports the current game state every loop.
 * This is the template task — it observes and logs everything needed
 * to understand how elite clue interactions work in V2.
 * <p>
 * Run this while manually solving clues to capture all the component
 * hashes, action types, interface states, and varbit changes.
 */
public final class DiagnosticTask implements Task {

    private static final BotLogger log = LoggerFactory.getLogger(DiagnosticTask.class);
    private final ClueContext ctx;

    // Throttle logging to avoid spam
    private long lastDetailedLog = 0;
    private static final long LOG_INTERVAL_MS = 2000;

    // Track state changes
    private ClueActivity lastLoggedActivity = ClueActivity.IDLE;
    private int lastSpotAnimState = 0;
    private boolean lastHintArrow = false;
    private int lastCelticKnot4941 = -1;
    private int lastCelticKnot4942 = -1;
    private int lastCelticKnot4943 = -1;
    private int lastCelticKnot4944 = -1;
    private int lastCompassVarc = 0;

    public DiagnosticTask(ClueContext ctx) {
        this.ctx = ctx;
    }

    @Override
    public String name() {
        return "Diagnostic";
    }

    @Override
    public int priority() {
        return 0; // Always runs as fallback
    }

    @Override
    public boolean validate() {
        return true; // Always valid — we always want diagnostics
    }

    @Override
    public int execute() {
        long now = System.currentTimeMillis();

        // ── Always log state changes immediately ──
        logStateChanges();

        // ── Periodic detailed dump ──
        if (now - lastDetailedLog > LOG_INTERVAL_MS) {
            lastDetailedLog = now;
            logDetailedState();
        }

        // ── Activity-specific diagnostics ──
        switch (ctx.activity) {
            case SCANNER -> logScannerDiagnostics();
            case CELTIC_KNOT -> logCelticKnotDiagnostics();
            case SLIDE_PUZZLE -> logSlidePuzzleDiagnostics();
            case COMPASS -> logCompassDiagnostics();
            case DIG -> logDigDiagnostics();
            case OPEN_CLUE -> logOpenClueDiagnostics();
            default -> {}
        }

        return 600; // Check every game tick
    }

    // ───────────────────────────────────────────────────────────────
    //  State Change Detection (logged immediately on change)
    // ───────────────────────────────────────────────────────────────

    private void logStateChanges() {
        // Activity change
        if (ctx.activity != lastLoggedActivity) {
            ctx.logAction(">>> ACTIVITY CHANGED: " + lastLoggedActivity.label()
                    + " -> " + ctx.activity.label());
            lastLoggedActivity = ctx.activity;
        }

        // Spot anim state change (scanner colors)
        if (ctx.spotAnimState != lastSpotAnimState) {
            ctx.logAction("SPOT ANIM: " + spotAnimLabel(lastSpotAnimState)
                    + " -> " + spotAnimLabel(ctx.spotAnimState));
            lastSpotAnimState = ctx.spotAnimState;
        }

        // Hint arrow appeared/disappeared
        if (ctx.hasHintArrow != lastHintArrow) {
            if (ctx.hasHintArrow) {
                ctx.logAction("HINT ARROW APPEARED at ("
                        + ctx.hintArrowX + ", " + ctx.hintArrowY + ", " + ctx.hintArrowPlane + ")");
            } else {
                ctx.logAction("HINT ARROW DISAPPEARED");
            }
            lastHintArrow = ctx.hasHintArrow;
        }

        // Celtic knot varbit changes
        if (ctx.celticKnot4941 != lastCelticKnot4941) {
            ctx.logAction("VARBIT 4941: " + lastCelticKnot4941 + " -> " + ctx.celticKnot4941);
            lastCelticKnot4941 = ctx.celticKnot4941;
        }
        if (ctx.celticKnot4942 != lastCelticKnot4942) {
            ctx.logAction("VARBIT 4942: " + lastCelticKnot4942 + " -> " + ctx.celticKnot4942);
            lastCelticKnot4942 = ctx.celticKnot4942;
        }
        if (ctx.celticKnot4943 != lastCelticKnot4943) {
            ctx.logAction("VARBIT 4943: " + lastCelticKnot4943 + " -> " + ctx.celticKnot4943);
            lastCelticKnot4943 = ctx.celticKnot4943;
        }
        if (ctx.celticKnot4944 != lastCelticKnot4944) {
            ctx.logAction("VARBIT 4944: " + lastCelticKnot4944 + " -> " + ctx.celticKnot4944);
            lastCelticKnot4944 = ctx.celticKnot4944;
        }

        // Compass varc change
        if (ctx.compassVarc1323 != lastCompassVarc) {
            if (ctx.compassVarc1323 != 0) {
                int x = (ctx.compassVarc1323 >> 14) & 0x3FFF;
                int y = ctx.compassVarc1323 & 0x3FFF;
                int z = ctx.compassVarc1323 >> 28;
                ctx.logAction("VARC 1323 (compass): " + ctx.compassVarc1323
                        + " -> decoded (" + x + ", " + y + ", " + z + ")");
            } else {
                ctx.logAction("VARC 1323 (compass): cleared to 0");
            }
            lastCompassVarc = ctx.compassVarc1323;
        }
    }

    // ───────────────────────────────────────────────────────────────
    //  Periodic Detailed State Dump
    // ───────────────────────────────────────────────────────────────

    private void logDetailedState() {
        StringBuilder sb = new StringBuilder();
        sb.append("=== STATE DUMP ===");
        sb.append("\n  Activity: ").append(ctx.activity.label());
        sb.append("\n  Player: (").append(ctx.playerX).append(", ").append(ctx.playerY)
                .append(", ").append(ctx.playerPlane).append(")");
        sb.append(" anim=").append(ctx.animationId);
        sb.append(" moving=").append(ctx.playerMoving);
        sb.append(" combat=").append(ctx.inCombat);
        sb.append("\n  Clue: id=").append(ctx.clueItemId)
                .append(" inBackpack=").append(ctx.hasClueInBackpack);
        sb.append("\n  Interfaces: scanner=").append(ctx.scannerOpen)
                .append(" slidePuzzle=").append(ctx.slidePuzzleOpen)
                .append(" celticKnot=").append(ctx.celticKnotInterfaceId)
                .append(" direct=").append(ctx.directClueOpen)
                .append(" dialog=").append(ctx.dialogOpen);
        sb.append("\n  Scanner: spotAnim=").append(ctx.getSpotAnimLabel())
                .append(" distance='").append(ctx.scannerDistanceText).append("'");
        sb.append("\n  HintArrow: ").append(ctx.hasHintArrow);
        if (ctx.hasHintArrow) {
            sb.append(" at (").append(ctx.hintArrowX).append(", ")
                    .append(ctx.hintArrowY).append(", ").append(ctx.hintArrowPlane).append(")");
        }
        sb.append("\n  CelticKnot: [").append(ctx.celticKnot4941).append(", ")
                .append(ctx.celticKnot4942).append(", ").append(ctx.celticKnot4943)
                .append(", ").append(ctx.celticKnot4944).append("]");
        sb.append("\n  Compass varc1323: ").append(ctx.compassVarc1323);
        sb.append("\n  Familiar: time=").append(ctx.familiarTimeMinutes)
                .append("min scrolls=").append(ctx.familiarScrollsStored);

        log.info(sb.toString());
    }

    // ───────────────────────────────────────────────────────────────
    //  Activity-Specific Diagnostics
    // ───────────────────────────────────────────────────────────────

    private void logScannerDiagnostics() {
        // Log all open interfaces to find which components are available
        logOpenInterfaces("SCANNER");
    }

    private void logCelticKnotDiagnostics() {
        ctx.logAction("CELTIC KNOT: interface=" + ctx.celticKnotInterfaceId
                + " varbits=[" + ctx.celticKnot4941 + "," + ctx.celticKnot4942
                + "," + ctx.celticKnot4943 + "," + ctx.celticKnot4944 + "]");
    }

    private void logSlidePuzzleDiagnostics() {
        // Log sprite-based tile state from component children
        int[] sprites = ctx.slidePuzzleSpriteIds;
        StringBuilder grid = new StringBuilder("SLIDE PUZZLE sprites [");
        for (int i = 0; i < 25; i++) {
            grid.append(sprites[i]);
            if (i < 24) grid.append(",");
        }
        grid.append("] empty=").append(ctx.slidePuzzleEmptySlot);
        ctx.logAction(grid.toString());

        // Also check server varps 7736-7750 for state
        StringBuilder varps = new StringBuilder("SLIDE PUZZLE varps 7736-7750: ");
        for (int i = 7736; i <= 7750; i++) {
            int val = ctx.api.getVarp(i);
            if (val != 0) {
                varps.append(i).append("=").append(val).append(" ");
            }
        }
        ctx.logAction(varps.toString());
    }

    private void logCompassDiagnostics() {
        if (ctx.compassVarc1323 != 0) {
            int x = (ctx.compassVarc1323 >> 14) & 0x3FFF;
            int y = ctx.compassVarc1323 & 0x3FFF;
            int z = ctx.compassVarc1323 >> 28;
            int distX = Math.abs(ctx.playerX - x);
            int distY = Math.abs(ctx.playerY - y);
            ctx.logAction("COMPASS: target=(" + x + "," + y + "," + z
                    + ") player=(" + ctx.playerX + "," + ctx.playerY
                    + ") dist=(" + distX + "," + distY + ")");
        }
    }

    private void logDigDiagnostics() {
        ctx.logAction("DIG: arrow at (" + ctx.hintArrowX + "," + ctx.hintArrowY
                + "," + ctx.hintArrowPlane + ") player at ("
                + ctx.playerX + "," + ctx.playerY + "," + ctx.playerPlane + ")");
    }

    private void logOpenClueDiagnostics() {
        ctx.logAction("OPEN CLUE: itemId=" + ctx.clueItemId + " in backpack");
    }

    // ───────────────────────────────────────────────────────────────
    //  Helpers
    // ───────────────────────────────────────────────────────────────

    private void logOpenInterfaces(String context) {
        List<OpenInterface> interfaces = ctx.api.getOpenInterfaces();
        if (interfaces != null && !interfaces.isEmpty()) {
            StringBuilder sb = new StringBuilder(context + " open interfaces: ");
            for (OpenInterface iface : interfaces) {
                sb.append(iface.interfaceId()).append(" ");
            }
            ctx.logAction(sb.toString().trim());
        }
    }

    private String spotAnimLabel(int state) {
        return switch (state) {
            case 1 -> "BLUE";
            case 2 -> "ORANGE";
            case 3 -> "RED";
            default -> "NONE";
        };
    }
}
