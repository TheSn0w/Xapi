package com.botwithus.bot.scripts.eliteclue.task;

import com.botwithus.bot.api.log.BotLogger;
import com.botwithus.bot.api.log.LoggerFactory;
import com.botwithus.bot.api.model.ChatMessage;
import com.botwithus.bot.api.model.GameAction;
import com.botwithus.bot.api.script.Task;
import com.botwithus.bot.scripts.eliteclue.ClueContext;
import com.botwithus.bot.scripts.eliteclue.SlidePuzzleSolver;

import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Solves the 5x5 slide puzzle (interface 1931).
 * <p>
 * Flow:
 * 1. Read initial tile state from sprites
 * 2. Compute solution (V2 IDA* + MTM, ~56 MTM / ~103 STM clicks)
 * 3. Send all tile clicks without re-reading visual state (visual bug — server-side works)
 * 4. Click "Select" (check) button — GameAction(57, 1, -1, 126550050) [1931:34]
 * 5. Wait for chat message "Puzzle complete!"
 * 6. Click "Close" button — GameAction(57, 1, -1, 126550036) [1931:20]
 * <p>
 * If no "Puzzle complete!" message after checking, the solution was wrong —
 * close the interface, reopen, and retry.
 */
public final class SlidePuzzleTask implements Task {

    private static final BotLogger log = LoggerFactory.getLogger(SlidePuzzleTask.class);

    // Action hashes
    private static final int SLIDE_GRID_HASH = 126550034;   // 1931 << 16 | 18  (tile grid)
    private static final int SLIDE_CHECK_HASH = 126550050;   // 1931 << 16 | 34  (Select/check button)
    private static final int SLIDE_CLOSE_HASH = 126550036;   // 1931 << 16 | 20  (Close button)

    private final ClueContext ctx;

    /** Phases of the puzzle solving process. */
    private enum Phase {
        COMPUTE,        // Read state and compute solution
        CLICKING,       // Sending tile clicks
        CHECK,          // Click the "Select" (check) button
        WAIT_CONFIRM,   // Waiting for "Puzzle complete!" chat message
        CLOSE,          // Click "Close" to dismiss the interface
        FAILED          // Solution didn't work — need to close and retry
    }

    private Phase phase = Phase.COMPUTE;
    private List<Integer> solution = null;
    private int moveIndex = 0;
    private long phaseStartTime = 0;
    private int computeRetries = 0;

    public SlidePuzzleTask(ClueContext ctx) {
        this.ctx = ctx;
    }

    @Override
    public String name() {
        return "Slide Puzzle";
    }

    @Override
    public int priority() {
        return 90;
    }

    @Override
    public boolean validate() {
        // Reset if interface closed (puzzle was dismissed or completed)
        if (!ctx.slidePuzzleOpen && phase != Phase.COMPUTE) {
            ctx.logAction("SLIDE PUZZLE: Interface closed, resetting.");
            reset();
        }
        return ctx.slidePuzzleOpen;
    }

    @Override
    public int execute() {
        return switch (phase) {
            case COMPUTE -> doCompute();
            case CLICKING -> doClicking();
            case CHECK -> doCheck();
            case WAIT_CONFIRM -> doWaitConfirm();
            case CLOSE -> doClose();
            case FAILED -> doFailed();
        };
    }

    // ════════════════════════════════════════════════════════════════
    //  PHASE: COMPUTE — read state and solve
    // ════════════════════════════════════════════════════════════════

    private int doCompute() {
        int[] tiles = ctx.slidePuzzleTiles;

        // Validate tile state — sprites may not be loaded yet
        if (!isValidTileState(tiles)) {
            computeRetries++;
            if (computeRetries > 10) {
                ctx.logAction("SLIDE PUZZLE: Tile state invalid after 10 retries, giving up.");
                phase = Phase.FAILED;
                return 1000;
            }
            ctx.logAction("SLIDE PUZZLE: Waiting for sprites to load... (attempt " + computeRetries + ")");
            return 1200; // Wait and re-read
        }
        computeRetries = 0;

        if (isSolved(tiles)) {
            ctx.logAction("SLIDE PUZZLE: Already solved! Checking...");
            phase = Phase.CHECK;
            phaseStartTime = System.currentTimeMillis();
            return 600;
        }

        ctx.slideSolving = true;
        ctx.slideSolverStatus = "Computing solution...";
        ctx.logAction("SLIDE PUZZLE: Computing solution...");
        logGrid(tiles);

        long t0 = System.currentTimeMillis();
        List<Integer> mtm = SlidePuzzleSolver.solve(tiles);
        List<Integer> stm = SlidePuzzleSolver.expandToSTM(mtm, tiles);
        long elapsed = System.currentTimeMillis() - t0;

        ctx.slideSolverMTMCount = mtm != null ? mtm.size() : 0;
        ctx.slideSolverSTMCount = stm != null ? stm.size() : 0;
        ctx.slideSolverTimeMs = elapsed;

        if (stm == null || stm.isEmpty()) {
            ctx.logAction("SLIDE PUZZLE: No solution found! (" + elapsed + "ms)");
            ctx.slideSolverStatus = "No solution found!";
            phase = Phase.FAILED;
            return 1000;
        }

        // ── Verify solution before sending clicks ──
        if (!verifySolution(tiles, stm)) {
            ctx.logAction("SLIDE PUZZLE: Solution FAILED verification!");
            log.warn("[SlidePuzzle] Solution failed verification");
            ctx.slideSolverStatus = "Solver error!";
            phase = Phase.FAILED;
            return 1000;
        }

        solution = stm;
        moveIndex = 0;
        ctx.slideSolverMovesTotal = stm.size();
        ctx.slideSolverMovesExecuted = 0;

        ctx.logAction(String.format("SLIDE PUZZLE: Solution: %d clicks (%d MTM) in %dms — VERIFIED",
                stm.size(), ctx.slideSolverMTMCount, elapsed));
        log.info("[SlidePuzzle] Solution: {} STM ({} MTM), {}ms — verified", stm.size(), ctx.slideSolverMTMCount, elapsed);

        phase = Phase.CLICKING;
        phaseStartTime = System.currentTimeMillis();
        return 1200; // Wait for game to be ready before first click
    }

    // ════════════════════════════════════════════════════════════════
    //  PHASE: CLICKING — send tile moves
    // ════════════════════════════════════════════════════════════════

    private int doClicking() {
        // Wait for queue to drain
        if (ctx.api.getActionQueueSize() > 0) {
            return 300;
        }

        int pos = solution.get(moveIndex);
        ctx.api.queueAction(new GameAction(57, 1, pos, SLIDE_GRID_HASH));

        moveIndex++;
        ctx.slideSolverMovesExecuted = moveIndex;
        ctx.slideSolverStatus = String.format("Clicking: %d/%d", moveIndex, solution.size());

        if (moveIndex >= solution.size()) {
            ctx.logAction(String.format("SLIDE PUZZLE: All %d clicks sent. Checking solution...", solution.size()));
            phase = Phase.CHECK;
            phaseStartTime = System.currentTimeMillis();
            return 2400; // Wait for last move to fully process before checking
        }

        return clickDelay();
    }

    // ════════════════════════════════════════════════════════════════
    //  PHASE: CHECK — click "Select" (check) button
    // ════════════════════════════════════════════════════════════════

    private int doCheck() {
        if (ctx.api.getActionQueueSize() > 0) {
            return 300;
        }

        ctx.logAction("SLIDE PUZZLE: Clicking 'Select' (check) button...");
        ctx.slideSolverStatus = "Checking solution...";

        // GameAction(57, 1, -1, 126550050) — iface:1931 comp:34 "Select"
        ctx.api.queueAction(new GameAction(57, 1, -1, SLIDE_CHECK_HASH));

        phase = Phase.WAIT_CONFIRM;
        phaseStartTime = System.currentTimeMillis();
        return 1200; // Wait for server response
    }

    // ════════════════════════════════════════════════════════════════
    //  PHASE: WAIT_CONFIRM — check for "Puzzle complete!" chat message
    // ════════════════════════════════════════════════════════════════

    private int doWaitConfirm() {
        // Check recent chat for "Puzzle complete!"
        List<ChatMessage> messages = ctx.api.queryChatHistory(0, 10);
        if (messages != null) {
            for (ChatMessage msg : messages) {
                if (msg.text() != null && msg.text().contains("Puzzle complete")) {
                    ctx.logAction("SLIDE PUZZLE: Puzzle complete! Closing interface...");
                    ctx.slideSolverStatus = "SOLVED! Closing...";
                    log.info("[SlidePuzzle] Puzzle complete!");
                    ctx.stepsCompleted++;
                    phase = Phase.CLOSE;
                    phaseStartTime = System.currentTimeMillis();
                    return 800;
                }
            }
        }

        // Timeout after 5 seconds — solution was wrong
        if (System.currentTimeMillis() - phaseStartTime > 5000) {
            ctx.logAction("SLIDE PUZZLE: No 'Puzzle complete!' message — solution failed. Retrying...");
            ctx.slideSolverStatus = "Failed! Retrying...";
            log.warn("[SlidePuzzle] Solution verification failed, retrying");
            phase = Phase.FAILED;
            return 600;
        }

        ctx.slideSolverStatus = "Waiting for confirmation...";
        return 600; // Poll again
    }

    // ════════════════════════════════════════════════════════════════
    //  PHASE: CLOSE — click "Close" button to dismiss interface
    // ════════════════════════════════════════════════════════════════

    private int doClose() {
        if (ctx.api.getActionQueueSize() > 0) {
            return 300;
        }

        ctx.logAction("SLIDE PUZZLE: Clicking 'Close' button...");

        // GameAction(57, 1, -1, 126550036) — iface:1931 comp:20 "Close"
        ctx.api.queueAction(new GameAction(57, 1, -1, SLIDE_CLOSE_HASH));

        ctx.slideSolving = false;
        ctx.slideSolverStatus = "SOLVED!";
        reset();
        return 1000;
    }

    // ════════════════════════════════════════════════════════════════
    //  PHASE: FAILED — close and retry
    // ════════════════════════════════════════════════════════════════

    private int doFailed() {
        if (ctx.api.getActionQueueSize() > 0) {
            return 300;
        }

        ctx.logAction("SLIDE PUZZLE: Closing interface to retry...");

        // Close the puzzle interface
        ctx.api.queueAction(new GameAction(57, 1, -1, SLIDE_CLOSE_HASH));

        // Reset so next validate()+execute() will re-read and re-solve
        reset();
        ctx.slideSolverStatus = "Retrying...";
        return 2000; // Wait for interface to close and reopen
    }

    // ════════════════════════════════════════════════════════════════
    //  HELPERS
    // ════════════════════════════════════════════════════════════════

    private void reset() {
        phase = Phase.COMPUTE;
        solution = null;
        moveIndex = 0;
        phaseStartTime = 0;
        computeRetries = 0;
    }

    /**
     * Check that the tile state contains all 25 unique values (0-24).
     * Rejects garbage reads where sprites haven't loaded yet.
     */
    private boolean isValidTileState(int[] tiles) {
        if (tiles == null || tiles.length != 25) return false;
        boolean[] seen = new boolean[25];
        for (int i = 0; i < 25; i++) {
            int t = tiles[i];
            if (t < 0 || t > 24) return false;
            if (seen[t]) return false; // Duplicate tile
            seen[t] = true;
        }
        return true;
    }

    private int clickDelay() {
        ThreadLocalRandom rng = ThreadLocalRandom.current();
        if (rng.nextInt(10) == 0) {
            return rng.nextInt(800, 1201); // Occasional longer pause
        }
        return rng.nextInt(500, 601);
    }

    private boolean isSolved(int[] tiles) {
        if (tiles == null || tiles.length != 25) return false;
        for (int i = 0; i < 25; i++) if (tiles[i] != i) return false;
        return true;
    }

    /**
     * Simulate the STM solution on a copy of the tiles to verify correctness.
     * Each STM click must be adjacent to the blank; the click swaps blank with tile.
     */
    private boolean verifySolution(int[] initialTiles, List<Integer> stmClicks) {
        int[] grid = initialTiles.clone();
        int blank = -1;
        for (int i = 0; i < 25; i++) if (grid[i] == 24) { blank = i; break; }
        if (blank == -1) return false;

        for (int i = 0; i < stmClicks.size(); i++) {
            int click = stmClicks.get(i);
            if (click < 0 || click >= 25) {
                ctx.logAction(String.format("VERIFY FAIL: click #%d pos=%d out of range", i, click));
                return false;
            }

            // Check adjacency: must be exactly 1 step away (same row ±1 col, or same col ±1 row)
            int br = blank / 5, bc = blank % 5;
            int cr = click / 5, cc = click % 5;
            boolean adjacent = (br == cr && Math.abs(bc - cc) == 1)
                    || (bc == cc && Math.abs(br - cr) == 1);
            if (!adjacent) {
                ctx.logAction(String.format("VERIFY FAIL: click #%d pos=%d not adjacent to blank=%d (blank=%d,%d click=%d,%d)",
                        i, click, blank, br, bc, cr, cc));
                return false;
            }

            // Swap
            grid[blank] = grid[click];
            grid[click] = 24;
            blank = click;
        }

        boolean solved = isSolved(grid);
        if (!solved) {
            ctx.logAction("VERIFY FAIL: after all clicks, puzzle not solved");
            logGrid(grid);
        }
        return solved;
    }

    private void logGrid(int[] tiles) {
        StringBuilder sb = new StringBuilder("  State:\n");
        for (int r = 0; r < 5; r++) {
            sb.append("  ");
            for (int c = 0; c < 5; c++) {
                int t = tiles[r * 5 + c];
                sb.append(t == 24 ? " [__]" : String.format(" [%2d]", t));
            }
            if (r < 4) sb.append("\n");
        }
        int correct = 0;
        for (int i = 0; i < 25; i++) if (tiles[i] == i) correct++;
        ctx.logAction(sb.toString());
        ctx.logAction("  " + correct + "/25 tiles correct");
    }
}
