package com.botwithus.bot.scripts.woodcutting.task;

import com.botwithus.bot.api.log.BotLogger;
import com.botwithus.bot.api.log.LoggerFactory;
import com.botwithus.bot.api.script.Task;
import com.botwithus.bot.scripts.woodcutting.WoodcuttingContext;

import java.util.concurrent.ThreadLocalRandom;

/**
 * Walks back to the tree area when the player is too far away and the backpack is not full.
 */
public final class WalkToTreesTask implements Task {

    private static final BotLogger log = LoggerFactory.getLogger(WalkToTreesTask.class);

    private final WoodcuttingContext wctx;

    public WalkToTreesTask(WoodcuttingContext wctx) {
        this.wctx = wctx;
    }

    @Override
    public String name() {
        return "Walking to trees";
    }

    @Override
    public int priority() {
        return 10;
    }

    @Override
    public boolean validate() {
        return !wctx.backpack.isFull()
                && wctx.playerHelper.distanceTo(wctx.config.getTreeAreaX(), wctx.config.getTreeAreaY()) > wctx.config.getWalkRadius();
    }

    @Override
    public int execute() {
        int destX = wctx.config.getTreeAreaX();
        int destY = wctx.config.getTreeAreaY();

        // Quirk 8: overshoot — walk past the tree area, then course-correct next tick
        if (wctx.quirks.shouldOvershoot()) {
            int overshootX = destX + ThreadLocalRandom.current().nextInt(-8, 9);
            int overshootY = destY + ThreadLocalRandom.current().nextInt(-8, 9);
            wctx.logAction("Walking to trees (overshot)");
            wctx.api.walkWorldPathAsync(overshootX, overshootY, 0);
        } else {
            wctx.logAction("Walking to trees");
            wctx.api.walkWorldPathAsync(destX, destY, 0);
        }

        return (int) wctx.pace.delay("walk");
    }
}
