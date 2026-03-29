package com.botwithus.bot.scripts.woodcutting;

import com.botwithus.bot.api.GameAPI;
import com.botwithus.bot.api.antiban.Pace;
import com.botwithus.bot.api.entities.SceneObject;
import com.botwithus.bot.api.entities.SceneObjects;
import com.botwithus.bot.api.event.TickEvent;
import com.botwithus.bot.api.inventory.Backpack;
import com.botwithus.bot.api.inventory.WoodBox;
import com.botwithus.bot.api.inventory.banking.Bank;
import com.botwithus.bot.api.inventory.banking.DepositBox;
import com.botwithus.bot.api.log.BotLogger;
import com.botwithus.bot.api.log.LoggerFactory;
import com.botwithus.bot.api.model.GameWindowRect;
import com.botwithus.bot.api.model.LocalPlayer;
import com.botwithus.bot.api.util.LocalPlayerHelper;
import com.botwithus.bot.api.util.Skills;

import java.util.Arrays;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;

public final class WoodcuttingContext {

    private static final BotLogger log = LoggerFactory.getLogger(WoodcuttingContext.class);
    private static final int MAX_LOG_ENTRIES = 220;

    public static final int[] WOOD_BOX_KEEP_IDS = Arrays.stream(WoodBox.Tier.values())
            .mapToInt(t -> t.itemId)
            .toArray();

    public final GameAPI api;
    public final Pace pace;
    public final Backpack backpack;
    public final SceneObjects objects;
    public final WoodBox woodBox;
    public final Bank bank;
    public final DepositBox depositBox;
    public final LocalPlayerHelper playerHelper;
    public final WoodcuttingConfig config;
    public final Quirks quirks;

    public volatile int logsChopped = 0;
    public volatile int woodboxFills = 0;
    public volatile int bankTrips = 0;
    volatile long startTime = 0;

    volatile int animationId = -1;
    volatile int freeSlots = 0;
    volatile int woodboxStored = 0;
    volatile int woodboxCapacity = 0;
    volatile boolean hasWoodBox = false;
    volatile boolean bankOpen = false;
    volatile boolean depositOpen = false;
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

    volatile int gameWindowX = 0;
    volatile int gameWindowY = 0;
    volatile int gameWindowWidth = 0;
    volatile int gameWindowHeight = 0;

    volatile int woodcuttingLevel = 1;
    volatile String selectedTreeName = "";
    volatile String selectedHotspotName = "";
    volatile String modeLabel = "";
    volatile String inventoryModeLabel = "";
    volatile String requirementStatus = "";
    volatile String requirementDetail = "";
    volatile String profileSummary = "";
    volatile String hotspotNote = "";
    volatile String currentTargetName = "-";
    volatile int currentTargetId = -1;
    volatile String currentTargetTile = "-";
    volatile String currentRouteLabel = "";
    volatile boolean requirementsMet = false;
    volatile boolean manualRequirementOnly = false;

    final CopyOnWriteArrayList<String> actionLog = new CopyOnWriteArrayList<>();

    private int previousResourceCount = -1;
    private String previousResourceKey = "";
    private String lastTreeId = "";
    private String lastHotspotId = "";
    private int routeIndex = 0;
    private long lastRouteAdvanceMs = 0L;
    private long lastGuardLogMs = 0L;
    private volatile boolean assumeWoodBoxEmpty = false;

    public WoodcuttingContext(GameAPI api, Pace pace, WoodcuttingConfig config) {
        this.api = api;
        this.pace = pace;
        this.config = config;
        this.backpack = new Backpack(api);
        this.objects = new SceneObjects(api);
        this.woodBox = new WoodBox(api);
        this.bank = new Bank(api);
        this.depositBox = new DepositBox(api);
        this.quirks = new Quirks();
        this.playerHelper = new LocalPlayerHelper(api);
        this.startTime = System.currentTimeMillis();
        syncSelectionState();
    }

    public TreeProfile profile() {
        return config.selectedTree();
    }

    public HotspotProfile hotspot() {
        return config.selectedHotspot();
    }

    public TileAnchor currentTreeAnchor() {
        HotspotProfile hotspot = hotspot();
        if (hotspot == null) {
            return new TileAnchor(api.getLocalPlayer().tileX(), api.getLocalPlayer().tileY(), api.getLocalPlayer().plane(), "Current tile");
        }
        if (hotspot.hasRoute()) {
            return hotspot.routeAnchors().get(routeIndex % hotspot.routeAnchors().size());
        }
        return hotspot.treeAnchor();
    }

    public TileAnchor currentTravelAnchor() {
        HotspotProfile hotspot = hotspot();
        if (hotspot == null) {
            return currentTreeAnchor();
        }
        if (hotspot.hasRoute()) {
            return currentTreeAnchor();
        }
        return hotspot.travelAnchor();
    }

    public TileAnchor currentBankingAnchor() {
        HotspotProfile hotspot = hotspot();
        if (hotspot == null) {
            return null;
        }
        return switch (hotspot.inventoryMode()) {
            case BANK -> hotspot.bankAnchor();
            case DEPOSIT_BOX -> hotspot.depositAnchor();
            default -> null;
        };
    }

    public boolean shouldHandleInventory() {
        InventoryMode mode = hotspot().inventoryMode();
        if (mode == InventoryMode.NONE) {
            return false;
        }
        if (mode == InventoryMode.BANK || mode == InventoryMode.DEPOSIT_BOX) {
            return backpack.isFull() || bankOpen || depositOpen || quirks.shouldEarlyBank(backpack.freeSlots());
        }
        return backpack.isFull();
    }

    public boolean woodBoxShouldBeTreatedAsEmpty() {
        return assumeWoodBoxEmpty;
    }

    public boolean woodBoxIsFull(TreeProfile profile) {
        return !assumeWoodBoxEmpty && profile.supportsWoodBox() && woodBox.isFull(profile.woodBoxLogType());
    }

    public int woodBoxStoredFor(TreeProfile profile) {
        if (assumeWoodBoxEmpty || !profile.supportsWoodBox()) {
            return 0;
        }
        return woodBox.count(profile.woodBoxLogType());
    }

    public boolean woodBoxIsEmptyEffective() {
        return assumeWoodBoxEmpty || woodBox.isEmpty();
    }

    public void markWoodBoxEmptied() {
        assumeWoodBoxEmpty = true;
        woodboxStored = 0;
    }

    public void clearWoodBoxEmptyAssumption() {
        assumeWoodBoxEmpty = false;
    }

    public int trackedProductCount() {
        TreeProfile profile = profile();
        if (profile.logItemId() != null) {
            return backpack.count(profile.logItemId());
        }
        if (!profile.productName().isBlank()) {
            return backpack.count(profile.productName());
        }
        return 0;
    }

    public void collectUIState() {
        syncSelectionState();

        LocalPlayer player = api.getLocalPlayer();
        this.animationId = player.animationId();
        this.freeSlots = backpack.freeSlots();
        this.hasWoodBox = woodBox.hasWoodBox();
        this.woodboxStored = assumeWoodBoxEmpty ? 0 : woodBox.getTotalStored();
        this.woodboxCapacity = woodBox.getCapacity();
        this.bankOpen = bank.isOpen();
        this.depositOpen = depositBox.isOpen();
        this.pacePhase = pace.phase();
        this.fatigue = pace.fatigue();
        this.onBreak = pace.onBreak();
        this.breakLabel = pace.breakLabel();
        this.breakRemainingMs = pace.breakRemainingMs();
        this.attentionState = pace.attentionState().label();
        this.lastQuirk = quirks.getLastQuirk();
        this.sessionMinutes = pace.sessionMinutes();
        this.woodcuttingLevel = Skills.getLevel(api, Skills.WOODCUTTING);

        TreeProfile profile = profile();
        HotspotProfile hotspot = hotspot();
        boolean levelReady = woodcuttingLevel >= profile.requiredLevel();
        boolean hasManualGate = !profile.requirementNote().isBlank() || !hotspot.requirementNote().isBlank();
        this.selectedTreeName = profile.displayName();
        this.selectedHotspotName = hotspot.label();
        this.modeLabel = profile.mode().displayName();
        this.inventoryModeLabel = hotspot.inventoryMode().displayName();
        this.profileSummary = profile.behaviourSummary();
        this.hotspotNote = hotspot.note();
        this.requirementsMet = levelReady;
        this.manualRequirementOnly = levelReady && hasManualGate;

        if (!levelReady) {
            this.requirementStatus = "Need Woodcutting " + profile.requiredLevel();
            this.requirementDetail = "Current level: " + woodcuttingLevel;
        } else if (hasManualGate) {
            this.requirementStatus = "Manual unlocks";
            String detail = profile.requirementNote();
            if (!hotspot.requirementNote().isBlank()) {
                detail = detail.isBlank() ? hotspot.requirementNote() : detail + " | " + hotspot.requirementNote();
            }
            this.requirementDetail = detail;
        } else {
            this.requirementStatus = "Ready";
            this.requirementDetail = "Level and profile checks are clear.";
        }

        this.currentRouteLabel = hotspot.hasRoute()
                ? currentTreeAnchor().label() + " (" + (routeIndex + 1) + "/" + hotspot.routeAnchors().size() + ")"
                : hotspot.treeAnchor().label();

        if (assumeWoodBoxEmpty && woodBox.getTotalStored() > 0) {
            assumeWoodBoxEmpty = false;
            this.woodboxStored = woodBox.getTotalStored();
        }
    }

    public void syncSelectionState() {
        TreeProfile profile = profile();
        HotspotProfile hotspot = hotspot();
        boolean changed = !Objects.equals(lastTreeId, profile.id()) || !Objects.equals(lastHotspotId, hotspot.id());
        if (!changed) {
            return;
        }

        lastTreeId = profile.id();
        lastHotspotId = hotspot.id();
        routeIndex = 0;
        previousResourceCount = trackedProductCount();
        previousResourceKey = profile.id() + "|" + hotspot.id() + "|" + profile.productName();
        clearCurrentTarget();
        logAction("INFO: Profile -> " + profile.displayName() + " / " + hotspot.label());
    }

    public void advanceRoute(String reason) {
        HotspotProfile hotspot = hotspot();
        if (!hotspot.hasRoute()) {
            return;
        }
        long now = System.currentTimeMillis();
        if (now - lastRouteAdvanceMs < 1200) {
            return;
        }
        routeIndex = (routeIndex + 1) % hotspot.routeAnchors().size();
        lastRouteAdvanceMs = now;
        logAction("MOVE: Route -> " + currentTreeAnchor().label() + " (" + reason + ")");
    }

    public boolean isNearCurrentTreeArea() {
        TileAnchor anchor = currentTravelAnchor();
        return playerHelper.distanceTo(anchor.x(), anchor.y()) <= hotspot().radius();
    }

    public void rememberTarget(SceneObject target) {
        if (target == null) {
            clearCurrentTarget();
            return;
        }
        this.currentTargetName = target.name();
        this.currentTargetId = target.typeId();
        this.currentTargetTile = String.format("(%d, %d, %d)", target.tileX(), target.tileY(), target.plane());
    }

    public void clearCurrentTarget() {
        this.currentTargetName = "-";
        this.currentTargetId = -1;
        this.currentTargetTile = "-";
    }

    public void logAction(String message) {
        String entry = String.format("[%tT] %s", System.currentTimeMillis(), message);
        actionLog.addFirst(entry);
        while (actionLog.size() > MAX_LOG_ENTRIES) {
            actionLog.removeLast();
        }
    }

    public void logGuarded(String message) {
        long now = System.currentTimeMillis();
        if (now - lastGuardLogMs < 4000) {
            return;
        }
        lastGuardLogMs = now;
        logAction("WARN: " + message);
    }

    public void onTick(TickEvent event) {
        LocalPlayer player = api.getLocalPlayer();
        this.animationId = player.animationId();
        this.playerMoving = player.isMoving();
        this.onBreak = pace.onBreak();
        this.breakLabel = pace.breakLabel();
        this.breakRemainingMs = pace.breakRemainingMs();
        this.delayContext = pace.currentContext();

        try {
            GameWindowRect rect = api.getGameWindowRect();
            this.gameWindowX = rect.x();
            this.gameWindowY = rect.y();
            this.gameWindowWidth = rect.width();
            this.gameWindowHeight = rect.height();
        } catch (Exception ignored) {
        }

        String key = profile().id() + "|" + hotspot().id() + "|" + profile().productName();
        int current = trackedProductCount();
        if (!previousResourceKey.equals(key)) {
            previousResourceKey = key;
            previousResourceCount = current;
            return;
        }

        if (previousResourceCount >= 0 && current > previousResourceCount) {
            int gained = current - previousResourceCount;
            logsChopped += gained;
            log.debug("[Woodcutting] +{} resources (total {})", gained, logsChopped);
        }

        previousResourceCount = current;
    }

    void forceTask(String taskName) {
        this.forceTaskName = taskName;
        logAction("DEBUG: forcing task -> " + taskName);
    }
}
