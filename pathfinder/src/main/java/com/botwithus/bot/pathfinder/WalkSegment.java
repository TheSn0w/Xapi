package com.botwithus.bot.pathfinder;

/**
 * A single segment of a walk plan — either a walk target or an interaction
 * followed by a walk target.
 * <p>
 * Each segment represents one minimap click (~15-18 tiles in RS3).
 * The executor walks to {@code targetX, targetY}, optionally interacting
 * with an object first.
 */
public record WalkSegment(
        int targetX,
        int targetY,
        int plane,
        PathStep.Interact interactBefore
) {

    public static WalkSegment walkTo(int x, int y, int plane) {
        return new WalkSegment(x, y, plane, null);
    }

    public static WalkSegment withInteraction(int x, int y, int plane, PathStep.Interact interact) {
        return new WalkSegment(x, y, plane, interact);
    }

    public boolean hasInteraction() {
        return interactBefore != null;
    }
}
