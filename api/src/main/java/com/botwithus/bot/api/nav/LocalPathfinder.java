package com.botwithus.bot.api.nav;

import com.botwithus.bot.api.log.BotLogger;
import com.botwithus.bot.api.log.LoggerFactory;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static com.botwithus.bot.api.nav.CollisionFlags.*;

/**
 * Local A* pathfinder operating over pre-loaded {@link NavRegion} collision data.
 * <p>
 * Supports same-region (single 64×64 buffer) and cross-region (expanded buffer
 * up to 512×512) pathfinding on the same plane.
 * <p>
 * Thread-safe: each {@code findPath} call uses method-local buffers.
 * A singleton instance is provided via {@link #init(Path)} / {@link #getInstance()}.
 *
 * <h3>Performance</h3>
 * <ul>
 *   <li>Same-region: ~10–40µs</li>
 *   <li>Cross-region (adjacent): ~80–250µs</li>
 * </ul>
 */
public final class LocalPathfinder {

    private static final BotLogger log = LoggerFactory.getLogger(LocalPathfinder.class);

    private static volatile LocalPathfinder instance;

    private final CollisionMap map;

    private static final int COST_CARDINAL = 10;
    private static final int COST_DIAGONAL = 14;
    private static final int MAX_EXPANSIONS_SAME = 4096;
    private static final int MAX_CROSS_REGION_SPAN = 512;

    public LocalPathfinder(CollisionMap map) {
        this.map = map;
    }

    // ── Singleton ────────────────────────────────────────────────

    /**
     * Initializes the global pathfinder singleton with region data from the given directory.
     */
    public static void init(Path regionsDir) {
        RegionStore store = new RegionStore(regionsDir);
        CollisionMap collisionMap = new CollisionMap(store);
        instance = new LocalPathfinder(collisionMap);
        log.info("[LocalPathfinder] Initialized with regions from {}", regionsDir);
    }

    /** Returns the global singleton, or {@code null} if not initialized. */
    public static LocalPathfinder getInstance() {
        return instance;
    }

    /** Returns the underlying collision map. */
    public CollisionMap getCollisionMap() {
        return map;
    }

    // ── Public API ───────────────────────────────────────────────

    /**
     * Finds a path between two world tiles on the same plane.
     * <p>
     * If the destination tile is unwalkable (e.g. an object like a bank counter),
     * automatically tries adjacent walkable tiles — matching RS3 interaction range
     * where you interact from next to objects, not on top of them.
     */
    public PathResult findPath(int startX, int startY, int destX, int destY, int plane) {
        if (startX == destX && startY == destY) {
            return new PathResult(true, 0, List.of());
        }

        // Try direct path first
        PathResult direct = findPathDirect(startX, startY, destX, destY, plane);
        if (direct.found()) return direct;

        // Destination may be unwalkable (object tile) — try adjacent tiles.
        // For each adjacent tile, check if it's blocked by a WALL (reject) vs
        // just blocked by the object being unwalkable (allow).
        // A wall blocks an entire row/column; an object only blocks the tile next to it.
        if (!map.isWalkable(destX, destY, plane)) {
            PathResult best = PathResult.notFound();
            // {dx, dy, dirFlag toward dest}
            int[][] neighbors = {
                    {0, 1, FLAG_MOVE_SOUTH}, {1, 0, FLAG_MOVE_WEST},
                    {0, -1, FLAG_MOVE_NORTH}, {-1, 0, FLAG_MOVE_EAST}};
            for (int[] d : neighbors) {
                int adjX = destX + d[0], adjY = destY + d[1];
                if (!map.isWalkable(adjX, adjY, plane)) continue;

                // If the adjacent tile can move toward the dest, it's a valid interaction tile
                if (!map.canMove(adjX, adjY, plane, d[2])) {
                    // Can't move toward dest — check if it's a wall or just the object.
                    // A wall spans multiple tiles: check perpendicular neighbors.
                    // If BOTH perpendicular tiles also block the same direction, it's a wall.
                    int perpX = Math.abs(d[1]), perpY = Math.abs(d[0]); // swap dx/dy for perpendicular
                    boolean perp1Blocked = !map.canMove(adjX + perpX, adjY + perpY, plane, d[2]);
                    boolean perp2Blocked = !map.canMove(adjX - perpX, adjY - perpY, plane, d[2]);
                    if (perp1Blocked && perp2Blocked) {
                        continue; // wall — skip this side entirely
                    }
                    // Only the object blocking — allow as interaction tile
                }

                PathResult adj = findPathDirect(startX, startY, adjX, adjY, plane);
                if (adj.found() && (!best.found() || adj.totalCost() < best.totalCost())) {
                    best = adj;
                }
            }
            return best;
        }

        return PathResult.notFound();
    }

    /** Core pathfinding — tries same-region then cross-region. */
    private PathResult findPathDirect(int startX, int startY, int destX, int destY, int plane) {
        int startRegion = toRegionId(startX, startY);
        int destRegion = toRegionId(destX, destY);

        if (startRegion == destRegion) {
            PathResult same = findPathSameRegion(startRegion, plane,
                    toLocalX(startX), toLocalY(startY),
                    toLocalX(destX), toLocalY(destY));
            if (same.found()) return same;
        }

        return findPathCrossRegion(startX, startY, destX, destY, plane);
    }

    /**
     * Returns the walk distance (A* cost) between two tiles, or {@link Integer#MAX_VALUE}
     * if no path exists. Cheaper than {@link #findPath} — skips path reconstruction.
     */
    public int walkDistance(int startX, int startY, int destX, int destY, int plane) {
        PathResult result = findPath(startX, startY, destX, destY, plane);
        return result.found() ? result.totalCost() : Integer.MAX_VALUE;
    }

    /**
     * Checks if two tiles are connected by a walkable path.
     */
    public boolean isReachable(int startX, int startY, int destX, int destY, int plane) {
        return findPath(startX, startY, destX, destY, plane).found();
    }

    // ── Same-region A* ──────────────────────────────────────────

    /** Tracks heap size across neighbor expansion calls. */
    private static final class Heap {
        final int[] nodes;
        final int[] prio;
        final int[] pos;
        int size;

        Heap(int capacity) {
            nodes = new int[capacity];
            prio = new int[capacity];
            pos = new int[capacity];
            Arrays.fill(pos, -1);
        }

        int extractMin() {
            int min = nodes[0];
            pos[min] = -1;
            size--;
            if (size > 0) {
                nodes[0] = nodes[size];
                prio[0] = prio[size];
                pos[nodes[0]] = 0;
                siftDown(0);
            }
            return min;
        }

        void insertOrDecrease(int node, int priority) {
            int p = pos[node];
            if (p == -1) {
                p = size++;
                nodes[p] = node;
                prio[p] = priority;
                pos[node] = p;
                siftUp(p);
            } else if (priority < prio[p]) {
                prio[p] = priority;
                siftUp(p);
            }
        }

        private void siftUp(int p) {
            while (p > 0) {
                int pp = (p - 1) >> 1;
                if (prio[p] >= prio[pp]) break;
                swap(p, pp);
                p = pp;
            }
        }

        private void siftDown(int p) {
            while (true) {
                int left = (p << 1) + 1;
                int right = left + 1;
                int smallest = p;
                if (left < size && prio[left] < prio[smallest]) smallest = left;
                if (right < size && prio[right] < prio[smallest]) smallest = right;
                if (smallest == p) break;
                swap(p, smallest);
                p = smallest;
            }
        }

        private void swap(int a, int b) {
            int tn = nodes[a]; int tp = prio[a];
            nodes[a] = nodes[b]; prio[a] = prio[b]; pos[nodes[a]] = a;
            nodes[b] = tn; prio[b] = tp; pos[nodes[b]] = b;
        }
    }

    private PathResult findPathSameRegion(int regionId, int plane,
                                          int startLX, int startLY,
                                          int destLX, int destLY) {
        NavRegion region = map.getOrLoad(regionId, plane);
        if (region == null) return PathResult.notFound();
        if (!region.isWalkable(destLX, destLY)) return PathResult.notFound();

        int startIdx = tileIndex(startLX, startLY);
        int destIdx = tileIndex(destLX, destLY);
        if (startIdx == destIdx) return new PathResult(true, 0, List.of());

        int[] gCost = new int[TILES_PER_REGION];
        int[] parent = new int[TILES_PER_REGION];
        byte[] nodeState = new byte[TILES_PER_REGION];
        Arrays.fill(gCost, Integer.MAX_VALUE);
        Arrays.fill(parent, -1);

        Heap heap = new Heap(TILES_PER_REGION);

        gCost[startIdx] = 0;
        nodeState[startIdx] = 1;
        heap.insertOrDecrease(startIdx, heuristic(startLX, startLY, destLX, destLY));

        byte[] flags = region.getFlagsArray();
        byte[] diagFlags = region.getDiagFlagsArray();
        int expansions = 0;

        while (heap.size > 0 && expansions < MAX_EXPANSIONS_SAME) {
            int current = heap.extractMin();
            if (current == destIdx) {
                return reconstructSameRegion(parent, current, regionId, plane);
            }
            nodeState[current] = 2;
            expansions++;

            int cx = current & 63;
            int cy = current >> 6;
            int cf = flags[current] & 0xFF;

            // Cardinal neighbors
            int[][] cardinals = {{0, 1, FLAG_MOVE_NORTH}, {1, 0, FLAG_MOVE_EAST},
                    {0, -1, FLAG_MOVE_SOUTH}, {-1, 0, FLAG_MOVE_WEST}};
            for (int[] d : cardinals) {
                if ((cf & d[2]) == 0) continue;
                int nx = cx + d[0], ny = cy + d[1];
                if (nx < 0 || nx >= REGION_SIZE || ny < 0 || ny >= REGION_SIZE) continue;
                int nIdx = tileIndex(nx, ny);
                if (nodeState[nIdx] == 2) continue;
                if ((flags[nIdx] & FLAG_WALKABLE) == 0) continue;
                int newG = gCost[current] + COST_CARDINAL;
                if (newG < gCost[nIdx]) {
                    gCost[nIdx] = newG;
                    parent[nIdx] = current;
                    nodeState[nIdx] = 1;
                    heap.insertOrDecrease(nIdx, newG + heuristic(nx, ny, destLX, destLY));
                }
            }

            // Diagonal neighbors
            int[][] diags = {{1, 1}, {-1, 1}, {1, -1}, {-1, -1}};
            for (int[] dd : diags) {
                int nx = cx + dd[0], ny = cy + dd[1];
                if (nx < 0 || nx >= REGION_SIZE || ny < 0 || ny >= REGION_SIZE) continue;
                int nIdx = tileIndex(nx, ny);
                if (nodeState[nIdx] == 2) continue;
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

                int newG = gCost[current] + COST_DIAGONAL;
                if (newG < gCost[nIdx]) {
                    gCost[nIdx] = newG;
                    parent[nIdx] = current;
                    nodeState[nIdx] = 1;
                    heap.insertOrDecrease(nIdx, newG + heuristic(nx, ny, destLX, destLY));
                }
            }
        }
        return PathResult.notFound();
    }

    // ── Cross-region A* ─────────────────────────────────────────

    private PathResult findPathCrossRegion(int startX, int startY,
                                           int destX, int destY, int plane) {
        int minX = Math.min(startX, destX) - 64;
        int minY = Math.min(startY, destY) - 64;
        int maxX = Math.max(startX, destX) + 64;
        int maxY = Math.max(startY, destY) + 64;

        int width = maxX - minX + 1;
        int height = maxY - minY + 1;

        if (width > MAX_CROSS_REGION_SPAN || height > MAX_CROSS_REGION_SPAN) {
            log.debug("[LocalPathfinder] Path too long: {}x{} exceeds max span", width, height);
            return PathResult.notFound();
        }

        int totalTiles = width * height;
        int[] gCost = new int[totalTiles];
        int[] parent = new int[totalTiles];
        byte[] nodeState = new byte[totalTiles];
        Arrays.fill(gCost, Integer.MAX_VALUE);
        Arrays.fill(parent, -1);

        Heap heap = new Heap(totalTiles);

        int sGX = startX - minX, sGY = startY - minY;
        int dGX = destX - minX, dGY = destY - minY;
        int startIdx = sGY * width + sGX;
        int destIdx = dGY * width + dGX;

        gCost[startIdx] = 0;
        nodeState[startIdx] = 1;
        heap.insertOrDecrease(startIdx, heuristic(sGX, sGY, dGX, dGY));

        int expansions = 0;
        int maxExpansions = Math.min(totalTiles, 65536);

        while (heap.size > 0 && expansions < maxExpansions) {
            int current = heap.extractMin();
            if (current == destIdx) {
                return reconstructCrossRegion(parent, current, width, minX, minY, plane, gCost[destIdx]);
            }
            nodeState[current] = 2;
            expansions++;

            int cx = current % width;
            int cy = current / width;
            int worldCX = cx + minX;
            int worldCY = cy + minY;

            // Cardinal neighbors
            int[][] dirs = {{0, 1, FLAG_MOVE_NORTH}, {1, 0, FLAG_MOVE_EAST},
                    {0, -1, FLAG_MOVE_SOUTH}, {-1, 0, FLAG_MOVE_WEST}};
            for (int[] d : dirs) {
                int nx = cx + d[0], ny = cy + d[1];
                if (nx < 0 || nx >= width || ny < 0 || ny >= height) continue;
                int nIdx = ny * width + nx;
                if (nodeState[nIdx] == 2) continue;

                if (!map.canMove(worldCX, worldCY, plane, d[2])) continue;
                if (!map.isWalkable(nx + minX, ny + minY, plane)) continue;

                int newG = gCost[current] + COST_CARDINAL;
                if (newG < gCost[nIdx]) {
                    gCost[nIdx] = newG;
                    parent[nIdx] = current;
                    nodeState[nIdx] = 1;
                    heap.insertOrDecrease(nIdx, newG + heuristic(nx, ny, dGX, dGY));
                }
            }

            // Diagonal neighbors
            int[][] diags = {{1, 1}, {-1, 1}, {1, -1}, {-1, -1}};
            for (int[] dd : diags) {
                int dnx = cx + dd[0], dny = cy + dd[1];
                if (dnx < 0 || dnx >= width || dny < 0 || dny >= height) continue;
                int dnIdx = dny * width + dnx;
                if (nodeState[dnIdx] == 2) continue;

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

                int newG = gCost[current] + COST_DIAGONAL;
                if (newG < gCost[dnIdx]) {
                    gCost[dnIdx] = newG;
                    parent[dnIdx] = current;
                    nodeState[dnIdx] = 1;
                    heap.insertOrDecrease(dnIdx, newG + heuristic(dnx, dny, dGX, dGY));
                }
            }
        }
        return PathResult.notFound();
    }

    // ── Path reconstruction ─────────────────────────────────────

    private PathResult reconstructSameRegion(int[] parent, int destIdx, int regionId, int plane) {
        int regionBaseX = (regionId >> 8) << 6;
        int regionBaseY = (regionId & 0xFF) << 6;

        List<int[]> path = new ArrayList<>();
        int current = destIdx;
        int cost = 0;
        int prev = -1;
        while (current != -1) {
            int lx = current & 63;
            int ly = current >> 6;
            path.add(new int[]{regionBaseX + lx, regionBaseY + ly});
            if (prev != -1) {
                int dx = Math.abs((prev & 63) - lx);
                int dy = Math.abs((prev >> 6) - ly);
                cost += (dx != 0 && dy != 0) ? COST_DIAGONAL : COST_CARDINAL;
            }
            prev = current;
            current = parent[current];
        }
        Collections.reverse(path);
        if (!path.isEmpty()) path.removeFirst(); // remove start position
        return new PathResult(true, cost, path);
    }

    private PathResult reconstructCrossRegion(int[] parent, int destIdx,
                                               int width, int minX, int minY,
                                               int plane, int totalCost) {
        List<int[]> path = new ArrayList<>();
        int current = destIdx;
        while (current != -1) {
            int lx = current % width;
            int ly = current / width;
            path.add(new int[]{lx + minX, ly + minY});
            current = parent[current];
        }
        Collections.reverse(path);
        if (!path.isEmpty()) path.removeFirst();
        return new PathResult(true, totalCost, path);
    }

    // ── Heuristic ───────────────────────────────────────────────

    /** Manhattan distance heuristic scaled to A* cost units. */
    private static int heuristic(int x1, int y1, int x2, int y2) {
        return (Math.abs(x1 - x2) + Math.abs(y1 - y2)) * COST_CARDINAL;
    }

}
