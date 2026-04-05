package com.botwithus.bot.pathfinder;

import com.botwithus.bot.api.nav.CollisionMap;
import com.botwithus.bot.api.nav.NavRegion;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;

import static com.botwithus.bot.api.nav.CollisionFlags.*;

/**
 * A* pathfinder with transition edge support.
 * <p>
 * Unlike the plain {@link com.botwithus.bot.api.nav.LocalPathfinder}, this
 * implementation treats transitions (doors, gates, stiles, stairs) as first-class
 * graph edges. When a player is enclosed (e.g. inside the Lumbridge cow pen),
 * the A* naturally routes through transition edges because all walk directions
 * are blocked by transition walls.
 * <p>
 * <b>Walk vs Transition decision</b>: Both walk edges and transition edges compete
 * in the same A* priority queue. The cheapest path wins. Transitions carry type-specific
 * penalties (doors: 200, gates: 60, stairs: 25) to discourage unnecessary interaction
 * when walking is possible.
 * <p>
 * Thread-safe: each pathfinding call uses method-local state for same-region searches.
 * Cross-region searches allocate temporary buffers.
 *
 * <h3>Execution models</h3>
 * <ul>
 *   <li><b>Blocking</b>: call {@link #findPath} directly — returns when done</li>
 *   <li><b>Non-blocking</b>: call from a virtual thread or submit to an executor;
 *       use {@link PathExecutor} for step-by-step execution on the game loop</li>
 * </ul>
 */
public final class AStarPathfinder {

    private static final Logger log = LoggerFactory.getLogger(AStarPathfinder.class);

    static final int COST_CARDINAL = 10;
    static final int COST_DIAGONAL = 14;
    private static final int MAX_EXPANSIONS_SAME = 4096;
    private static final int MAX_EXPANSIONS_CROSS = 65536;
    private static final int MAX_CROSS_REGION_SPAN = 512;
    private static final int MAX_JITTER = 5;

    private final CollisionMap map;
    private final TransitionStore transitions;

    /** Cross-plane search trace — populated during findPathCrossPlane, readable after. */
    private final List<String> searchTrace = new ArrayList<>();
    public List<String> getSearchTrace() { return List.copyOf(searchTrace); }
    private void trace(String msg) { log.info(msg); searchTrace.add(msg); }

    /** Types currently enabled for pathfinding. Null = all enabled. */
    private volatile java.util.Set<Transition.Type> enabledTypes;

    public AStarPathfinder(CollisionMap map, TransitionStore transitions) {
        this.map = map;
        this.transitions = transitions;
    }

    public CollisionMap getCollisionMap() { return map; }
    public TransitionStore getTransitionStore() { return transitions; }

    /**
     * Sets which transition types the pathfinder considers.
     * Pass null to enable all types.
     */
    public void setEnabledTypes(java.util.Set<Transition.Type> types) {
        this.enabledTypes = types;
    }

    private boolean isTypeEnabled(Transition.Type type) {
        var et = enabledTypes;
        return et == null || et.contains(type);
    }

    // ── Public API ───────────────────────────────────────────────

    /**
     * Finds a path from start to dest on the same plane, using both walking
     * and transition edges.
     *
     * @param seed jitter seed for path variation (0 = deterministic shortest)
     */
    public PathResult findPath(int startX, int startY, int destX, int destY, int plane, long seed) {
        if (startX == destX && startY == destY) {
            return new PathResult(true, 0, List.of());
        }

        // If start is non-walkable (e.g. stair landing), find nearest walkable start
        int effStartX = startX, effStartY = startY;
        if (!map.isWalkable(startX, startY, plane)) {
            int[] walkable = findNearestWalkable(startX, startY, plane);
            if (walkable == null) return PathResult.notFound();
            effStartX = walkable[0];
            effStartY = walkable[1];
        }

        // Try same-plane pathfinding
        PathResult direct = findPathDirect(effStartX, effStartY, destX, destY, plane, seed);
        if (direct.found()) return direct;

        // Try adjacent tiles if dest is unwalkable (object tile)
        if (!map.isWalkable(destX, destY, plane)) {
            return findPathToAdjacent(effStartX, effStartY, destX, destY, plane, seed);
        }

        return PathResult.notFound();
    }

    /** Convenience — no jitter. */
    public PathResult findPath(int startX, int startY, int destX, int destY, int plane) {
        return findPath(startX, startY, destX, destY, plane, 0);
    }

    /**
     * Finds a path across planes using transition chains (stairs, ladders).
     */
    /** Max tiles from start/dest to consider a transition candidate. */
    private static final int CROSS_PLANE_MARGIN = 128;
    /** Max transition candidates to evaluate with full A*. */
    private static final int CROSS_PLANE_MAX_EVAL = 16;
    /** Max recursion depth for multi-floor routing. */
    private static final int CROSS_PLANE_MAX_DEPTH = 6;

    public PathResult findPathCrossPlane(int startX, int startY, int startPlane,
                                          int destX, int destY, int destPlane, long seed) {
        searchTrace.clear();
        return findPathCrossPlane(startX, startY, startPlane, destX, destY, destPlane, seed, 0, -1);
    }

    private PathResult findPathCrossPlane(int startX, int startY, int startPlane,
                                           int destX, int destY, int destPlane, long seed,
                                           int depth, int cameFromPlane) {
        if (startPlane == destPlane) {
            // Try same-plane first (both in the same building).
            // Always use findPath here — it handles unwalkable start via
            // findNearestWalkable (rings 1-3), which is more robust than
            // findPathFromAdjacent (ring 1 only). findPathFromAdjacent is
            // reserved for cross-plane transition landings where connectivity
            // analysis matters.
            PathResult samePlane = findPath(startX, startY, destX, destY, startPlane, seed);
            if (samePlane.found()) return samePlane;
            // Same-plane failed — fall through to cross-plane routing.
            trace(String.format("CrossPlane d%d: same-plane %d failed (%d,%d)->(%d,%d), trying cross-plane",
                    depth, startPlane, startX, startY, destX, destY));
        }
        if (depth >= CROSS_PLANE_MAX_DEPTH) {
            trace(String.format("CrossPlane d%d: MAX_DEPTH reached (%d,%d,%d)->(%d,%d,%d)",
                    depth, startX, startY, startPlane, destX, destY, destPlane));
            return PathResult.notFound();
        }

        final int sx = startX, sy = startY;
        final int dx = destX, dy = destY;

        // ── 1. Bounding box filter: only transitions near start or dest ──
        // This reduces 4000+ candidates to a handful.
        int minX = Math.min(sx, dx) - CROSS_PLANE_MARGIN;
        int maxX = Math.max(sx, dx) + CROSS_PLANE_MARGIN;
        int minY = Math.min(sy, dy) - CROSS_PLANE_MARGIN;
        int maxY = Math.max(sy, dy) + CROSS_PLANE_MARGIN;

        List<Transition> candidates = new ArrayList<>();
        for (Transition t : transitions.getAll()) {
            if (!isTypeEnabled(t.type())) continue;
            if (t.srcP() != startPlane || t.dstP() == startPlane) continue;
            // Prevent backtracking: don't go back to the plane we just came from.
            // This stops 3→2→3→2→... cycles that cause exponential explosion.
            // Exception: the destination plane is always allowed (final hop).
            if (t.dstP() == cameFromPlane && t.dstP() != destPlane) continue;
            // Skip xapi_live staircase entries where src != dst (bad captures where
            // the player position was recorded instead of the object position).
            // Valid staircase transitions always have src == dst (same tile, different plane).
            // Bounding box: src must be reachable from start, dst should be toward dest
            if (t.srcX() < minX || t.srcX() > maxX || t.srcY() < minY || t.srcY() > maxY) continue;
            candidates.add(t);
        }

        if (candidates.isEmpty()) return PathResult.notFound();

        // ── 2. Sort by reachability first, then destination proximity ──
        // Primary: distance from start to transition source (can we reach it?)
        // Secondary: small bonus for matching destination plane
        // This ensures nearby reachable transitions are tried before far-away
        // ones on the "correct" plane that can't be reached from the current building.
        candidates.sort((a, b) -> {
            int distA = Math.abs(a.srcX() - sx) + Math.abs(a.srcY() - sy);
            int distB = Math.abs(b.srcX() - sx) + Math.abs(b.srcY() - sy);
            // Small penalty for intermediate planes (doesn't override start distance)
            if (a.dstP() != destPlane) distA += 50;
            if (b.dstP() != destPlane) distB += 50;
            return Integer.compare(distA, distB);
        });

        int maxEval = Math.min(candidates.size(), CROSS_PLANE_MAX_EVAL);
        trace(String.format("CrossPlane d%d: (%d,%d,%d)->(%d,%d,%d) candidates=%d eval=%d",
                depth, startX, startY, startPlane, destX, destY, destPlane, candidates.size(), maxEval));

        // ── 3. Evaluate top candidates with full A* ──
        PathResult best = PathResult.notFound();

        for (int i = 0; i < maxEval; i++) {
            Transition t = candidates.get(i);

            // Early exit: heuristic lower bound exceeds best known cost
            if (best.found()) {
                int lb = (Math.abs(t.srcX() - sx) + Math.abs(t.srcY() - sy)
                        + Math.abs(t.dstX() - dx) + Math.abs(t.dstY() - dy)) * COST_CARDINAL;
                if (lb > best.totalCost()) break;
            }

            // Path from start to transition source.
            // At recursive depths, the start is a previous transition's landing tile
            // which is often unwalkable. Use findPathFromAdjacent for the start to
            // avoid picking a neighbor on the wrong side of a wall.
            PathResult toTransition;
            boolean startUnwalkable = !map.isWalkable(startX, startY, startPlane);
            if (startUnwalkable) {
                toTransition = findPathFromAdjacent(startX, startY, t.srcX(), t.srcY(), startPlane, seed);
            } else {
                toTransition = findPath(startX, startY, t.srcX(), t.srcY(), startPlane, seed);
            }
            if (!toTransition.found()) {
                trace(String.format("  d%d #%d '%s' (%d,%d,%d) -> no path TO src", depth, i, t.name(), t.srcX(), t.srcY(), t.srcP()));
                continue;
            }
            // Note: zero-step toTransition paths are valid for back-to-back transitions
            // (e.g. ladder landing at (2995,3341), next ladder at (2994,3341) — 1 tile gap).
            // The player interacts from the adjacent tile without walking.

            // Path from transition dest to final dest.
            // The landing tile is often unwalkable (object tile). Use findPathFromAdjacent
            // which tries all walkable neighbors with full A*, ensuring we don't start on
            // the wrong side of a wall (findNearestWalkable picks the first match blindly).
            PathResult fromTransition;
            if (t.dstP() == destPlane) {
                // Try same-plane first (e.g. both in the same building on plane 2)
                if (map.isWalkable(t.dstX(), t.dstY(), destPlane)) {
                    fromTransition = findPath(t.dstX(), t.dstY(), destX, destY, destPlane, seed);
                } else {
                    fromTransition = findPathFromAdjacent(t.dstX(), t.dstY(), destX, destY, destPlane, seed);
                }
                // Fallback: same dest plane but not reachable on that plane
                // (e.g. plane 2 in Falador → plane 2 in Lumbridge requires going
                // through plane 1 overworld). Use cross-plane routing.
                if (!fromTransition.found() && depth + 1 < CROSS_PLANE_MAX_DEPTH) {
                    fromTransition = findPathCrossPlane(t.dstX(), t.dstY(), t.dstP(),
                            destX, destY, destPlane, seed, depth + 1, startPlane);
                }
            } else {
                fromTransition = findPathCrossPlane(t.dstX(), t.dstY(), t.dstP(),
                        destX, destY, destPlane, seed, depth + 1, startPlane);
            }
            if (!fromTransition.found()) {
                trace(String.format("  d%d #%d '%s' -> no path FROM dst (%d,%d,%d)", depth, i, t.name(), t.dstX(), t.dstY(), t.dstP()));
                continue;
            }

            // Combine: walk to transition + interact + walk from transition
            int transitionCost = t.costTicks() * COST_CARDINAL + t.type().penalty();
            int totalCost = toTransition.totalCost() + transitionCost + fromTransition.totalCost();

            if (totalCost < best.totalCost()) {
                List<PathStep> combined = new ArrayList<>(toTransition.steps());
                combined.add(new PathStep.Interact(
                        t.name(), t.option(), t.srcX(), t.srcY(), t.srcP(),
                        t.dstX(), t.dstY(), t.type()));
                combined.addAll(fromTransition.steps());
                best = new PathResult(true, totalCost, combined);
                trace(String.format("  d%d Found route via '%s' (%d,%d,%d) cost=%d",
                        depth, t.name(), t.srcX(), t.srcY(), t.srcP(), totalCost));
            }
        }

        return best;
    }

    // ── Internal routing ─────────────────────────────────────────

    private PathResult findPathDirect(int startX, int startY, int destX, int destY,
                                       int plane, long seed) {
        int startRegion = toRegionId(startX, startY);
        int destRegion = toRegionId(destX, destY);

        if (startRegion == destRegion) {
            ensureWallsApplied(startRegion, plane);
            PathResult same = findPathSameRegion(startRegion, plane,
                    toLocalX(startX), toLocalY(startY),
                    toLocalX(destX), toLocalY(destY), seed);
            if (same.found()) return same;
        }

        return findPathCrossRegion(startX, startY, destX, destY, plane, seed);
    }

    private PathResult findPathToAdjacent(int startX, int startY,
                                            int destX, int destY, int plane, long seed) {
        PathResult best = PathResult.notFound();
        int[][] neighbors = {
                {0, 1, FLAG_MOVE_SOUTH}, {1, 0, FLAG_MOVE_WEST},
                {0, -1, FLAG_MOVE_NORTH}, {-1, 0, FLAG_MOVE_EAST}};
        for (int[] d : neighbors) {
            int adjX = destX + d[0], adjY = destY + d[1];
            if (!map.isWalkable(adjX, adjY, plane)) continue;
            PathResult adj = findPathDirect(startX, startY, adjX, adjY, plane, seed);
            if (adj.found() && adj.totalCost() < best.totalCost()) {
                best = adj;
            }
        }
        return best;
    }

    /**
     * Finds a path from an unwalkable start (e.g. transition landing) to a destination.
     * <p>
     * Tries each walkable neighbor of the start as the effective origin.
     * Neighbors that are isolated (can't walk to any other walkable neighbor of the start)
     * are deprioritized — they're likely on the wrong side of a wall. Among connected
     * neighbors, the cheapest path wins. Falls back to isolated neighbors only if no
     * connected neighbor produces a valid path.
     */
    private PathResult findPathFromAdjacent(int startX, int startY,
                                             int destX, int destY, int plane, long seed) {
        int[][] offsets = {{0, 1}, {1, 0}, {0, -1}, {-1, 0},
                {1, 1}, {1, -1}, {-1, 1}, {-1, -1}};

        // Collect walkable neighbors
        List<int[]> walkable = new ArrayList<>();
        for (int[] d : offsets) {
            int nx = startX + d[0], ny = startY + d[1];
            if (map.isWalkable(nx, ny, plane)) walkable.add(new int[]{nx, ny});
        }

        // Classify: a neighbor is "connected" if it can walk to at least one other
        // walkable neighbor. Isolated neighbors are likely on the wrong side of a wall.
        boolean[] connected = new boolean[walkable.size()];
        for (int i = 0; i < walkable.size(); i++) {
            for (int j = i + 1; j < walkable.size(); j++) {
                int dx = walkable.get(j)[0] - walkable.get(i)[0];
                int dy = walkable.get(j)[1] - walkable.get(i)[1];
                if (Math.abs(dx) > 1 || Math.abs(dy) > 1) continue; // not adjacent
                // Check if cardinal movement between them is possible
                if (dx == 0 || dy == 0) {
                    // Cardinal: check direction flag
                    int dir = dy == 1 ? FLAG_MOVE_NORTH : dy == -1 ? FLAG_MOVE_SOUTH
                            : dx == 1 ? FLAG_MOVE_EAST : FLAG_MOVE_WEST;
                    if (map.canMove(walkable.get(i)[0], walkable.get(i)[1], plane, dir)) {
                        connected[i] = true;
                        connected[j] = true;
                    }
                } else {
                    // Diagonal: both cardinal components must be passable
                    int horizDir = dx == 1 ? FLAG_MOVE_EAST : FLAG_MOVE_WEST;
                    int vertDir = dy == 1 ? FLAG_MOVE_NORTH : FLAG_MOVE_SOUTH;
                    if (map.canMove(walkable.get(i)[0], walkable.get(i)[1], plane, horizDir)
                            && map.canMove(walkable.get(i)[0], walkable.get(i)[1], plane, vertDir)) {
                        connected[i] = true;
                        connected[j] = true;
                    }
                }
            }
        }

        // Try connected neighbors first, then fall back to isolated
        PathResult bestConnected = PathResult.notFound();
        PathResult bestIsolated = PathResult.notFound();
        for (int i = 0; i < walkable.size(); i++) {
            int[] n = walkable.get(i);
            PathResult adj = findPath(n[0], n[1], destX, destY, plane, seed);
            if (!adj.found()) continue;
            if (connected[i]) {
                if (adj.totalCost() < bestConnected.totalCost()) bestConnected = adj;
            } else {
                if (adj.totalCost() < bestIsolated.totalCost()) bestIsolated = adj;
            }
        }
        return bestConnected.found() ? bestConnected : bestIsolated;
    }

    /**
     * Finds the nearest walkable tile to the given world position.
     * Searches in Chebyshev distance rings from r=1 to r=3.
     * Returns {x, y} or null if nothing found.
     */
    private int[] findNearestWalkable(int worldX, int worldY, int plane) {
        for (int r = 1; r <= 3; r++) {
            for (int dx = -r; dx <= r; dx++) {
                for (int dy = -r; dy <= r; dy++) {
                    if (Math.abs(dx) != r && Math.abs(dy) != r) continue; // ring only
                    int nx = worldX + dx, ny = worldY + dy;
                    if (map.isWalkable(nx, ny, plane)) {
                        return new int[]{nx, ny};
                    }
                }
            }
        }
        return null;
    }

    /** Ensures transition walls are applied to the region's collision flags. */
    private void ensureWallsApplied(int regionId, int plane) {
        NavRegion region = map.getOrLoad(regionId, plane);
        if (region != null) {
            transitions.applyWalls(region);
        }
    }

    // ── Same-region A* with transitions ──────────────────────────

    private PathResult findPathSameRegion(int regionId, int plane,
                                           int startLX, int startLY,
                                           int destLX, int destLY, long seed) {
        NavRegion region = map.getOrLoad(regionId, plane);
        if (region == null) return PathResult.notFound();
        if (!region.isWalkable(destLX, destLY)) return PathResult.notFound();

        int startIdx = tileIndex(startLX, startLY);
        int destIdx = tileIndex(destLX, destLY);
        if (startIdx == destIdx) return new PathResult(true, 0, List.of());

        SearchContext ctx = new SearchContext(TILES_PER_REGION);
        ctx.reset();
        ctx.touch(startIdx);
        ctx.gCost[startIdx] = 0;
        ctx.state[startIdx] = SearchContext.OPEN;
        ctx.heapInsert(startIdx, heuristic(startLX, startLY, destLX, destLY));

        byte[] flags = region.getFlagsArray();
        byte[] diagFlags = region.getDiagFlagsArray();
        // Track which tiles were reached via transition (for interact step insertion)
        int[] transitionFrom = new int[TILES_PER_REGION];
        Arrays.fill(transitionFrom, -1);
        // Map tile index to the transition used to reach it
        Transition[] transitionUsed = new Transition[TILES_PER_REGION];

        int expansions = 0;

        while (ctx.heapSize > 0 && expansions < MAX_EXPANSIONS_SAME) {
            int current = ctx.heapExtractMin();
            if (current == destIdx) {
                return reconstructSameRegion(ctx.parent, transitionFrom, transitionUsed,
                        current, regionId, plane);
            }
            ctx.state[current] = SearchContext.CLOSED;
            expansions++;

            int cx = current & 63;
            int cy = current >> 6;
            int cf = flags[current] & 0xFF;
            int currentG = ctx.gCost[current];

            // ── Cardinal walk neighbors ──
            expandCardinal(ctx, flags, current, cx, cy, cf, currentG, destLX, destLY, seed,
                    transitionFrom, transitionUsed);

            // ── Diagonal walk neighbors ──
            expandDiagonal(ctx, flags, diagFlags, current, cx, cy, cf, currentG, destLX, destLY, seed,
                    transitionFrom, transitionUsed);

            // ── Transition edges ──
            List<Transition> tList = transitions.getAt(regionId, plane, current);
            for (Transition t : tList) {
                if (!isTypeEnabled(t.type())) continue;
                // Only same-region, same-plane destinations in this method
                int dstRegion = toRegionId(t.dstX(), t.dstY());
                if (dstRegion != regionId || t.dstP() != plane) continue;

                int dstLX = toLocalX(t.dstX());
                int dstLY = toLocalY(t.dstY());
                int dstIdx = tileIndex(dstLX, dstLY);
                if (ctx.isClosed(dstIdx)) continue;

                ctx.touch(dstIdx);
                int tCost = t.costTicks() * COST_CARDINAL + t.type().penalty();
                int newG = currentG + tCost + edgeJitter(seed, current, dstIdx);

                if (newG < ctx.gCost[dstIdx]) {
                    ctx.gCost[dstIdx] = newG;
                    ctx.parent[dstIdx] = current;
                    ctx.state[dstIdx] = SearchContext.OPEN;
                    transitionFrom[dstIdx] = current;
                    transitionUsed[dstIdx] = t;
                    ctx.heapInsertOrDecrease(dstIdx, newG + heuristic(dstLX, dstLY, destLX, destLY));
                }
            }
        }

        return PathResult.notFound();
    }

    private void expandCardinal(SearchContext ctx, byte[] flags,
                                 int current, int cx, int cy, int cf, int currentG,
                                 int destLX, int destLY, long seed,
                                 int[] transitionFrom, Transition[] transitionUsed) {
        int[][] dirs = {{0, 1, FLAG_MOVE_NORTH}, {1, 0, FLAG_MOVE_EAST},
                {0, -1, FLAG_MOVE_SOUTH}, {-1, 0, FLAG_MOVE_WEST}};
        for (int[] d : dirs) {
            if ((cf & d[2]) == 0) continue;
            int nx = cx + d[0], ny = cy + d[1];
            if (nx < 0 || nx >= REGION_SIZE || ny < 0 || ny >= REGION_SIZE) continue;
            int nIdx = tileIndex(nx, ny);
            if (ctx.isClosed(nIdx)) continue;
            if ((flags[nIdx] & FLAG_WALKABLE) == 0) continue;

            ctx.touch(nIdx);
            int newG = currentG + COST_CARDINAL + edgeJitter(seed, current, nIdx);
            if (newG < ctx.gCost[nIdx]) {
                ctx.gCost[nIdx] = newG;
                ctx.parent[nIdx] = current;
                ctx.state[nIdx] = SearchContext.OPEN;
                // Walk edge won — clear any stale transition markers
                transitionFrom[nIdx] = -1;
                transitionUsed[nIdx] = null;
                ctx.heapInsertOrDecrease(nIdx, newG + heuristic(nx, ny, destLX, destLY));
            }
        }
    }

    private void expandDiagonal(SearchContext ctx, byte[] flags, byte[] diagFlags,
                                 int current, int cx, int cy, int cf, int currentG,
                                 int destLX, int destLY, long seed,
                                 int[] transitionFrom, Transition[] transitionUsed) {
        int[][] diags = {{1, 1}, {-1, 1}, {1, -1}, {-1, -1}};
        for (int[] dd : diags) {
            int nx = cx + dd[0], ny = cy + dd[1];
            if (nx < 0 || nx >= REGION_SIZE || ny < 0 || ny >= REGION_SIZE) continue;
            int nIdx = tileIndex(nx, ny);
            if (ctx.isClosed(nIdx)) continue;
            if ((flags[nIdx] & FLAG_WALKABLE) == 0) continue;

            int horizFlag = dd[0] > 0 ? FLAG_MOVE_EAST : FLAG_MOVE_WEST;
            int vertFlag = dd[1] > 0 ? FLAG_MOVE_NORTH : FLAG_MOVE_SOUTH;
            if ((cf & horizFlag) == 0 || (cf & vertFlag) == 0) continue;

            int vertNeighborIdx = tileIndex(cx, cy + dd[1]);
            if ((flags[vertNeighborIdx] & horizFlag) == 0) continue;
            int horizNeighborIdx = tileIndex(cx + dd[0], cy);
            if ((flags[horizNeighborIdx] & vertFlag) == 0) continue;

            int dBit = diagBit(dd[0], dd[1]);
            if ((diagFlags[current] & dBit) == 0) continue;
            if ((diagFlags[nIdx] & diagReverse(dBit)) == 0) continue;

            ctx.touch(nIdx);
            int newG = currentG + COST_DIAGONAL + edgeJitter(seed, current, nIdx);
            if (newG < ctx.gCost[nIdx]) {
                ctx.gCost[nIdx] = newG;
                ctx.parent[nIdx] = current;
                ctx.state[nIdx] = SearchContext.OPEN;
                transitionFrom[nIdx] = -1;
                transitionUsed[nIdx] = null;
                ctx.heapInsertOrDecrease(nIdx, newG + heuristic(nx, ny, destLX, destLY));
            }
        }
    }

    // ── Cross-region A* with transitions ─────────────────────────

    private PathResult findPathCrossRegion(int startX, int startY,
                                            int destX, int destY, int plane, long seed) {
        int minX = Math.min(startX, destX) - 64;
        int minY = Math.min(startY, destY) - 64;
        int maxX = Math.max(startX, destX) + 64;
        int maxY = Math.max(startY, destY) + 64;
        int width = maxX - minX + 1;
        int height = maxY - minY + 1;

        if (width > MAX_CROSS_REGION_SPAN || height > MAX_CROSS_REGION_SPAN) {
            return PathResult.notFound();
        }

        // Ensure walls are applied for all regions in the search area
        for (int wy = minY; wy <= maxY; wy += REGION_SIZE) {
            for (int wx = minX; wx <= maxX; wx += REGION_SIZE) {
                ensureWallsApplied(toRegionId(wx, wy), plane);
            }
        }

        int totalTiles = width * height;
        SearchContext ctx = new SearchContext(totalTiles);
        ctx.reset();

        int sGX = startX - minX, sGY = startY - minY;
        int dGX = destX - minX, dGY = destY - minY;
        int startIdx = sGY * width + sGX;
        int destIdx = dGY * width + dGX;

        ctx.touch(startIdx);
        ctx.gCost[startIdx] = 0;
        ctx.state[startIdx] = SearchContext.OPEN;
        ctx.heapInsert(startIdx, heuristic(sGX, sGY, dGX, dGY));

        // Transition tracking for cross-region
        int[] transitionFrom = new int[totalTiles];
        Arrays.fill(transitionFrom, -1);
        Transition[] transitionUsed = new Transition[totalTiles];

        int expansions = 0;
        int maxExpansions = Math.min(totalTiles, MAX_EXPANSIONS_CROSS);

        while (ctx.heapSize > 0 && expansions < maxExpansions) {
            int current = ctx.heapExtractMin();
            if (current == destIdx) {
                return reconstructCrossRegion(ctx.parent, transitionFrom, transitionUsed,
                        current, width, minX, minY, plane, ctx.gCost[destIdx]);
            }
            ctx.state[current] = SearchContext.CLOSED;
            expansions++;

            int cx = current % width;
            int cy = current / width;
            int worldCX = cx + minX;
            int worldCY = cy + minY;
            int currentG = ctx.gCost[current];

            // ── Cardinal walk neighbors ──
            int[][] dirs = {{0, 1, FLAG_MOVE_NORTH}, {1, 0, FLAG_MOVE_EAST},
                    {0, -1, FLAG_MOVE_SOUTH}, {-1, 0, FLAG_MOVE_WEST}};
            for (int[] d : dirs) {
                int nx = cx + d[0], ny = cy + d[1];
                if (nx < 0 || nx >= width || ny < 0 || ny >= height) continue;
                int nIdx = ny * width + nx;
                if (ctx.isClosed(nIdx)) continue;
                if (!map.canMove(worldCX, worldCY, plane, d[2])) continue;
                if (!map.isWalkable(nx + minX, ny + minY, plane)) continue;

                ctx.touch(nIdx);
                int newG = currentG + COST_CARDINAL + edgeJitter(seed, current, nIdx);
                if (newG < ctx.gCost[nIdx]) {
                    ctx.gCost[nIdx] = newG;
                    ctx.parent[nIdx] = current;
                    ctx.state[nIdx] = SearchContext.OPEN;
                    ctx.heapInsertOrDecrease(nIdx, newG + heuristic(nx, ny, dGX, dGY));
                }
            }

            // ── Diagonal walk neighbors ──
            int[][] diags = {{1, 1}, {-1, 1}, {1, -1}, {-1, -1}};
            for (int[] dd : diags) {
                int dnx = cx + dd[0], dny = cy + dd[1];
                if (dnx < 0 || dnx >= width || dny < 0 || dny >= height) continue;
                int dnIdx = dny * width + dnx;
                if (ctx.isClosed(dnIdx)) continue;
                int worldDNX = dnx + minX, worldDNY = dny + minY;
                if (!map.isWalkable(worldDNX, worldDNY, plane)) continue;

                int horizDir = dd[0] > 0 ? FLAG_MOVE_EAST : FLAG_MOVE_WEST;
                int vertDir = dd[1] > 0 ? FLAG_MOVE_NORTH : FLAG_MOVE_SOUTH;
                if (!map.canMove(worldCX, worldCY, plane, horizDir)) continue;
                if (!map.canMove(worldCX, worldCY, plane, vertDir)) continue;
                if (!map.canMove(worldCX, worldCY + dd[1], plane, horizDir)) continue;
                if (!map.canMove(worldCX + dd[0], worldCY, plane, vertDir)) continue;

                int dBit = diagBit(dd[0], dd[1]);
                if ((map.getDiagFlags(worldCX, worldCY, plane) & dBit) == 0) continue;
                if ((map.getDiagFlags(worldDNX, worldDNY, plane) & diagReverse(dBit)) == 0) continue;

                ctx.touch(dnIdx);
                int newG = currentG + COST_DIAGONAL + edgeJitter(seed, current, dnIdx);
                if (newG < ctx.gCost[dnIdx]) {
                    ctx.gCost[dnIdx] = newG;
                    ctx.parent[dnIdx] = current;
                    ctx.state[dnIdx] = SearchContext.OPEN;
                    ctx.heapInsertOrDecrease(dnIdx, newG + heuristic(dnx, dny, dGX, dGY));
                }
            }

            // ── Transition edges ──
            List<Transition> tList = transitions.getAtWorld(worldCX, worldCY, plane);
            for (Transition t : tList) {
                if (!isTypeEnabled(t.type())) continue;
                if (t.dstP() != plane) continue; // cross-plane handled separately
                int tdx = t.dstX() - minX, tdy = t.dstY() - minY;
                if (tdx < 0 || tdx >= width || tdy < 0 || tdy >= height) continue;
                int tIdx = tdy * width + tdx;
                if (ctx.isClosed(tIdx)) continue;

                ctx.touch(tIdx);
                int tCost = t.costTicks() * COST_CARDINAL + t.type().penalty();
                int newG = currentG + tCost + edgeJitter(seed, current, tIdx);
                if (newG < ctx.gCost[tIdx]) {
                    ctx.gCost[tIdx] = newG;
                    ctx.parent[tIdx] = current;
                    ctx.state[tIdx] = SearchContext.OPEN;
                    transitionFrom[tIdx] = current;
                    transitionUsed[tIdx] = t;
                    ctx.heapInsertOrDecrease(tIdx, newG + heuristic(tdx, tdy, dGX, dGY));
                }
            }
        }

        return PathResult.notFound();
    }

    // ── Path reconstruction ──────────────────────────────────────

    private PathResult reconstructSameRegion(int[] parent, int[] transitionFrom,
                                              Transition[] transitionUsed,
                                              int destIdx, int regionId, int plane) {
        int regionBaseX = (regionId >> 8) << 6;
        int regionBaseY = (regionId & 0xFF) << 6;

        List<int[]> rawPath = new ArrayList<>();
        List<Transition> rawTransitions = new ArrayList<>();
        int current = destIdx;
        int cost = 0;
        int prev = -1;

        while (current != -1) {
            int lx = current & 63;
            int ly = current >> 6;
            rawPath.add(new int[]{regionBaseX + lx, regionBaseY + ly});

            // Check if this tile was reached via a transition
            rawTransitions.add(transitionFrom[current] >= 0 ? transitionUsed[current] : null);

            if (prev != -1) {
                Transition t = transitionFrom[current] >= 0 ? transitionUsed[current] : null;
                if (t != null) {
                    cost += t.costTicks() * COST_CARDINAL + t.type().penalty();
                } else {
                    int dx = Math.abs((prev & 63) - lx);
                    int dy = Math.abs((prev >> 6) - ly);
                    cost += (dx != 0 && dy != 0) ? COST_DIAGONAL : COST_CARDINAL;
                }
            }
            prev = current;
            current = parent[current];
        }

        Collections.reverse(rawPath);
        Collections.reverse(rawTransitions);
        if (!rawPath.isEmpty()) {
            rawPath.removeFirst();
            rawTransitions.removeFirst();
        }

        return buildSteps(rawPath, rawTransitions, plane, cost);
    }

    private PathResult reconstructCrossRegion(int[] parent, int[] transitionFrom,
                                               Transition[] transitionUsed,
                                               int destIdx, int width,
                                               int minX, int minY, int plane, int totalCost) {
        List<int[]> rawPath = new ArrayList<>();
        List<Transition> rawTransitions = new ArrayList<>();
        int current = destIdx;

        while (current != -1) {
            int lx = current % width;
            int ly = current / width;
            rawPath.add(new int[]{lx + minX, ly + minY});
            rawTransitions.add(transitionFrom[current] >= 0 ? transitionUsed[current] : null);
            current = parent[current];
        }

        Collections.reverse(rawPath);
        Collections.reverse(rawTransitions);
        if (!rawPath.isEmpty()) {
            rawPath.removeFirst();
            rawTransitions.removeFirst();
        }

        return buildSteps(rawPath, rawTransitions, plane, totalCost);
    }

    /**
     * Converts raw coordinate path + transition markers into typed PathStep list.
     * Inserts Interact steps where the A* used a transition edge.
     */
    private PathResult buildSteps(List<int[]> rawPath, List<Transition> rawTransitions,
                                   int plane, int totalCost) {
        List<PathStep> steps = new ArrayList<>();

        for (int i = 0; i < rawPath.size(); i++) {
            int[] tile = rawPath.get(i);
            Transition t = rawTransitions.get(i);

            if (t != null) {
                // This tile was reached via a transition — insert Interact before WalkTo
                steps.add(new PathStep.Interact(
                        t.name(), t.option(), t.srcX(), t.srcY(), t.srcP(),
                        t.dstX(), t.dstY(), t.type()));
            }

            steps.add(new PathStep.WalkTo(tile[0], tile[1], plane));
        }

        return new PathResult(true, totalCost, steps);
    }

    // ── Heuristic ────────────────────────────────────────────────

    /**
     * Chebyshev distance heuristic, admissible for 8-directional movement.
     * Slightly tighter than Manhattan: accounts for diagonal shortcuts.
     */
    static int heuristic(int x1, int y1, int x2, int y2) {
        int dx = Math.abs(x1 - x2);
        int dy = Math.abs(y1 - y2);
        return Math.max(dx, dy) * COST_CARDINAL + Math.min(dx, dy) * (COST_DIAGONAL - COST_CARDINAL);
    }

    // ── Edge jitter ──────────────────────────────────────────────

    /**
     * FNV-1a based deterministic jitter for humanized path variation.
     * Returns 0 when seed is 0 (deterministic mode).
     */
    static int edgeJitter(long seed, int from, int to) {
        if (seed == 0) return 0;
        long h = 0xcbf29ce484222325L;
        h ^= seed; h *= 0x100000001b3L;
        h ^= from; h *= 0x100000001b3L;
        h ^= to;   h *= 0x100000001b3L;
        return (int) ((h >>> 32) % MAX_JITTER);
    }
}
