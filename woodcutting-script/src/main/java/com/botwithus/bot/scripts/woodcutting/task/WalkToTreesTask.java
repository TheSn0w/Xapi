package com.botwithus.bot.scripts.woodcutting.task;

import com.botwithus.bot.api.script.Task;
import com.botwithus.bot.scripts.woodcutting.TileAnchor;
import com.botwithus.bot.scripts.woodcutting.WoodcuttingContext;

import java.util.concurrent.ThreadLocalRandom;

public final class WalkToTreesTask implements Task {

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
        return !wctx.bankOpen && !wctx.depositOpen && !wctx.isNearCurrentTreeArea();
    }

    @Override
    public int execute() {
        TileAnchor anchor = wctx.currentTravelAnchor();
        int destX = anchor.x();
        int destY = anchor.y();

        if (wctx.quirks.shouldOvershoot()) {
            int overshootX = destX + ThreadLocalRandom.current().nextInt(-6, 7);
            int overshootY = destY + ThreadLocalRandom.current().nextInt(-6, 7);
            wctx.logAction("MOVE: Walking to " + anchor.label() + " (overshoot)");
            wctx.api.walkWorldPathAsync(overshootX, overshootY, anchor.plane());
        } else {
            wctx.logAction("MOVE: Walking to " + anchor.label());
            wctx.api.walkWorldPathAsync(destX, destY, anchor.plane());
        }

        return (int) wctx.pace.delay("walk");
    }
}
