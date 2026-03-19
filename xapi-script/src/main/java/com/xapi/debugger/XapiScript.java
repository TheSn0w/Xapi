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
import java.time.format.DateTimeFormatter;
import java.util.*;
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
    static final int VARC_SCAN_MAX = 10000;
    private static final int VARC_SCAN_BATCH = 200;

    private ScriptContext ctx;

    // ── Tab instances ────────────────────────────────────────────────────

    private ActionsTab actionsTab;
    private VariablesTab variablesTab;
    private ChatTab chatTab;
    private PlayerTab playerTab;
    private EntitiesTab entitiesTab;
    private InterfacesTab interfacesTab;

    // ── Shared state (package-private for tab access) ────────────────────

    final List<LogEntry> actionLog = new CopyOnWriteArrayList<>();
    final List<VarChange> varLog = new CopyOnWriteArrayList<>();
    final List<ChatEntry> chatLog = new CopyOnWriteArrayList<>();

    // Index: gameTick -> list of VarChanges on that tick (for action-linked display)
    final ConcurrentHashMap<Integer, List<VarChange>> varsByTick = new ConcurrentHashMap<>();

    volatile boolean recording = true;
    volatile boolean blocking;
    private volatile boolean lastBlockingSentToClient;
    volatile boolean selectiveBlocking;
    volatile boolean trackVars = true;
    volatile boolean trackChat = true;
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

    // Selected entity for info panel
    volatile int selectedEntityHandle = -1;
    volatile EntityInfo selectedEntityInfo;

    // Chat history polling fallback
    private volatile int lastChatHistorySize;

    // Varc scan
    volatile boolean varcScanRequested;
    volatile int varcScanProgress = -1;
    final List<int[]> varcScanResults = new CopyOnWriteArrayList<>();
    final ConcurrentHashMap<Integer, List<Component>> componentCache = new ConcurrentHashMap<>();
    final ConcurrentHashMap<String, String> componentTextCache = new ConcurrentHashMap<>();
    final ConcurrentHashMap<String, List<String>> componentOptionsCache = new ConcurrentHashMap<>();
    private volatile long lastInspectorUpdate;

    // Inventory diff tracking
    private volatile Map<Integer, Integer> lastInventorySnapshot = Map.of();
    volatile String lastInventoryDiff = "";

    // Pinned variables (survive clears)
    final Set<String> pinnedVars = ConcurrentHashMap.newKeySet(); // "varbit:1234", "varp:567"
    final ConcurrentHashMap<String, Integer> pinnedCurrentValues = new ConcurrentHashMap<>();

    // Var change frequency counter
    final ConcurrentHashMap<String, Integer> varChangeCount = new ConcurrentHashMap<>();

    // Var annotations (user-defined labels)
    final ConcurrentHashMap<String, String> varAnnotations = new ConcurrentHashMap<>();

    // Varc polling cache
    private final ConcurrentHashMap<Integer, Integer> varcCache = new ConcurrentHashMap<>();

    // Ground item type cache (itemId -> ItemType with name/options)
    volatile Map<Integer, ItemType> groundItemTypeCache = Map.of();

    // Interface event tracking
    final List<InterfaceEvent> interfaceEventLog = new CopyOnWriteArrayList<>();
    private volatile Set<Integer> previousOpenInterfaceIds = Set.of();

    // Entity overlay
    private final XapiOverlay overlay = new XapiOverlay();

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
    final boolean[] selectiveBlockCategories = new boolean[7];
    boolean useNamesForGeneration = true;
    final float[] replaySpeedArr = {1.0f};
    String[] sessionFiles = new String[0];
    private long lastSessionScan;

    // Variables tab: type filters
    boolean showVarbits = true;
    boolean showVarps = true;
    boolean showVarcs = true;

    // Varc watch IDs input
    final ImString varcWatchIdsInput = new ImString(256);

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

        // Load persistent settings
        loadSettings();

        EventBus events = ctx.getEventBus();
        events.subscribe(ActionExecutedEvent.class, this::onActionExecuted);
        events.subscribe(TickEvent.class, this::onTick);
        events.subscribe(VarbitChangeEvent.class, this::onVarbitChange);
        events.subscribe(VarChangeEvent.class, this::onVarChange);
        events.subscribe(ChatMessageEvent.class, this::onChatMessage);

        try { Files.createDirectories(SESSION_DIR); } catch (IOException ignored) {}

        // Initialize overlay with background position poller
        try {
            overlay.initFX();
            overlay.startTracking(ctx.getGameAPI(),
                    () -> selectedEntityHandle,
                    () -> selectedEntityInfo);
        } catch (Exception e) { log.debug("Overlay init failed: {}", e.getMessage()); }
    }

    // ── Event handlers (event thread -- RPC safe) ────────────────────────

    private void onTick(TickEvent e) {
        currentTick = e.getTick();
    }

    private void onActionExecuted(ActionExecutedEvent e) {
        if (recording || blocking || selectiveBlocking) {
            String[] names = resolveNames(ctx.getGameAPI(),
                    e.getActionId(), e.getParam1(), e.getParam2(), e.getParam3());

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

    private void onVarbitChange(VarbitChangeEvent e) {
        try {
            if (trackVars) {
                VarChange vc = new VarChange("varbit", e.getVarId(), e.getOldValue(), e.getNewValue(),
                        System.currentTimeMillis(), currentTick);
                varLog.add(vc);
                varsByTick.computeIfAbsent(currentTick, k -> new CopyOnWriteArrayList<>()).add(vc);
                varChangeCount.merge("varbit:" + e.getVarId(), 1, Integer::sum);
            }
        } catch (Exception ex) { log.debug("varbit event error: {}", ex.getMessage()); }
    }

    private void onVarChange(VarChangeEvent e) {
        try {
            if (trackVars) {
                VarChange vc = new VarChange("varp", e.getVarId(), e.getOldValue(), e.getNewValue(),
                        System.currentTimeMillis(), currentTick);
                varLog.add(vc);
                varsByTick.computeIfAbsent(currentTick, k -> new CopyOnWriteArrayList<>()).add(vc);
                varChangeCount.merge("varp:" + e.getVarId(), 1, Integer::sum);
            }
        } catch (Exception ex) { log.debug("varp event error: {}", ex.getMessage()); }
    }

    private void onChatMessage(ChatMessageEvent e) {
        try {
            if (trackChat) {
                var msg = e.getMessage();
                String text = msg.text() != null ? msg.text().replaceAll("<img=\\d+>", "").trim() : "";
                chatLog.add(new ChatEntry(msg.messageType(), text,
                        msg.playerName(), System.currentTimeMillis(), currentTick));
            }
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

            if (actionId == ActionTypes.COMPONENT) return resolveComponent(api, p1, p3);
            if (actionId == ActionTypes.SELECT_COMPONENT_ITEM) return resolveComponent(api, p1, p3);
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

    private String[] resolveComponent(GameAPI api, int optionIndex, int packedHash) {
        try {
            int ifaceId = packedHash >>> 16;
            int compId = packedHash & 0xFFFF;
            List<String> options = api.getComponentOptions(ifaceId, compId);
            if (options != null && optionIndex - 1 >= 0 && optionIndex - 1 < options.size()) {
                return new String[]{null, options.get(optionIndex - 1)};
            }
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
        debugger.setSelectiveBlocking(selectiveBlocking);

        // Sync selective block categories to ActionDebugger
        syncSelectiveBlocking(debugger);

        if (blocking != lastBlockingSentToClient) {
            try {
                api.setActionsBlocked(blocking);
                lastBlockingSentToClient = blocking;
            } catch (Exception e) {
                log.debug("Failed to sync blocking state to client: {}", e.getMessage());
            }
        }

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
        long now = System.currentTimeMillis();
        if (now - lastInspectorUpdate > 1800) {
            lastInspectorUpdate = now;
            collectInspectorData(api);
        }

        // Inventory diff
        collectInventoryDiff(api);

        // Poll pinned variable live values
        pollPinnedVars(api);

        // Poll varc watch IDs
        pollVarcs(api);

        // Poll chat history as fallback
        pollChatHistory(api);

        // Fetch info for selected entity
        if (selectedEntityHandle >= 0) {
            try {
                if (api.isEntityValid(selectedEntityHandle)) {
                    selectedEntityInfo = api.getEntityInfo(selectedEntityHandle);
                } else {
                    selectedEntityHandle = -1;
                    selectedEntityInfo = null;
                }
            } catch (Exception e) {
                selectedEntityHandle = -1;
                selectedEntityInfo = null;
            }
        }

        // Varc scan (batched over multiple ticks)
        if (varcScanRequested) {
            if (varcScanProgress < 0) {
                varcScanProgress = 0;
                varcScanResults.clear();
            }
            int end = Math.min(varcScanProgress + VARC_SCAN_BATCH, VARC_SCAN_MAX);
            for (int i = varcScanProgress; i < end; i++) {
                try {
                    int val = api.getVarcInt(i);
                    if (val != 0) varcScanResults.add(new int[]{i, val});
                } catch (Exception ignored) {}
            }
            varcScanProgress = end;
            if (varcScanProgress >= VARC_SCAN_MAX) {
                varcScanRequested = false;
                varcScanProgress = -1;
            }
        }

        // Update overlay with selected entity
        try {
            overlay.update(api, selectedEntityHandle, selectedEntityInfo);
        } catch (Exception e) {
            log.debug("Overlay update error: {}", e.getMessage());
        }

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

    private void syncSelectiveBlocking(ActionDebugger debugger) {
        Set<Integer> blocked = debugger.getBlockedActionIds();
        blocked.clear();
        if (selectiveBlocking) {
            if (selectiveBlockCategories[0]) addActionIds(blocked, ActionTypes.NPC_OPTIONS);
            if (selectiveBlockCategories[1]) addActionIds(blocked, ActionTypes.OBJECT_OPTIONS);
            if (selectiveBlockCategories[2]) addActionIds(blocked, ActionTypes.GROUND_ITEM_OPTIONS);
            if (selectiveBlockCategories[3]) addActionIds(blocked, ActionTypes.PLAYER_OPTIONS);
            if (selectiveBlockCategories[4]) { blocked.add(ActionTypes.COMPONENT); blocked.add(ActionTypes.SELECT_COMPONENT_ITEM); }
            if (selectiveBlockCategories[5]) blocked.add(ActionTypes.WALK);
            if (selectiveBlockCategories[6]) { blocked.add(ActionTypes.DIALOGUE); blocked.add(ActionTypes.SELECT_NPC); blocked.add(ActionTypes.SELECT_OBJECT); }
        }
    }

    private void addActionIds(Set<Integer> blocked, int[] options) {
        for (int i = 1; i < options.length; i++) {
            blocked.add(options[i]);
        }
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
                    case "varc" -> value = api.getVarcInt(id);
                    default -> { continue; }
                }
                pinnedCurrentValues.put(key, value);
            } catch (Exception ignored) {}
        }
    }

    private void pollVarcs(GameAPI api) {
        String varcIds = varcWatchIdsInput.get().trim();
        if (varcIds.isEmpty()) return;
        int[] ids = VariablesTab.parseWatchIds(varcIds);
        for (int id : ids) {
            try {
                int value = api.getVarcInt(id);
                Integer old = varcCache.put(id, value);
                if (old != null && old != value) {
                    VarChange vc = new VarChange("varc", id, old, value,
                            System.currentTimeMillis(), currentTick);
                    varLog.add(vc);
                    varsByTick.computeIfAbsent(currentTick, k -> new CopyOnWriteArrayList<>()).add(vc);
                    varChangeCount.merge("varc:" + id, 1, Integer::sum);
                }
            } catch (Exception ignored) {}
        }
    }

    // ── Settings Persistence ─────────────────────────────────────────────

    private void saveSettings() {
        try {
            XapiSettings settings = new XapiSettings(
                    recording, blocking, selectiveBlocking,
                    trackVars, trackChat,
                    Arrays.copyOf(categoryFilters, categoryFilters.length),
                    Arrays.copyOf(selectiveBlockCategories, selectiveBlockCategories.length),
                    showVarbits, showVarps, showVarcs,
                    varFilterText.get(), varcWatchIdsInput.get(),
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
            blocking = s.blocking();
            selectiveBlocking = s.selectiveBlocking();
            trackVars = s.trackVars();
            trackChat = s.trackChat();

            if (s.categoryFilters() != null) {
                System.arraycopy(s.categoryFilters(), 0, categoryFilters, 0,
                        Math.min(s.categoryFilters().length, categoryFilters.length));
            }
            if (s.selectiveBlockCategories() != null) {
                System.arraycopy(s.selectiveBlockCategories(), 0, selectiveBlockCategories, 0,
                        Math.min(s.selectiveBlockCategories().length, selectiveBlockCategories.length));
            }

            showVarbits = s.showVarbits();
            showVarps = s.showVarps();
            showVarcs = s.showVarcs();

            if (s.varFilterText() != null) varFilterText.set(s.varFilterText());
            if (s.varcWatchIds() != null) varcWatchIdsInput.set(s.varcWatchIds());
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
        try {
            api.queueAction(new GameAction(entry.actionId(), entry.param1(), entry.param2(), entry.param3()));
        } catch (Exception e) {
            log.debug("Replay action failed at step {}: {}", replayIndex, e.getMessage());
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

    // ── Inspector data collection ───────────────────────────────────────

    private void collectInspectorData(GameAPI api) {
        // Build entity filters with optional distance constraint
        LocalPlayer lp = localPlayerData;
        int dist = entityDistanceFilter;
        EntityFilter.Builder npcF = EntityFilter.builder().type("npc").sortByDistance(true).maxResults(100);
        EntityFilter.Builder playerF = EntityFilter.builder().type("player").sortByDistance(true).maxResults(50);
        EntityFilter.Builder objF = EntityFilter.builder().type("location").sortByDistance(true).maxResults(100);
        EntityFilter.Builder giF = EntityFilter.builder().sortByDistance(true).maxResults(50);
        if (lp != null && dist > 0 && dist < 200) {
            npcF.tileX(lp.tileX()).tileY(lp.tileY()).radius(dist);
            playerF.tileX(lp.tileX()).tileY(lp.tileY()).radius(dist);
            objF.tileX(lp.tileX()).tileY(lp.tileY()).radius(dist);
            giF.tileX(lp.tileX()).tileY(lp.tileY()).radius(dist);
        }
        try { nearbyNpcs = api.queryEntities(npcF.build()); } catch (Exception ignored) { nearbyNpcs = List.of(); }
        try { nearbyPlayers = api.queryEntities(playerF.build()); } catch (Exception ignored) { nearbyPlayers = List.of(); }
        try { nearbyObjects = api.queryEntities(objF.build()); } catch (Exception ignored) { nearbyObjects = List.of(); }
        try { nearbyGroundItems = api.queryGroundItems(giF.build()); } catch (Exception ignored) { nearbyGroundItems = List.of(); }
        try { openInterfaces = api.getOpenInterfaces(); } catch (Exception ignored) { openInterfaces = List.of(); }

        // Diff open interfaces to detect opens/closes
        Set<Integer> currentIfaceIds = new HashSet<>();
        for (OpenInterface oi : openInterfaces) currentIfaceIds.add(oi.interfaceId());
        Set<Integer> prevIds = previousOpenInterfaceIds;

        // Newly opened interfaces
        for (int id : currentIfaceIds) {
            if (!prevIds.contains(id)) {
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
        if (!trackChat) return;
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
                    String text = msg.text() != null ? msg.text().replaceAll("<img=\\d+>", "").trim() : "";
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
            if (!prev.isEmpty() || !current.isEmpty()) {
                StringBuilder diff = new StringBuilder();
                Set<Integer> allIds = new HashSet<>(prev.keySet());
                allIds.addAll(current.keySet());
                for (int id : allIds) {
                    int oldQty = prev.getOrDefault(id, 0);
                    int newQty = current.getOrDefault(id, 0);
                    if (oldQty != newQty) {
                        int delta = newQty - oldQty;
                        String name;
                        try { name = api.getItemType(id).name(); } catch (Exception e) { name = "item:" + id; }
                        if (!diff.isEmpty()) diff.append(", ");
                        diff.append(delta > 0 ? "+" : "").append(delta).append(" ").append(name);
                    }
                }
                if (!diff.isEmpty()) {
                    lastInventoryDiff = diff.toString();
                }
            }
            lastInventorySnapshot = current;
        } catch (Exception ignored) {}
    }

    @Override
    public void onStop() {
        log.info("Xapi stopped — {} actions, {} var changes, {} chat messages logged",
                actionLog.size(), varLog.size(), chatLog.size());

        // Dispose overlay
        try { overlay.dispose(); } catch (Exception ignored) {}

        // Save settings on stop
        saveSettings();

        if (blocking) {
            blocking = false;
            ActionDebugger.get().setBlocking(false);
            try {
                ctx.getGameAPI().setActionsBlocked(false);
            } catch (Exception e) {
                log.debug("Failed to unblock on stop: {}", e.getMessage());
            }
        }
        ActionDebugger.get().setRecording(false);
        ActionDebugger.get().setSelectiveBlocking(false);
        ActionDebugger.get().getBlockedActionIds().clear();
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
                lastInventoryDiff = "";
                interfaceEventLog.clear();
                // Don't clear pinnedVars, varChangeCount, or varAnnotations
            }

            ImGui.sameLine();
            ImGui.text("Tick: " + currentTick);

            if (blocking) { ImGui.sameLine(); ImGui.textColored(0.9f, 0.2f, 0.2f, 1f, "  ACTIONS BLOCKED"); }

            // Row 2: Tracking toggles
            boolean wTV = trackVars;
            if (ImGui.checkbox("Track Vars", wTV)) { trackVars = !wTV; settingsDirty = true; }
            ImGui.sameLine();
            boolean wTC = trackChat;
            if (ImGui.checkbox("Track Chat", wTC)) { trackChat = !wTC; settingsDirty = true; }

            // Inventory diff display
            String invDiff = lastInventoryDiff;
            if (!invDiff.isEmpty()) {
                ImGui.sameLine();
                ImGui.textColored(1f, 1f, 0.5f, 1f, "  Inv: " + invDiff);
            }
        }

        // Selective blocking
        if (ImGui.collapsingHeader("Selective Blocking")) {
            boolean wasSB = selectiveBlocking;
            if (ImGui.checkbox("Enable Selective Blocking", wasSB)) { selectiveBlocking = !wasSB; settingsDirty = true; }
            if (selectiveBlocking) {
                ImGui.indent();
                if (ImGui.checkbox("NPC##sb", selectiveBlockCategories[0])) { selectiveBlockCategories[0] = !selectiveBlockCategories[0]; settingsDirty = true; }
                ImGui.sameLine();
                if (ImGui.checkbox("Object##sb", selectiveBlockCategories[1])) { selectiveBlockCategories[1] = !selectiveBlockCategories[1]; settingsDirty = true; }
                ImGui.sameLine();
                if (ImGui.checkbox("Ground Item##sb", selectiveBlockCategories[2])) { selectiveBlockCategories[2] = !selectiveBlockCategories[2]; settingsDirty = true; }
                ImGui.sameLine();
                if (ImGui.checkbox("Player##sb", selectiveBlockCategories[3])) { selectiveBlockCategories[3] = !selectiveBlockCategories[3]; settingsDirty = true; }
                ImGui.sameLine();
                if (ImGui.checkbox("Component##sb", selectiveBlockCategories[4])) { selectiveBlockCategories[4] = !selectiveBlockCategories[4]; settingsDirty = true; }
                ImGui.sameLine();
                if (ImGui.checkbox("Walk##sb", selectiveBlockCategories[5])) { selectiveBlockCategories[5] = !selectiveBlockCategories[5]; settingsDirty = true; }
                ImGui.sameLine();
                if (ImGui.checkbox("Other##sb", selectiveBlockCategories[6])) { selectiveBlockCategories[6] = !selectiveBlockCategories[6]; settingsDirty = true; }
                ImGui.unindent();
            }
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
