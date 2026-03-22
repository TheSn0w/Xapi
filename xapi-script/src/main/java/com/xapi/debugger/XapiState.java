package com.xapi.debugger;

import static com.xapi.debugger.XapiData.*;

import com.botwithus.bot.api.ScriptContext;
import com.botwithus.bot.api.entities.Npcs;
import com.botwithus.bot.api.entities.Players;
import com.botwithus.bot.api.entities.SceneObjects;
import com.botwithus.bot.api.inventory.Smithing;
import com.botwithus.bot.api.model.*;
import com.botwithus.bot.core.impl.ActionDebugger;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import imgui.type.ImString;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Shared mutable state for the Xapi debugger script.
 * All fields are package-private for direct access by tabs and helper classes.
 * Thread safety is maintained via volatile, CopyOnWriteArrayList, and ConcurrentHashMap.
 */
final class XapiState {

    private static final Logger log = LoggerFactory.getLogger(XapiState.class);
    private static final Path ITEMVAR_ACCOUNTS_FILE = Path.of("xapi_itemvar_accounts.json");

    // ── Constants ────────────────────────────────────────────────────────
    static final DateTimeFormatter TIME_FMT = DateTimeFormatter.ofPattern("HH:mm:ss.SSS");
    static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    static final Path SESSION_DIR = Path.of("xapi_sessions");
    static final Path SETTINGS_FILE = Path.of("xapi_settings.json");

    // ── Script context & debugger ────────────────────────────────────────
    ScriptContext ctx;
    ActionDebugger actionDebugger;

    // ── Shared log collections ───────────────────────────────────────────
    final List<LogEntry> actionLog = new CopyOnWriteArrayList<>();
    final List<VarChange> varLog = new CopyOnWriteArrayList<>();
    final List<ChatEntry> chatLog = new CopyOnWriteArrayList<>();

    // Index: gameTick -> list of VarChanges on that tick (for action-linked display)
    final ConcurrentHashMap<Integer, List<VarChange>> varsByTick = new ConcurrentHashMap<>();

    // ── Recording/blocking state ─────────────────────────────────────────
    volatile boolean recording = true;
    volatile boolean blocking;
    // Tracking is always enabled (varps, varbits, varcs, chat, item varbits)
    volatile int currentTick;

    // ── Export/import (render thread requests, onLoop() executes) ─────────
    volatile boolean exportRequested;
    volatile String importPath;
    volatile String lastExportStatus = "";

    // ── Clear requests (set on render thread, executed on onLoop thread) ──
    volatile boolean clearRequested;
    volatile boolean clearActionsRequested;

    // ── Active tab (set on render thread, read by onLoop for conditional polling) ──
    volatile int activeTab;

    // ── Replay state ─────────────────────────────────────────────────────
    volatile boolean replaying;
    volatile int replayIndex;
    volatile float replaySpeed = 1.0f;

    // ── Entity/Interface inspector data (collected in onLoop, rendered on UI thread) ──
    volatile List<Entity> nearbyNpcs = List.of();
    volatile List<Entity> nearbyPlayers = List.of();
    volatile List<Entity> nearbyObjects = List.of();
    volatile List<GroundItemStack> nearbyGroundItems = List.of();
    volatile List<OpenInterface> openInterfaces = List.of();
    volatile int inspectInterfaceId = -1;
    volatile Map<Integer, EntityInfo> entityInfoCache = Map.of();

    // ── Local player data (collected in onLoop) ──────────────────────────
    volatile LocalPlayer localPlayerData;
    volatile List<PlayerStat> playerStats = List.of();

    // ── Mini menu cache ──────────────────────────────────────────────────
    volatile List<MiniMenuEntry> lastMiniMenu = List.of();
    final List<MenuSnapshot> menuLog = new CopyOnWriteArrayList<>();

    // ── Component caches ─────────────────────────────────────────────────
    final ConcurrentHashMap<Integer, List<Component>> componentCache = new ConcurrentHashMap<>();
    final ConcurrentHashMap<String, String> componentTextCache = new ConcurrentHashMap<>();
    final ConcurrentHashMap<String, List<String>> componentOptionsCache = new ConcurrentHashMap<>();

    // ── Inventory change tracking ────────────────────────────────────────
    volatile Map<Integer, Integer> lastInventorySnapshot = Map.of();
    volatile int lastInventorySlotCount = 0;
    final List<InventoryChange> inventoryLog = new CopyOnWriteArrayList<>();

    // ── Interaction snapshot state ───────────────────────────────────────
    volatile BackpackSnapshot cachedBackpack;
    final List<ActionSnapshot> snapshotLog = new CopyOnWriteArrayList<>();

    // ── Type caches (shared across name resolution and inspector) ─────────
    final ConcurrentHashMap<Integer, NpcType> npcTypeCache = new ConcurrentHashMap<>();
    final ConcurrentHashMap<Integer, LocationType> locTypeCache = new ConcurrentHashMap<>();
    final ConcurrentHashMap<Integer, ItemType> itemTypeCache = new ConcurrentHashMap<>();

    // ── Offline game cache ───────────────────────────────────────────────
    final GameCacheData gameCache = new GameCacheData();

    // ── Pre-resolved action names ────────────────────────────────────────
    record ActionCacheKey(int actionId, int p1, int p2, int p3) {}
    final ConcurrentHashMap<ActionCacheKey, String[]> preResolvedActions = new ConcurrentHashMap<>();
    // Track which interfaces we've already cached component options for
    final Set<Integer> cachedInterfaceOptions = ConcurrentHashMap.newKeySet();

    // ── Pinned variables (survive clears) ────────────────────────────────
    final Set<String> pinnedVars = ConcurrentHashMap.newKeySet(); // "varbit:1234", "varp:567"
    final ConcurrentHashMap<String, Integer> pinnedCurrentValues = new ConcurrentHashMap<>();

    // ── Var change frequency counter ─────────────────────────────────────
    final ConcurrentHashMap<String, Integer> varChangeCount = new ConcurrentHashMap<>();

    // ── Var annotations (user-defined labels) ────────────────────────────
    final ConcurrentHashMap<String, String> varAnnotations = new ConcurrentHashMap<>();

    // ── Ground item type cache ───────────────────────────────────────────
    volatile Map<Integer, ItemType> groundItemTypeCache = Map.of();

    // ── Item varbits state ────────────────────────────────────────────────
    volatile Boolean itemVarSystemAvailable = null;
    volatile int itemVarErrorLogCount = 0;
    volatile String itemVarPlayerName = null;

    // ── Inventory varbit live polling ────────────────────────────────────
    volatile boolean invVarLiveEnabled;
    volatile boolean invVarChangedOnly;
    final imgui.type.ImInt invVarSearchInvId = new imgui.type.ImInt(94);
    final imgui.type.ImInt invVarSearchSlot = new imgui.type.ImInt(0);
    volatile List<InvVarLiveEntry> invVarLiveResults = List.of();
    volatile String invVarSearchStatus = "";
    final List<InvVarChangeEntry> invVarChangeLog = new CopyOnWriteArrayList<>();

    // ── Interface event tracking ─────────────────────────────────────────
    final List<InterfaceEvent> interfaceEventLog = new CopyOnWriteArrayList<>();

    // ── Production interface state ───────────────────────────────────────
    volatile boolean prodOpen;
    volatile int prodSelectedItem, prodMaxQty, prodChosenQty;
    volatile int prodCategoryEnum, prodProductListEnum, prodCategoryDropdown;
    volatile boolean prodHasCategories;
    volatile String prodSelectedName;
    volatile List<ProductionTab.ProductionTabEntry> prodGridEntries = List.of();
    volatile List<String> prodCategoryNames = List.of();

    // ── Production progress (interface 1251) state ───────────────────────
    volatile boolean progressOpen;
    volatile int progressTotal, progressRemaining, progressSpeedModifier;
    volatile int progressProductId, progressVisibility;
    volatile String progressProductName, progressTimeText, progressCounterText;
    volatile int progressPercent;

    // ── Smithing interface (37) state ────────────────────────────────────
    volatile boolean smithOpen;
    volatile boolean smithIsSmelting;
    volatile int smithMaterialDbrow, smithProductDbrow, smithSelectedItem;
    volatile int smithLocation, smithQuantity, smithQualityTier;
    volatile int smithOutfitBonus1, smithOutfitBonus2, smithHeatEfficiency;
    volatile String smithProductName, smithQualityName;
    volatile List<SmithingTab.SmithingTabEntry> smithMaterialEntries = List.of();
    volatile List<SmithingTab.SmithingTabEntry> smithProductEntries = List.of();
    volatile List<Integer> smithActiveBonuses = List.of();
    volatile boolean smithExceedsBackpack;
    volatile boolean smithFullOutfit;
    volatile boolean smithVarrockArmour;

    // ── Active smithing progress ─────────────────────────────────────────
    volatile boolean activelySmithing;
    volatile Smithing.UnfinishedItem activeSmithingItem;
    volatile List<Smithing.UnfinishedItem> allUnfinishedItems = List.of();
    volatile int smithMaxHeat;
    volatile int smithHeatPercent;
    volatile String smithHeatBand = "Zero";
    volatile int smithProgressPerStrike;
    volatile int smithReheatRate;

    // ── Entity facades (initialized in onStart) ──────────────────────────
    Npcs npcs;
    Players players;
    SceneObjects sceneObjects;

    // ── Entity distance filter (tiles) ───────────────────────────────────
    volatile int entityDistanceFilter = 50;
    final int[] entityDistanceArr = {50};

    // ── Settings persistence ─────────────────────────────────────────────
    volatile boolean settingsDirty;
    volatile boolean saveSettingsRequested;

    // ── lastSeenActionTimestamp (shared between SessionManager and DataPoller) ──
    volatile long lastSeenActionTimestamp;

    // ── UI state (render thread only) ────────────────────────────────────

    // Column visibility: #, Time, Tick, Action, Target, Intent, Vars, P1, P2, P3, Code
    final boolean[] columnVisible = {true, true, true, true, true, true, true, true, true, true, true};
    boolean autoScroll = true;

    // Cross-tab linking: when user clicks an action row, highlight vars/chat on the same tick
    volatile int selectedActionTick = -1;

    // Max log entry cap (0 = unlimited)
    int maxLogEntries = 5000;
    volatile int trimmedActionCount;
    volatile int trimmedVarCount;
    volatile int trimmedChatCount;

    int lastActionSize = -1;
    int lastVarSize = -1;
    int lastChatSize = -1;
    final ImString filterText = new ImString(256);
    final ImString varFilterText = new ImString(256);
    final ImString chatFilterText = new ImString(256);
    final ImString entityFilterText = new ImString(256);
    final ImString scriptClassName = new ImString("GeneratedScript", 128);
    final boolean[] categoryFilters = {true, true, true, true, true, true, true};
    // indices: 0=NPC, 1=Object, 2=GroundItem, 3=Player, 4=Component, 5=Walk, 6=Other
    boolean useNamesForGeneration = true;
    final float[] replaySpeedArr = {1.0f};

    // Variables tab: type filters
    boolean showVarbits = true;
    boolean showVarps = true;

    // Session files (for export/import UI)
    String[] sessionFiles = new String[0];

    // Var annotation editing
    final ImString annotationInput = new ImString(256);
    String editingAnnotationKey = null;

    // Separate editing state for inv var search labels (avoids conflict with pinned vars editor)
    final ImString invVarAnnotationInput = new ImString(256);
    String invVarEditingKey = null;

    // ── Helpers (package-private for tab access) ─────────────────────────

    String buildTargetText(LogEntry entry) {
        String entity = entry.entityName();
        String option = entry.optionName();
        boolean hasEntity = entity != null && !entity.isEmpty();
        boolean hasOption = option != null && !option.isEmpty();
        if (hasOption && hasEntity) return option + " -> " + entity;
        else if (hasEntity) return entity;
        else if (hasOption) return option;
        if (entry.wasBlocked()) return "[BLOCKED]";
        return "";
    }

    boolean hasActionOnTick(int tick) {
        List<LogEntry> entries = actionLog;
        for (int i = entries.size() - 1; i >= 0 && i >= entries.size() - 50; i--) {
            if (entries.get(i).gameTick() == tick) return true;
        }
        return false;
    }

    @SuppressWarnings("unchecked")
    void resetItemVarProbe() {
        itemVarSystemAvailable = null;
        itemVarErrorLogCount = 0;
        // Remove current player from persistent file
        String player = itemVarPlayerName;
        if (player == null) return;
        try {
            if (!Files.exists(ITEMVAR_ACCOUNTS_FILE)) return;
            String json = Files.readString(ITEMVAR_ACCOUNTS_FILE);
            Map<String, Map<String, Object>> accounts = GSON.fromJson(json, Map.class);
            if (accounts != null && accounts.remove(player) != null) {
                Files.writeString(ITEMVAR_ACCOUNTS_FILE, GSON.toJson(accounts));
            }
        } catch (Exception e) {
            log.debug("Failed to reset item var probe: {}", e.getMessage());
        }
    }
}
