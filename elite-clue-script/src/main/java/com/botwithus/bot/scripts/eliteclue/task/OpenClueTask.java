package com.botwithus.bot.scripts.eliteclue.task;

import com.botwithus.bot.api.log.BotLogger;
import com.botwithus.bot.api.log.LoggerFactory;
import com.botwithus.bot.api.script.Task;
import com.botwithus.bot.scripts.eliteclue.ClueContext;

/**
 * Opens the clue scroll item in the backpack.
 * <p>
 * Handles: opened clue scrolls, scroll boxes, puzzle scroll boxes, and sealed clues.
 * Uses backpack interaction by item ID with "Open" option.
 */
public final class OpenClueTask implements Task {

    private static final BotLogger log = LoggerFactory.getLogger(OpenClueTask.class);
    private final ClueContext ctx;

    private long lastInteractTime = 0;
    private static final long INTERACT_COOLDOWN_MS = 3000; // Don't spam clicks

    public OpenClueTask(ClueContext ctx) {
        this.ctx = ctx;
    }

    @Override
    public String name() {
        return "Open Clue";
    }

    @Override
    public int priority() {
        return 100; // Highest priority — open clue before anything else
    }

    @Override
    public boolean validate() {
        // Only activate when activity is OPEN_CLUE and we have a clue item
        return ctx.activity == com.botwithus.bot.scripts.eliteclue.ClueActivity.OPEN_CLUE
                && ctx.hasClueInBackpack
                && ctx.clueItemId > 0;
    }

    @Override
    public int execute() {
        long now = System.currentTimeMillis();

        // Cooldown to prevent spam
        if (now - lastInteractTime < INTERACT_COOLDOWN_MS) {
            return 600;
        }

        // Don't try to open if we're moving or in combat
        if (ctx.playerMoving || ctx.inCombat) {
            return 600;
        }

        int itemId = ctx.clueItemId;
        ctx.logAction("OPEN CLUE: Opening item " + itemId + "...");

        boolean success = ctx.backpack.interact(itemId, "Open");
        if (!success) {
            ctx.logAction("OPEN CLUE: Failed to interact with item " + itemId + ", trying option 1...");
            success = ctx.backpack.interact(itemId, 1);
        }

        lastInteractTime = now;

        if (success) {
            ctx.logAction("OPEN CLUE: Interaction queued for item " + itemId);
        } else {
            ctx.logAction("OPEN CLUE: Could not interact with item " + itemId);
        }

        return 1200; // Wait for interface to open
    }
}
