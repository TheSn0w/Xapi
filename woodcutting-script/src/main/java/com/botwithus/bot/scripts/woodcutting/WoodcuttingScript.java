package com.botwithus.bot.scripts.woodcutting;

import com.botwithus.bot.api.GameAPI;
import com.botwithus.bot.api.ScriptCategory;
import com.botwithus.bot.api.ScriptContext;
import com.botwithus.bot.api.ScriptManifest;
import com.botwithus.bot.api.antiban.Pace;
import com.botwithus.bot.api.event.TickEvent;
import com.botwithus.bot.api.log.BotLogger;
import com.botwithus.bot.api.log.LoggerFactory;
import com.botwithus.bot.api.nav.LocalPathfinder;
import com.botwithus.bot.api.nav.WorldPathfinder;
import com.botwithus.bot.api.script.Task;
import com.botwithus.bot.api.script.TaskScript;
import com.botwithus.bot.api.ui.ScriptUI;
import com.botwithus.bot.scripts.woodcutting.task.BankTask;
import com.botwithus.bot.scripts.woodcutting.task.ChoppingTask;
import com.botwithus.bot.scripts.woodcutting.task.FillWoodBoxTask;
import com.botwithus.bot.scripts.woodcutting.task.WalkToTreesTask;

import java.nio.file.Path;

@ScriptManifest(
        name = "Woodcutting",
        version = "2.0",
        author = "Xapi",
        description = "Profile-driven woodcutting with colourful controls, quirks, and overlay diagnostics",
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

        int idleTimeoutTicks = api.getVarbit(54077);
        if (idleTimeoutTicks > 0) {
            pace.idleTimeout(idleTimeoutTicks * 600L);
            log.info("[Woodcutting] Idle timeout from varbit 54077: {}s", idleTimeoutTicks * 600L / 1000);
        }

        this.wctx = new WoodcuttingContext(api, pace, new WoodcuttingConfig());
        this.ui = new WoodcuttingUI(this);

        super.onStart(ctx);

        ctx.getEventBus().subscribe(TickEvent.class, wctx::onTick);

        if (WorldPathfinder.getInstance() == null) {
            WorldPathfinder.init(Path.of("navdata"));
            wctx.logAction("INFO: WorldPathfinder initialized");
        }
        if (LocalPathfinder.getInstance() == null) {
            LocalPathfinder.init(Path.of("navdata/regions"));
        }

        this.overlay = new ScriptOverlay(wctx);
        overlay.show();

        wctx.collectUIState();
        wctx.logAction("INFO: Started -> " + wctx.selectedTreeName + " / " + wctx.selectedHotspotName);
        log.info("[Woodcutting] Started - tree={}, hotspot={}", wctx.selectedTreeName, wctx.selectedHotspotName);
    }

    @Override
    protected void setupTasks() {
        addTask(new FillWoodBoxTask(wctx));
        addTask(new BankTask(wctx));
        addTask(new WalkToTreesTask(wctx));
        addTask(new ChoppingTask(wctx));
    }

    @Override
    public int onLoop() {
        wctx.pace.breakCheck();
        wctx.collectUIState();

        if (wctx.playerMoving) {
            return (int) wctx.pace.delay("walk");
        }

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

        for (Task task : getTasks()) {
            if (task.validate()) {
                wctx.currentTaskName = task.name();
                return task.execute();
            }
        }

        wctx.currentTaskName = "Idle";
        return 600;
    }

    @Override
    public void onStop() {
        if (overlay != null) {
            overlay.close();
        }
        if (wctx != null) {
            wctx.logAction("INFO: Stopped -> " + wctx.logsChopped + " resources, " + wctx.bankTrips + " trips");
            log.info("[Woodcutting] Stopped - logs={}, fills={}, trips={}", wctx.logsChopped, wctx.woodboxFills, wctx.bankTrips);
        }
    }

    @Override
    public ScriptUI getUI() {
        return ui;
    }

    @Override
    public java.util.List<Task> getTasks() {
        return super.getTasks();
    }

    WoodcuttingContext getContext() {
        return wctx;
    }
}
