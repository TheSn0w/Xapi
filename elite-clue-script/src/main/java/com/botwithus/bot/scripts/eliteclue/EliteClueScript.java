package com.botwithus.bot.scripts.eliteclue;

import com.botwithus.bot.api.GameAPI;
import com.botwithus.bot.api.ScriptCategory;
import com.botwithus.bot.api.ScriptContext;
import com.botwithus.bot.api.ScriptManifest;
import com.botwithus.bot.api.antiban.Pace;
import com.botwithus.bot.api.event.TickEvent;
import com.botwithus.bot.api.log.BotLogger;
import com.botwithus.bot.api.log.LoggerFactory;
import com.botwithus.bot.api.nav.LocalPathfinder;
import com.botwithus.bot.api.Navigation;
import com.botwithus.bot.api.nav.WorldPathfinder;
import com.botwithus.bot.api.script.Task;
import com.botwithus.bot.api.script.TaskScript;
import com.botwithus.bot.api.ui.ScriptUI;
import com.botwithus.bot.scripts.eliteclue.task.DiagnosticTask;
import com.botwithus.bot.scripts.eliteclue.task.OpenClueTask;
import com.botwithus.bot.scripts.eliteclue.task.ScanClueTask;
import com.botwithus.bot.scripts.eliteclue.task.SlidePuzzleTask;

import java.nio.file.Path;

@ScriptManifest(
        name = "Elite Clue (Diagnostic)",
        version = "0.1",
        author = "Snow",
        description = "Elite clue scroll diagnostic template - reports game state for building the full solver",
        category = ScriptCategory.MINIGAME
)
public class EliteClueScript extends TaskScript {

    private static final BotLogger log = LoggerFactory.getLogger(EliteClueScript.class);

    ClueContext ctx;
    private EliteClueUI ui;
    ScanClueTask scanClueTask;

    @Override
    public void onStart(ScriptContext scriptCtx) {
        GameAPI api = scriptCtx.getGameAPI();
        Pace pace = scriptCtx.getPace();
        Navigation nav = scriptCtx.getNavigation();
        pace.seed(scriptCtx.getConnectionName());

        this.ctx = new ClueContext(api, pace, nav);
        this.ui = new EliteClueUI(this);

        super.onStart(scriptCtx);

        // Subscribe to game tick for lightweight tracking
        scriptCtx.getEventBus().subscribe(TickEvent.class, this::onTick);

        // Initialize pathfinding if not already done
        if (WorldPathfinder.getInstance() == null) {
            WorldPathfinder.init(Path.of("navdata"));
            ctx.logAction("WorldPathfinder initialized");
        }
        if (LocalPathfinder.getInstance() == null) {
            LocalPathfinder.init(Path.of("navdata/regions"));
        }

        // Load scan clue coordinate database from cache
        if (ctx.scanData.loadAll()) {
            ctx.logAction("Scan data loaded: " + ctx.scanData.getAllRegions().size() + " regions");
        } else {
            ctx.logAction("WARNING: Scan data failed to load from cache");
        }

        ctx.collectUIState();
        ctx.logAction("Script started - diagnostic mode");
        log.info("[EliteClue] Script started in diagnostic mode");
    }

    @Override
    protected void setupTasks() {
        // Priority order: highest checked first
        // Open clue (priority 100) — open clue items in backpack
        addTask(new OpenClueTask(ctx));           // priority 100

        // Puzzle solvers (priority 90) — activate when interface opens
        addTask(new SlidePuzzleTask(ctx));       // priority 90

        // Scanner (priority 80) — scan clue candidate elimination
        this.scanClueTask = new ScanClueTask(ctx, ctx.scanData, ctx.scanTracker);
        addTask(scanClueTask);                   // priority 80

        // Future tasks:
        //   - CelticKnotTask (priority 90)
        //   - CompassTask (priority 80)
        //   - DigTask (priority 70)
        //   - DialogTask (priority 60)
        //   - CombatTask (priority 50)
        //   - FamiliarTask (priority 40)
        //   - NavigateTask (priority 10)

        // Diagnostic fallback (priority 0) — always runs when nothing else validates
        addTask(new DiagnosticTask(ctx));
    }

    @Override
    public int onLoop() {
        ctx.pace.breakCheck();
        ctx.collectUIState();

        // Determine what activity we should be doing
        ClueActivity detected = ctx.determineActivity();
        if (detected != ctx.activity) {
            ctx.previousActivity = ctx.activity;
            ctx.activity = detected;
            ctx.logAction("Activity: " + ctx.previousActivity.label() + " -> " + detected.label());

            // Notify scan task when scanner closes
            if (ctx.previousActivity == ClueActivity.SCANNER && detected != ClueActivity.SCANNER) {
                if (scanClueTask != null) {
                    scanClueTask.onScannerClosed();
                }
            }
        }

        // If player is moving, wait
        if (ctx.playerMoving) {
            return (int) ctx.pace.delay("walk");
        }

        return super.onLoop();
    }

    private void onTick(TickEvent event) {
        // Lightweight per-tick updates (NO RPC here)
    }

    @Override
    public void onStop() {
        if (ctx != null) {
            ctx.logAction("Script stopped");
            log.info("[EliteClue] Stopped - {} steps completed", ctx.stepsCompleted);
        }
    }

    @Override
    public ScriptUI getUI() {
        return ui;
    }
}
