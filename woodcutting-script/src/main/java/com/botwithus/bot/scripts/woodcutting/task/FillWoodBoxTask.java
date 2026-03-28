package com.botwithus.bot.scripts.woodcutting.task;

import com.botwithus.bot.api.log.BotLogger;
import com.botwithus.bot.api.log.LoggerFactory;
import com.botwithus.bot.api.script.Task;
import com.botwithus.bot.api.util.Conditions;
import com.botwithus.bot.scripts.woodcutting.WoodcuttingConfig;
import com.botwithus.bot.scripts.woodcutting.WoodcuttingContext;

/**
 * Fills the wood box when the backpack is full and the box can still accept logs.
 */
public final class FillWoodBoxTask implements Task {

    private static final BotLogger log = LoggerFactory.getLogger(FillWoodBoxTask.class);

    private final WoodcuttingContext wctx;

    public FillWoodBoxTask(WoodcuttingContext wctx) {
        this.wctx = wctx;
    }

    @Override
    public String name() {
        return "Filling wood box";
    }

    @Override
    public int priority() {
        return 30;
    }

    @Override
    public boolean validate() {
        WoodcuttingConfig.TreeType tree = wctx.config.getTreeType();
        if (!wctx.woodBox.hasWoodBox() || !wctx.woodBox.canStore(tree.logType) || wctx.woodBox.isFull(tree.logType)) {
            return false;
        }
        // Normal: fill when backpack is full
        if (wctx.backpack.isFull()) return true;
        // Quirk 1: early fill — fill before backpack is full
        int logCount = wctx.backpack.count(tree.logType.itemId);
        int cap = wctx.woodBox.getCapacity();
        return cap > 0 && wctx.quirks.shouldEarlyFill(logCount, wctx.woodBox.count(tree.logType), cap);
    }

    @Override
    public int execute() {
        WoodcuttingConfig.TreeType tree = wctx.config.getTreeType();
        int storedBefore = wctx.woodBox.count(tree.logType);
        int logsBefore = wctx.backpack.count(tree.logType.itemId);

        wctx.logAction("Filling wood box");
        boolean filled = wctx.woodBox.fill();
        if (!filled) {
            log.warn("[Woodcutting] Fill action failed");
            wctx.logAction("Wood box fill failed");
            return (int) wctx.pace.delay("react");
        }

        wctx.woodboxFills++;
        Conditions.waitUntil(() -> {
            int storedNow = wctx.woodBox.count(tree.logType);
            int logsNow = wctx.backpack.count(tree.logType.itemId);
            return storedNow > storedBefore || logsNow < logsBefore;
        }, 3000);

        int storedAfter = wctx.woodBox.count(tree.logType);
        int logsAfter = wctx.backpack.count(tree.logType.itemId);
        int capacity = wctx.woodBox.getCapacity();

        if (storedAfter <= storedBefore && logsAfter >= logsBefore) {
            log.warn("[Woodcutting] Fill made no progress (stored {}/{}, logs still {})",
                    storedAfter, capacity, logsAfter);
            wctx.logAction("Wood box fill made no progress");
        } else {
            wctx.logAction("Wood box filled (" + storedAfter + "/" + capacity + ")");
        }

        wctx.pace.after("gather");
        wctx.pace.breakCheck();
        return (int) wctx.pace.delay("gather");
    }
}
