package com.botwithus.bot.pathfinder;

import java.util.ArrayList;
import java.util.List;

/**
 * Breaks a {@link PathResult} into minimap-click-sized {@link WalkSegment}s.
 * <p>
 * RS3 minimap clicks move the player roughly 15-18 tiles. This class buffers
 * consecutive WalkTo steps and flushes them into segments. Interactions
 * (doors, gates, stiles) create segment boundaries.
 */
public final class WalkPlan {

    private static final int MAX_WALK_BUFFER = 16;

    private WalkPlan() {}

    /**
     * Plans walk segments from a pathfinding result.
     *
     * @return ordered list of segments for the executor to process
     */
    public static List<WalkSegment> plan(PathResult result) {
        if (!result.found() || result.steps().isEmpty()) return List.of();

        List<WalkSegment> segments = new ArrayList<>();
        List<PathStep.WalkTo> buffer = new ArrayList<>();
        PathStep.Interact pendingInteract = null;

        for (PathStep step : result.steps()) {
            switch (step) {
                case PathStep.WalkTo w -> {
                    buffer.add(w);
                    if (buffer.size() >= MAX_WALK_BUFFER) {
                        segments.add(flush(buffer, pendingInteract));
                        pendingInteract = null;
                    }
                }
                case PathStep.Interact interact -> {
                    // Flush current buffer before interaction
                    if (!buffer.isEmpty()) {
                        segments.add(flush(buffer, pendingInteract));
                        pendingInteract = null;
                    }
                    pendingInteract = interact;
                }
                case PathStep.Teleport tp -> {
                    // Flush before teleport
                    if (!buffer.isEmpty()) {
                        segments.add(flush(buffer, pendingInteract));
                        pendingInteract = null;
                    }
                    // Teleports get their own segment (target = teleport destination)
                    segments.add(WalkSegment.walkTo(tp.x(), tp.y(), tp.plane()));
                }
            }
        }

        // Flush remaining
        if (!buffer.isEmpty()) {
            segments.add(flush(buffer, pendingInteract));
        }

        return segments;
    }

    private static WalkSegment flush(List<PathStep.WalkTo> buffer, PathStep.Interact interact) {
        PathStep.WalkTo last = buffer.getLast();
        buffer.clear();
        if (interact != null) {
            return WalkSegment.withInteraction(last.x(), last.y(), last.plane(), interact);
        }
        return WalkSegment.walkTo(last.x(), last.y(), last.plane());
    }
}
