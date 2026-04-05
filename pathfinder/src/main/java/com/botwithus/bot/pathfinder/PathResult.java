package com.botwithus.bot.pathfinder;

import java.util.List;

/**
 * Result of an A* pathfinding query, containing typed steps and total cost.
 */
public record PathResult(
        boolean found,
        int totalCost,
        List<PathStep> steps
) {
    private static final PathResult NOT_FOUND = new PathResult(false, Integer.MAX_VALUE, List.of());

    public static PathResult notFound() {
        return NOT_FOUND;
    }

    public int walkStepCount() {
        return (int) steps.stream().filter(s -> s instanceof PathStep.WalkTo).count();
    }

    public int interactionCount() {
        return (int) steps.stream().filter(s -> s instanceof PathStep.Interact).count();
    }

    public int teleportCount() {
        return (int) steps.stream().filter(s -> s instanceof PathStep.Teleport).count();
    }
}
