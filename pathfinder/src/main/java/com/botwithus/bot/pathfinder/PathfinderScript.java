package com.botwithus.bot.pathfinder;

import com.botwithus.bot.api.*;
import com.botwithus.bot.api.model.LocalPlayer;
import com.botwithus.bot.api.ui.ScriptUI;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Standalone pathfinder test script.
 * <p>
 * Provides a UI for testing A* pathfinding with transition support.
 * Enter destination coordinates, click "Find Path" to compute, and
 * "Execute" to walk the path with automatic object interactions.
 * <p>
 * Later this will also serve as the public API entry point via
 * {@code Pathfinder.navTo(x, y, plane)}.
 */
@ScriptManifest(
        name = "Pathfinder",
        version = "0.1",
        author = "Snow",
        description = "A* pathfinder with transition support (doors, gates, stiles, stairs)",
        category = ScriptCategory.UTILITY
)
public class PathfinderScript implements BotScript {

    private static final Logger log = LoggerFactory.getLogger(PathfinderScript.class);

    // ── Shared state (thread-safe for UI reads) ──────────────────
    volatile ScriptContext ctx;
    volatile GameAPI api;
    volatile AStarPathfinder pathfinder;
    volatile TransitionStore transitions;
    volatile PathResult lastResult;
    volatile String statusMessage = "Initialising...";

    // Path executor (non-blocking, ticked from onLoop)
    final PathExecutor executor = new PathExecutor();

    // Player position (updated each loop)
    volatile int playerX, playerY, playerPlane;
    volatile boolean playerMoving;

    private PathfinderUI ui;

    // ── Lifecycle ────────────────────────────────────────────────

    @Override
    public void onStart(ScriptContext ctx) {
        this.ctx = ctx;
        this.api = ctx.getGameAPI();
        this.ui = new PathfinderUI(this);

        // Locate region data and transitions
        Path navdata = resolveNavdata();
        Path transitionsJson = resolveTransitionsJson();

        if (navdata == null) {
            statusMessage = "ERROR: navdata/regions/ not found";
            log.error(statusMessage);
            return;
        }

        // Initialize collision map and pathfinder
        var regionStore = new com.botwithus.bot.api.nav.RegionStore(navdata);
        var collisionMap = new com.botwithus.bot.api.nav.CollisionMap(regionStore);
        transitions = new TransitionStore();

        if (transitionsJson != null) {
            try {
                int count = transitions.loadJson(transitionsJson);
                transitions.applyWallsToMap(collisionMap);
                statusMessage = "Ready — " + count + " transitions loaded";
            } catch (Exception e) {
                log.error("Failed to load transitions: {}", e.getMessage());
                statusMessage = "Ready (no transitions — " + e.getMessage() + ")";
            }
        } else {
            statusMessage = "Ready (no transitions.json found)";
        }

        pathfinder = new AStarPathfinder(collisionMap, transitions);
        log.info("Pathfinder initialised: {}", statusMessage);
    }

    @Override
    public int onLoop() {
        if (api == null) return 600;

        // Update player position
        try {
            LocalPlayer lp = api.getLocalPlayer();
            if (lp != null) {
                playerX = lp.tileX();
                playerY = lp.tileY();
                playerPlane = lp.plane();
                playerMoving = lp.isMoving();
            }
        } catch (Exception ignored) {}

        // Tick the path executor if active
        if (executor.isActive()) {
            return executor.tick();
        }

        return 600;
    }

    @Override
    public void onStop() {
        if (executor.isActive()) {
            executor.cancel();
        }
        log.info("Pathfinder script stopped");
    }

    @Override
    public ScriptUI getUI() {
        return ui;
    }

    // ── Public API (for future use by other scripts) ─────────────

    /**
     * Find a path from the player's current position to the destination.
     */
    public PathResult findPathTo(int destX, int destY, int destPlane) {
        if (pathfinder == null) return PathResult.notFound();
        // Always use findPathCrossPlane — it handles same-plane internally and
        // falls back to cross-plane routing when buildings are disconnected on
        // the same plane (e.g. plane 2 Falador → plane 2 Lumbridge).
        return pathfinder.findPathCrossPlane(playerX, playerY, playerPlane,
                destX, destY, destPlane, 0);
    }

    /**
     * Navigate to destination — finds path and starts non-blocking execution.
     */
    public void navTo(int destX, int destY, int destPlane) {
        PathResult result = findPathTo(destX, destY, destPlane);
        lastResult = result;
        if (result.found()) {
            executor.start(result, api);
            statusMessage = "Navigating — " + result.walkStepCount() + " steps, "
                    + result.interactionCount() + " interactions";
        } else {
            statusMessage = "No path found to (" + destX + ", " + destY + ", " + destPlane + ")";
            // Show cross-plane search trace in the executor log
            if (pathfinder != null) {
                var trace = pathfinder.getSearchTrace();
                if (!trace.isEmpty()) {
                    executor.getLogBuffer().add("--- Cross-plane search trace ---");
                    for (String line : trace) {
                        executor.getLogBuffer().add(line);
                    }
                }
            }
        }
    }

    // ── Data resolution ──────────────────────────────────────────

    private Path resolveNavdata() {
        // Try multiple locations
        Path[] candidates = {
                Path.of("navdata/regions"),
                Path.of("E:/Desktop/Projects/Tools/pathfinder/navdata/regions"),
                Path.of("D:/SnowsDecoder/Walkability/world_walkability/regions")
        };
        for (Path p : candidates) {
            if (Files.isDirectory(p)) return p;
        }
        return null;
    }

    private Path resolveTransitionsJson() {
        Path[] candidates = {
                Path.of("map-debugger/data/transitions.json"),
                Path.of("E:/Desktop/Projects/V2 Xapi/map-debugger/data/transitions.json")
        };
        for (Path p : candidates) {
            if (Files.isRegularFile(p)) return p;
        }
        return null;
    }
}
