package com.botwithus.bot.api.nav;

import java.util.List;

/**
 * Result of a local A* pathfinding query.
 *
 * @param found     whether a valid path was found
 * @param totalCost the total A* cost of the path (10 per cardinal step, 14 per diagonal)
 * @param path      ordered list of waypoints as {@code {worldX, worldY}} arrays (empty if not found)
 */
public record PathResult(boolean found, int totalCost, List<int[]> path) {

    /** Creates a "no path found" result. */
    public static PathResult notFound() {
        return new PathResult(false, 0, List.of());
    }

    /** Number of waypoints in the path. */
    public int stepCount() {
        return path.size();
    }
}
