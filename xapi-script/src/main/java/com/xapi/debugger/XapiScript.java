package com.xapi.debugger;

import static com.xapi.debugger.XapiData.*;

import com.botwithus.bot.api.BotScript;
import com.botwithus.bot.api.GameAPI;
import com.botwithus.bot.api.ScriptCategory;
import com.botwithus.bot.api.ScriptContext;
import com.botwithus.bot.api.ScriptManifest;
import com.botwithus.bot.api.event.*;
import com.botwithus.bot.api.inventory.ActionTranslator;
import com.botwithus.bot.api.inventory.ActionTypes;
import com.botwithus.bot.api.model.*;
import com.botwithus.bot.api.model.ItemVar;
import com.botwithus.bot.api.model.InventoryItem;
import com.botwithus.bot.api.entities.Npcs;
import com.botwithus.bot.api.entities.Npc;
import com.botwithus.bot.api.entities.Players;
import com.botwithus.bot.api.entities.Player;
import com.botwithus.bot.api.entities.SceneObjects;
import com.botwithus.bot.api.entities.SceneObject;
import com.botwithus.bot.api.entities.EntityContext;
import com.botwithus.bot.api.query.ComponentFilter;
import com.botwithus.bot.api.query.EntityFilter;
import com.botwithus.bot.api.query.InventoryFilter;
import com.botwithus.bot.api.ui.ScriptUI;
import com.botwithus.bot.core.impl.ActionDebugger;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import imgui.ImGui;
import imgui.flag.ImGuiCol;
import imgui.flag.ImGuiTableFlags;
import imgui.flag.ImGuiTreeNodeFlags;
import imgui.type.ImString;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Collectors;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

@ScriptManifest(
        name = "Xapi",
        version = "2.1",
        author = "Xapi",
        description = "Action debugger — logs, blocks, and reverse-engineers game actions",
        category = ScriptCategory.UTILITY
)
public class XapiScript implements BotScript {

    private static final Logger log = LoggerFactory.getLogger(XapiScript.class);
    static final DateTimeFormatter TIME_FMT = DateTimeFormatter.ofPattern("HH:mm:ss.SSS");
    static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    static final Path SESSION_DIR = Path.of("xapi_sessions");
    static final Path SETTINGS_FILE = Path.of("xapi_settings.json");


    private ScriptContext ctx;

    // ── Tab instances ────────────────────────────────────────────────────

    private ActionsTab actionsTab;
    private VariablesTab variablesTab;
    private ChatTab chatTab;
    private PlayerTab playerTab;
    private EntitiesTab entitiesTab;
    private InterfacesTab interfacesTab;
    private InventoryTab inventoryTab;

    // ── Shared state (package-private for tab access) ────────────────────

    final List<LogEntry> actionLog = new CopyOnWriteArrayList<>();
    final List<VarChange> varLog = new CopyOnWriteArrayList<>();
    final List<ChatEntry> chatLog = new CopyOnWriteArrayList<>();

    // Index: gameTick -> list of VarChanges on that tick (for action-linked display)
    final ConcurrentHashMap<Integer, List<VarChange>> varsByTick = new ConcurrentHashMap<>();

    volatile boolean recording = true;
    volatile boolean blocking;
    private volatile boolean lastBlockingSentToClient;
    // Tracking is always enabled (varps, varbits, varcs, chat, item varbits)
    volatile int currentTick;

    // Export/import (render thread requests, onLoop() executes)
    volatile boolean exportRequested;
    volatile String importPath;
    volatile String lastExportStatus = "";

    // Replay state
    volatile boolean replaying;
    volatile int replayIndex;
    private volatile long replayNextTime;
    volatile float replaySpeed = 1.0f;

    // Entity/Interface inspector data (collected in onLoop, rendered on UI thread)
    volatile List<Entity> nearbyNpcs = List.of();
    volatile List<Entity> nearbyPlayers = List.of();
    volatile List<Entity> nearbyObjects = List.of();
    volatile List<GroundItemStack> nearbyGroundItems = List.of();
    volatile List<OpenInterface> openInterfaces = List.of();
    volatile int inspectInterfaceId = -1;
    volatile Map<Integer, EntityInfo> entityInfoCache = Map.of();

    // Local player data (collected in onLoop)
    volatile LocalPlayer localPlayerData;
    volatile List<PlayerStat> playerStats = List.of();


    // Chat history polling fallback
    private volatile int lastChatHistorySize;

    // Action history polling (captures manual player actions not seen by events)
    private volatile boolean actionHistoryAvailable = true;
    private volatile long lastActionHistoryPoll;
    private volatile long lastSeenActionTimestamp;

    // Mini menu cache — polled each tick, used to resolve option text for actions
    volatile List<MiniMenuEntry> lastMiniMenu = List.of();
    final List<MenuSnapshot> menuLog = new CopyOnWriteArrayList<>();
    private volatile int lastMiniMenuHash; // for change detection

    final ConcurrentHashMap<Integer, List<Component>> componentCache = new ConcurrentHashMap<>();
    final ConcurrentHashMap<String, String> componentTextCache = new ConcurrentHashMap<>();
    final ConcurrentHashMap<String, List<String>> componentOptionsCache = new ConcurrentHashMap<>();
    private volatile long lastInspectorUpdate;

    // Inventory change tracking
    private volatile Map<Integer, Integer> lastInventorySnapshot = Map.of();
    final List<InventoryChange> inventoryLog = new CopyOnWriteArrayList<>();

    // Pinned variables (survive clears)
    final Set<String> pinnedVars = ConcurrentHashMap.newKeySet(); // "varbit:1234", "varp:567"
    final ConcurrentHashMap<String, Integer> pinnedCurrentValues = new ConcurrentHashMap<>();

    // Var change frequency counter
    final ConcurrentHashMap<String, Integer> varChangeCount = new ConcurrentHashMap<>();

    // Var annotations (user-defined labels)
    final ConcurrentHashMap<String, String> varAnnotations = new ConcurrentHashMap<>();


    // Ground item type cache (itemId -> ItemType with name/options)
    volatile Map<Integer, ItemType> groundItemTypeCache = Map.of();

    // Item varbits cache (equipment items with their vars)
    volatile List<ItemVarEntry> itemVarCache = List.of();
    volatile boolean showItemVarbits = false;

    // Previous item varbit snapshot for change detection (slot:varId -> value)
    private volatile Map<String, Integer> previousItemVarSnapshot = Map.of();
    // Item var system probe state: null=untested, true/false=tested for current player
    volatile Boolean itemVarSystemAvailable = null;
    private volatile String itemVarPlayerName = null;
    private static final Path ITEMVAR_ACCOUNTS_FILE = Path.of("xapi_itemvar_accounts.json");
    volatile int itemVarErrorLogCount = 0;
    private static final int ITEM_VAR_ERROR_LOG_MAX = 20;

    // Interface event tracking
    final List<InterfaceEvent> interfaceEventLog = new CopyOnWriteArrayList<>();
    private volatile Set<Integer> previousOpenInterfaceIds = Set.of();

    // Entity facades (initialized in onStart)
    private Npcs npcs;
    private Players players;
    private SceneObjects sceneObjects;


    // Entity distance filter (tiles)
    volatile int entityDistanceFilter = 50;
    final int[] entityDistanceArr = {50};

    // Settings persistence
    volatile boolean settingsDirty;
    private volatile long lastSettingsSave;
    volatile boolean saveSettingsRequested;

    // ── UI state (render thread only) ───────────────────────────────────

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
    String[] sessionFiles = new String[0];
    private long lastSessionScan;

    // Variables tab: type filters
    boolean showVarbits = true;
    boolean showVarps = true;


    // Var annotation editing
    final ImString annotationInput = new ImString(64);
    String editingAnnotationKey = null;

    // ── Lifecycle ───────────────────────────────────────────────────────

    @Override
    public void onStart(ScriptContext ctx) {
        this.ctx = ctx;
        log.info("Xapi action debugger v2.1 started");

        // Create tab instances
        actionsTab = new ActionsTab(this);
        variablesTab = new VariablesTab(this);
        chatTab = new ChatTab(this);
        playerTab = new PlayerTab(this);
        entitiesTab = new EntitiesTab(this);
        interfacesTab = new InterfacesTab(this);
        inventoryTab = new InventoryTab(this);

        // Initialize entity facades
        GameAPI gameApi = ctx.getGameAPI();
        npcs = new Npcs(gameApi);
        players = new Players(gameApi);
        sceneObjects = new SceneObjects(gameApi);

        // Load persistent settings
        loadSettings();

        // Force-sync blocking state to game client on start (selective blocking is per-action, not client-wide)
        try {
            gameApi.setActionsBlocked(blocking);
            lastBlockingSentToClient = blocking;
            log.info("Initial blocking state synced to client: {}", blocking);
        } catch (Exception e) {
            log.warn("Failed to sync initial blocking state: {}", e.getMessage());
        }

        EventBus events = ctx.getEventBus();
        events.subscribe(ActionExecutedEvent.class, this::onActionExecuted);
        events.subscribe(TickEvent.class, this::onTick);
        events.subscribe(VarbitChangeEvent.class, this::onVarbitChange);
        events.subscribe(VarChangeEvent.class, this::onVarChange);
        events.subscribe(ChatMessageEvent.class, this::onChatMessage);

        try { Files.createDirectories(SESSION_DIR); } catch (IOException ignored) {}

    }

    // ── Event handlers (event thread -- RPC safe) ────────────────────────

    private void onTick(TickEvent e) {
        currentTick = e.getTick();
    }

    private void onActionExecuted(ActionExecutedEvent e) {
        if (recording || blocking) {
            String[] names = resolveNames(ctx.getGameAPI(),
                    e.getActionId(), e.getParam1(), e.getParam2(), e.getParam3());

            // Try to resolve option text and item name from the cached mini menu snapshot
            String[] menuMatch = matchMiniMenu(e.getActionId(), e.getParam1(), e.getParam2(), e.getParam3());
            if (menuMatch != null) {
                if (names[1] == null || names[1].isEmpty()) names[1] = menuMatch[0]; // optionText
                if (names[0] == null || names[0].isEmpty()) names[0] = menuMatch[1]; // itemName
            }

            int px = 0, py = 0, pp = 0, pa = -1;
            boolean pm = false;
            try {
                LocalPlayer lp = ctx.getGameAPI().getLocalPlayer();
                if (lp != null) {
                    px = lp.tileX(); py = lp.tileY(); pp = lp.plane();
                    pa = lp.animationId(); pm = lp.isMoving();
                }
            } catch (Exception ignored) {}

            actionLog.add(new LogEntry(
                    e.getActionId(), e.getParam1(), e.getParam2(), e.getParam3(),
                    System.currentTimeMillis(), currentTick, false, "client",
                    names[0], names[1], px, py, pp, pa, pm
            ));
        }
    }

    /**
     * Matches an action against the cached mini menu to find option text and item name.
     * Returns {optionText, itemName} or null if no match.
     */
    private String[] matchMiniMenu(int actionId, int p1, int p2, int p3) {
        try {
            List<MiniMenuEntry> menu = lastMiniMenu;
            if (menu == null || menu.isEmpty()) return null;
            for (MiniMenuEntry entry : menu) {
                if (entry.actionId() == actionId
                        && entry.param1() == p1
                        && entry.param2() == p2
                        && entry.param3() == p3) {
                    String optionText = entry.optionText();
                    String itemName = null;
                    // Resolve item name from the menu entry's itemId if available
                    if (entry.itemId() > 0) {
                        try {
                            var itemType = ctx.getGameAPI().getItemType(entry.itemId());
                            if (itemType != null && itemType.name() != null) {
                                itemName = itemType.name();
                            }
                        } catch (Exception ignored) {}
                    }
                    return new String[]{optionText, itemName};
                }
            }
        } catch (Exception ignored) {}
        return null;
    }

    private void onVarbitChange(VarbitChangeEvent e) {
        try {
            VarChange vc = new VarChange("varbit", e.getVarId(), e.getOldValue(), e.getNewValue(),
                    System.currentTimeMillis(), currentTick);
            varLog.add(vc);
            varsByTick.computeIfAbsent(currentTick, k -> new CopyOnWriteArrayList<>()).add(vc);
            varChangeCount.merge("varbit:" + e.getVarId(), 1, Integer::sum);
        } catch (Exception ex) { log.debug("varbit event error: {}", ex.getMessage()); }
    }

    private void onVarChange(VarChangeEvent e) {
        try {
            VarChange vc = new VarChange("varp", e.getVarId(), e.getOldValue(), e.getNewValue(),
                    System.currentTimeMillis(), currentTick);
            varLog.add(vc);
            varsByTick.computeIfAbsent(currentTick, k -> new CopyOnWriteArrayList<>()).add(vc);
            varChangeCount.merge("varp:" + e.getVarId(), 1, Integer::sum);
        } catch (Exception ex) { log.debug("varp event error: {}", ex.getMessage()); }
    }

    private void onChatMessage(ChatMessageEvent e) {
        try {
            var msg = e.getMessage();
            String text = msg.text() != null ? msg.text().replaceAll("<img=\\d+>", "").replaceAll("<col=[0-9a-fA-F]+>", "").replaceAll("</col>", "").trim() : "";
            chatLog.add(new ChatEntry(msg.messageType(), text,
                    msg.playerName(), System.currentTimeMillis(), currentTick));
        } catch (Exception ex) { log.debug("chat event error: {}", ex.getMessage()); }
    }

    // ── Name resolution (runs on event/script thread -- RPC safe) ─────────

    private String[] resolveNames(GameAPI api, int actionId, int p1, int p2, int p3) {
        try {
            int npcSlot = findSlot(ActionTypes.NPC_OPTIONS, actionId);
            if (npcSlot > 0) return resolveNpc(api, p1, npcSlot);

            int objSlot = findSlot(ActionTypes.OBJECT_OPTIONS, actionId);
            if (objSlot > 0) return resolveObject(api, p1, objSlot);

            int giSlot = findSlot(ActionTypes.GROUND_ITEM_OPTIONS, actionId);
            if (giSlot > 0) return resolveGroundItem(api, p1, giSlot);

            int playerSlot = findSlot(ActionTypes.PLAYER_OPTIONS, actionId);
            if (playerSlot > 0) return resolvePlayer(api, p1);

            if (actionId == ActionTypes.COMPONENT) return resolveComponent(api, p1, p2, p3);
            if (actionId == ActionTypes.SELECT_COMPONENT_ITEM) return resolveComponent(api, p1, p2, p3);
            if (actionId == ActionTypes.CONTAINER_ACTION) return resolveComponent(api, p1, p2, p3);
        } catch (Exception e) {
            log.debug("Name resolution failed for action {}: {}", actionId, e.getMessage());
        }
        return new String[]{null, null};
    }

    private String[] resolveNpc(GameAPI api, int serverIndex, int optionSlot) {
        try {
            List<Entity> npcs = api.queryEntities(
                    EntityFilter.builder().type("npc").maxResults(500).build());
            for (Entity npc : npcs) {
                if (npc.serverIndex() == serverIndex) {
                    String name = npc.name();
                    String option = null;
                    try {
                        NpcType type = api.getNpcType(npc.typeId());
                        if (type != null && type.options() != null && optionSlot - 1 < type.options().size()) {
                            option = type.options().get(optionSlot - 1);
                        }
                    } catch (Exception ignored) {}
                    return new String[]{name, option};
                }
            }
        } catch (Exception ignored) {}
        return new String[]{null, null};
    }

    private String[] resolveObject(GameAPI api, int typeId, int optionSlot) {
        try {
            LocationType type = api.getLocationType(typeId);
            if (type != null) {
                String name = type.name();
                String option = null;
                if (type.options() != null && optionSlot - 1 < type.options().size()) {
                    option = type.options().get(optionSlot - 1);
                }
                return new String[]{name, option};
            }
        } catch (Exception ignored) {}
        return new String[]{null, null};
    }

    private String[] resolveGroundItem(GameAPI api, int itemId, int optionSlot) {
        try {
            ItemType type = api.getItemType(itemId);
            if (type != null) {
                String name = type.name();
                String option = null;
                if (type.groundOptions() != null && optionSlot - 1 < type.groundOptions().size()) {
                    option = type.groundOptions().get(optionSlot - 1);
                }
                return new String[]{name, option};
            }
        } catch (Exception ignored) {}
        return new String[]{null, null};
    }

    private String[] resolvePlayer(GameAPI api, int serverIndex) {
        try {
            List<Entity> players = api.queryEntities(
                    EntityFilter.builder().type("player").maxResults(200).build());
            for (Entity player : players) {
                if (player.serverIndex() == serverIndex) {
                    return new String[]{player.name(), null};
                }
            }
        } catch (Exception ignored) {}
        return new String[]{null, null};
    }

    private String[] resolveComponent(GameAPI api, int optionIndex, int subComponent, int packedHash) {
        try {
            int ifaceId = packedHash >>> 16;
            int compId = packedHash & 0xFFFF;

            // Resolve option name
            String optionName = null;
            List<String> options = api.getComponentOptions(ifaceId, compId);
            log.debug("resolveComponent iface:{} comp:{} sub:{} optIdx:{} options:{}",
                    ifaceId, compId, subComponent, optionIndex, options);
            if (options != null && optionIndex - 1 >= 0 && optionIndex - 1 < options.size()) {
                optionName = options.get(optionIndex - 1);
            }

            // Resolve item name from the sub-component (slot)
            String entityName = null;
            if (subComponent >= 0) {
                try {
                    List<com.botwithus.bot.api.model.Component> children = api.getComponentChildren(ifaceId, compId);
                    log.debug("resolveComponent children count:{} for iface:{} comp:{}",
                            children != null ? children.size() : 0, ifaceId, compId);
                    if (children != null) {
                        for (var child : children) {
                            if (child.subComponentId() == subComponent) {
                                log.debug("resolveComponent found child sub:{} itemId:{} type:{}",
                                        child.subComponentId(), child.itemId(), child.type());
                                if (child.itemId() > 0) {
                                    var itemType = api.getItemType(child.itemId());
                                    if (itemType != null && itemType.name() != null) {
                                        entityName = itemType.name();
                                    }
                                }
                                break;
                            }
                        }
                    }
                } catch (Exception ignored) {}
            }

            log.debug("resolveComponent result: entity={} option={}", entityName, optionName);
            return new String[]{entityName, optionName};
        } catch (Exception ignored) {}
        return new String[]{null, null};
    }

    private static int findSlot(int[] options, int actionId) {
        for (int i = 1; i < options.length; i++) {
            if (options[i] == actionId) return i;
        }
        return -1;
    }

    // ── Script loop ──────────────────────────────────────────────────────

    @Override
    public int onLoop() {
        GameAPI api = ctx.getGameAPI();
        ActionDebugger debugger = ActionDebugger.get();
        debugger.setRecording(recording);
        debugger.setBlocking(blocking);

        // Sync blocking to game client
        if (blocking != lastBlockingSentToClient) {
            try {
                api.setActionsBlocked(blocking);
                lastBlockingSentToClient = blocking;
                log.info("Actions blocked on client: {}", blocking);
            } catch (Exception e) {
                log.warn("Failed to sync blocking state to client: {}", e.getMessage());
            }
        }

        long now = System.currentTimeMillis();

        // Export session
        if (exportRequested) {
            exportRequested = false;
            doExport();
        }

        // Import session
        String impPath = importPath;
        if (impPath != null) {
            importPath = null;
            doImport(impPath);
        }

        // Replay
        if (replaying) {
            doReplayStep(api);
        }

        // Collect local player data (before inspector -- used for distance filter)
        try { localPlayerData = api.getLocalPlayer(); } catch (Exception ignored) {}
        try { playerStats = api.getPlayerStats(); } catch (Exception ignored) {}

        // Inspector data collection (every ~3 ticks / 1.8s)
        if (now - lastInspectorUpdate > 1800) {
            lastInspectorUpdate = now;
            collectInspectorData(api);
        }

        // Inventory diff
        collectInventoryDiff(api);

        // Poll mini menu — cache the current right-click menu so we can match option text to actions
        try {
            var menu = api.getMiniMenu();
            if (menu != null && !menu.isEmpty()) {
                lastMiniMenu = menu;
                // Track menu changes for history log
                int hash = menu.stream()
                        .mapToInt(e -> Objects.hash(e.optionText(), e.actionId(), e.param1(), e.param2(), e.param3()))
                        .sum();
                if (hash != lastMiniMenuHash) {
                    lastMiniMenuHash = hash;
                    menuLog.add(new MenuSnapshot(System.currentTimeMillis(), currentTick, List.copyOf(menu)));
                    // Cap at 500 entries
                    while (menuLog.size() > 500) menuLog.remove(0);
                }
            }
        } catch (Exception ignored) {}

        // Poll pinned variable live values
        pollPinnedVars(api);

        // Poll chat history as fallback
        pollChatHistory(api);

        // Poll action history (captures manual player actions not seen by events)
        pollActionHistory(api);

        // Poll interface events every tick (fast open/close detection)
        pollInterfaceEvents(api);


        // Prune varsByTick to prevent unbounded growth
        if (varsByTick.size() > 5000) {
            List<Integer> keys = new ArrayList<>(varsByTick.keySet());
            keys.sort(Comparator.naturalOrder());
            int removeCount = keys.size() - 4000;
            for (int i = 0; i < removeCount; i++) {
                varsByTick.remove(keys.get(i));
            }
        }

        // Persist settings periodically (every 30s) or on request
        if (saveSettingsRequested || (settingsDirty && now - lastSettingsSave > 30000)) {
            saveSettingsRequested = false;
            settingsDirty = false;
            lastSettingsSave = now;
            saveSettings();
        }

        return 600;
    }


    private void pollPinnedVars(GameAPI api) {
        for (String key : pinnedVars) {
            try {
                String[] parts = key.split(":");
                if (parts.length != 2) continue;
                int id = Integer.parseInt(parts[1]);
                int value;
                switch (parts[0]) {
                    case "varbit" -> value = api.getVarbit(id);
                    case "varp" -> value = api.getVarp(id);
                    case "itemvar" -> {
                        int slot = id / 100000;
                        int itemVarId = id % 100000;
                        value = api.getItemVarValue(94, slot, itemVarId);
                    }
                    default -> { continue; }
                }
                pinnedCurrentValues.put(key, value);
            } catch (Exception e) {
                logItemVarError("pollPinnedVars key=" + key, e);
            }
        }
    }

    // ── Settings Persistence ─────────────────────────────────────────────

    private void saveSettings() {
        try {
            XapiSettings settings = new XapiSettings(
                    recording, false, false,
                    true, true, true,
                    Arrays.copyOf(categoryFilters, categoryFilters.length),
                    new boolean[7],
                    showVarbits, showVarps, false, false, false,
                    varFilterText.get(), "",
                    new HashSet<>(pinnedVars), new HashMap<>(varAnnotations),
                    useNamesForGeneration, scriptClassName.get(),
                    replaySpeedArr[0],
                    entityDistanceFilter
            );
            Files.writeString(SETTINGS_FILE, GSON.toJson(settings));
            log.debug("Settings saved to {}", SETTINGS_FILE);
        } catch (Exception e) {
            log.debug("Failed to save settings: {}", e.getMessage());
        }
    }

    private void loadSettings() {
        try {
            if (!Files.exists(SETTINGS_FILE)) return;
            String json = Files.readString(SETTINGS_FILE);
            XapiSettings s = GSON.fromJson(json, XapiSettings.class);
            if (s == null) return;

            recording = s.recording();
            // blocking is NOT loaded — always starts as false (safe default)

            if (s.categoryFilters() != null) {
                System.arraycopy(s.categoryFilters(), 0, categoryFilters, 0,
                        Math.min(s.categoryFilters().length, categoryFilters.length));
            }

            showVarbits = s.showVarbits();
            showVarps = s.showVarps();
            // showVarcs/showVarcStrs removed — varc tracking disabled until API supports events
            // showItemVarbits is NOT loaded — always starts as false (crash safety)

            if (s.varFilterText() != null) varFilterText.set(s.varFilterText());
            // varcWatchIds removed — varc tracking disabled until API supports events
            if (s.pinnedVars() != null) { pinnedVars.clear(); pinnedVars.addAll(s.pinnedVars()); }
            if (s.varAnnotations() != null) { varAnnotations.clear(); varAnnotations.putAll(s.varAnnotations()); }

            useNamesForGeneration = s.useNamesForGeneration();
            if (s.scriptClassName() != null) scriptClassName.set(s.scriptClassName());
            replaySpeedArr[0] = s.replaySpeed();
            replaySpeed = s.replaySpeed();

            if (s.entityDistanceFilter() > 0) {
                entityDistanceFilter = s.entityDistanceFilter();
                entityDistanceArr[0] = entityDistanceFilter;
            }

            log.info("Settings loaded from {}", SETTINGS_FILE);
        } catch (Exception e) {
            log.debug("Failed to load settings: {}", e.getMessage());
        }
    }

    // ── Export/Import ────────────────────────────────────────────────────

    private void doExport() {
        try {
            SessionData session = new SessionData(
                    new ArrayList<>(actionLog), new ArrayList<>(varLog), new ArrayList<>(chatLog),
                    System.currentTimeMillis(), "Xapi session export"
            );
            String filename = "session_" + new java.text.SimpleDateFormat("yyyyMMdd_HHmmss").format(new java.util.Date()) + ".json";
            Path file = SESSION_DIR.resolve(filename);
            Files.writeString(file, GSON.toJson(session));
            lastExportStatus = "Exported: " + filename;
            log.info("Session exported to {}", file);
        } catch (Exception e) {
            lastExportStatus = "Export failed: " + e.getMessage();
            log.error("Export failed", e);
        }
    }

    private void doImport(String filename) {
        try {
            Path file = SESSION_DIR.resolve(filename);
            String json = Files.readString(file);
            SessionData session = GSON.fromJson(json, SessionData.class);
            if (session.actions() != null) { actionLog.clear(); actionLog.addAll(session.actions()); }
            if (session.vars() != null) {
                varLog.clear(); varLog.addAll(session.vars());
                // Rebuild varsByTick index
                varsByTick.clear();
                for (VarChange vc : varLog) {
                    varsByTick.computeIfAbsent(vc.gameTick(), k -> new CopyOnWriteArrayList<>()).add(vc);
                }
            }
            if (session.chat() != null) { chatLog.clear(); chatLog.addAll(session.chat()); }
            lastExportStatus = "Imported: " + filename + " (" + actionLog.size() + " actions)";
            log.info("Session imported from {}", file);
        } catch (Exception e) {
            lastExportStatus = "Import failed: " + e.getMessage();
            log.error("Import failed", e);
        }
    }

    // ── Replay ──────────────────────────────────────────────────────────

    private void doReplayStep(GameAPI api) {
        if (replayIndex >= actionLog.size()) {
            replaying = false;
            return;
        }
        long now = System.currentTimeMillis();
        if (now < replayNextTime) return;

        LogEntry entry = actionLog.get(replayIndex);

        // Validate target still exists before queueing
        String skipReason = validateReplayAction(api, entry);
        if (skipReason != null) {
            log.warn("Replay step {} skipped — {}: {} (action {})",
                    replayIndex, skipReason, entry.optionName(), ActionTypes.nameOf(entry.actionId()));
        } else {
            try {
                api.queueAction(new GameAction(entry.actionId(), entry.param1(), entry.param2(), entry.param3()));
            } catch (Exception e) {
                log.debug("Replay action failed at step {}: {}", replayIndex, e.getMessage());
            }
        }

        replayIndex++;
        if (replayIndex < actionLog.size()) {
            long delta = actionLog.get(replayIndex).timestamp() - entry.timestamp();
            delta = (long) (delta / replaySpeed);
            delta = Math.max(100, Math.min(delta, 15000));
            replayNextTime = now + delta;
        } else {
            replaying = false;
        }
    }

    /** Validates a replay action target. Returns null if valid, or a skip reason string. */
    private String validateReplayAction(GameAPI api, LogEntry entry) {
        try {
            int actionId = entry.actionId();

            // Component actions — check interface is open and visible
            if (actionId == ActionTypes.COMPONENT || actionId == ActionTypes.SELECT_COMPONENT_ITEM
                    || actionId == ActionTypes.CONTAINER_ACTION) {
                int ifaceId = entry.param3() >>> 16;
                if (!api.isInterfaceOpen(ifaceId)) {
                    return "interface " + ifaceId + " not open";
                }
                try {
                    var visible = api.queryComponents(
                            ComponentFilter.builder().interfaceId(ifaceId).visibleOnly(true).maxResults(1).build());
                    if (visible == null || visible.isEmpty()) {
                        return "interface " + ifaceId + " hidden";
                    }
                } catch (Exception ignored) {}
            }

            // NPC actions — check NPC exists
            int npcSlot = findSlot(ActionTypes.NPC_OPTIONS, actionId);
            if (npcSlot > 0) {
                var npcs = api.queryEntities(EntityFilter.builder().type("npc")
                        .visibleOnly(true).maxResults(500).build());
                boolean found = npcs != null && npcs.stream().anyMatch(e -> e.serverIndex() == entry.param1());
                if (!found) return "NPC not found (serverIndex=" + entry.param1() + ")";
            }

            // Object actions — check object exists
            int objSlot = findSlot(ActionTypes.OBJECT_OPTIONS, actionId);
            if (objSlot > 0) {
                var objs = api.queryEntities(EntityFilter.builder().type("location")
                        .visibleOnly(true).maxResults(200).build());
                boolean found = objs != null && objs.stream().anyMatch(e -> e.typeId() == entry.param1());
                if (!found) return "Object not found (typeId=" + entry.param1() + ")";
            }
        } catch (Exception e) {
            // Validation failed — don't skip, just proceed with the action
            log.debug("Replay validation error at step {}: {}", replayIndex, e.getMessage());
        }
        return null; // Valid
    }

    // ── Inspector data collection ───────────────────────────────────────

    private void collectInspectorData(GameAPI api) {
        int dist = entityDistanceFilter;

        // Use entity facades for querying (SceneObjects uses "location" internally)
        try {
            var npcQuery = npcs.query().limit(100);
            if (dist > 0 && dist < 200) npcQuery.withinDistance(dist);
            nearbyNpcs = npcQuery.all().stream().map(EntityContext::raw).toList();
        } catch (Exception e) { log.info("NPC query failed: {}", e.getMessage()); nearbyNpcs = List.of(); }

        try {
            var playerQuery = players.query().limit(50);
            if (dist > 0 && dist < 200) playerQuery.withinDistance(dist);
            nearbyPlayers = playerQuery.all().stream().map(EntityContext::raw).toList();
        } catch (Exception e) { log.info("Player query failed: {}", e.getMessage()); nearbyPlayers = List.of(); }

        try {
            var objQuery = sceneObjects.query().limit(100);
            if (dist > 0 && dist < 200) objQuery.withinDistance(dist);
            nearbyObjects = objQuery.all().stream().map(EntityContext::raw).toList();
        } catch (Exception e) { log.info("Object query failed: {}", e.getMessage()); nearbyObjects = List.of(); }

        // Ground items — keep using raw API (facade returns different type)
        try {
            EntityFilter.Builder giF = EntityFilter.builder().sortByDistance(true).maxResults(50);
            LocalPlayer lp = localPlayerData;
            if (lp != null && dist > 0 && dist < 200) {
                giF.tileX(lp.tileX()).tileY(lp.tileY()).radius(dist);
            }
            nearbyGroundItems = api.queryGroundItems(giF.build());
        } catch (Exception e) { log.info("Ground item query failed: {}", e.getMessage()); nearbyGroundItems = List.of(); }

        try { openInterfaces = api.getOpenInterfaces(); } catch (Exception ignored) { openInterfaces = List.of(); }

        log.debug("Inspector: NPCs={} Players={} Objects={} GroundItems={} dist={}",
                nearbyNpcs.size(), nearbyPlayers.size(), nearbyObjects.size(),
                nearbyGroundItems.size(), dist);

        // Batch-resolve ground item names (cap 50 unique IDs)
        Set<Integer> itemIds = new HashSet<>();
        for (GroundItemStack stack : nearbyGroundItems) {
            if (stack.items() != null) {
                for (GroundItem gi : stack.items()) itemIds.add(gi.itemId());
            }
            if (itemIds.size() >= 50) break;
        }
        Map<Integer, ItemType> typeMap = new HashMap<>();
        for (int id : itemIds) {
            try {
                ItemType it = api.getItemType(id);
                if (it != null) typeMap.put(id, it);
            } catch (Exception ignored) {}
        }
        groundItemTypeCache = Map.copyOf(typeMap);

        // Batch-fetch EntityInfo for hover tooltips (cap to 30 per type)
        Map<Integer, EntityInfo> infoMap = new HashMap<>();
        fetchEntityInfoBatch(api, nearbyNpcs, infoMap, 30);
        fetchEntityInfoBatch(api, nearbyPlayers, infoMap, 20);
        fetchEntityInfoBatch(api, nearbyObjects, infoMap, 30);
        entityInfoCache = Map.copyOf(infoMap);

        // Fetch children for inspected interface
        int inspId = inspectInterfaceId;
        if (inspId >= 0) {
            try {
                List<Component> children = api.getComponentChildren(inspId, 0);
                componentCache.put(inspId, children != null ? children : List.of());
                // Fetch text and options for each child
                if (children != null) {
                    for (Component c : children) {
                        String key = c.interfaceId() + ":" + c.componentId();
                        try {
                            String text = api.getComponentText(c.interfaceId(), c.componentId());
                            if (text != null && !text.isEmpty()) componentTextCache.put(key, text);
                        } catch (Exception ignored) {}
                        try {
                            List<String> opts = api.getComponentOptions(c.interfaceId(), c.componentId());
                            if (opts != null && !opts.isEmpty()) componentOptionsCache.put(key, opts);
                        } catch (Exception ignored) {}
                    }
                }
            } catch (Exception ignored) {}
            inspectInterfaceId = -1; // Reset -- one-shot fetch
        }

        // Collect item varbits for equipped items (guarded — may crash game client)
        if (showItemVarbits) {
            collectItemVarbits(api);
        } else {
            itemVarCache = List.of();
            previousItemVarSnapshot = Map.of();
        }
    }

    /**
     * Collects item varbits for all equipped items. Uses a single-probe approach:
     * on first call per player, tries one getItemVars call to check if the item var
     * system is initialized for this account. Result is persisted to disk so we
     * never crash the same account twice.
     */
    private void collectItemVarbits(GameAPI api) {
        int[] slotIndices = {0, 1, 2, 3, 4, 5, 7, 9, 10, 12, 13, 14, 17};
        String[] slotNames = {"Head", "Cape", "Neck", "Weapon", "Body", "Shield",
                "Legs", "Hands", "Feet", "Ring", "Ammo", "Aura", "Pocket"};

        // Need player name for persistence
        LocalPlayer lp = localPlayerData;
        if (lp == null || lp.name() == null || lp.name().isEmpty()) return;
        String playerName = lp.name().toLowerCase();

        // Detect player switch mid-session
        if (!playerName.equals(itemVarPlayerName)) {
            itemVarSystemAvailable = null;
            itemVarPlayerName = playerName;
        }

        // Probe if untested
        if (itemVarSystemAvailable == null) {
            Boolean persisted = loadItemVarStatus(playerName);
            if (persisted != null) {
                itemVarSystemAvailable = persisted;
            } else {
                // Find first equipped item to probe with
                int probeSlot = -1;
                for (int idx : slotIndices) {
                    try {
                        InventoryItem item = api.getInventoryItem(94, idx);
                        if (item != null && item.itemId() > 0) { probeSlot = idx; break; }
                    } catch (Exception ignored) {}
                }
                if (probeSlot < 0) return; // No equipped items — can't probe yet

                try {
                    api.getItemVars(94, probeSlot); // may crash pipe if system not initialized
                    itemVarSystemAvailable = true;
                    saveItemVarStatus(playerName, true);
                    log.info("Item var system available for player '{}'", playerName);
                } catch (Exception e) {
                    itemVarSystemAvailable = false;
                    saveItemVarStatus(playerName, false);
                    logItemVarError("probe getItemVars(94, " + probeSlot + ") for player '" + playerName + "'", e);
                    log.warn("Item var system NOT available for player '{}' — disabled", playerName);
                    itemVarCache = List.of();
                    previousItemVarSnapshot = Map.of();
                    return;
                }
            }
        }

        if (!itemVarSystemAvailable) {
            itemVarCache = List.of();
            previousItemVarSnapshot = Map.of();
            return;
        }

        // System is available — collect varbits for all equipped items
        List<ItemVarEntry> entries = new ArrayList<>();
        Map<String, Integer> newSnapshot = new HashMap<>();

        for (int s = 0; s < slotIndices.length; s++) {
            int slotIdx = slotIndices[s];
            try {
                InventoryItem item = api.getInventoryItem(94, slotIdx);
                if (item == null || item.itemId() <= 0) continue;

                List<ItemVar> vars = api.getItemVars(94, slotIdx);
                if (vars == null || vars.isEmpty()) continue;

                // Filter to vars with varId > 0
                List<ItemVar> meaningful = new ArrayList<>();
                for (ItemVar v : vars) {
                    if (v.varId() > 0) meaningful.add(v);
                }
                if (meaningful.isEmpty()) continue;

                // Get item name
                String itemName = "";
                try {
                    ItemType type = api.getItemType(item.itemId());
                    if (type != null && type.name() != null) itemName = type.name();
                } catch (Exception ignored) {}

                entries.add(new ItemVarEntry(slotNames[s], item.itemId(), itemName, slotIdx, meaningful));

                // Build snapshot for change detection
                for (ItemVar v : meaningful) {
                    newSnapshot.put(slotIdx + ":" + v.varId(), v.value());
                }
            } catch (Exception e) {
                logItemVarError("collectItemVarbits slot=" + slotNames[s] + " slotIdx=" + slotIdx, e);
            }
        }

        // Detect changes and log as VarChange entries
        Map<String, Integer> oldSnapshot = previousItemVarSnapshot;
        for (Map.Entry<String, Integer> entry : newSnapshot.entrySet()) {
            Integer oldVal = oldSnapshot.get(entry.getKey());
            if (oldVal != null && !oldVal.equals(entry.getValue())) {
                String[] parts = entry.getKey().split(":");
                int slot = Integer.parseInt(parts[0]);
                int varId = Integer.parseInt(parts[1]);
                int compositeId = slot * 100000 + varId;
                varLog.add(new VarChange("itemvar", compositeId, oldVal, entry.getValue(),
                        System.currentTimeMillis(), currentTick));
                String countKey = "itemvar:" + compositeId;
                varChangeCount.merge(countKey, 1, Integer::sum);
            }
        }

        itemVarCache = entries;
        previousItemVarSnapshot = newSnapshot;
    }

    @SuppressWarnings("unchecked")
    private Boolean loadItemVarStatus(String playerName) {
        try {
            if (!Files.exists(ITEMVAR_ACCOUNTS_FILE)) return null;
            String json = Files.readString(ITEMVAR_ACCOUNTS_FILE);
            Map<String, Map<String, Object>> accounts = GSON.fromJson(json, Map.class);
            if (accounts == null || !accounts.containsKey(playerName)) return null;
            Map<String, Object> entry = accounts.get(playerName);
            Object available = entry.get("available");
            if (available instanceof Boolean b) return b;
            return null;
        } catch (Exception e) {
            log.debug("Failed to load item var accounts: {}", e.getMessage());
            return null;
        }
    }

    @SuppressWarnings("unchecked")
    private void saveItemVarStatus(String playerName, boolean available) {
        try {
            Map<String, Map<String, Object>> accounts;
            if (Files.exists(ITEMVAR_ACCOUNTS_FILE)) {
                String json = Files.readString(ITEMVAR_ACCOUNTS_FILE);
                accounts = GSON.fromJson(json, Map.class);
                if (accounts == null) accounts = new LinkedHashMap<>();
            } else {
                accounts = new LinkedHashMap<>();
            }
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("available", available);
            entry.put("lastChecked", LocalDateTime.now().toString());
            accounts.put(playerName, entry);
            Files.writeString(ITEMVAR_ACCOUNTS_FILE, GSON.toJson(accounts));
        } catch (Exception e) {
            log.debug("Failed to save item var account status: {}", e.getMessage());
        }
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

    private void logItemVarError(String context, Exception e) {
        int count = ++itemVarErrorLogCount;
        if (count > ITEM_VAR_ERROR_LOG_MAX + 1) return;
        try {
            Path logFile = Path.of("logs", "xapi_itemvar_errors.log");
            Files.createDirectories(logFile.getParent());
            String msg;
            if (count > ITEM_VAR_ERROR_LOG_MAX) {
                msg = "[%s] Further item varbit errors suppressed (limit=%d)\n".formatted(
                        LocalDateTime.now(), ITEM_VAR_ERROR_LOG_MAX);
            } else {
                String stackTrace = Arrays.stream(e.getStackTrace()).limit(10)
                        .map(st -> "  at " + st).collect(Collectors.joining("\n"));
                msg = "[%s] %s: %s\n%s\n".formatted(
                        LocalDateTime.now(), context, e.getMessage(), stackTrace);
            }
            Files.writeString(logFile, msg, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (Exception ignored) {}
    }

    private void fetchEntityInfoBatch(GameAPI api, List<Entity> entities, Map<Integer, EntityInfo> target, int cap) {
        int limit = Math.min(cap, entities.size());
        for (int i = 0; i < limit; i++) {
            try {
                Entity e = entities.get(i);
                target.put(e.handle(), api.getEntityInfo(e.handle()));
            } catch (Exception ignored) {}
        }
    }

    private void pollChatHistory(GameAPI api) {
        try {
            List<ChatMessage> history = api.queryChatHistory(-1, 100);
            if (history != null && history.size() < lastChatHistorySize) {
                lastChatHistorySize = 0; // Server reset chat history
            }
            if (history != null && history.size() > lastChatHistorySize) {
                for (int i = lastChatHistorySize; i < history.size(); i++) {
                    ChatMessage msg = history.get(i);
                    if (msg == null) continue;
                    // Avoid duplicates by checking if chatLog already has this message
                    String text = msg.text() != null ? msg.text().replaceAll("<img=\\d+>", "").replaceAll("<col=[0-9a-fA-F]+>", "").replaceAll("</col>", "").trim() : "";
                    String player = msg.playerName() != null ? msg.playerName() : "";
                    boolean duplicate = false;
                    // Check last few entries for duplicate
                    for (int j = Math.max(0, chatLog.size() - 10); j < chatLog.size(); j++) {
                        ChatEntry ce = chatLog.get(j);
                        if (ce.text().equals(text) && ce.playerName().equals(player)
                                && ce.messageType() == msg.messageType()) {
                            duplicate = true;
                            break;
                        }
                    }
                    if (!duplicate) {
                        chatLog.add(new ChatEntry(msg.messageType(), text, player,
                                System.currentTimeMillis(), currentTick));
                    }
                }
                lastChatHistorySize = history.size();
            }
        } catch (Exception ignored) {}
    }

    // ── Interface event polling (every tick for fast detection) ─────────

    private void pollInterfaceEvents(GameAPI api) {
        try {
            List<OpenInterface> current = api.getOpenInterfaces();
            if (current == null) current = List.of();

            Set<Integer> currentIfaceIds = new HashSet<>();
            for (OpenInterface oi : current) currentIfaceIds.add(oi.interfaceId());
            Set<Integer> prevIds = previousOpenInterfaceIds;

            // Newly opened interfaces
            for (int id : currentIfaceIds) {
                if (!prevIds.contains(id)) {
                    // Check visibility — only log if the interface has at least one visible component
                    try {
                        var visibleComps = api.queryComponents(
                                ComponentFilter.builder().interfaceId(id).visibleOnly(true).maxResults(1).build());
                        if (visibleComps == null || visibleComps.isEmpty()) continue; // Hidden interface
                    } catch (Exception ignored) {
                        // If query fails, still log the event (better to have false positives than miss events)
                    }

                    // Snapshot components for the newly opened interface
                    List<InterfaceComponentSnapshot> snapshots = new ArrayList<>();
                    try {
                        List<Component> children = api.getComponentChildren(id, 0);
                        if (children != null) {
                            for (Component c : children) {
                                String text = "";
                                List<String> opts = List.of();
                                try { text = api.getComponentText(c.interfaceId(), c.componentId()); } catch (Exception ignored) {}
                                try {
                                    List<String> o = api.getComponentOptions(c.interfaceId(), c.componentId());
                                    if (o != null) opts = o;
                                } catch (Exception ignored) {}
                                snapshots.add(new InterfaceComponentSnapshot(
                                        c.componentId(), c.subComponentId(), c.type(),
                                        text != null ? text : "", opts,
                                        c.itemId(), c.spriteId()));
                            }
                        }
                    } catch (Exception ignored) {}
                    interfaceEventLog.add(new InterfaceEvent("OPENED", id,
                            System.currentTimeMillis(), currentTick, snapshots));
                }
            }
            // Closed interfaces
            for (int id : prevIds) {
                if (!currentIfaceIds.contains(id)) {
                    interfaceEventLog.add(new InterfaceEvent("CLOSED", id,
                            System.currentTimeMillis(), currentTick, List.of()));
                }
            }
            previousOpenInterfaceIds = Set.copyOf(currentIfaceIds);
        } catch (Exception e) {
            log.debug("Interface event poll error: {}", e.getMessage());
        }
    }

    // ── Action history polling (captures manual player actions) ─────────

    private void pollActionHistory(GameAPI api) {
        if (!actionHistoryAvailable || !recording) return;
        long now = System.currentTimeMillis();
        if (now - lastActionHistoryPoll < 600) return; // ~1 tick
        lastActionHistoryPoll = now;
        try {
            var history = api.getActionHistory(50, -1);
            if (history == null || history.isEmpty()) return;

            for (var entry : history) {
                // Only process entries newer than what we've already seen
                if (entry.timestamp() <= lastSeenActionTimestamp) continue;
                lastSeenActionTimestamp = entry.timestamp();

                // Check if this action was already captured by ActionExecutedEvent
                boolean duplicate = false;
                for (int j = Math.max(0, actionLog.size() - 20); j < actionLog.size(); j++) {
                    LogEntry existing = actionLog.get(j);
                    if (existing.actionId() == entry.actionId()
                            && existing.param1() == entry.param1()
                            && existing.param2() == entry.param2()
                            && existing.param3() == entry.param3()
                            && Math.abs(existing.timestamp() - entry.timestamp()) < 1200) {
                        duplicate = true;
                        break;
                    }
                }
                if (duplicate) continue;

                // New action from history — resolve names and log it
                String[] names = resolveNames(api,
                        entry.actionId(), entry.param1(), entry.param2(), entry.param3());

                // Try mini menu cache for option text and item name
                String[] menuMatch = matchMiniMenu(entry.actionId(), entry.param1(),
                        entry.param2(), entry.param3());
                if (menuMatch != null) {
                    if (names[1] == null || names[1].isEmpty()) names[1] = menuMatch[0];
                    if (names[0] == null || names[0].isEmpty()) names[0] = menuMatch[1];
                }

                int px = 0, py = 0, pp = 0, pa = -1;
                boolean pm = false;
                try {
                    LocalPlayer lp = api.getLocalPlayer();
                    if (lp != null) {
                        px = lp.tileX(); py = lp.tileY(); pp = lp.plane();
                        pa = lp.animationId(); pm = lp.isMoving();
                    }
                } catch (Exception ignored) {}

                actionLog.add(new LogEntry(
                        entry.actionId(), entry.param1(), entry.param2(), entry.param3(),
                        entry.timestamp(), currentTick, false, "history",
                        names[0], names[1], px, py, pp, pa, pm
                ));
            }
        } catch (NoClassDefFoundError e) {
            actionHistoryAvailable = false;
            log.warn("Action history polling unavailable (classloader issue) — using event-based capture only");
        } catch (Throwable t) {
            log.debug("pollActionHistory error: {}", t.getMessage());
        }
    }

    // ── Inventory diff ──────────────────────────────────────────────────

    private void collectInventoryDiff(GameAPI api) {
        try {
            List<InventoryItem> items = api.queryInventoryItems(
                    InventoryFilter.builder().inventoryId(93).nonEmpty(true).build());
            Map<Integer, Integer> current = new HashMap<>();
            for (InventoryItem item : items) {
                current.merge(item.itemId(), item.quantity(), Integer::sum);
            }

            Map<Integer, Integer> prev = lastInventorySnapshot;
            if (!prev.isEmpty()) {
                Set<Integer> allIds = new HashSet<>(prev.keySet());
                allIds.addAll(current.keySet());
                long now = System.currentTimeMillis();
                for (int id : allIds) {
                    int oldQty = prev.getOrDefault(id, 0);
                    int newQty = current.getOrDefault(id, 0);
                    if (oldQty != newQty) {
                        String name;
                        try { name = api.getItemType(id).name(); } catch (Exception e) { name = "item:" + id; }
                        inventoryLog.add(new InventoryChange(id, name, oldQty, newQty, now, currentTick));
                    }
                }
                // Cap at 2000 entries
                while (inventoryLog.size() > 2000) inventoryLog.remove(0);
            }
            lastInventorySnapshot = current;
        } catch (Exception ignored) {}
    }

    @Override
    public void onStop() {
        log.info("Xapi stopped — {} actions, {} var changes, {} chat messages logged",
                actionLog.size(), varLog.size(), chatLog.size());


        // Save settings on stop
        saveSettings();

        // Always unblock on stop
        blocking = false;
        ActionDebugger.get().setBlocking(false);
        ActionDebugger.get().setRecording(false);
        try {
            ctx.getGameAPI().setActionsBlocked(false);
        } catch (Exception e) {
            log.debug("Failed to unblock on stop: {}", e.getMessage());
        }
        replaying = false;
    }

    // =================================================================
    // == UI (render thread -- NO RPC calls) ===========================
    // =================================================================

    private final ScriptUI ui = this::renderUI;

    @Override
    public ScriptUI getUI() { return ui; }

    private void renderUI() {
        try {
            renderControls();

            ImGui.spacing();
            ImGui.separator();
            ImGui.spacing();

            // Show counts in a status line above tabs
            ImGui.textColored(0.6f, 0.6f, 0.6f, 0.8f,
                    String.format("Actions: %d  |  Variables: %d  |  Chat: %d",
                            actionLog.size(), varLog.size(), chatLog.size()));

            if (ImGui.beginTabBar("xapi_tabs")) {
                if (ImGui.beginTabItem("Actions")) {
                    try { actionsTab.render(); } catch (Exception e) { log.error("Actions tab error", e); ImGui.text("Error: " + e.getMessage()); }
                    ImGui.endTabItem();
                }
                if (ImGui.beginTabItem("Variables")) {
                    try { variablesTab.render(); } catch (Exception e) { log.error("Variables tab error", e); ImGui.text("Error: " + e.getMessage()); }
                    ImGui.endTabItem();
                }
                if (ImGui.beginTabItem("Chat")) {
                    try { chatTab.render(); } catch (Exception e) { log.error("Chat tab error", e); ImGui.text("Error: " + e.getMessage()); }
                    ImGui.endTabItem();
                }
                if (ImGui.beginTabItem("Player")) {
                    try { playerTab.render(); } catch (Exception e) { log.error("Player tab error", e); ImGui.text("Error: " + e.getMessage()); }
                    ImGui.endTabItem();
                }
                if (ImGui.beginTabItem("Entities")) {
                    try { entitiesTab.render(); } catch (Exception e) { log.error("Entities tab error", e); ImGui.text("Error: " + e.getMessage()); }
                    ImGui.endTabItem();
                }
                if (ImGui.beginTabItem("Interfaces")) {
                    try { interfacesTab.render(); } catch (Exception e) { log.error("Interfaces tab error", e); ImGui.text("Error: " + e.getMessage()); }
                    ImGui.endTabItem();
                }
                if (ImGui.beginTabItem("Inventory")) {
                    try { inventoryTab.render(); } catch (Exception e) { log.error("Inventory tab error", e); ImGui.text("Error: " + e.getMessage()); }
                    ImGui.endTabItem();
                }
                ImGui.endTabBar();
            }
        } catch (Exception e) {
            log.error("renderUI error", e);
            try { ImGui.text("UI Error: " + e.getMessage()); } catch (Exception ignored) {}
        }
    }

    // ── Controls ────────────────────────────────────────────────────────

    private void renderControls() {
        if (ImGui.collapsingHeader("Controls", ImGuiTreeNodeFlags.DefaultOpen)) {
            // Row 1: Record, Block, Clear, Tick
            boolean wasRecording = recording;
            if (wasRecording) {
                ImGui.pushStyleColor(ImGuiCol.Button, 0.2f, 0.8f, 0.4f, 0.6f);
                ImGui.pushStyleColor(ImGuiCol.ButtonHovered, 0.2f, 0.8f, 0.4f, 0.8f);
            }
            if (ImGui.button(wasRecording ? "Recording" : "Record")) { recording = !recording; settingsDirty = true; }
            if (wasRecording) ImGui.popStyleColor(2);

            ImGui.sameLine();
            boolean wasBlocking = blocking;
            if (wasBlocking) {
                ImGui.pushStyleColor(ImGuiCol.Button, 0.9f, 0.2f, 0.2f, 0.6f);
                ImGui.pushStyleColor(ImGuiCol.ButtonHovered, 0.9f, 0.2f, 0.2f, 0.8f);
            }
            if (ImGui.button(wasBlocking ? "BLOCKING" : "Block")) {
                blocking = !blocking;
                if (blocking && !recording) recording = true;
                settingsDirty = true;
            }
            if (wasBlocking) ImGui.popStyleColor(2);

            ImGui.sameLine();
            if (ImGui.button("Clear All")) {
                actionLog.clear(); varLog.clear(); chatLog.clear();
                varsByTick.clear();
                ActionDebugger.get().clear();
                lastActionSize = -1; lastVarSize = -1; lastChatSize = -1;
                inventoryLog.clear();
                interfaceEventLog.clear();
                // Don't clear pinnedVars, varChangeCount, or varAnnotations
            }

            ImGui.sameLine();
            ImGui.text("Tick: " + currentTick);

            if (blocking) { ImGui.sameLine(); ImGui.textColored(0.9f, 0.2f, 0.2f, 1f, "  ACTIONS BLOCKED"); }

        }

        // Session management
        if (ImGui.collapsingHeader("Session Export/Import")) {
            if (ImGui.button("Export Session")) exportRequested = true;
            ImGui.sameLine();

            // Scan session files periodically
            long now = System.currentTimeMillis();
            if (now - lastSessionScan > 5000) {
                lastSessionScan = now;
                actionsTab.scanSessionFiles();
            }

            if (sessionFiles.length > 0) {
                ImGui.text("Import:");
                ImGui.sameLine();
                for (String file : sessionFiles) {
                    if (ImGui.smallButton(file)) {
                        importPath = file;
                    }
                    ImGui.sameLine();
                }
                ImGui.newLine();
            }

            if (!lastExportStatus.isEmpty()) {
                ImGui.textColored(0.5f, 0.8f, 1f, 1f, lastExportStatus);
            }
        }

        // Script generation
        if (ImGui.collapsingHeader("Generate Script")) {
            ImGui.text("Class Name:");
            ImGui.sameLine();
            ImGui.pushItemWidth(200);
            ImGui.inputText("##className", scriptClassName);
            ImGui.popItemWidth();
            ImGui.sameLine();
            boolean wasUseNames = useNamesForGeneration;
            if (ImGui.checkbox("Use Names (skeleton)", wasUseNames)) { useNamesForGeneration = !wasUseNames; settingsDirty = true; }

            if (ImGui.button("Generate & Copy to Clipboard")) {
                actionsTab.generateAndCopyScript();
            }
        }

        // Replay controls
        if (ImGui.collapsingHeader("Replay")) {
            if (!replaying) {
                if (ImGui.button("Start Replay") && !actionLog.isEmpty()) {
                    replaying = true;
                    replayIndex = 0;
                    replayNextTime = System.currentTimeMillis();
                    replaySpeed = replaySpeedArr[0];
                }
            } else {
                ImGui.textColored(0.3f, 0.9f, 0.3f, 1f,
                        "Replaying: step " + replayIndex + "/" + actionLog.size());
                ImGui.sameLine();
                if (ImGui.button("Stop Replay")) replaying = false;
            }
            ImGui.sameLine();
            ImGui.pushItemWidth(150);
            ImGui.sliderFloat("Speed##replay", replaySpeedArr, 0.25f, 4f);
            ImGui.popItemWidth();
            replaySpeed = replaySpeedArr[0];
        }
    }

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
}
