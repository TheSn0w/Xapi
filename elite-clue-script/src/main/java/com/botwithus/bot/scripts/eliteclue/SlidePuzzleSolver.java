package com.botwithus.bot.scripts.eliteclue;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 5x5 slide puzzle solver combining two strategies:
 * <ol>
 *   <li><b>IDA* with MTM</b> — Weighted IDA* with multi-tile moves and directional alternation.
 *       Finds near-optimal solutions (~56 MTM clicks average).</li>
 *   <li><b>Row/column BFS</b> — Deterministic row-then-column strategy as fallback.
 *       Always succeeds, ~136 MTM clicks average.</li>
 * </ol>
 * Returns whichever strategy produces fewer MTM clicks.
 */
public final class SlidePuzzleSolver {

    private static final int N = 5;
    private static final int TOTAL = 25;
    private static final int BLANK = 24;
    private static final int MAX_DEPTH = 300;
    private static final long TIME_BUDGET_MS = 3000;

    private static final int DIR_ANY = 0;
    private static final int DIR_HORIZ = 1;
    private static final int DIR_VERT = 2;

    private static final int NOT_FOUND = Integer.MAX_VALUE;
    private static final int FOUND = -1;

    // BFS direction offsets
    private static final int[] DR = {-1, 1, 0, 0};
    private static final int[] DC = {0, 0, -1, 1};

    // ── IDA* instance state ──
    private final int[] grid = new int[TOTAL];
    private int blankPos;
    private int manhattanDist;
    private final int[] pathClicks = new int[MAX_DEPTH];
    private int[] bestSolution;
    private int bestLen;
    private long deadline;
    private long nodeCount;
    private boolean timeUp;

    private SlidePuzzleSolver() {}

    // ════════════════════════════════════════════════════════════════
    //  PUBLIC API
    // ════════════════════════════════════════════════════════════════

    /**
     * Solve the puzzle, returning MTM click positions.
     * Runs IDA* and row/col strategies, returns whichever is shorter.
     *
     * @param tiles grid state, tiles[pos] = tile number (0-23), 24 = empty
     * @return list of positions to click (MTM), in order
     */
    public static List<Integer> solve(int[] tiles) {
        if (tiles == null || tiles.length != TOTAL) return List.of();
        if (isSolved(tiles)) return List.of();

        SlidePuzzleSolver solver = new SlidePuzzleSolver();
        List<Integer> mtmSolution = solver.doSolveIDA(tiles);

        List<Integer> rowColSTM = solveRowCol(tiles);
        List<Integer> rowColMTM = compressToMTM(rowColSTM, tiles);

        if (mtmSolution != null && !mtmSolution.isEmpty()) {
            if (rowColMTM.size() < mtmSolution.size()) {
                return rowColMTM;
            }
            return mtmSolution;
        }
        return rowColMTM;
    }

    /**
     * Solve, returning single-tile (STM) click positions.
     *
     * @param tiles grid state
     * @return list of adjacent-to-blank positions to click, in order
     */
    public static List<Integer> solveSTM(int[] tiles) {
        List<Integer> mtm = solve(tiles);
        return expandToSTM(mtm, tiles);
    }

    // ════════════════════════════════════════════════════════════════
    //  IDA* SOLVER (WEIGHTED, MTM, DIRECTIONAL ALTERNATION)
    // ════════════════════════════════════════════════════════════════

    private List<Integer> doSolveIDA(int[] tiles) {
        bestSolution = null;
        bestLen = Integer.MAX_VALUE;
        deadline = System.currentTimeMillis() + TIME_BUDGET_MS;
        nodeCount = 0;
        timeUp = false;

        double weight = 2.8;

        while (!timeUp) {
            System.arraycopy(tiles, 0, grid, 0, TOTAL);
            blankPos = findBlankPos(grid);
            initManhattan();

            int h0 = (int) Math.ceil(manhattanDist * weight);
            int threshold = Math.max(1, h0);

            while (threshold < bestLen && !timeUp) {
                System.arraycopy(tiles, 0, grid, 0, TOTAL);
                blankPos = findBlankPos(grid);
                initManhattan();

                int result = search(0, threshold, DIR_ANY, weight);
                if (result == FOUND) break;
                if (result == NOT_FOUND) break;
                threshold = result;
            }

            if (weight <= 1.001) break;
            weight = 1.0 + (weight - 1.0) * 0.9;
            if (weight < 1.01) weight = 1.0;
        }

        if (bestSolution != null) {
            List<Integer> result = new ArrayList<>(bestLen);
            for (int i = 0; i < bestLen; i++) {
                result.add(bestSolution[i]);
            }
            return result;
        }
        return null;
    }

    private int search(int g, int threshold, int lastDir, double weight) {
        if ((++nodeCount & 0x1FFF) == 0) {
            if (System.currentTimeMillis() > deadline) {
                timeUp = true;
                return NOT_FOUND;
            }
        }

        int f = g + (int) Math.ceil(manhattanDist * weight);
        if (f > threshold) return f;

        if (manhattanDist == 0) {
            if (g < bestLen) {
                bestLen = g;
                bestSolution = new int[g];
                System.arraycopy(pathClicks, 0, bestSolution, 0, g);
            }
            return FOUND;
        }

        if (g >= bestLen - 1) return NOT_FOUND;

        int minExceeded = NOT_FOUND;
        int br = blankPos / N, bc = blankPos % N;

        if (lastDir != DIR_HORIZ) {
            // Horizontal moves
            for (int d = 1; bc - d >= 0; d++) {
                int target = br * N + (bc - d);
                int savedManhattan = manhattanDist;
                int savedBlank = blankPos;
                applyMove(target);
                pathClicks[g] = target;
                int result = search(g + 1, threshold, DIR_HORIZ, weight);
                undoMove(savedBlank);
                manhattanDist = savedManhattan;
                if (result == FOUND) return FOUND;
                if (result < minExceeded) minExceeded = result;
                if (timeUp) return NOT_FOUND;
            }
            for (int d = 1; bc + d < N; d++) {
                int target = br * N + (bc + d);
                int savedManhattan = manhattanDist;
                int savedBlank = blankPos;
                applyMove(target);
                pathClicks[g] = target;
                int result = search(g + 1, threshold, DIR_HORIZ, weight);
                undoMove(savedBlank);
                manhattanDist = savedManhattan;
                if (result == FOUND) return FOUND;
                if (result < minExceeded) minExceeded = result;
                if (timeUp) return NOT_FOUND;
            }
        }

        if (lastDir != DIR_VERT) {
            // Vertical moves
            for (int d = 1; br - d >= 0; d++) {
                int target = (br - d) * N + bc;
                int savedManhattan = manhattanDist;
                int savedBlank = blankPos;
                applyMove(target);
                pathClicks[g] = target;
                int result = search(g + 1, threshold, DIR_VERT, weight);
                undoMove(savedBlank);
                manhattanDist = savedManhattan;
                if (result == FOUND) return FOUND;
                if (result < minExceeded) minExceeded = result;
                if (timeUp) return NOT_FOUND;
            }
            for (int d = 1; br + d < N; d++) {
                int target = (br + d) * N + bc;
                int savedManhattan = manhattanDist;
                int savedBlank = blankPos;
                applyMove(target);
                pathClicks[g] = target;
                int result = search(g + 1, threshold, DIR_VERT, weight);
                undoMove(savedBlank);
                manhattanDist = savedManhattan;
                if (result == FOUND) return FOUND;
                if (result < minExceeded) minExceeded = result;
                if (timeUp) return NOT_FOUND;
            }
        }

        return minExceeded;
    }

    private void applyMove(int targetPos) {
        int br = blankPos / N, bc = blankPos % N;
        int tr = targetPos / N, tc = targetPos % N;

        if (br == tr) {
            int step = (tc > bc) ? 1 : -1;
            int pos = blankPos;
            while (pos != targetPos) {
                int next = pos + step;
                int tile = grid[next];
                int oldCol = next % N;
                int newCol = oldCol - step;
                int tgtCol = tile % N;
                manhattanDist += Math.abs(tgtCol - newCol) - Math.abs(tgtCol - oldCol);
                grid[pos] = tile;
                pos = next;
            }
        } else {
            int step = (tr > br) ? N : -N;
            int rowStep = (tr > br) ? 1 : -1;
            int pos = blankPos;
            while (pos != targetPos) {
                int next = pos + step;
                int tile = grid[next];
                int oldRow = next / N;
                int newRow = oldRow - rowStep;
                int tgtRow = tile / N;
                manhattanDist += Math.abs(tgtRow - newRow) - Math.abs(tgtRow - oldRow);
                grid[pos] = tile;
                pos = next;
            }
        }

        grid[targetPos] = BLANK;
        blankPos = targetPos;
    }

    private void undoMove(int oldBlankPos) {
        int br = blankPos / N, bc = blankPos % N;
        int or_ = oldBlankPos / N, oc = oldBlankPos % N;

        if (br == or_) {
            int step = (oc > bc) ? 1 : -1;
            int pos = blankPos;
            while (pos != oldBlankPos) {
                int next = pos + step;
                grid[pos] = grid[next];
                pos = next;
            }
        } else {
            int step = (or_ > br) ? N : -N;
            int pos = blankPos;
            while (pos != oldBlankPos) {
                int next = pos + step;
                grid[pos] = grid[next];
                pos = next;
            }
        }

        grid[oldBlankPos] = BLANK;
        blankPos = oldBlankPos;
    }

    private void initManhattan() {
        manhattanDist = 0;
        for (int pos = 0; pos < TOTAL; pos++) {
            int tile = grid[pos];
            if (tile == BLANK) continue;
            manhattanDist += Math.abs(tile / N - pos / N) + Math.abs(tile % N - pos % N);
        }
    }

    // ════════════════════════════════════════════════════════════════
    //  ROW/COLUMN BFS SOLVER (FALLBACK)
    // ════════════════════════════════════════════════════════════════

    private static List<Integer> solveRowCol(int[] tiles) {
        if (tiles == null || tiles.length != TOTAL) return List.of();
        if (isSolved(tiles)) return List.of();

        int[] g = tiles.clone();
        List<Integer> allMoves = new ArrayList<>();
        Set<Integer> locked = new HashSet<>();

        // Phase 1: top 3 rows
        for (int row = 0; row < 3; row++) {
            for (int col = 0; col < N - 2; col++) {
                int tile = row * N + col;
                List<Integer> moves = moveTileBFS(g, tile, tile, locked);
                if (moves == null) return allMoves;
                applyMovesBFS(g, moves);
                allMoves.addAll(moves);
                locked.add(tile);
            }
            int tileA = row * N + (N - 2);
            int tileB = row * N + (N - 1);
            List<Integer> moves = solveRowPair(g, tileA, tileB, locked);
            if (moves == null) return allMoves;
            applyMovesBFS(g, moves);
            allMoves.addAll(moves);
            locked.add(tileA);
            locked.add(tileB);
        }

        // Phase 2: bottom 2 rows column by column
        for (int col = 0; col < N; col++) {
            int tileA = 3 * N + col;
            int tileB = 4 * N + col;
            if (findTile(g, tileA) == tileA && findTile(g, tileB) == tileB) {
                locked.add(tileA);
                locked.add(tileB);
                continue;
            }
            List<Integer> moves;
            if (col == N - 1) {
                moves = moveTileBFS(g, tileA, tileA, locked);
            } else {
                moves = solveColPair(g, tileA, tileB, locked);
            }
            if (moves == null) return allMoves;
            applyMovesBFS(g, moves);
            allMoves.addAll(moves);
            locked.add(tileA);
            locked.add(tileB);
        }

        return allMoves;
    }

    private static List<Integer> moveTileBFS(int[] grid, int tile, int target, Set<Integer> locked) {
        int tilePos = findTile(grid, tile);
        int emptyPos = findBlankPos(grid);
        if (tilePos == target) return List.of();

        int startState = tilePos * TOTAL + emptyPos;
        Map<Integer, Integer> parent = new HashMap<>();
        Map<Integer, Integer> moveAt = new HashMap<>();
        parent.put(startState, -1);

        Deque<int[]> queue = new ArrayDeque<>();
        queue.add(new int[]{tilePos, emptyPos});

        while (!queue.isEmpty()) {
            int[] cur = queue.poll();
            int cTile = cur[0], cEmpty = cur[1];

            if (cTile == target) {
                List<Integer> moves = new ArrayList<>();
                int state = cTile * TOTAL + cEmpty;
                while (parent.get(state) != -1) {
                    moves.addFirst(moveAt.get(state));
                    state = parent.get(state);
                }
                return moves;
            }

            int eR = cEmpty / N, eC = cEmpty % N;
            for (int d = 0; d < 4; d++) {
                int nr = eR + DR[d], nc = eC + DC[d];
                if (nr < 0 || nr >= N || nc < 0 || nc >= N) continue;
                int clickPos = nr * N + nc;
                if (locked.contains(clickPos)) continue;

                int newTile = cTile;
                int newEmpty = clickPos;
                if (clickPos == cTile) {
                    newTile = cEmpty;
                }

                int newState = newTile * TOTAL + newEmpty;
                if (parent.containsKey(newState)) continue;
                parent.put(newState, cTile * TOTAL + cEmpty);
                moveAt.put(newState, clickPos);
                queue.add(new int[]{newTile, newEmpty});
            }
        }
        return null;
    }

    private static List<Integer> movePairBFS(int[] grid, int tileA, int tileB,
                                              int targetA, int targetB, Set<Integer> locked) {
        int posA = findTile(grid, tileA);
        int posB = findTile(grid, tileB);
        int emptyPos = findBlankPos(grid);
        if (posA == targetA && posB == targetB) return List.of();

        long startState = (long) posA * TOTAL * TOTAL + (long) posB * TOTAL + emptyPos;
        Map<Long, Long> parent = new HashMap<>();
        Map<Long, Integer> moveAt = new HashMap<>();
        parent.put(startState, -1L);

        Deque<int[]> queue = new ArrayDeque<>();
        queue.add(new int[]{posA, posB, emptyPos});

        while (!queue.isEmpty()) {
            int[] cur = queue.poll();
            int cA = cur[0], cB = cur[1], cE = cur[2];

            if (cA == targetA && cB == targetB) {
                List<Integer> moves = new ArrayList<>();
                long state = (long) cA * TOTAL * TOTAL + (long) cB * TOTAL + cE;
                while (parent.get(state) != -1L) {
                    moves.addFirst(moveAt.get(state));
                    state = parent.get(state);
                }
                return moves;
            }

            int eR = cE / N, eC = cE % N;
            for (int d = 0; d < 4; d++) {
                int nr = eR + DR[d], nc = eC + DC[d];
                if (nr < 0 || nr >= N || nc < 0 || nc >= N) continue;
                int clickPos = nr * N + nc;
                if (locked.contains(clickPos)) continue;

                int nA = cA, nB = cB, nE = clickPos;
                if (clickPos == cA) nA = cE;
                else if (clickPos == cB) nB = cE;

                long newState = (long) nA * TOTAL * TOTAL + (long) nB * TOTAL + nE;
                if (parent.containsKey(newState)) continue;
                parent.put(newState, (long) cA * TOTAL * TOTAL + (long) cB * TOTAL + cE);
                moveAt.put(newState, clickPos);
                queue.add(new int[]{nA, nB, nE});
            }
        }
        return null;
    }

    private static List<Integer> solveRowPair(int[] grid, int tileA, int tileB, Set<Integer> locked) {
        int posA = tileA, posB = tileB;
        if (findTile(grid, tileA) == posA && findTile(grid, tileB) == posB) return List.of();

        List<Integer> direct = movePairBFS(grid, tileA, tileB, posA, posB, locked);
        if (direct != null) return direct;

        int row = posA / N;
        int colB = posB % N;
        int belowB = (row + 1) * N + colB;

        List<Integer> allMoves = new ArrayList<>();

        List<Integer> step1 = moveTileBFS(grid, tileB, belowB, locked);
        if (step1 == null) return null;
        applyMovesBFS(grid, step1);
        allMoves.addAll(step1);

        Set<Integer> lock2 = new HashSet<>(locked);
        lock2.add(belowB);
        List<Integer> step2 = moveTileBFS(grid, tileA, posB, lock2);
        if (step2 == null) return null;
        applyMovesBFS(grid, step2);
        allMoves.addAll(step2);

        List<Integer> step3 = movePairBFS(grid, tileA, tileB, posA, posB, locked);
        if (step3 == null) return null;
        applyMovesBFS(grid, step3);
        allMoves.addAll(step3);

        return allMoves;
    }

    private static List<Integer> solveColPair(int[] grid, int tileA, int tileB, Set<Integer> locked) {
        int posA = tileA, posB = tileB;
        if (findTile(grid, tileA) == posA && findTile(grid, tileB) == posB) return List.of();

        List<Integer> direct = movePairBFS(grid, tileA, tileB, posA, posB, locked);
        if (direct != null) return direct;

        int col = posA % N;
        if (col >= N - 1) return null;

        int rightB = posB + 1;

        List<Integer> allMoves = new ArrayList<>();

        List<Integer> step1 = moveTileBFS(grid, tileB, rightB, locked);
        if (step1 == null) return null;
        applyMovesBFS(grid, step1);
        allMoves.addAll(step1);

        Set<Integer> lock2 = new HashSet<>(locked);
        lock2.add(rightB);
        List<Integer> step2 = moveTileBFS(grid, tileA, posB, lock2);
        if (step2 == null) return null;
        applyMovesBFS(grid, step2);
        allMoves.addAll(step2);

        List<Integer> step3 = movePairBFS(grid, tileA, tileB, posA, posB, locked);
        if (step3 == null) return null;
        applyMovesBFS(grid, step3);
        allMoves.addAll(step3);

        return allMoves;
    }

    private static void applyMovesBFS(int[] grid, List<Integer> moves) {
        for (int clickPos : moves) {
            int ep = findBlankPos(grid);
            grid[ep] = grid[clickPos];
            grid[clickPos] = BLANK;
        }
    }

    // ════════════════════════════════════════════════════════════════
    //  MTM ↔ STM CONVERSION
    // ════════════════════════════════════════════════════════════════

    public static List<Integer> expandToSTM(List<Integer> mtmClicks, int[] initialTiles) {
        if (mtmClicks == null || mtmClicks.isEmpty()) return mtmClicks != null ? mtmClicks : List.of();

        int[] g = initialTiles.clone();
        int blank = findBlankPos(g);
        List<Integer> stm = new ArrayList<>();

        for (int click : mtmClicks) {
            int br = blank / N, bc = blank % N;
            int cr = click / N, cc = click % N;

            if (br == cr) {
                int step = (cc > bc) ? 1 : -1;
                for (int c = bc + step; ; c += step) {
                    int pos = br * N + c;
                    stm.add(pos);
                    g[blank] = g[pos];
                    g[pos] = BLANK;
                    blank = pos;
                    if (c == cc) break;
                }
            } else {
                int step = (cr > br) ? 1 : -1;
                for (int r = br + step; ; r += step) {
                    int pos = r * N + bc;
                    stm.add(pos);
                    g[blank] = g[pos];
                    g[pos] = BLANK;
                    blank = pos;
                    if (r == cr) break;
                }
            }
        }
        return stm;
    }

    public static List<Integer> compressToMTM(List<Integer> stmClicks, int[] initialTiles) {
        if (stmClicks == null || stmClicks.isEmpty()) return stmClicks != null ? stmClicks : List.of();

        int[] g = initialTiles.clone();
        int blank = findBlankPos(g);
        List<Integer> mtm = new ArrayList<>();

        int i = 0;
        while (i < stmClicks.size()) {
            int click = stmClicks.get(i);
            int br = blank / N, bc = blank % N;
            int cr = click / N, cc = click % N;

            boolean horiz = (br == cr);
            int dir = horiz ? Integer.signum(cc - bc) : Integer.signum(cr - br);

            g[blank] = g[click];
            g[click] = BLANK;
            blank = click;
            i++;

            while (i < stmClicks.size()) {
                int nextClick = stmClicks.get(i);
                int nbr = blank / N, nbc = blank % N;
                int ncr = nextClick / N, ncc = nextClick % N;

                boolean nextHoriz = (nbr == ncr);
                if (nextHoriz != horiz) break;

                int nextDir = nextHoriz
                        ? Integer.signum(ncc - nbc)
                        : Integer.signum(ncr - nbr);
                if (nextDir != dir) break;

                g[blank] = g[nextClick];
                g[nextClick] = BLANK;
                blank = nextClick;
                i++;
            }

            mtm.add(blank);
        }

        return mtm;
    }

    // ════════════════════════════════════════════════════════════════
    //  HELPERS
    // ════════════════════════════════════════════════════════════════

    private static int findBlankPos(int[] grid) {
        for (int i = 0; i < TOTAL; i++) if (grid[i] == BLANK) return i;
        return -1;
    }

    private static int findTile(int[] grid, int tile) {
        for (int i = 0; i < TOTAL; i++) if (grid[i] == tile) return i;
        return -1;
    }

    private static boolean isSolved(int[] tiles) {
        for (int i = 0; i < TOTAL; i++) if (tiles[i] != i) return false;
        return true;
    }
}
