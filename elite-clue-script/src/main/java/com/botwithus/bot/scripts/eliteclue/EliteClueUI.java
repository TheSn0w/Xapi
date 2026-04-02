package com.botwithus.bot.scripts.eliteclue;

import com.botwithus.bot.api.ui.ScriptUI;
import com.botwithus.bot.scripts.eliteclue.scan.*;
import com.botwithus.bot.scripts.eliteclue.task.ScanClueTask;
import imgui.ImGui;
import imgui.flag.ImGuiCol;

import java.util.List;

/**
 * ImGui UI panel for the Elite Clue diagnostic script.
 * Displays real-time game state information across multiple tabs.
 */
final class EliteClueUI implements ScriptUI {

    // Colours (R, G, B, A)
    private static final float[] GOLD = {1f, 0.65f, 0.01f, 1f};
    private static final float[] CYAN = {0f, 0.82f, 1f, 1f};
    private static final float[] GREEN = {0.48f, 0.93f, 0.62f, 1f};
    private static final float[] RED = {0.91f, 0.27f, 0.37f, 1f};
    private static final float[] ORANGE = {1f, 0.62f, 0.01f, 1f};
    private static final float[] BLUE = {0.33f, 0.63f, 1f, 1f};
    private static final float[] YELLOW = {1f, 0.92f, 0.23f, 1f};
    private static final float[] MUTED = {0.67f, 0.67f, 0.67f, 1f};
    private static final float[] PURPLE = {0.81f, 0.42f, 1f, 1f};

    private final EliteClueScript script;

    EliteClueUI(EliteClueScript script) {
        this.script = script;
    }

    @Override
    public void render() {
        ClueContext ctx = script.ctx;
        if (ctx == null) {
            ImGui.text("Initialising...");
            return;
        }

        // Header
        ImGui.textColored(GOLD[0], GOLD[1], GOLD[2], GOLD[3], "Elite Clue Diagnostic");
        ImGui.sameLine();
        ImGui.textColored(MUTED[0], MUTED[1], MUTED[2], MUTED[3], "v0.1");
        ImGui.separator();

        // Activity badge
        ImGui.text("Activity: ");
        ImGui.sameLine();
        float[] activityColor = getActivityColor(ctx.activity);
        ImGui.textColored(activityColor[0], activityColor[1], activityColor[2], activityColor[3],
                ctx.activity.label());
        ImGui.separator();

        if (ImGui.beginTabBar("##eliteClue")) {
            if (ImGui.beginTabItem("State")) {
                renderStateTab(ctx);
                ImGui.endTabItem();
            }
            if (ImGui.beginTabItem("Scanner")) {
                renderScannerTab(ctx);
                ImGui.endTabItem();
            }
            if (ImGui.beginTabItem("Puzzles")) {
                renderPuzzlesTab(ctx);
                ImGui.endTabItem();
            }
            if (ImGui.beginTabItem("Familiar")) {
                renderFamiliarTab(ctx);
                ImGui.endTabItem();
            }
            if (ImGui.beginTabItem("Log")) {
                renderLogTab(ctx);
                ImGui.endTabItem();
            }
            ImGui.endTabBar();
        }
    }

    // ── State Tab ──

    private void renderStateTab(ClueContext ctx) {
        ImGui.textColored(CYAN[0], CYAN[1], CYAN[2], CYAN[3], "Player Position");
        ImGui.text("  X: " + ctx.playerX + "  Y: " + ctx.playerY + "  Plane: " + ctx.playerPlane);
        ImGui.text("  Animation: " + ctx.animationId);
        ImGui.text("  Moving: " + ctx.playerMoving + "  Combat: " + ctx.inCombat);
        ImGui.separator();

        ImGui.textColored(CYAN[0], CYAN[1], CYAN[2], CYAN[3], "Clue Status");
        ImGui.text("  Has Clue: " + ctx.hasClueInBackpack);
        ImGui.text("  Item ID: " + ctx.clueItemId);
        ImGui.separator();

        ImGui.textColored(CYAN[0], CYAN[1], CYAN[2], CYAN[3], "Open Interfaces");
        ImGui.text("  Scanner (1752): " + ctx.scannerOpen);
        ImGui.text("  Slide Puzzle (1931): " + ctx.slidePuzzleOpen);
        ImGui.text("  Celtic Knot: " + (ctx.celticKnotInterfaceId > 0 ? ctx.celticKnotInterfaceId : "none"));
        ImGui.text("  Direct/Compass (996): " + ctx.directClueOpen);
        ImGui.text("  Dialog: " + ctx.dialogOpen);
        ImGui.text("  Bank: " + ctx.bankOpen);
        ImGui.separator();

        ImGui.textColored(CYAN[0], CYAN[1], CYAN[2], CYAN[3], "Hint Arrow");
        if (ctx.hasHintArrow) {
            ImGui.textColored(GREEN[0], GREEN[1], GREEN[2], GREEN[3],
                    "  ACTIVE at (" + ctx.hintArrowX + ", " + ctx.hintArrowY + ", " + ctx.hintArrowPlane + ")");
        } else {
            ImGui.textColored(MUTED[0], MUTED[1], MUTED[2], MUTED[3], "  None");
        }
        ImGui.separator();

        ImGui.textColored(CYAN[0], CYAN[1], CYAN[2], CYAN[3], "Compass (varc 1323)");
        if (ctx.compassVarc1323 != 0) {
            int x = (ctx.compassVarc1323 >> 14) & 0x3FFF;
            int y = ctx.compassVarc1323 & 0x3FFF;
            int z = ctx.compassVarc1323 >> 28;
            ImGui.text("  Raw: " + ctx.compassVarc1323);
            ImGui.text("  Decoded: (" + x + ", " + y + ", " + z + ")");
        } else {
            ImGui.textColored(MUTED[0], MUTED[1], MUTED[2], MUTED[3], "  Not active");
        }
    }

    // ── Scanner Tab ──

    private void renderScannerTab(ClueContext ctx) {
        // ── Scanner status header ──
        ImGui.textColored(CYAN[0], CYAN[1], CYAN[2], CYAN[3], "Scanner Status");
        ImGui.text("  Interface Open: " + ctx.scannerOpen);
        ImGui.text("  Distance Text: '" + ctx.scannerDistanceText + "'");
        ImGui.separator();

        // ── Spot animation ──
        ImGui.textColored(CYAN[0], CYAN[1], CYAN[2], CYAN[3], "Spot Animation");
        float[] spotColor = switch (ctx.spotAnimState) {
            case 1 -> BLUE;
            case 2 -> ORANGE;
            case 3 -> RED;
            default -> MUTED;
        };
        ImGui.textColored(spotColor[0], spotColor[1], spotColor[2], spotColor[3],
                "  " + ctx.getSpotAnimLabel());
        ImGui.separator();

        // ── Hint arrow ──
        ImGui.textColored(CYAN[0], CYAN[1], CYAN[2], CYAN[3], "Hint Arrow");
        if (ctx.hasHintArrow) {
            ImGui.textColored(GREEN[0], GREEN[1], GREEN[2], GREEN[3],
                    "  TARGET FOUND at (" + ctx.hintArrowX + ", " + ctx.hintArrowY + ")");
            int dist = Math.max(Math.abs(ctx.playerX - ctx.hintArrowX),
                    Math.abs(ctx.playerY - ctx.hintArrowY));
            ImGui.text("  Distance: " + dist + " tiles");
        } else {
            ImGui.textColored(MUTED[0], MUTED[1], MUTED[2], MUTED[3], "  Scanning...");
        }
        ImGui.separator();

        // ── Scan Tracker Overlay ──
        renderScanTrackerOverlay(ctx);
    }

    private void renderScanTrackerOverlay(ClueContext ctx) {
        ScanClueTracker tracker = ctx.scanTracker;

        ImGui.textColored(GOLD[0], GOLD[1], GOLD[2], GOLD[3], "Scan Tracker");

        if (!tracker.isTracking()) {
            ImGui.textColored(MUTED[0], MUTED[1], MUTED[2], MUTED[3],
                    "  Not tracking — open scanner to begin");
            return;
        }

        ScanRegion region = tracker.getActiveRegion();
        ImGui.textColored(GREEN[0], GREEN[1], GREEN[2], GREEN[3],
                "  Region: " + region.name());
        ImGui.text("  Scanner Text: '" + ctx.scannerDistanceText + "'");

        int baseDist = tracker.getBaseDistance();
        int orangeThresh = tracker.getOrangeThreshold();
        ImGui.textColored(CYAN[0], CYAN[1], CYAN[2], CYAN[3],
                String.format("  Base Distance: %d paces", baseDist));
        ImGui.text(String.format("  Thresholds: RED <= %d | ORANGE %d-%d | BLUE > %d",
                baseDist, baseDist + 1, orangeThresh, orangeThresh));
        ImGui.text("  Observations: " + tracker.getObservationCount());

        // Solved state
        if (tracker.isSolved()) {
            ScanCoordinate solved = tracker.getSolvedCoordinate();
            ImGui.textColored(GREEN[0], GREEN[1], GREEN[2], GREEN[3],
                    "  SOLVED: " + (solved != null ? solved.toString() : "unknown"));
            ImGui.separator();
        }

        // Candidate count with color
        int candidateCount = tracker.getCandidateCount();
        int totalCoords = region.coords().size();
        float[] countColor;
        if (candidateCount <= 1) countColor = GREEN;
        else if (candidateCount <= 5) countColor = ORANGE;
        else countColor = CYAN;

        ImGui.textColored(countColor[0], countColor[1], countColor[2], countColor[3],
                String.format("  Candidates: %d / %d", candidateCount, totalCoords));
        ImGui.text("  Eliminated: " + tracker.getEliminationCount());

        // Centroid / walk target
        int[] centroid = tracker.getCentroid();
        if (centroid != null && candidateCount > 1) {
            int centDist = Math.max(Math.abs(ctx.playerX - centroid[0]),
                    Math.abs(ctx.playerY - centroid[1]));
            ImGui.text(String.format("  Centroid: (%d, %d) dist=%d", centroid[0], centroid[1], centDist));
        }

        ImGui.separator();

        // ── Candidate list ──
        if (ImGui.treeNode("Candidates (" + candidateCount + ")")) {
            var candidates = tracker.getCandidates();
            int shown = 0;
            for (ScanCoordinate c : candidates) {
                if (shown >= 50) {
                    ImGui.textColored(MUTED[0], MUTED[1], MUTED[2], MUTED[3],
                            "  ... and " + (candidateCount - 50) + " more");
                    break;
                }
                int dist = c.chebyshevDistance(ctx.playerX, ctx.playerY);
                String distLabel = dist <= baseDist ? "RED" : dist <= orangeThresh ? "ORG" : "BLU";
                float[] dColor = dist <= baseDist ? RED : dist <= orangeThresh ? ORANGE : BLUE;
                ImGui.textColored(dColor[0], dColor[1], dColor[2], dColor[3],
                        String.format("  %s  dist=%-3d  [%s]", c, dist, distLabel));
                shown++;
            }
            ImGui.treePop();
        }

        // ── Elimination log ──
        if (ImGui.treeNode("Eliminations (" + tracker.getEliminationCount() + ")")) {
            var eliminations = tracker.getEliminations();
            int shown = 0;
            // Show most recent first
            for (int i = eliminations.size() - 1; i >= 0; i--) {
                if (shown >= 30) {
                    ImGui.textColored(MUTED[0], MUTED[1], MUTED[2], MUTED[3],
                            "  ... and " + (eliminations.size() - 30) + " more");
                    break;
                }
                EliminationRecord rec = eliminations.get(i);
                ImGui.textColored(RED[0], RED[1], RED[2], RED[3],
                        String.format("  %s — %s", rec.coordinate(), rec.reason()));
                shown++;
            }
            ImGui.treePop();
        }

        ImGui.separator();

        // ── Solver State ──
        renderSolverOverlay(ctx);
    }

    private void renderSolverOverlay(ClueContext ctx) {
        ScanClueTask task = script.scanClueTask;
        if (task == null) return;

        ScanNavigationSolver solver = task.getSolver();
        ScanObservationLog obsLog = task.getObservationLog();

        ImGui.textColored(GOLD[0], GOLD[1], GOLD[2], GOLD[3], "Navigation Solver");

        // Phase with color
        ScanNavigationSolver.SolverPhase phase = solver.getPhase();
        float[] phaseColor = switch (phase) {
            case INITIAL_APPROACH -> CYAN;
            case DIRECTIONAL_PROBE -> BLUE;
            case BACKTRACK -> ORANGE;
            case CONVERGE -> GREEN;
            case PROXIMITY_EXPLORE -> YELLOW;
        };
        ImGui.text("  Phase: ");
        ImGui.sameLine();
        ImGui.textColored(phaseColor[0], phaseColor[1], phaseColor[2], phaseColor[3],
                phase.label());

        // Probe info
        ImGui.text(String.format("  Direction: %s  Step: %d tiles",
                solver.getCurrentHeadingLabel(), solver.getProbeStepSize()));

        // Last good position
        int[] lgp = solver.getLastGoodPosition();
        if (lgp != null) {
            ImGui.text(String.format("  Best anchor: (%d,%d) %s",
                    lgp[0], lgp[1], solver.getLastGoodColor().label()));
        }

        // Pending walk target
        int[] target = task.getPendingWalkTarget();
        if (target != null) {
            int dist = Math.max(Math.abs(ctx.playerX - target[0]),
                    Math.abs(ctx.playerY - target[1]));
            ImGui.textColored(CYAN[0], CYAN[1], CYAN[2], CYAN[3],
                    String.format("  Walk target: (%d,%d) dist=%d", target[0], target[1], dist));
        }

        ImGui.separator();

        // ── Negative Validation ──
        ImGui.textColored(GOLD[0], GOLD[1], GOLD[2], GOLD[3], "Negative Validation");
        ScanCoordinate nvTarget = solver.getNegValTarget();
        if (nvTarget != null) {
            ImGui.text("  Target: " + nvTarget);
            ImGui.text(String.format("  Inside radius: %s  Confirmations: %d/%d",
                    solver.isNegValInsideRadius() ? "YES" : "no",
                    solver.getNegValConfirmations(), 3));
            if (solver.isNegValSawRed()) {
                ImGui.textColored(RED[0], RED[1], RED[2], RED[3], "  Saw RED — not negative");
            } else if (solver.isNegValSawAnyColor() && solver.getNegValConfirmations() >= 3) {
                ImGui.textColored(ORANGE[0], ORANGE[1], ORANGE[2], ORANGE[3],
                        "  Trigger pending!");
            }
        } else {
            ImGui.textColored(MUTED[0], MUTED[1], MUTED[2], MUTED[3],
                    "  No target candidate");
        }

        // ── Proximity Exploration (tunnel mode) ──
        if (phase == ScanNavigationSolver.SolverPhase.PROXIMITY_EXPLORE) {
            ImGui.separator();
            ImGui.textColored(YELLOW[0], YELLOW[1], YELLOW[2], YELLOW[3], "Proximity Explore");
            ScanCoordinate exploreTarget = solver.getCurrentExploreTarget();
            if (exploreTarget != null) {
                int distToTarget = exploreTarget.chebyshevDistance(ctx.playerX, ctx.playerY);
                ImGui.text(String.format("  Target: %s (dist=%d)", exploreTarget, distToTarget));
            }
            ImGui.text(String.format("  Queue: %d/%d  Stuck: %d/%d  Anchor revisits: %d",
                    solver.getExplorationIndex() + 1, solver.getExplorationQueueSize(),
                    solver.getStuckCount(), 3, solver.getAnchorRevisitCount()));
        } else if (solver.getAnchorRevisitCount() > 0) {
            ImGui.separator();
            ImGui.textColored(MUTED[0], MUTED[1], MUTED[2], MUTED[3],
                    String.format("  Tunnel detection: %d/%d anchor revisits",
                            solver.getAnchorRevisitCount(), 2));
        }

        ImGui.separator();

        // ── Observation Log ──
        if (ImGui.treeNode("Observations (" + obsLog.size() + ")")) {
            List<ScanObservationLog.ScanObservation> all = obsLog.getAll();
            int shown = 0;
            // Show most recent first, up to 10
            for (int i = all.size() - 1; i >= 0; i--) {
                if (shown >= 10) {
                    ImGui.textColored(MUTED[0], MUTED[1], MUTED[2], MUTED[3],
                            "  ... and " + (all.size() - 10) + " more");
                    break;
                }
                ScanObservationLog.ScanObservation obs = all.get(i);
                float[] obsColor = switch (obs.color()) {
                    case BLUE -> BLUE;
                    case ORANGE -> ORANGE;
                    case RED -> RED;
                    default -> MUTED;
                };

                // Compute transition relative to previous
                String transLabel = "";
                if (i > 0) {
                    ScanObservationLog.ScanObservation prev = all.get(i - 1);
                    ScanObservationLog.ColorTransition t =
                            ScanObservationLog.ColorTransition.compute(prev.color(), obs.color());
                    transLabel = switch (t) {
                        case IMPROVED -> " ^";
                        case DEGRADED -> " v";
                        case SAME -> " =";
                        case NONE -> "";
                    };
                }

                ImGui.textColored(obsColor[0], obsColor[1], obsColor[2], obsColor[3],
                        String.format("  #%d: %s%s", i + 1, obs, transLabel));
                shown++;
            }
            ImGui.treePop();
        }
    }

    // ── Puzzles Tab ──

    private void renderPuzzlesTab(ClueContext ctx) {
        // Celtic Knot
        ImGui.textColored(PURPLE[0], PURPLE[1], PURPLE[2], PURPLE[3], "Celtic Knot");
        ImGui.text("  Interface: " + (ctx.celticKnotInterfaceId > 0 ? ctx.celticKnotInterfaceId : "none"));
        ImGui.text("  Varbit 4941: " + ctx.celticKnot4941 + " (need " + clicksNeeded(ctx.celticKnot4941) + " clicks)");
        ImGui.text("  Varbit 4942: " + ctx.celticKnot4942 + " (need " + clicksNeeded(ctx.celticKnot4942) + " clicks)");
        ImGui.text("  Varbit 4943: " + ctx.celticKnot4943 + " (need " + clicksNeeded(ctx.celticKnot4943) + " clicks)");
        ImGui.text("  Varbit 4944: " + ctx.celticKnot4944 + " (need " + clicksNeeded(ctx.celticKnot4944) + " clicks)");
        boolean solved = ctx.celticKnot4941 == 0 && ctx.celticKnot4942 == 0
                && ctx.celticKnot4943 == 0 && ctx.celticKnot4944 == 0;
        if (solved) {
            ImGui.textColored(GREEN[0], GREEN[1], GREEN[2], GREEN[3], "  SOLVED - click unlock!");
        }
        ImGui.separator();

        // Slide Puzzle
        renderSlidePuzzleSection(ctx);
    }

    // ── Slide Puzzle Section ──

    private void renderSlidePuzzleSection(ClueContext ctx) {
        ImGui.textColored(BLUE[0], BLUE[1], BLUE[2], BLUE[3], "Slide Puzzle");
        ImGui.text("  Interface (1931): " + (ctx.slidePuzzleOpen ? "OPEN" : "closed"));

        if (!ctx.slidePuzzleOpen) {
            ImGui.textColored(MUTED[0], MUTED[1], MUTED[2], MUTED[3],
                    "  Open the slide puzzle to enable solver.");
            return;
        }

        // ── Solver Controls ──
        ImGui.separator();
        ImGui.textColored(CYAN[0], CYAN[1], CYAN[2], CYAN[3], "Solver Controls");

        // Solve button — only enabled when puzzle is open and not already solving
        boolean alreadySolving = ctx.slideSolving;
        if (alreadySolving) {
            ImGui.pushStyleColor(ImGuiCol.Button, 0.3f, 0.3f, 0.3f, 1f);
            ImGui.pushStyleColor(ImGuiCol.ButtonHovered, 0.3f, 0.3f, 0.3f, 1f);
            ImGui.pushStyleColor(ImGuiCol.ButtonActive, 0.3f, 0.3f, 0.3f, 1f);
        }

        if (ImGui.button("Solve Puzzle") && !alreadySolving) {
            ctx.slideSolveRequested = true;
            ctx.logAction("SLIDE PUZZLE: Solve requested via UI button");
        }

        if (alreadySolving) {
            ImGui.popStyleColor(3);
            ImGui.sameLine();
            ImGui.textColored(ORANGE[0], ORANGE[1], ORANGE[2], ORANGE[3], "Solving...");
        }

        // ── Solver Status ──
        ImGui.separator();
        ImGui.textColored(CYAN[0], CYAN[1], CYAN[2], CYAN[3], "Solver Status");

        // Status text with color coding
        String status = ctx.slideSolverStatus;
        float[] statusColor = MUTED;
        if (status.contains("SOLVED")) statusColor = GREEN;
        else if (status.contains("Solving")) statusColor = ORANGE;
        else if (status.contains("Computing")) statusColor = BLUE;
        else if (status.contains("No solution")) statusColor = RED;

        ImGui.textColored(statusColor[0], statusColor[1], statusColor[2], statusColor[3],
                "  " + status);

        // Solution stats (if we have them)
        if (ctx.slideSolverSTMCount > 0) {
            ImGui.text(String.format("  Solution: %d clicks (STM), %d multi-tile moves (MTM)",
                    ctx.slideSolverSTMCount, ctx.slideSolverMTMCount));
            ImGui.text(String.format("  Compute time: %dms", ctx.slideSolverTimeMs));
        }

        // Progress bar
        if (ctx.slideSolverMovesTotal > 0) {
            float progress = (float) ctx.slideSolverMovesExecuted / ctx.slideSolverMovesTotal;
            ImGui.progressBar(progress, 200, 20,
                    String.format("%d / %d clicks", ctx.slideSolverMovesExecuted, ctx.slideSolverMovesTotal));
        }

        // ── Current State Grid ──
        ImGui.separator();
        ImGui.textColored(CYAN[0], CYAN[1], CYAN[2], CYAN[3], "Current State");

        int[] tiles = ctx.slidePuzzleTiles;

        // Count tiles in correct position
        int correct = 0;
        for (int i = 0; i < 25; i++) {
            if (tiles[i] == i) correct++;
        }

        if (correct == 25) {
            ImGui.textColored(GREEN[0], GREEN[1], GREEN[2], GREEN[3],
                    "  ALL 25 TILES CORRECT!");
        } else {
            ImGui.text("  Tiles correct: " + correct + "/25");
        }

        // Grid display with color-coded tiles (green = correct, white = wrong)
        for (int row = 0; row < 5; row++) {
            StringBuilder sb = new StringBuilder("  ");
            for (int col = 0; col < 5; col++) {
                int pos = row * 5 + col;
                int tile = tiles[pos];
                if (tile == 24) {
                    sb.append(" [__]");
                } else if (tile == pos) {
                    sb.append(String.format(" [%2d]", tile)); // correct
                } else {
                    sb.append(String.format(" [%2d]", tile)); // wrong
                }
            }
            ImGui.text(sb.toString());
        }

        // ── Debug Info (collapsible) ──
        ImGui.separator();
        if (ImGui.treeNode("Debug Info")) {
            ImGui.text("  Empty slot: position " + ctx.slidePuzzleEmptySlot);
            ImGui.text("  Base sprite: " + ctx.slidePuzzleBaseSprite);

            // Raw sprites
            ImGui.text("  Raw sprite IDs:");
            int[] sprites = ctx.slidePuzzleSpriteIds;
            for (int row = 0; row < 5; row++) {
                StringBuilder sb = new StringBuilder("    ");
                for (int col = 0; col < 5; col++) {
                    int pos = row * 5 + col;
                    sb.append(String.format("%7d", sprites[pos]));
                }
                ImGui.text(sb.toString());
            }

            // Goal state reference
            ImGui.separator();
            ImGui.textColored(GREEN[0], GREEN[1], GREEN[2], GREEN[3], "  Goal State:");
            ImGui.text("    [ 0] [ 1] [ 2] [ 3] [ 4]");
            ImGui.text("    [ 5] [ 6] [ 7] [ 8] [ 9]");
            ImGui.text("    [10] [11] [12] [13] [14]");
            ImGui.text("    [15] [16] [17] [18] [19]");
            ImGui.text("    [20] [21] [22] [23] [__]");

            ImGui.treePop();
        }
    }

    // ── Familiar Tab ──

    private void renderFamiliarTab(ClueContext ctx) {
        ImGui.textColored(ORANGE[0], ORANGE[1], ORANGE[2], ORANGE[3], "Familiar Status");
        ImGui.text("  Time Remaining: " + ctx.familiarTimeMinutes + " minutes (varbit 6055)");
        ImGui.text("  Scrolls Stored: " + ctx.familiarScrollsStored + " (varbit 25412)");
        ImGui.separator();
        if (ctx.familiarTimeMinutes == 0) {
            ImGui.textColored(RED[0], RED[1], RED[2], RED[3], "  No familiar active!");
        } else if (ctx.familiarTimeMinutes <= 5) {
            ImGui.textColored(ORANGE[0], ORANGE[1], ORANGE[2], ORANGE[3], "  Familiar running low!");
        } else {
            ImGui.textColored(GREEN[0], GREEN[1], GREEN[2], GREEN[3], "  Familiar active");
        }
    }

    // ── Log Tab ──

    private void renderLogTab(ClueContext ctx) {
        ImGui.textColored(CYAN[0], CYAN[1], CYAN[2], CYAN[3],
                "Action Log (" + ctx.actionLog.size() + " entries)");
        ImGui.sameLine();
        if (ImGui.smallButton("Copy All")) {
            StringBuilder sb = new StringBuilder();
            for (String entry : ctx.actionLog) {
                sb.append(entry).append('\n');
            }
            ImGui.setClipboardText(sb.toString());
        }
        ImGui.separator();

        // Show most recent entries
        int shown = 0;
        for (String entry : ctx.actionLog) {
            if (shown >= 50) break;
            ImGui.textWrapped(entry);
            shown++;
        }
    }

    // ── Helpers ──

    private String clicksNeeded(int value) {
        if (value == 0) return "0";
        int cw = value;
        int ccw = 16 - value;
        int min = Math.min(cw, ccw);
        String dir = cw <= ccw ? "CW" : "CCW";
        return min + " " + dir;
    }

    private float[] getActivityColor(ClueActivity activity) {
        return switch (activity) {
            case IDLE -> MUTED;
            case SCANNER, COMPASS, NAVIGATING -> BLUE;
            case CELTIC_KNOT, SLIDE_PUZZLE -> PURPLE;
            case DIG -> GREEN;
            case COMBAT -> RED;
            case DIALOG -> ORANGE;
            case OPEN_CLUE, FAMILIAR -> CYAN;
        };
    }
}
