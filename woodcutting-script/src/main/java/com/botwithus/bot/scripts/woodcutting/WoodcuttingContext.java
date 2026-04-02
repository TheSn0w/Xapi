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

    public volatile int animationId = -1;
    public volatile int freeSlots = 0;
    volatile int woodboxStored = 0;
    public volatile int woodboxCapacity = 0;
    public volatile boolean hasWoodBox = false;
    public volatile boolean bankOpen = false;
    public volatile boolean depositOpen = false;
    volatile String pacePhase = "";
    volatile double fatigue = 0.0;
    volatile String currentTaskName = "Idle";
    volatile String forceTaskName = null;
    volatile boolean onBreak = false;
    volatile String breakLabel = null;
    volatile long breakRemainingMs = 0;
    volatile double sessionMinutes = 0;
    volatile String delayContext = "";
    public volatile boolean playerMoving = false;
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

    // ── Cached per-loop state (avoid redundant RPC in validate methods) ─
    public volatile boolean backpackFull = false;
    public volatile boolean woodBoxCanStore = false;
    public volatile boolean woodBoxUnsupported = false;
    public volatile boolean woodBoxFull = false;
    public volatile int woodBoxStoredForProfile = 0;
    public volatile int backpackProductCount = 0;

    final CopyOnWriteArrayList<String> actionLog = new CopyOnWriteArrayList<>();

    private int previousResourceCount = -1;
    private int windowRectLoopCounter = 0;
    private int wcLevelTickCounter = 0;
    public WoodBox.Tier cachedWoodBoxTier = null;
    private boolean woodBoxTierScanned = false;
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
        // Uses cached volatile fields — no RPC calls
        InventoryMode mode = hotspot().inventoryMode();
        if (mode == InventoryMode.NONE) {
            return false;
        }
        if (mode == InventoryMode.BANK || mode == InventoryMode.DEPOSIT_BOX) {
            return backpackFull || bankOpen || depositOpen || quirks.shouldEarlyBank(freeSlots);
        }
        return backpackFull;
    }

    public boolean woodBoxShouldBeTreatedAsEmpty() {
        return assumeWoodBoxEmpty;
    }

    /** Uses cached value from collectUIState — no RPC. */
    public boolean woodBoxIsFull(TreeProfile profile) {
        return !assumeWoodBoxEmpty && profile.supportsWoodBox() && woodBoxFull;
    }

    /** Uses cached value from collectUIState — no RPC. */
    public int woodBoxStoredFor(TreeProfile profile) {
        if (assumeWoodBoxEmpty || !profile.supportsWoodBox()) {
            return 0;
        }
        return woodBoxStoredForProfile;
    }

    /** Uses cached woodboxStored — no RPC. */
    public boolean woodBoxIsEmptyEffective() {
        return assumeWoodBoxEmpty || woodboxStored == 0;
    }

    public void markWoodBoxEmptied() {
        assumeWoodBoxEmpty = true;
        woodboxStored = 0;
    }

    public void clearWoodBoxEmptyAssumption() {
        assumeWoodBoxEmpty = false;
    }

    /** Invalidate cached wood box tier — call after banking when equipment might change. */
    public void invalidateWoodBoxCache() {
        woodBoxTierScanned = false;
    }

    /** Returns cached backpack product count (updated by collectUIState, no RPC). */
    public int trackedProductCount() {
        return backpackProductCount;
    }

    public void collectUIState() {
        syncSelectionState();

        // ── Player state (1 RPC call) ───────────────────────────
        LocalPlayer player = api.getLocalPlayer();
        this.animationId = player.animationId();
        this.playerMoving = player.isMoving();

        // ── Backpack (1 RPC call — freeSlots fetches all 28 slots once) ─
        this.freeSlots = backpack.freeSlots();
        this.backpackFull = freeSlots == 0;

        // ── Interface checks (2 RPC calls) ──────────────────────
        this.bankOpen = bank.isOpen();
        this.depositOpen = depositBox.isOpen();

        // ── Woodcutting level (cached, 1 RPC call every ~60s) ───
        if (++wcLevelTickCounter >= 100 || woodcuttingLevel <= 0) {
            wcLevelTickCounter = 0;
            this.woodcuttingLevel = Skills.getLevel(api, Skills.WOODCUTTING);
        }

        // ── Game window position (1 RPC call every ~5 loops) ────
        if (++windowRectLoopCounter >= 8) {
            windowRectLoopCounter = 0;
            try {
                GameWindowRect rect = api.getGameWindowRect();
                this.gameWindowX = rect.x();
                this.gameWindowY = rect.y();
                this.gameWindowWidth = rect.width();
                this.gameWindowHeight = rect.height();
            } catch (Exception ignored) { }
        }

        // ── Wood box state (scan tier once, cache until bank trip/reload) ──
        TreeProfile profile = profile();
        if (!woodBoxTierScanned) {
            cachedWoodBoxTier = woodBox.getEquippedTier(); // 1-11 RPC calls (tier scan, only once)
            woodBoxTierScanned = true;
        }
        WoodBox.Tier equippedTier = cachedWoodBoxTier;
        this.hasWoodBox = equippedTier != null;
        if (hasWoodBox) {
            int capacity = 70 + (equippedTier.level * 10) + wcLevelBonus(woodcuttingLevel);
            this.woodboxCapacity = capacity;
            if (assumeWoodBoxEmpty) {
                this.woodboxStored = 0;
            } else {
                int stored = woodBox.getTotalStored(); // 1 RPC call
                this.woodboxStored = stored;
                // Check for stale empty assumption
                if (assumeWoodBoxEmpty && stored > 0) {
                    assumeWoodBoxEmpty = false;
                    this.woodboxStored = stored;
                }
            }
            // Cache wood box state for validate methods
            if (profile.supportsWoodBox()) {
                boolean canStore = equippedTier.level >= profile.woodBoxLogType().requiredTier;
                this.woodBoxCanStore = canStore;
                this.woodBoxUnsupported = !canStore;
                if (canStore) {
                    int profileStored = assumeWoodBoxEmpty ? 0 : woodBox.count(profile.woodBoxLogType()); // 1 RPC
                    this.woodBoxStoredForProfile = profileStored;
                    this.woodBoxFull = profileStored >= capacity;
                } else {
                    this.woodBoxStoredForProfile = 0;
                    this.woodBoxFull = false;
                }
            } else {
                this.woodBoxCanStore = false;
                this.woodBoxUnsupported = false;
                this.woodBoxStoredForProfile = 0;
                this.woodBoxFull = false;
            }
        } else {
            this.woodboxCapacity = 0;
            this.woodboxStored = 0;
            this.woodBoxCanStore = false;
            this.woodBoxUnsupported = false;
            this.woodBoxStoredForProfile = 0;
            this.woodBoxFull = false;
        }

        // ── Backpack product count (1 RPC call) ─────────────────
        if (profile.logItemId() != null) {
            this.backpackProductCount = backpack.count(profile.logItemId());
        } else if (!profile.productName().isBlank()) {
            this.backpackProductCount = backpack.count(profile.productName());
        } else {
            this.backpackProductCount = 0;
        }

        // ── Pace/antiban state (no RPC — local state only) ──────
        this.pacePhase = pace.phase();
        this.fatigue = pace.fatigue();
        this.onBreak = pace.onBreak();
        this.breakLabel = pace.breakLabel();
        this.breakRemainingMs = pace.breakRemainingMs();
        this.attentionState = pace.attentionState().label();
        this.lastQuirk = quirks.getLastQuirk();
        this.sessionMinutes = pace.sessionMinutes();

        // ── Profile/hotspot display state (no RPC) ──────────────
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
    }

    /** Same formula as WoodBox.wcLevelBonus but avoids calling back into WoodBox. */
    private static int wcLevelBonus(int wcLevel) {
        if (wcLevel < 5) return 0;
        return Math.min(11, (wcLevel - 5) / 10 + 1) * 10;
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

    /**
     * Called every game tick (~600ms) on the EventBus thread.
     * IMPORTANT: No RPC calls here — all state read from cached volatile fields
     * populated by collectUIState() on the script thread. This avoids concurrent
     * pipe access which can destabilize the game client.
     */
    public void onTick(TickEvent event) {
        // Pace state (local, no RPC)
        this.onBreak = pace.onBreak();
        this.breakLabel = pace.breakLabel();
        this.breakRemainingMs = pace.breakRemainingMs();
        this.delayContext = pace.currentContext();

        // Track resource gains using cached backpackProductCount (set by collectUIState)
        String key = profile().id() + "|" + hotspot().id() + "|" + profile().productName();
        int current = backpackProductCount;
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
