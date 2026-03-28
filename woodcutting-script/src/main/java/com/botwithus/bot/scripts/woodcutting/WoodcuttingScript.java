package com.botwithus.bot.scripts.woodcutting;

import com.botwithus.bot.api.*;
import com.botwithus.bot.api.antiban.Pace;
import com.botwithus.bot.api.entities.SceneObject;
import com.botwithus.bot.api.entities.SceneObjects;
import com.botwithus.bot.api.inventory.Backpack;
import com.botwithus.bot.api.inventory.Bank;
import com.botwithus.bot.api.inventory.WoodBox;
import com.botwithus.bot.api.log.BotLogger;
import com.botwithus.bot.api.log.LoggerFactory;
import com.botwithus.bot.api.model.LocalPlayer;
import com.botwithus.bot.api.nav.LocalPathfinder;
import com.botwithus.bot.api.nav.WorldPathfinder;
import com.botwithus.bot.api.ui.ScriptUI;
import com.botwithus.bot.api.util.Conditions;

import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Woodcutting script with wood box support and collision-aware banking.
 *
 * <p>State machine: CHOPPING -> FILLING_WOODBOX -> BANKING -> WALKING_TO_TREES -> CHOPPING</p>
 */
@ScriptManifest(
        name = "Woodcutting",
        version = "1.0",
        author = "Xapi",
        description = "Chops trees with wood box support and collision-aware pathing",
        category = ScriptCategory.WOODCUTTING
)
public class WoodcuttingScript implements BotScript {

    private static final BotLogger log = LoggerFactory.getLogger(WoodcuttingScript.class);

    private ScriptContext ctx;
    private GameAPI api;
    private Pace pace;
    private Backpack backpack;
    private SceneObjects objects;
    private WoodBox woodBox;
    private Bank bank;

    final WoodcuttingConfig config = new WoodcuttingConfig();
    private volatile WoodcuttingState state = WoodcuttingState.IDLE;

    volatile int logsChopped = 0;
    volatile int woodboxFills = 0;
    volatile int bankTrips = 0;
    volatile long startTime = 0;

    volatile int animationId = -1;
    volatile int freeSlots = 0;
    volatile int woodboxStored = 0;
    volatile int woodboxCapacity = 0;
    volatile boolean hasWoodBox = false;
    volatile boolean bankOpen = false;
    volatile String pacePhase = "";
    volatile double fatigue = 0.0;
    volatile boolean onBreak = false;

    final CopyOnWriteArrayList<String> actionLog = new CopyOnWriteArrayList<>();
    private static final int MAX_LOG_ENTRIES = 200;

    private WoodcuttingUI ui;

    @Override
    public void onStart(ScriptContext ctx) {
        this.ctx = ctx;
        this.api = ctx.getGameAPI();
        this.pace = ctx.getPace();
        this.backpack = new Backpack(api);
        this.objects = new SceneObjects(api);
        this.woodBox = new WoodBox(api);
        this.bank = new Bank(api);
        this.ui = new WoodcuttingUI(this);
        this.startTime = System.currentTimeMillis();
        this.state = WoodcuttingState.CHOPPING;

        // Initialize transition-aware world pathfinder (ClaudeDecoder A*)
        if (WorldPathfinder.getInstance() == null) {
            WorldPathfinder.init(Path.of("navdata"));
            logAction("WorldPathfinder initialized from script");
        }
        logAction("WorldPathfinder: " + (WorldPathfinder.getInstance() != null ? "ACTIVE" : "NOT AVAILABLE"));

        // Also init local pathfinder for debug/fallback
        if (LocalPathfinder.getInstance() == null) {
            LocalPathfinder.init(Path.of("navdata/regions"));
        }
        logAction("Script started - tree: " + config.getTreeType().objectName);
        log.info("[Woodcutting] Started - tree={}, logType={}, pathfinder={}",
                config.getTreeType().objectName, config.getTreeType().logType.name,
                LocalPathfinder.getInstance() != null ? "active" : "null");
    }

    @Override
    public ScriptUI getUI() {
        return ui;
    }

    @Override
    public int onLoop() {
        pace.breakCheck();
        collectUIState();

        LocalPlayer player = api.getLocalPlayer();
        if (player.isMoving()) {
            return (int) pace.idle("walk");
        }

        return switch (state) {
            case IDLE -> handleIdle();
            case CHOPPING -> handleChopping(player);
            case FILLING_WOODBOX -> handleFillingWoodbox();
            case WALKING_TO_BANK -> handleBanking(); // No separate walk needed; interact handles it.
            case BANKING -> handleBanking();
            case WALKING_TO_TREES -> handleWalkingToTrees(player);
        };
    }

    @Override
    public void onStop() {
        logAction("Script stopped - chopped " + logsChopped + " logs, " + bankTrips + " bank trips");
        log.info("[Woodcutting] Stopped - logs={}, fills={}, trips={}", logsChopped, woodboxFills, bankTrips);
    }

    private int handleIdle() {
        state = WoodcuttingState.CHOPPING;
        logAction("Transitioning from IDLE to CHOPPING");
        return (int) pace.delay("react");
    }

    private int handleChopping(LocalPlayer player) {
        WoodcuttingConfig.TreeType tree = config.getTreeType();

        if (backpack.isFull()) {
            if (woodBox.hasWoodBox() && woodBox.canStore(tree.logType) && !woodBox.isFull()) {
                state = WoodcuttingState.FILLING_WOODBOX;
                logAction("Backpack full -> filling wood box");
                return (int) pace.delay("react");
            }
            state = WoodcuttingState.BANKING;
            logAction("Backpack full" + (woodBox.isFull() ? ", wood box full" : "") + " -> banking");
            return (int) pace.delay("react");
        }

        if (distanceTo(player, config.getTreeAreaX(), config.getTreeAreaY()) > config.getWalkRadius()) {
            log.debug("[Woodcutting] Too far from trees, walking to tree area");
            logAction("Walking to tree area");
            api.walkWorldPathAsync(config.getTreeAreaX(), config.getTreeAreaY(), 0);
            return (int) pace.delay("walk");
        }

        if (player.animationId() != -1) {
            return (int) pace.idle("gather");
        }

        List<SceneObject> candidates = objects.query()
                .named(tree.objectName)
                .within(config.getTreeAreaX(), config.getTreeAreaY(), config.getWalkRadius())
                .visible()
                .filter(o -> o.hasOption(tree.interactOption))
                .all();

        if (candidates.isEmpty()) {
            logAction("No choppable " + tree.objectName + " found nearby");
            return (int) pace.idle("gather");
        }

        SceneObject target = pickNearest(candidates);
        logAction(tree.interactOption + " " + target.name() + " at (" + target.tileX() + ", " + target.tileY() + ")");
        if (!target.interact(tree.interactOption)) {
            log.warn("[Woodcutting] Failed to interact with {} at ({}, {})", target.name(), target.tileX(), target.tileY());
            logAction("Failed to " + tree.interactOption + " " + target.name());
            return (int) pace.delay("react");
        }

        boolean started = Conditions.waitUntil(() -> {
            LocalPlayer local = api.getLocalPlayer();
            return local.animationId() != -1 || local.isMoving();
        }, 1200);

        if (!started) {
            log.debug("[Woodcutting] Interaction queued but chopping did not start");
            logAction("Interaction queued but chopping did not start");
            return (int) pace.delay("react");
        }

        logsChopped++;
        return (int) pace.idle("gather");
    }

    private int handleFillingWoodbox() {
        WoodcuttingConfig.TreeType tree = config.getTreeType();

        if (woodBox.isFull()) {
            logAction("Wood box full (" + woodBox.getTotalStored() + "/" + woodBox.getCapacity() + ") -> banking");
            state = WoodcuttingState.BANKING;
            return (int) pace.delay("react");
        }

        if (!woodBox.hasWoodBox()) {
            log.warn("[Woodcutting] No wood box found - going to bank instead");
            logAction("No wood box in backpack -> walking to bank");
            state = WoodcuttingState.BANKING;
            return (int) pace.delay("react");
        }

        if (!woodBox.canStore(tree.logType)) {
            log.warn("[Woodcutting] Wood box cannot store {} - going to bank", tree.logType.name);
            logAction("Wood box can't store " + tree.logType.name + " -> walking to bank");
            state = WoodcuttingState.BANKING;
            return (int) pace.delay("react");
        }

        int storedBefore = woodBox.getTotalStored();
        int logsBefore = backpack.count(tree.logType.itemId);

        log.info("[Woodcutting] Filling wood box with {}", tree.logType.name);
        logAction("Filling wood box");
        boolean filled = woodBox.fill();
        if (!filled) {
            log.warn("[Woodcutting] Fill action failed - going to bank");
            state = WoodcuttingState.BANKING;
            return (int) pace.delay("react");
        }

        woodboxFills++;
        Conditions.waitUntil(() -> {
            int storedNow = woodBox.getTotalStored();
            int logsNow = backpack.count(tree.logType.itemId);
            return storedNow > storedBefore || logsNow < logsBefore;
        }, 3000);

        int storedAfter = woodBox.getTotalStored();
        int logsAfter = backpack.count(tree.logType.itemId);
        int capacity = woodBox.getCapacity();
        boolean storageIncreased = storedAfter > storedBefore;
        boolean backpackLogsDropped = logsAfter < logsBefore;

        if (!storageIncreased && !backpackLogsDropped) {
            log.warn("[Woodcutting] Fill action did not move any logs (stored {}/{}, backpack logs still {})",
                    storedAfter, capacity, logsAfter);
            logAction("Wood box fill made no progress -> walking to bank");
            state = WoodcuttingState.BANKING;
            return (int) pace.delay("gather");
        }

        if (logsAfter > 0 && storedAfter >= capacity) {
            log.info("[Woodcutting] Wood box reached capacity after fill ({}/{}), backpack still has {} logs",
                    storedAfter, capacity, logsAfter);
            logAction("Wood box full after fill -> walking to bank");
            state = WoodcuttingState.BANKING;
            return (int) pace.delay("gather");
        }

        if (logsAfter > 0) {
            log.info("[Woodcutting] Fill stored more logs ({}/{}), backpack still reports {} logs; resuming chopping",
                    storedAfter, capacity, logsAfter);
            logAction("Wood box fill progressed (" + storedAfter + "/" + capacity + ") -> resuming chopping");
        } else {
            log.info("[Woodcutting] Wood box fill complete - resuming chopping");
            logAction("Wood box filled -> resuming chopping");
        }

        state = WoodcuttingState.CHOPPING;
        return (int) pace.delay("gather");
    }

    private int handleBanking() {
        if (!bank.isOpen()) {
            SceneObject counter = objects.query().withId(2012).nearest();
            if (counter == null) {
                log.warn("[Woodcutting] No bank Counter found nearby");
                logAction("No Counter found - retrying");
                return (int) pace.delay("react");
            }

            logAction("Bank at Counter (" + counter.tileX() + ", " + counter.tileY() + ")");
            counter.interact("Bank");
            Conditions.waitUntil(bank::isOpen, 30000);
            return (int) pace.delay("bank");
        }

        if (woodBox.hasWoodBox() && !woodBox.isEmpty()) {
            log.info("[Woodcutting] Emptying wood box ({} items stored)", woodBox.getTotalStored());
            logAction("Emptying wood box (" + woodBox.getTotalStored() + " items)");
            woodBox.empty();
            Conditions.waitUntil(woodBox::isEmpty, 3000);
            return (int) pace.delay("bank");
        }

        int[] keepIds = getWoodBoxItemIds();
        log.info("[Woodcutting] Depositing all items except wood box (keeping {} IDs)", keepIds.length);
        logAction("Depositing backpack (keeping wood box)");
        bank.depositAllExcept(keepIds);
        Conditions.waitUntil(() -> bank.backpackOccupiedSlots() <= keepIds.length, 3000);

        log.info("[Woodcutting] Banking complete - closing bank");
        logAction("Banking complete - closing bank");
        bank.close();
        Conditions.waitUntil(() -> !bank.isOpen(), 2000);

        bankTrips++;
        state = WoodcuttingState.WALKING_TO_TREES;
        return (int) pace.delay("bank");
    }

    private int handleWalkingToTrees(LocalPlayer player) {
        if (distanceTo(player, config.getTreeAreaX(), config.getTreeAreaY()) <= config.getWalkRadius()) {
            log.info("[Woodcutting] Arrived at tree area - resuming chopping");
            logAction("Arrived at trees -> chopping");
            state = WoodcuttingState.CHOPPING;
            return (int) pace.delay("react");
        }

        logAction("Walking to trees");
        api.walkWorldPathAsync(config.getTreeAreaX(), config.getTreeAreaY(), 0);
        return (int) pace.delay("walk");
    }

    private static int distanceTo(LocalPlayer p, int x, int y) {
        return Math.max(Math.abs(p.tileX() - x), Math.abs(p.tileY() - y));
    }

    /**
     * Picks the nearest entity from a pre-filtered list using the transition-aware
     * WorldPathfinder (ClaudeDecoder A*) for accurate walk distances that respect
     * walls, doors, and collision data.
     */
    private SceneObject pickNearest(List<SceneObject> candidates) {
        if (candidates.size() == 1) return candidates.getFirst();

        LocalPlayer lp = api.getLocalPlayer();
        int px = lp.tileX(), py = lp.tileY(), plane = lp.plane();

        WorldPathfinder wpf = WorldPathfinder.getInstance();
        SceneObject best = null;
        int bestDist = Integer.MAX_VALUE;

        for (SceneObject obj : candidates) {
            int dist;
            if (wpf != null) {
                dist = wpf.walkDistance(px, py, obj.tileX(), obj.tileY(), plane);
                if (dist < 0) dist = Integer.MAX_VALUE; // no path
            } else {
                // Fallback to Chebyshev if pathfinder unavailable
                dist = Math.max(Math.abs(px - obj.tileX()), Math.abs(py - obj.tileY()));
            }
            if (dist < bestDist) {
                bestDist = dist;
                best = obj;
            }
        }
        return best != null ? best : candidates.getFirst();
    }

    /**
     * Returns item IDs of all wood box tiers to keep in the backpack during deposit.
     */
    private int[] getWoodBoxItemIds() {
        return Arrays.stream(WoodBox.Tier.values())
                .mapToInt(t -> t.itemId)
                .toArray();
    }

    /**
     * Collects volatile state snapshots for the UI thread to read safely.
     */
    private void collectUIState() {
        LocalPlayer player = api.getLocalPlayer();
        this.animationId = player.animationId();
        this.freeSlots = backpack.freeSlots();
        this.hasWoodBox = woodBox.hasWoodBox();
        this.woodboxStored = woodBox.getTotalStored();
        this.woodboxCapacity = woodBox.getCapacity();
        this.bankOpen = bank.isOpen();
        this.pacePhase = pace.phase();
        this.fatigue = pace.fatigue();
        this.onBreak = false; // breakCheck is handled in onLoop().
    }

    /**
     * Adds a timestamped entry to the action log shared with the UI.
     */
    void logAction(String message) {
        String entry = String.format("[%tT] %s", System.currentTimeMillis(), message);
        actionLog.addFirst(entry);
        while (actionLog.size() > MAX_LOG_ENTRIES) {
            actionLog.removeLast();
        }
    }

    /**
     * Forces the script into a specific state. Used by debug buttons in the UI.
     */
    void forceState(WoodcuttingState newState) {
        WoodcuttingState old = this.state;
        this.state = newState;
        logAction("DEBUG: forced state " + old.getDisplayName() + " -> " + newState.getDisplayName());
        log.info("[Woodcutting] DEBUG: forced state {} -> {}", old, newState);
    }

    WoodcuttingState getState() { return state; }
    WoodcuttingConfig getConfig() { return config; }
    Pace getPace() { return pace; }
    GameAPI getAPI() { return api; }
}
