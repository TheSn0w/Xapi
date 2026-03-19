package com.botwithus.bot.scripts.xapi;

import com.botwithus.bot.api.BotScript;
import com.botwithus.bot.api.GameAPI;
import com.botwithus.bot.api.ScriptCategory;
import com.botwithus.bot.api.ScriptContext;
import com.botwithus.bot.api.ScriptManifest;
import com.botwithus.bot.api.event.*;
import com.botwithus.bot.api.inventory.ActionTranslator;
import com.botwithus.bot.api.inventory.ActionTypes;
import com.botwithus.bot.api.model.*;
import com.botwithus.bot.api.query.ComponentFilter;
import com.botwithus.bot.api.query.EntityFilter;
import com.botwithus.bot.api.query.InventoryFilter;
import com.botwithus.bot.api.ui.ScriptUI;
import com.botwithus.bot.core.impl.ActionDebugger;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;

import imgui.ImGui;
import imgui.flag.ImGuiCol;
import imgui.flag.ImGuiTableFlags;
import imgui.flag.ImGuiTreeNodeFlags;
import imgui.type.ImString;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.lang.reflect.Type;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

@ScriptManifest(
        name = "Xapi",
        version = "2.0",
        author = "Xapi",
        description = "Action debugger — logs, blocks, and reverse-engineers game actions",
        category = ScriptCategory.UTILITY
)
public class XapiScript implements BotScript {

    private static final Logger log = LoggerFactory.getLogger(XapiScript.class);
    private static final DateTimeFormatter TIME_FMT = DateTimeFormatter.ofPattern("HH:mm:ss.SSS");
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Path SESSION_DIR = Path.of("xapi_sessions");

    private ScriptContext ctx;

    // ── Data records ────────────────────────────────────────────────────

    public record LogEntry(int actionId, int param1, int param2, int param3,
                           long timestamp, int gameTick, boolean wasBlocked, String source,
                           String entityName, String optionName,
                           int playerX, int playerY, int playerPlane,
                           int playerAnim, boolean playerMoving) {}

    public record VarChange(String type, int varId, int oldValue, int newValue,
                            long timestamp, int gameTick) {}

    public record ChatEntry(int messageType, String text, String playerName,
                            long timestamp, int gameTick) {}

    /** Session data for export/import */
    public record SessionData(List<LogEntry> actions, List<VarChange> vars, List<ChatEntry> chat,
                              long exportTime, String description) {}

    // ── Shared state ────────────────────────────────────────────────────

    private final List<LogEntry> actionLog = new CopyOnWriteArrayList<>();
    private final List<VarChange> varLog = new CopyOnWriteArrayList<>();
    private final List<ChatEntry> chatLog = new CopyOnWriteArrayList<>();

    private volatile boolean recording = true;
    private volatile boolean blocking;
    private volatile boolean lastBlockingSentToClient;
    private volatile boolean selectiveBlocking;
    private volatile boolean trackVars = true;
    private volatile boolean trackChat = true;
    private volatile int currentTick;

    // Export/import (render thread requests, onLoop() executes)
    private volatile boolean exportRequested;
    private volatile String importPath;
    private volatile String lastExportStatus = "";

    // Replay state
    private volatile boolean replaying;
    private volatile int replayIndex;
    private volatile long replayNextTime;
    private volatile float replaySpeed = 1.0f;

    // Entity/Interface inspector data (collected in onLoop, rendered on UI thread)
    private volatile List<Entity> nearbyNpcs = List.of();
    private volatile List<Entity> nearbyPlayers = List.of();
    private volatile List<Entity> nearbyObjects = List.of();
    private volatile List<OpenInterface> openInterfaces = List.of();
    private volatile int inspectInterfaceId = -1;
    private final ConcurrentHashMap<Integer, List<Component>> componentCache = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, String> componentTextCache = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, List<String>> componentOptionsCache = new ConcurrentHashMap<>();
    private volatile long lastInspectorUpdate;

    // Inventory diff tracking
    private volatile Map<Integer, Integer> lastInventorySnapshot = Map.of();
    private volatile String lastInventoryDiff = "";

    // ── UI state (render thread only) ───────────────────────────────────

    private int lastActionSize;
    private int lastVarSize;
    private int lastChatSize;
    private final ImString filterText = new ImString(256);
    private final ImString varFilterText = new ImString(256);
    private final ImString chatFilterText = new ImString(256);
    private final ImString entityFilterText = new ImString(256);
    private final ImString scriptClassName = new ImString("GeneratedScript", 128);
    private final boolean[] categoryFilters = {true, true, true, true, true, true, true};
    // indices: 0=NPC, 1=Object, 2=GroundItem, 3=Player, 4=Component, 5=Walk, 6=Other
    private final boolean[] selectiveBlockCategories = new boolean[7];
    private boolean useNamesForGeneration = true;
    private final float[] replaySpeedArr = {1.0f};
    private String[] sessionFiles = new String[0];
    private long lastSessionScan;

    // ── Lifecycle ───────────────────────────────────────────────────────

    @Override
    public void onStart(ScriptContext ctx) {
        this.ctx = ctx;
        log.info("Xapi action debugger v2.0 started");

        EventBus events = ctx.getEventBus();
        events.subscribe(ActionExecutedEvent.class, this::onActionExecuted);
        events.subscribe(TickEvent.class, this::onTick);
        events.subscribe(VarbitChangeEvent.class, this::onVarbitChange);
        events.subscribe(VarChangeEvent.class, this::onVarChange);
        events.subscribe(ChatMessageEvent.class, this::onChatMessage);

        try { Files.createDirectories(SESSION_DIR); } catch (IOException ignored) {}
    }

    // ── Event handlers (event thread — RPC safe) ────────────────────────

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
        if (trackVars) {
            varLog.add(new VarChange("varbit", e.getVarId(), e.getOldValue(), e.getNewValue(),
                    System.currentTimeMillis(), currentTick));
        }
    }

    private void onVarChange(VarChangeEvent e) {
        if (trackVars) {
            varLog.add(new VarChange("varp", e.getVarId(), e.getOldValue(), e.getNewValue(),
                    System.currentTimeMillis(), currentTick));
        }
    }

    private void onChatMessage(ChatMessageEvent e) {
        if (trackChat) {
            var msg = e.getMessage();
            chatLog.add(new ChatEntry(msg.messageType(), msg.text(),
                    msg.playerName(), System.currentTimeMillis(), currentTick));
        }
    }

    // ── Name resolution (runs on event/script thread — RPC safe) ─────────

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

        // Inspector data collection (every ~3 ticks / 1.8s)
        long now = System.currentTimeMillis();
        if (now - lastInspectorUpdate > 1800) {
            lastInspectorUpdate = now;
            collectInspectorData(api);
        }

        // Inventory diff
        collectInventoryDiff(api);

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
            if (session.vars() != null) { varLog.clear(); varLog.addAll(session.vars()); }
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
        try { nearbyNpcs = api.queryEntities(EntityFilter.builder().type("npc").sortByDistance(true).maxResults(100).build()); } catch (Exception ignored) { nearbyNpcs = List.of(); }
        try { nearbyPlayers = api.queryEntities(EntityFilter.builder().type("player").sortByDistance(true).maxResults(50).build()); } catch (Exception ignored) { nearbyPlayers = List.of(); }
        try { nearbyObjects = api.queryEntities(EntityFilter.builder().type("object").sortByDistance(true).maxResults(100).build()); } catch (Exception ignored) { nearbyObjects = List.of(); }
        try { openInterfaces = api.getOpenInterfaces(); } catch (Exception ignored) { openInterfaces = List.of(); }

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
            inspectInterfaceId = -1; // Reset — one-shot fetch
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

    // ═══════════════════════════════════════════════════════════════════
    // ══ UI (render thread — NO RPC calls) ═════════════════════════════
    // ═══════════════════════════════════════════════════════════════════

    private final ScriptUI ui = this::renderUI;

    @Override
    public ScriptUI getUI() { return ui; }

    private void renderUI() {
        renderControls();

        ImGui.spacing();
        ImGui.separator();
        ImGui.spacing();

        if (ImGui.beginTabBar("##xapi_tabs")) {
            if (ImGui.beginTabItem("Actions (" + actionLog.size() + ")")) {
                renderActionsTab();
                ImGui.endTabItem();
            }
            if (ImGui.beginTabItem("Variables (" + varLog.size() + ")")) {
                renderVariablesTab();
                ImGui.endTabItem();
            }
            if (ImGui.beginTabItem("Chat (" + chatLog.size() + ")")) {
                renderChatTab();
                ImGui.endTabItem();
            }
            if (ImGui.beginTabItem("Entities")) {
                renderEntitiesTab();
                ImGui.endTabItem();
            }
            if (ImGui.beginTabItem("Interfaces")) {
                renderInterfacesTab();
                ImGui.endTabItem();
            }
            ImGui.endTabBar();
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
            if (ImGui.button(wasRecording ? "Recording" : "Record")) recording = !recording;
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
            }
            if (wasBlocking) ImGui.popStyleColor(2);

            ImGui.sameLine();
            if (ImGui.button("Clear All")) {
                actionLog.clear(); varLog.clear(); chatLog.clear();
                ActionDebugger.get().clear();
                lastActionSize = 0; lastVarSize = 0; lastChatSize = 0;
                lastInventoryDiff = "";
            }

            ImGui.sameLine();
            ImGui.text("Tick: " + currentTick);

            if (blocking) { ImGui.sameLine(); ImGui.textColored(0.9f, 0.2f, 0.2f, 1f, "  ACTIONS BLOCKED"); }

            // Row 2: Tracking toggles
            boolean wTV = trackVars;
            if (ImGui.checkbox("Track Vars", wTV)) trackVars = !wTV;
            ImGui.sameLine();
            boolean wTC = trackChat;
            if (ImGui.checkbox("Track Chat", wTC)) trackChat = !wTC;

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
            if (ImGui.checkbox("Enable Selective Blocking", wasSB)) selectiveBlocking = !wasSB;
            if (selectiveBlocking) {
                ImGui.indent();
                if (ImGui.checkbox("NPC##sb", selectiveBlockCategories[0])) selectiveBlockCategories[0] = !selectiveBlockCategories[0];
                ImGui.sameLine();
                if (ImGui.checkbox("Object##sb", selectiveBlockCategories[1])) selectiveBlockCategories[1] = !selectiveBlockCategories[1];
                ImGui.sameLine();
                if (ImGui.checkbox("Ground Item##sb", selectiveBlockCategories[2])) selectiveBlockCategories[2] = !selectiveBlockCategories[2];
                ImGui.sameLine();
                if (ImGui.checkbox("Player##sb", selectiveBlockCategories[3])) selectiveBlockCategories[3] = !selectiveBlockCategories[3];
                ImGui.sameLine();
                if (ImGui.checkbox("Component##sb", selectiveBlockCategories[4])) selectiveBlockCategories[4] = !selectiveBlockCategories[4];
                ImGui.sameLine();
                if (ImGui.checkbox("Walk##sb", selectiveBlockCategories[5])) selectiveBlockCategories[5] = !selectiveBlockCategories[5];
                ImGui.sameLine();
                if (ImGui.checkbox("Other##sb", selectiveBlockCategories[6])) selectiveBlockCategories[6] = !selectiveBlockCategories[6];
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
                scanSessionFiles();
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
            if (ImGui.checkbox("Use Names (skeleton)", wasUseNames)) useNamesForGeneration = !wasUseNames;

            if (ImGui.button("Generate & Copy to Clipboard")) {
                generateAndCopyScript();
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

    private void scanSessionFiles() {
        try {
            if (Files.exists(SESSION_DIR)) {
                sessionFiles = Files.list(SESSION_DIR)
                        .filter(p -> p.toString().endsWith(".json"))
                        .map(p -> p.getFileName().toString())
                        .sorted(Comparator.reverseOrder())
                        .limit(10)
                        .toArray(String[]::new);
            }
        } catch (Exception ignored) {
            sessionFiles = new String[0];
        }
    }

    private void generateAndCopyScript() {
        List<ActionTranslator.ActionEntry> entries = new ArrayList<>();
        for (LogEntry e : actionLog) {
            entries.add(new ActionTranslator.ActionEntry(
                    e.actionId(), e.param1(), e.param2(), e.param3(),
                    e.timestamp(), e.entityName(), e.optionName()));
        }
        String code = ActionTranslator.generateScript(entries, scriptClassName.get(), useNamesForGeneration);
        ImGui.setClipboardText(code);
        lastExportStatus = "Script copied to clipboard (" + entries.size() + " steps)";
    }

    // ── Actions Tab ─────────────────────────────────────────────────────

    private void renderActionsTab() {
        // Filter bar
        ImGui.text("Filter:");
        ImGui.sameLine();
        ImGui.pushItemWidth(200);
        ImGui.inputText("##action_filter", filterText);
        ImGui.popItemWidth();

        ImGui.sameLine(); ImGui.text(" |");
        ImGui.sameLine();
        if (ImGui.checkbox("NPC##f", categoryFilters[0])) categoryFilters[0] = !categoryFilters[0];
        ImGui.sameLine();
        if (ImGui.checkbox("Obj##f", categoryFilters[1])) categoryFilters[1] = !categoryFilters[1];
        ImGui.sameLine();
        if (ImGui.checkbox("GI##f", categoryFilters[2])) categoryFilters[2] = !categoryFilters[2];
        ImGui.sameLine();
        if (ImGui.checkbox("Player##f", categoryFilters[3])) categoryFilters[3] = !categoryFilters[3];
        ImGui.sameLine();
        if (ImGui.checkbox("Comp##f", categoryFilters[4])) categoryFilters[4] = !categoryFilters[4];
        ImGui.sameLine();
        if (ImGui.checkbox("Walk##f", categoryFilters[5])) categoryFilters[5] = !categoryFilters[5];
        ImGui.sameLine();
        if (ImGui.checkbox("Other##f", categoryFilters[6])) categoryFilters[6] = !categoryFilters[6];

        List<LogEntry> entries = actionLog;
        String filter = filterText.get().toLowerCase();
        int flags = ImGuiTableFlags.Borders | ImGuiTableFlags.RowBg
                | ImGuiTableFlags.SizingStretchProp | ImGuiTableFlags.ScrollY
                | ImGuiTableFlags.Resizable;

        float tableHeight = ImGui.getContentRegionAvailY();
        if (ImGui.beginTable("##xapi_log", 9, flags, 0, tableHeight)) {
            ImGui.tableSetupColumn("#", 0, 0.3f);
            ImGui.tableSetupColumn("Time", 0, 0.7f);
            ImGui.tableSetupColumn("Tick", 0, 0.4f);
            ImGui.tableSetupColumn("Action", 0, 0.5f);
            ImGui.tableSetupColumn("Target", 0, 1.5f);
            ImGui.tableSetupColumn("P1", 0, 0.4f);
            ImGui.tableSetupColumn("P2", 0, 0.4f);
            ImGui.tableSetupColumn("P3", 0, 0.4f);
            ImGui.tableSetupColumn("Code (click to copy)", 0, 3.5f);
            ImGui.tableSetupScrollFreeze(0, 1);
            ImGui.tableHeadersRow();

            int prevTick = -1;
            for (int i = 0; i < entries.size(); i++) {
                LogEntry entry = entries.get(i);
                if (!passesCategory(entry.actionId())) continue;
                if (!filter.isEmpty()) {
                    String actionName = ActionTypes.nameOf(entry.actionId()).toLowerCase();
                    String target = buildTargetText(entry).toLowerCase();
                    String params = entry.param1() + " " + entry.param2() + " " + entry.param3();
                    if (!actionName.contains(filter) && !target.contains(filter) && !params.contains(filter)) continue;
                }

                ImGui.tableNextRow();
                if (entry.gameTick() == prevTick && prevTick > 0) {
                    ImGui.tableSetBgColor(0, ImGui.colorConvertFloat4ToU32(0.3f, 0.3f, 0.5f, 0.15f));
                }
                prevTick = entry.gameTick();

                if (entry.wasBlocked()) ImGui.pushStyleColor(ImGuiCol.Text, 0.9f, 0.3f, 0.3f, 0.9f);

                ImGui.tableSetColumnIndex(0);
                ImGui.text(String.valueOf(i + 1));

                ImGui.tableSetColumnIndex(1);
                ImGui.text(LocalTime.ofInstant(Instant.ofEpochMilli(entry.timestamp()), ZoneId.systemDefault()).format(TIME_FMT));

                ImGui.tableSetColumnIndex(2);
                if (i > 0) {
                    int delta = entry.gameTick() - entries.get(i - 1).gameTick();
                    ImGui.text(entry.gameTick() + " (+" + delta + ")");
                } else {
                    ImGui.text(String.valueOf(entry.gameTick()));
                }

                ImGui.tableSetColumnIndex(3);
                ImGui.text(ActionTypes.nameOf(entry.actionId()));

                ImGui.tableSetColumnIndex(4);
                String target = buildTargetText(entry);
                ImGui.text(target);
                if (ImGui.isItemHovered() && entry.playerX() > 0) {
                    ImGui.setTooltip(String.format("Player: (%d, %d) plane:%d anim:%d %s",
                            entry.playerX(), entry.playerY(), entry.playerPlane(),
                            entry.playerAnim(), entry.playerMoving() ? "MOVING" : "idle"));
                }

                ImGui.tableSetColumnIndex(5); ImGui.text(String.valueOf(entry.param1()));
                ImGui.tableSetColumnIndex(6); ImGui.text(String.valueOf(entry.param2()));
                ImGui.tableSetColumnIndex(7); ImGui.text(String.valueOf(entry.param3()));

                ImGui.tableSetColumnIndex(8);
                renderCodeColumn(entry, i);

                if (entry.wasBlocked()) ImGui.popStyleColor();
            }

            if (entries.size() > lastActionSize) {
                ImGui.setScrollHereY(1.0f);
                lastActionSize = entries.size();
            }
            ImGui.endTable();
        }
    }

    private boolean passesCategory(int actionId) {
        if (findSlot(ActionTypes.NPC_OPTIONS, actionId) > 0) return categoryFilters[0];
        if (findSlot(ActionTypes.OBJECT_OPTIONS, actionId) > 0) return categoryFilters[1];
        if (findSlot(ActionTypes.GROUND_ITEM_OPTIONS, actionId) > 0) return categoryFilters[2];
        if (findSlot(ActionTypes.PLAYER_OPTIONS, actionId) > 0) return categoryFilters[3];
        if (actionId == ActionTypes.COMPONENT || actionId == ActionTypes.SELECT_COMPONENT_ITEM) return categoryFilters[4];
        if (actionId == ActionTypes.WALK) return categoryFilters[5];
        return categoryFilters[6];
    }

    private void renderCodeColumn(LogEntry entry, int i) {
        String fullCode = ActionTranslator.toCode(
                entry.actionId(), entry.param1(), entry.param2(), entry.param3(),
                entry.entityName(), entry.optionName());
        int newline = fullCode.indexOf('\n');
        String human = buildTargetText(entry);
        boolean hasHuman = !human.isEmpty();

        if (newline > 0) {
            String highLevel = fullCode.substring(0, newline);
            String rawLine = fullCode.substring(newline + 1);
            String allLines = hasHuman ? "// " + human + "\n" + highLevel + "\n" + rawLine : highLevel + "\n" + rawLine;

            if (hasHuman) {
                ImGui.pushStyleColor(ImGuiCol.Text, 1f, 0.8f, 0.3f, 1f);
                if (ImGui.selectable(human + "##hum_" + i)) {
                    ImGui.setClipboardText(human + "\n// " + highLevel + "\n// " + rawLine);
                }
                ImGui.popStyleColor();
                if (ImGui.isItemHovered()) ImGui.setTooltip("Copy all — this line active, others commented");
            }

            ImGui.pushStyleColor(ImGuiCol.Text, 0.4f, 0.9f, 0.5f, 1f);
            if (ImGui.selectable(highLevel + "##hi_" + i)) {
                String copied = hasHuman ? "// " + human + "\n" : "";
                ImGui.setClipboardText(copied + highLevel + "\n// " + rawLine);
            }
            ImGui.popStyleColor();
            if (ImGui.isItemHovered()) ImGui.setTooltip("Copy all — this line active, others commented");

            ImGui.pushStyleColor(ImGuiCol.Text, 0.6f, 0.6f, 0.6f, 0.9f);
            if (ImGui.selectable(rawLine + "##raw_" + i)) {
                String copied = hasHuman ? "// " + human + "\n" : "";
                ImGui.setClipboardText(copied + "// " + highLevel + "\n" + rawLine);
            }
            ImGui.popStyleColor();
            if (ImGui.isItemHovered()) ImGui.setTooltip("Copy all — this line active, others commented");

            ImGui.pushStyleColor(ImGuiCol.Text, 0.5f, 0.5f, 0.8f, 0.9f);
            if (ImGui.smallButton("Copy All##all_" + i)) ImGui.setClipboardText(allLines);
            ImGui.popStyleColor();
            if (ImGui.isItemHovered()) ImGui.setTooltip("Copy all lines uncommented");
        } else {
            if (ImGui.selectable(fullCode + "##code_" + i)) ImGui.setClipboardText(fullCode);
            if (ImGui.isItemHovered()) ImGui.setTooltip("Click to copy");
        }
    }

    // ── Variables Tab ───────────────────────────────────────────────────

    private void renderVariablesTab() {
        ImGui.text("Watchlist (comma-separated IDs):");
        ImGui.sameLine();
        ImGui.pushItemWidth(300);
        ImGui.inputText("##var_filter", varFilterText);
        ImGui.popItemWidth();
        ImGui.sameLine();
        if (ImGui.button("Clear Vars")) { varLog.clear(); lastVarSize = 0; }

        List<VarChange> vars = varLog;
        int[] watchIds = parseWatchIds(varFilterText.get().trim());

        int flags = ImGuiTableFlags.Borders | ImGuiTableFlags.RowBg
                | ImGuiTableFlags.SizingStretchProp | ImGuiTableFlags.ScrollY | ImGuiTableFlags.Resizable;
        float tableHeight = ImGui.getContentRegionAvailY();
        if (ImGui.beginTable("##xapi_vars", 8, flags, 0, tableHeight)) {
            ImGui.tableSetupColumn("#", 0, 0.3f);
            ImGui.tableSetupColumn("Time", 0, 0.8f);
            ImGui.tableSetupColumn("Tick", 0, 0.4f);
            ImGui.tableSetupColumn("Type", 0, 0.4f);
            ImGui.tableSetupColumn("VarID", 0, 0.5f);
            ImGui.tableSetupColumn("Old", 0, 0.5f);
            ImGui.tableSetupColumn("New", 0, 0.5f);
            ImGui.tableSetupColumn("Delta", 0, 0.5f);
            ImGui.tableSetupScrollFreeze(0, 1);
            ImGui.tableHeadersRow();

            int displayed = 0;
            for (int i = 0; i < vars.size(); i++) {
                VarChange vc = vars.get(i);
                if (watchIds.length > 0 && !inWatchlist(vc.varId(), watchIds)) continue;
                displayed++;
                ImGui.tableNextRow();

                if (hasActionOnTick(vc.gameTick())) {
                    ImGui.tableSetBgColor(0, ImGui.colorConvertFloat4ToU32(0.4f, 0.4f, 0.2f, 0.2f));
                }

                ImGui.tableSetColumnIndex(0); ImGui.text(String.valueOf(displayed));
                ImGui.tableSetColumnIndex(1); ImGui.text(LocalTime.ofInstant(Instant.ofEpochMilli(vc.timestamp()), ZoneId.systemDefault()).format(TIME_FMT));
                ImGui.tableSetColumnIndex(2); ImGui.text(String.valueOf(vc.gameTick()));

                ImGui.tableSetColumnIndex(3);
                if ("varbit".equals(vc.type())) ImGui.textColored(0.4f, 0.8f, 1f, 1f, vc.type());
                else ImGui.textColored(1f, 0.7f, 0.4f, 1f, vc.type());

                ImGui.tableSetColumnIndex(4);
                String varCode = "varbit".equals(vc.type()) ? "api.getVarbit(" + vc.varId() + ")" : "api.getVarp(" + vc.varId() + ")";
                ImGui.pushStyleColor(ImGuiCol.Text, 0.4f, 0.9f, 0.5f, 1f);
                if (ImGui.selectable(String.valueOf(vc.varId()) + "##var_" + i)) ImGui.setClipboardText(varCode);
                ImGui.popStyleColor();
                if (ImGui.isItemHovered()) ImGui.setTooltip("Click to copy: " + varCode);

                ImGui.tableSetColumnIndex(5); ImGui.text(String.valueOf(vc.oldValue()));
                ImGui.tableSetColumnIndex(6);
                int delta = vc.newValue() - vc.oldValue();
                if (delta > 0) ImGui.textColored(0.3f, 0.9f, 0.3f, 1f, String.valueOf(vc.newValue()));
                else if (delta < 0) ImGui.textColored(0.9f, 0.3f, 0.3f, 1f, String.valueOf(vc.newValue()));
                else ImGui.text(String.valueOf(vc.newValue()));

                ImGui.tableSetColumnIndex(7);
                ImGui.text(delta >= 0 ? "+" + delta : String.valueOf(delta));
            }

            if (vars.size() > lastVarSize) { ImGui.setScrollHereY(1.0f); lastVarSize = vars.size(); }
            ImGui.endTable();
        }
    }

    // ── Chat Tab ────────────────────────────────────────────────────────

    private void renderChatTab() {
        ImGui.text("Filter:");
        ImGui.sameLine();
        ImGui.pushItemWidth(200);
        ImGui.inputText("##chat_filter", chatFilterText);
        ImGui.popItemWidth();
        ImGui.sameLine();
        if (ImGui.button("Clear Chat")) { chatLog.clear(); lastChatSize = 0; }

        List<ChatEntry> chats = chatLog;
        String filter = chatFilterText.get().toLowerCase();
        int flags = ImGuiTableFlags.Borders | ImGuiTableFlags.RowBg
                | ImGuiTableFlags.SizingStretchProp | ImGuiTableFlags.ScrollY | ImGuiTableFlags.Resizable;
        float tableHeight = ImGui.getContentRegionAvailY();
        if (ImGui.beginTable("##xapi_chat", 5, flags, 0, tableHeight)) {
            ImGui.tableSetupColumn("#", 0, 0.3f);
            ImGui.tableSetupColumn("Time", 0, 0.7f);
            ImGui.tableSetupColumn("Tick", 0, 0.4f);
            ImGui.tableSetupColumn("Type", 0, 0.3f);
            ImGui.tableSetupColumn("Message", 0, 4f);
            ImGui.tableSetupScrollFreeze(0, 1);
            ImGui.tableHeadersRow();

            int displayed = 0;
            for (int i = 0; i < chats.size(); i++) {
                ChatEntry ce = chats.get(i);
                if (!filter.isEmpty()) {
                    String text = (ce.text() != null ? ce.text() : "").toLowerCase();
                    String player = (ce.playerName() != null ? ce.playerName() : "").toLowerCase();
                    if (!text.contains(filter) && !player.contains(filter)) continue;
                }
                displayed++;
                ImGui.tableNextRow();
                if (hasActionOnTick(ce.gameTick())) ImGui.tableSetBgColor(0, ImGui.colorConvertFloat4ToU32(0.4f, 0.4f, 0.2f, 0.2f));

                ImGui.tableSetColumnIndex(0); ImGui.text(String.valueOf(displayed));
                ImGui.tableSetColumnIndex(1); ImGui.text(LocalTime.ofInstant(Instant.ofEpochMilli(ce.timestamp()), ZoneId.systemDefault()).format(TIME_FMT));
                ImGui.tableSetColumnIndex(2); ImGui.text(String.valueOf(ce.gameTick()));
                ImGui.tableSetColumnIndex(3); ImGui.text(String.valueOf(ce.messageType()));
                ImGui.tableSetColumnIndex(4);
                String msgText = ce.playerName() != null && !ce.playerName().isEmpty()
                        ? ce.playerName() + ": " + ce.text() : ce.text() != null ? ce.text() : "";
                ImGui.textColored(0.5f, 0.9f, 1f, 1f, msgText);
                if (ImGui.isItemHovered() && ImGui.isMouseClicked(0)) ImGui.setClipboardText(msgText);
                if (ImGui.isItemHovered()) ImGui.setTooltip("Click to copy");
            }

            if (chats.size() > lastChatSize) { ImGui.setScrollHereY(1.0f); lastChatSize = chats.size(); }
            ImGui.endTable();
        }
    }

    // ── Entities Tab ────────────────────────────────────────────────────

    private void renderEntitiesTab() {
        ImGui.text("Filter:");
        ImGui.sameLine();
        ImGui.pushItemWidth(200);
        ImGui.inputText("##entity_filter", entityFilterText);
        ImGui.popItemWidth();
        ImGui.sameLine();
        ImGui.textColored(0.6f, 0.6f, 0.6f, 1f, "(updates every ~2s)");

        String filter = entityFilterText.get().toLowerCase();

        if (ImGui.beginTabBar("##entity_sub_tabs")) {
            if (ImGui.beginTabItem("NPCs (" + nearbyNpcs.size() + ")")) {
                renderEntityTable(nearbyNpcs, "npc", filter);
                ImGui.endTabItem();
            }
            if (ImGui.beginTabItem("Players (" + nearbyPlayers.size() + ")")) {
                renderEntityTable(nearbyPlayers, "player", filter);
                ImGui.endTabItem();
            }
            if (ImGui.beginTabItem("Objects (" + nearbyObjects.size() + ")")) {
                renderEntityTable(nearbyObjects, "object", filter);
                ImGui.endTabItem();
            }
            ImGui.endTabBar();
        }
    }

    private void renderEntityTable(List<Entity> entities, String type, String filter) {
        int flags = ImGuiTableFlags.Borders | ImGuiTableFlags.RowBg
                | ImGuiTableFlags.SizingStretchProp | ImGuiTableFlags.ScrollY | ImGuiTableFlags.Resizable;
        float tableHeight = ImGui.getContentRegionAvailY();
        if (ImGui.beginTable("##entities_" + type, 6, flags, 0, tableHeight)) {
            ImGui.tableSetupColumn("Name", 0, 1.5f);
            ImGui.tableSetupColumn("TypeId", 0, 0.5f);
            ImGui.tableSetupColumn("Index", 0, 0.5f);
            ImGui.tableSetupColumn("Position", 0, 1f);
            ImGui.tableSetupColumn("Moving", 0, 0.4f);
            ImGui.tableSetupColumn("Code", 0, 2f);
            ImGui.tableSetupScrollFreeze(0, 1);
            ImGui.tableHeadersRow();

            for (int i = 0; i < entities.size(); i++) {
                Entity e = entities.get(i);
                String name = e.name() != null ? e.name() : "";
                if (!filter.isEmpty() && !name.toLowerCase().contains(filter)
                        && !String.valueOf(e.typeId()).contains(filter)) continue;

                ImGui.tableNextRow();
                ImGui.tableSetColumnIndex(0); ImGui.text(name);
                ImGui.tableSetColumnIndex(1); ImGui.text(String.valueOf(e.typeId()));
                ImGui.tableSetColumnIndex(2); ImGui.text(String.valueOf(e.serverIndex()));
                ImGui.tableSetColumnIndex(3); ImGui.text("(" + e.tileX() + ", " + e.tileY() + ")");
                ImGui.tableSetColumnIndex(4); ImGui.text(e.isMoving() ? "Y" : "");

                ImGui.tableSetColumnIndex(5);
                String queryCode;
                if ("npc".equals(type)) {
                    queryCode = name.isEmpty()
                            ? "api.queryEntities(EntityFilter.builder().type(\"npc\").typeId(" + e.typeId() + ").maxResults(1).build())"
                            : "api.queryEntities(EntityFilter.builder().type(\"npc\").namePattern(\"" + name + "\").maxResults(1).build())";
                } else if ("player".equals(type)) {
                    queryCode = "api.queryEntities(EntityFilter.builder().type(\"player\").namePattern(\"" + name + "\").maxResults(1).build())";
                } else {
                    queryCode = name.isEmpty()
                            ? "api.queryEntities(EntityFilter.builder().type(\"object\").typeId(" + e.typeId() + ").maxResults(1).build())"
                            : "api.queryEntities(EntityFilter.builder().type(\"object\").namePattern(\"" + name + "\").maxResults(1).build())";
                }

                ImGui.pushStyleColor(ImGuiCol.Text, 0.4f, 0.9f, 0.5f, 1f);
                String shortCode = type + "s.query().name(\"" + name + "\").first()";
                if (ImGui.selectable(shortCode + "##ent_" + type + "_" + i)) {
                    ImGui.setClipboardText(queryCode);
                }
                ImGui.popStyleColor();
                if (ImGui.isItemHovered()) ImGui.setTooltip("Click to copy full query");
            }
            ImGui.endTable();
        }
    }

    // ── Interfaces Tab ──────────────────────────────────────────────────

    private void renderInterfacesTab() {
        ImGui.textColored(0.6f, 0.6f, 0.6f, 1f, "Click an interface to load its components (updates every ~2s)");

        List<OpenInterface> ifaces = openInterfaces;
        if (ifaces.isEmpty()) {
            ImGui.text("No open interfaces detected.");
            return;
        }

        for (OpenInterface iface : ifaces) {
            int ifaceId = iface.interfaceId();
            boolean open = ImGui.treeNode("Interface " + ifaceId + "##iface_" + ifaceId);
            if (ImGui.isItemClicked()) {
                inspectInterfaceId = ifaceId;
            }

            if (open) {
                List<Component> children = componentCache.get(ifaceId);
                if (children == null || children.isEmpty()) {
                    ImGui.textColored(0.7f, 0.7f, 0.7f, 1f, "Click to load components...");
                } else {
                    int flags = ImGuiTableFlags.Borders | ImGuiTableFlags.RowBg
                            | ImGuiTableFlags.SizingStretchProp | ImGuiTableFlags.Resizable;
                    if (ImGui.beginTable("##comps_" + ifaceId, 7, flags)) {
                        ImGui.tableSetupColumn("CompId", 0, 0.4f);
                        ImGui.tableSetupColumn("SubId", 0, 0.4f);
                        ImGui.tableSetupColumn("Type", 0, 0.3f);
                        ImGui.tableSetupColumn("ItemId", 0, 0.4f);
                        ImGui.tableSetupColumn("Sprite", 0, 0.4f);
                        ImGui.tableSetupColumn("Text/Options", 0, 1.5f);
                        ImGui.tableSetupColumn("Code", 0, 2f);
                        ImGui.tableSetupScrollFreeze(0, 1);
                        ImGui.tableHeadersRow();

                        for (int i = 0; i < children.size(); i++) {
                            Component c = children.get(i);
                            String key = c.interfaceId() + ":" + c.componentId();

                            ImGui.tableNextRow();
                            ImGui.tableSetColumnIndex(0); ImGui.text(String.valueOf(c.componentId()));
                            ImGui.tableSetColumnIndex(1); ImGui.text(String.valueOf(c.subComponentId()));
                            ImGui.tableSetColumnIndex(2); ImGui.text(String.valueOf(c.type()));
                            ImGui.tableSetColumnIndex(3);
                            if (c.itemId() > 0) ImGui.text(String.valueOf(c.itemId()));
                            ImGui.tableSetColumnIndex(4);
                            if (c.spriteId() > 0) ImGui.text(String.valueOf(c.spriteId()));

                            ImGui.tableSetColumnIndex(5);
                            String text = componentTextCache.getOrDefault(key, "");
                            List<String> opts = componentOptionsCache.getOrDefault(key, List.of());
                            if (!text.isEmpty()) ImGui.text(text);
                            if (!opts.isEmpty()) {
                                ImGui.textColored(0.7f, 0.7f, 0.4f, 1f, String.join(" | ", opts));
                            }

                            ImGui.tableSetColumnIndex(6);
                            String compCode = "api.queryComponents(ComponentFilter.builder().interfaceId("
                                    + c.interfaceId() + ").componentId(" + c.componentId() + ").build())";
                            ImGui.pushStyleColor(ImGuiCol.Text, 0.4f, 0.9f, 0.5f, 1f);
                            if (ImGui.selectable("query##comp_" + ifaceId + "_" + i)) {
                                ImGui.setClipboardText(compCode);
                            }
                            ImGui.popStyleColor();
                            if (ImGui.isItemHovered()) ImGui.setTooltip(compCode);
                        }
                        ImGui.endTable();
                    }
                }
                ImGui.treePop();
            }
        }
    }

    // ── Helpers ──────────────────────────────────────────────────────────

    private String buildTargetText(LogEntry entry) {
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

    private int[] parseWatchIds(String input) {
        if (input == null || input.isEmpty()) return new int[0];
        String[] parts = input.split("[,\\s]+");
        int[] ids = new int[parts.length];
        int count = 0;
        for (String part : parts) {
            try { ids[count++] = Integer.parseInt(part.trim()); } catch (NumberFormatException ignored) {}
        }
        if (count == 0) return new int[0];
        int[] result = new int[count];
        System.arraycopy(ids, 0, result, 0, count);
        return result;
    }

    private boolean inWatchlist(int varId, int[] watchIds) {
        for (int id : watchIds) { if (id == varId) return true; }
        return false;
    }

    private boolean hasActionOnTick(int tick) {
        List<LogEntry> entries = actionLog;
        for (int i = entries.size() - 1; i >= 0 && i >= entries.size() - 50; i--) {
            if (entries.get(i).gameTick() == tick) return true;
        }
        return false;
    }
}
