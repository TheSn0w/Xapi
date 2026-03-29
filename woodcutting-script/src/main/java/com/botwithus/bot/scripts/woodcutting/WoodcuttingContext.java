package com.botwithus.bot.scripts.woodcutting;

import com.botwithus.bot.api.GameAPI;
import com.botwithus.bot.api.antiban.Pace;
import com.botwithus.bot.api.entities.SceneObjects;
import com.botwithus.bot.api.event.TickEvent;
import com.botwithus.bot.api.inventory.Backpack;
import com.botwithus.bot.api.inventory.banking.Bank;
import com.botwithus.bot.api.inventory.WoodBox;
import com.botwithus.bot.api.log.BotLogger;
import com.botwithus.bot.api.log.LoggerFactory;
import com.botwithus.bot.api.model.GameWindowRect;
import com.botwithus.bot.api.model.LocalPlayer;
import com.botwithus.bot.api.util.LocalPlayerHelper;

import java.util.Arrays;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Shared state between all woodcutting tasks and the UI.
 * All volatile fields are populated by {@link #collectUIState()} on the script thread.
 */
public final class WoodcuttingContext {

    private static final BotLogger log = LoggerFactory.getLogger(WoodcuttingContext.class);
    private static final int MAX_LOG_ENTRIES = 200;

    public static final int[] WOOD_BOX_KEEP_IDS = Arrays.stream(WoodBox.Tier.values())
            .mapToInt(t -> t.itemId)
            .toArray();

    // ── API references ───────────────────────────────────────────
    public final GameAPI api;
    public final Pace pace;
    public final Backpack backpack;
    public final SceneObjects objects;
    public final WoodBox woodBox;
    public final Bank bank;
    public final LocalPlayerHelper playerHelper;
    public final WoodcuttingConfig config;
    public final Quirks quirks;

    // ── Counters ─────────────────────────────────────────────────
    public volatile int logsChopped = 0;
    public volatile int woodboxFills = 0;
    public volatile int bankTrips = 0;
    volatile long startTime = 0;

    // ── UI snapshots (written by collectUIState, read by UI thread) ─
    volatile int animationId = -1;
    volatile int freeSlots = 0;
    volatile int woodboxStored = 0;
    volatile int woodboxCapacity = 0;
    volatile boolean hasWoodBox = false;
    volatile boolean bankOpen = false;
    volatile String pacePhase = "";
    volatile double fatigue = 0.0;
    volatile String currentTaskName = "Idle";
    volatile String forceTaskName = null;
    volatile boolean onBreak = false;
    volatile String breakLabel = null;
    volatile long breakRemainingMs = 0;
    volatile double sessionMinutes = 0;
    volatile String delayContext = "";
    volatile boolean playerMoving = false;
    volatile String attentionState = "Focused";
    volatile String lastQuirk = "None";

    // ── Game window position (updated every tick for overlay tracking) ─
    volatile int gameWindowX = 0;
    volatile int gameWindowY = 0;
    volatile int gameWindowWidth = 0;
    volatile int gameWindowHeight = 0;

    // ── Tick-based log tracking ─────────────────────────────────
    private int previousLogCount = -1;

    // ── Action log ───────────────────────────────────────────────
    final CopyOnWriteArrayList<String> actionLog = new CopyOnWriteArrayList<>();

    public WoodcuttingContext(GameAPI api, Pace pace, WoodcuttingConfig config) {
        this.api = api;
        this.pace = pace;
        this.config = config;
        this.backpack = new Backpack(api);
        this.objects = new SceneObjects(api);
        this.woodBox = new WoodBox(api);
        this.bank = new Bank(api);
        this.quirks = new Quirks();
        this.playerHelper = new LocalPlayerHelper(api);
        this.startTime = System.currentTimeMillis();
    }

    void collectUIState() {
        LocalPlayer player = api.getLocalPlayer();
        this.animationId = player.animationId();
        this.freeSlots = backpack.freeSlots();
        this.hasWoodBox = woodBox.hasWoodBox();
        this.woodboxStored = woodBox.getTotalStored();
        this.woodboxCapacity = woodBox.getCapacity();
        this.bankOpen = bank.isOpen();
        this.pacePhase = pace.phase();
        this.fatigue = pace.fatigue();
        this.onBreak = pace.onBreak();
        this.breakLabel = pace.breakLabel();
        this.breakRemainingMs = pace.breakRemainingMs();
        this.attentionState = pace.attentionState().label();
        this.lastQuirk = quirks.getLastQuirk();
        this.sessionMinutes = pace.sessionMinutes();
    }

    public void logAction(String message) {
        String entry = String.format("[%tT] %s", System.currentTimeMillis(), message);
        actionLog.addFirst(entry);
        while (actionLog.size() > MAX_LOG_ENTRIES) {
            actionLog.removeLast();
        }
    }

    /**
     * Called every game tick via EventBus subscription.
     * Diffs backpack log count to detect actual gains.
     */
    public void onTick(TickEvent event) {
        // Refresh player state every game tick so the overlay has fresh data
        // even while the script thread is sleeping between loop iterations.
        LocalPlayer player = api.getLocalPlayer();
        this.animationId = player.animationId();
        this.playerMoving = player.isMoving();
        this.onBreak = pace.onBreak();
        this.breakLabel = pace.breakLabel();
        this.breakRemainingMs = pace.breakRemainingMs();
        this.delayContext = pace.currentContext();

        // Track game window position for overlay anchoring
        try {
            GameWindowRect rect = api.getGameWindowRect();
            this.gameWindowX = rect.x();
            this.gameWindowY = rect.y();
            this.gameWindowWidth = rect.width();
            this.gameWindowHeight = rect.height();
        } catch (Exception ignored) { }

        // Diff backpack log count to detect actual gains
        int logItemId = config.getTreeType().logType.itemId;
        int current = backpack.count(logItemId);

        if (previousLogCount >= 0 && current > previousLogCount) {
            int gained = current - previousLogCount;
            logsChopped += gained;
            log.debug("[Woodcutting] +{} logs (total {})", gained, logsChopped);
        }

        previousLogCount = current;
    }

    void forceTask(String taskName) {
        this.forceTaskName = taskName;
        logAction("DEBUG: forcing task -> " + taskName);
    }
}
