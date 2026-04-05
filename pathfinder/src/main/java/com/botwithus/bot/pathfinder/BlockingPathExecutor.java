package com.botwithus.bot.pathfinder;

import com.botwithus.bot.api.GameAPI;
import com.botwithus.bot.api.entities.SceneObjects;
import com.botwithus.bot.api.entities.SceneObject;
import com.botwithus.bot.api.model.GameAction;
import com.botwithus.bot.api.model.LocalPlayer;
import com.botwithus.bot.api.model.WalkResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

/**
 * Blocking path executor — runs on the calling thread (typically a virtual thread
 * from the script's onLoop). Blocks until the entire path is walked or fails.
 * <p>
 * Uses {@code GameAction(23, 0, x, y)} for walking and polls player state
 * for interaction completion ({@code animationId == -1 && !isMoving}).
 */
public final class BlockingPathExecutor {

    private static final Logger log = LoggerFactory.getLogger(BlockingPathExecutor.class);

    private static final int WALK_ACTION = 23;
    private static final int ARRIVAL_DISTANCE = 2;
    private static final long POLL_MS = 300;

    private BlockingPathExecutor() {}

    /**
     * Executes a path result, blocking until completion.
     *
     * @param result the pathfinding result to execute
     * @param api    game API for actions and player queries
     * @return ARRIVED if successful, FAILED or TIMEOUT otherwise
     */
    public static WalkResult execute(PathResult result, GameAPI api) {
        if (!result.found()) return WalkResult.FAILED;

        List<WalkSegment> segments = WalkPlan.plan(result);
        if (segments.isEmpty()) return WalkResult.ARRIVED;

        log.info("BlockingPathExecutor: executing {} segments", segments.size());

        for (int i = 0; i < segments.size(); i++) {
            WalkSegment seg = segments.get(i);

            // Handle interaction
            if (seg.hasInteraction()) {
                WalkResult interactResult = performInteraction(seg.interactBefore(), api);
                if (interactResult != WalkResult.ARRIVED) {
                    log.warn("Interaction failed at segment {}: {}", i, interactResult);
                    return interactResult;
                }
            }

            // Check if already at target after interaction
            LocalPlayer lp = getPlayer(api);
            if (lp != null) {
                int dist = Math.abs(lp.tileX() - seg.targetX()) + Math.abs(lp.tileY() - seg.targetY());
                if (dist <= ARRIVAL_DISTANCE) continue;
            }

            // Walk to target
            WalkResult walkResult = walkTo(seg.targetX(), seg.targetY(), api);
            if (walkResult != WalkResult.ARRIVED) {
                log.warn("Walk failed at segment {}: {}", i, walkResult);
                return walkResult;
            }
        }

        log.info("BlockingPathExecutor: arrived at destination");
        return WalkResult.ARRIVED;
    }

    private static WalkResult performInteraction(PathStep.Interact interact, GameAPI api) {
        SceneObjects objects = new SceneObjects(api);
        int tx = interact.x(), ty = interact.y();
        List<SceneObject> candidates = objects.query()
                .named(interact.objectName())
                .within(tx, ty, 10)
                .all();

        // Pick the object closest to the transition coordinates, not the player
        SceneObject obj = null;
        int bestDist = Integer.MAX_VALUE;
        for (SceneObject c : candidates) {
            int dist = Math.abs(c.tileX() - tx) + Math.abs(c.tileY() - ty);
            if (dist < bestDist) { bestDist = dist; obj = c; }
        }

        if (obj == null) {
            log.warn("Object '{}' not found near ({},{})", interact.objectName(),
                    interact.x(), interact.y());
            return WalkResult.FAILED;
        }

        boolean success = obj.interact(interact.option());
        if (!success) return WalkResult.FAILED;

        // Wait for animation to start (or player to move)
        LocalPlayer before = getPlayer(api);
        int startX = before != null ? before.tileX() : 0;
        int startY = before != null ? before.tileY() : 0;

        long deadline = System.currentTimeMillis() + 5_000;
        while (System.currentTimeMillis() < deadline) {
            sleep(POLL_MS);
            LocalPlayer lp = getPlayer(api);
            if (lp == null) continue;

            // Animation started
            if (lp.animationId() != -1) break;

            // Player moved and settled
            int moved = Math.abs(lp.tileX() - startX) + Math.abs(lp.tileY() - startY);
            if (moved > 0 && !lp.isMoving()) return WalkResult.ARRIVED;

            // Player walking to object — keep waiting
            if (lp.isMoving()) deadline = System.currentTimeMillis() + 5_000;
        }

        // Wait for animation to end: animationId == -1 && !isMoving
        deadline = System.currentTimeMillis() + 15_000;
        while (System.currentTimeMillis() < deadline) {
            sleep(POLL_MS);
            LocalPlayer lp = getPlayer(api);
            if (lp == null) continue;

            if (lp.animationId() == -1 && !lp.isMoving()) {
                return WalkResult.ARRIVED;
            }
        }

        log.warn("Interaction animation timed out");
        return WalkResult.ARRIVED; // proceed anyway
    }

    private static WalkResult walkTo(int targetX, int targetY, GameAPI api) {
        api.queueAction(new GameAction(WALK_ACTION, 0, targetX, targetY));

        long deadline = System.currentTimeMillis() + 30_000;
        while (System.currentTimeMillis() < deadline) {
            sleep(600);
            LocalPlayer lp = getPlayer(api);
            if (lp == null) continue;

            int dist = Math.abs(lp.tileX() - targetX) + Math.abs(lp.tileY() - targetY);
            if (dist <= ARRIVAL_DISTANCE && !lp.isMoving()) {
                return WalkResult.ARRIVED;
            }

            // Player stopped but not at target — re-queue
            if (!lp.isMoving()) {
                api.queueAction(new GameAction(WALK_ACTION, 0, targetX, targetY));
            }
        }

        return WalkResult.TIMEOUT;
    }

    private static LocalPlayer getPlayer(GameAPI api) {
        try { return api.getLocalPlayer(); } catch (Exception e) { return null; }
    }

    private static void sleep(long ms) {
        try { Thread.sleep(ms); } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
