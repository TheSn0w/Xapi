package com.botwithus.bot.pathfinder;

/**
 * A single step in a pathfinding result.
 * <p>
 * Sealed hierarchy — the path executor pattern-matches on these to decide
 * whether to walk, interact with an object, or teleport.
 */
public sealed interface PathStep {

    int x();
    int y();
    int plane();

    /** Walk to a tile. */
    record WalkTo(int x, int y, int plane) implements PathStep {}

    /**
     * Interact with a scene object (door, gate, stile, staircase, etc.).
     * <p>
     * {@code x, y} = the interaction source tile (where the object is).
     * {@code dstX, dstY} = the transition exit tile (where the player ends up after crossing).
     */
    record Interact(
            String objectName,
            String option,
            int x, int y, int plane,
            int dstX, int dstY,
            Transition.Type type
    ) implements PathStep {}

    /** Use a teleport (lodestone, spell, item, fairy ring, etc.). */
    record Teleport(
            String name,
            int x, int y, int plane
    ) implements PathStep {}
}
