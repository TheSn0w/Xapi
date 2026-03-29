package com.botwithus.bot.scripts.woodcutting.task;

import com.botwithus.bot.api.log.BotLogger;
import com.botwithus.bot.api.log.LoggerFactory;
import com.botwithus.bot.api.script.Task;
import com.botwithus.bot.api.util.Conditions;
import com.botwithus.bot.scripts.woodcutting.TreeProfile;
import com.botwithus.bot.scripts.woodcutting.WoodcuttingContext;

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
        TreeProfile profile = wctx.profile();
        if (!profile.supportsWoodBox()) {
            return false;
        }
        if (!wctx.woodBox.hasWoodBox() || !wctx.woodBox.canStore(profile.woodBoxLogType()) || wctx.woodBoxIsFull(profile)) {
            return false;
        }
        if (wctx.backpack.isFull()) {
            return true;
        }
        int backpackCount = profile.logItemId() != null ? wctx.backpack.count(profile.logItemId()) : 0;
        int capacity = wctx.woodBox.getCapacity();
        return capacity > 0 && wctx.quirks.shouldEarlyFill(backpackCount, wctx.woodBoxStoredFor(profile), capacity);
    }

    @Override
    public int execute() {
        TreeProfile profile = wctx.profile();
        int storedBefore = wctx.woodBoxStoredFor(profile);
        int logsBefore = profile.logItemId() != null ? wctx.backpack.count(profile.logItemId()) : 0;

        wctx.logAction("TASK: Filling wood box");
        boolean filled = wctx.woodBox.fill();
        if (!filled) {
            log.warn("[Woodcutting] Fill action failed");
            wctx.logAction("WARN: Wood box fill failed");
            return (int) wctx.pace.delay("react");
        }

        wctx.woodboxFills++;
        Conditions.waitUntil(() -> {
            int storedNow = wctx.woodBox.count(profile.woodBoxLogType());
            int logsNow = profile.logItemId() != null ? wctx.backpack.count(profile.logItemId()) : 0;
            return storedNow > storedBefore || logsNow < logsBefore;
        }, 3000);
        wctx.clearWoodBoxEmptyAssumption();

        int storedAfter = wctx.woodBox.count(profile.woodBoxLogType());
        int logsAfter = profile.logItemId() != null ? wctx.backpack.count(profile.logItemId()) : 0;
        int capacity = wctx.woodBox.getCapacity();

        if (storedAfter <= storedBefore && logsAfter >= logsBefore) {
            log.warn("[Woodcutting] Fill made no progress (stored {}/{}, logs still {})", storedAfter, capacity, logsAfter);
            wctx.logAction("WARN: Wood box fill made no progress");
        } else {
            wctx.logAction("OK: Wood box -> " + storedAfter + "/" + capacity);
        }

        wctx.pace.after("gather");
        wctx.pace.breakCheck();
        return (int) wctx.pace.delay("gather");
    }
}
