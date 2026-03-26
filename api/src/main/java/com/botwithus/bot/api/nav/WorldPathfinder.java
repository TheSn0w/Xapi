package com.botwithus.bot.api.nav;

import com.botwithus.bot.api.log.BotLogger;
import com.botwithus.bot.api.log.LoggerFactory;
import net.bwu.nav.data.NavWorld;
import net.bwu.nav.pathfinder.AStarPathfinder;
import net.bwu.nav.pathfinder.PlayerState;
import net.bwu.nav.store.NavStore;
import net.bwu.nav.store.TransitionJsonLoader;

import java.nio.file.Path;

import static net.bwu.nav.store.NavConstants.*;

/**
 * Singleton wrapper around the ClaudeDecoder transition-aware A* pathfinder.
 * <p>
 * This pathfinder is aware of doors, gates, and other transition objects in
 * the navdata, so it produces accurate walk distances that respect walls and
 * blocked tiles — unlike {@link LocalPathfinder} which does not handle
 * transition edges.
 * <p>
 * Usage:
 * <pre>
 * WorldPathfinder.init(Path.of("navdata"));
 * var pf = WorldPathfinder.getInstance();
 * int walkDist = pf.walkDistance(startX, startY, destX, destY, plane);
 * </pre>
 */
public final class WorldPathfinder {

    private static final BotLogger log = LoggerFactory.getLogger(WorldPathfinder.class);

    private static volatile WorldPathfinder instance;

    private final NavWorld world;
    private final AStarPathfinder pathfinder;

    private WorldPathfinder(NavWorld world) {
        this.world = world;
        this.pathfinder = new AStarPathfinder();
    }

    // ── Singleton ────────────────────────────────────────────────

    /**
     * Initializes the global singleton with navdata from the given base directory.
     * The directory should contain a {@code regions/} subdirectory with {@code .dat} files,
     * plus optional {@code transitions.json}, {@code teleports.json}, {@code pois.json}.
     *
     * @param navDataDir base navdata directory (e.g. {@code navdata/})
     */
    public static void init(Path navDataDir) {
        NavStore store = new NavStore(navDataDir);

        // Load transitions from JSON for transition-aware routing (doors, gates, etc.)
        Path transitionsFile = navDataDir.resolve("transitions.json");
        TransitionJsonLoader transitionLoader = null;
        if (transitionsFile.toFile().exists()) {
            transitionLoader = new TransitionJsonLoader(transitionsFile);
            log.info("[WorldPathfinder] Loaded transitions: {} local, {} teleports, {} regions",
                    transitionLoader.localCount(), transitionLoader.teleportCount(), transitionLoader.regionCount());
        } else {
            log.warn("[WorldPathfinder] No transitions.json found at {} — doors/gates will not be handled", transitionsFile);
        }

        NavWorld world = new NavWorld(store, transitionLoader);
        instance = new WorldPathfinder(world);
        log.info("[WorldPathfinder] Initialized with navdata from {}", navDataDir);
    }

    /** Returns the global singleton, or {@code null} if not initialized. */
    public static WorldPathfinder getInstance() {
        return instance;
    }

    // ── Public API ───────────────────────────────────────────────

    /**
     * Computes the walk distance (in tile steps) from start to destination.
     * Returns {@code -1} if no path is found.
     * <p>
     * If the destination tile is unwalkable (e.g. a bank counter), automatically
     * tries adjacent walkable tiles — matching RS3's interaction range.
     *
     * @param startX start world X
     * @param startY start world Y
     * @param destX  destination world X
     * @param destY  destination world Y
     * @param plane  the plane (0–3)
     * @return walk distance in tile steps, or -1 if unreachable
     */
    public int walkDistance(int startX, int startY, int destX, int destY, int plane) {
        if (startX == destX && startY == destY) return 0;

        // Try direct path
        var result = pathfinder.findPath(world, startX, startY, plane, destX, destY, plane, PlayerState.UNRESTRICTED);
        if (result.found()) {
            return result.walkStepCount();
        }

        // Dest may be unwalkable (object tile like a bank counter) — try nearby tiles.
        //
        // Problem: some adjacent tiles are behind a building wall (reachable from the
        // player in few steps, but you can't interact with the object from there).
        // Others are on the correct interaction side (inside the bank) and require
        // walking through the entrance.
        //
        // Solution: path to ALL walkable adjacent tiles, collect distances, and use
        // the MEDIAN distance. Outlier-short distances (tiles behind building walls)
        // are outnumbered by correct interior distances. Then return the minimum
        // distance that's >= the median (best tile on the correct side).
        int[] distances = new int[8];
        int[][] adjCoords = new int[8][];
        int count = 0;

        int[][] cardinals = {{0, 1}, {1, 0}, {0, -1}, {-1, 0}};
        for (int[] d : cardinals) {
            int ax = destX + d[0], ay = destY + d[1];
            if (!world.isWalkable(ax, ay, plane)) continue;
            var adjResult = pathfinder.findPath(world, startX, startY, plane,
                    ax, ay, plane, PlayerState.UNRESTRICTED);
            if (adjResult.found()) {
                distances[count] = adjResult.walkStepCount();
                adjCoords[count] = new int[]{ax, ay};
                count++;
            }
        }

        if (count == 0) return -1;
        if (count == 1) return distances[0];

        // Find median distance
        int[] sorted = java.util.Arrays.copyOf(distances, count);
        java.util.Arrays.sort(sorted);
        int median = sorted[count / 2];

        // Return the minimum distance that's within 50% of the median.
        // This filters out outlier-short distances (wrong side of wall) while
        // still picking the best tile on the correct side.
        int bestDist = Integer.MAX_VALUE;
        int threshold = Math.max(median / 2, median - 5);
        for (int i = 0; i < count; i++) {
            if (distances[i] >= threshold && distances[i] < bestDist) {
                bestDist = distances[i];
            }
        }
        // Fallback: if threshold filtering rejected everything, use raw minimum
        if (bestDist == Integer.MAX_VALUE) {
            bestDist = sorted[0];
        }

        return bestDist < Integer.MAX_VALUE ? bestDist : -1;
    }

    /**
     * Returns the full path result from the ClaudeDecoder A* pathfinder.
     * Useful for debugging or getting step-by-step path details.
     */
    public net.bwu.nav.pathfinder.PathResult findPath(int startX, int startY, int destX, int destY, int plane) {
        return pathfinder.findPath(world, startX, startY, plane, destX, destY, plane, PlayerState.UNRESTRICTED);
    }

    /** Returns the underlying NavWorld for advanced queries. */
    public NavWorld getNavWorld() {
        return world;
    }
}
