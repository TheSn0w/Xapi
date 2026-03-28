package com.botwithus.bot.scripts.woodcutting;

import com.botwithus.bot.api.*;
import com.botwithus.bot.api.antiban.Pace;
import com.botwithus.bot.api.event.TickEvent;
import com.botwithus.bot.api.log.BotLogger;
import com.botwithus.bot.api.log.LoggerFactory;
import com.botwithus.bot.api.nav.LocalPathfinder;
import com.botwithus.bot.api.nav.WorldPathfinder;
import com.botwithus.bot.api.script.Task;
import com.botwithus.bot.api.script.TaskScript;
import com.botwithus.bot.api.ui.ScriptUI;
import com.botwithus.bot.scripts.woodcutting.task.*;

import java.nio.file.Path;

/**
 * Woodcutting script with wood box support and collision-aware banking.
 * Uses the TaskScript/Task SPI for clean state management.
 */
@ScriptManifest(
        name = "Woodcutting",
        version = "1.0",
        author = "Xapi",
        description = "Chops trees with wood box support and collision-aware pathing",
        category = ScriptCategory.WOODCUTTING
)
public class WoodcuttingScript extends TaskScript {

    private static final BotLogger log = LoggerFactory.getLogger(WoodcuttingScript.class);

    WoodcuttingContext wctx;
    private WoodcuttingUI ui;
    private ScriptOverlay overlay;

    @Override
    public void onStart(ScriptContext ctx) {
        GameAPI api = ctx.getGameAPI();
        Pace pace = ctx.getPace();
        pace.seed(ctx.getConnectionName());

        this.wctx = new WoodcuttingContext(api, pace, new WoodcuttingConfig());
        this.ui = new WoodcuttingUI(this);

        super.onStart(ctx); // calls setupTasks() — wctx must be initialized above

        // Subscribe to tick events for accurate log counting
        ctx.getEventBus().subscribe(TickEvent.class, wctx::onTick);

        if (WorldPathfinder.getInstance() == null) {
            WorldPathfinder.init(Path.of("navdata"));
            wctx.logAction("WorldPathfinder initialized from script");
        }
        if (LocalPathfinder.getInstance() == null) {
            LocalPathfinder.init(Path.of("navdata/regions"));
        }

        // Launch overlay
        this.overlay = new ScriptOverlay(wctx);
        overlay.show();

        wctx.logAction("Script started - tree: " + wctx.config.getTreeType().objectName);
        log.info("[Woodcutting] Started - tree={}, pathfinder={}",
                wctx.config.getTreeType().objectName,
                WorldPathfinder.getInstance() != null ? "active" : "null");
    }

    @Override
    protected void setupTasks() {
        addTask(new FillWoodBoxTask(wctx));   // priority 30
        addTask(new BankTask(wctx));          // priority 20
        addTask(new WalkToTreesTask(wctx));   // priority 10
        addTask(new ChoppingTask(wctx));      // priority 0
    }

    @Override
    public int onLoop() {
        wctx.pace.breakCheck();
        wctx.collectUIState();

        if (wctx.playerHelper.isMoving()) {
            return (int) wctx.pace.delay("walk");
        }

        // Handle debug force-task
        String forced = wctx.forceTaskName;
        if (forced != null) {
            wctx.forceTaskName = null;
            for (Task task : getTasks()) {
                if (task.name().equals(forced)) {
                    wctx.currentTaskName = task.name();
                    return task.execute();
                }
            }
        }

        // Run highest-priority validating task
        for (Task task : getTasks()) {
            if (task.validate()) {
                wctx.currentTaskName = task.name();
                return task.execute();
            }
        }

        return 600;
    }

    @Override
    public void onStop() {
        if (overlay != null) {
            overlay.close();
        }
        wctx.logAction("Script stopped - chopped " + wctx.logsChopped + " logs, " + wctx.bankTrips + " bank trips");
        log.info("[Woodcutting] Stopped - logs={}, fills={}, trips={}",
                wctx.logsChopped, wctx.woodboxFills, wctx.bankTrips);
    }

    @Override
    public ScriptUI getUI() {
        return ui;
    }

    @Override
    public java.util.List<com.botwithus.bot.api.script.Task> getTasks() {
        return super.getTasks();
    }

    WoodcuttingContext getContext() {
        return wctx;
    }
}
