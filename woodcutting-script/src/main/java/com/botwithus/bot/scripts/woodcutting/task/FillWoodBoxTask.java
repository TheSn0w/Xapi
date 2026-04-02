package com.botwithus.bot.scripts.woodcutting.task;

import com.botwithus.bot.api.log.BotLogger;
import com.botwithus.bot.api.log.LoggerFactory;
import com.botwithus.bot.api.script.Task;
import com.botwithus.bot.api.inventory.WoodBox;
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
        // All values read from cached volatile fields (populated by collectUIState, no RPC here)
        if (!wctx.profile().supportsWoodBox()) return false;
        if (!wctx.hasWoodBox || !wctx.woodBoxCanStore || wctx.woodBoxFull) return false;
        if (wctx.backpackFull) return true;
        int capacity = wctx.woodboxCapacity;
        return capacity > 0 && wctx.quirks.shouldEarlyFill(wctx.backpackProductCount, wctx.woodBoxStoredForProfile, capacity);
    }

    @Override
    public int execute() {
        TreeProfile profile = wctx.profile();
        int storedBefore = wctx.woodBoxStoredForProfile;
        int logsBefore = wctx.backpackProductCount;

        wctx.logAction("TASK: Filling wood box");
        // Use cached tier name to avoid re-scanning all 11 tiers via RPC
        WoodBox.Tier tier = wctx.cachedWoodBoxTier;
        boolean filled = tier != null && wctx.backpack.interact(tier.name, "Fill");
        if (!filled) {
            log.warn("[Woodcutting] Fill action failed");
            wctx.logAction("WARN: Wood box fill failed");
            return (int) wctx.pace.delay("react");
        }

        wctx.woodboxFills++;
        wctx.quirks.resetEarlyBankLatch();
        // Poll with single RPC — check if backpack log count decreased
        boolean success = Conditions.waitUntil(() -> {
            int logsNow = profile.logItemId() != null ? wctx.backpack.count(profile.logItemId()) : 0;
            return logsNow < logsBefore;
        }, 3000, 600);
        wctx.clearWoodBoxEmptyAssumption();

        if (!success) {
            log.warn("[Woodcutting] Fill made no progress");
            wctx.logAction("WARN: Wood box fill made no progress");
        } else {
            // Single RPC to get updated stored count for the log message
            int storedAfter = wctx.woodBox.count(profile.woodBoxLogType());
            wctx.logAction("OK: Wood box -> " + storedAfter + "/" + wctx.woodboxCapacity);
        }

        wctx.pace.after("gather");
        wctx.pace.breakCheck();
        return (int) wctx.pace.delay("gather");
    }
}
