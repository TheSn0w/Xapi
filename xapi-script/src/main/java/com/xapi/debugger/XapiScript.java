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
import com.botwithus.bot.api.inventory.Smithing;
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
import com.botwithus.bot.api.model.Component;
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

    // ── Shared state ─────────────────────────────────────────────────────
    final XapiState state = new XapiState();

    // ── Tab instances ────────────────────────────────────────────────────

    private ActionsTab actionsTab;
    private VariablesTab variablesTab;
    private ChatTab chatTab;
    private PlayerTab playerTab;
    private EntitiesTab entitiesTab;
    private InterfacesTab interfacesTab;
    private InventoryTab inventoryTab;
    private ProductionTab productionTab;
    private SmithingTab smithingTab;

    // ── Helper instances ─────────────────────────────────────────────────
    private NameResolver nameResolver;
    private MiniMenuMatcher miniMenuMatcher;
    private SessionManager sessionManager;
    private ReplayController replayController;
    private InspectorCollector inspectorCollector;
    private DataPoller dataPoller;
    private InventoryTracker inventoryTracker;

    // ── Private fields (not shared with tabs) ────────────────────────────
    private volatile boolean lastBlockingSentToClient;

    // Mini menu hash for change detection
    private volatile int lastMiniMenuHash;
    private volatile long lastInspectorUpdate;

    // UI rendering state (private)
    private int selectedTab = 0;
    private long lastSessionScan;

    // ── Lifecycle ───────────────────────────────────────────────────────

    @Override
    public void onStart(ScriptContext ctx) {
        state.ctx = ctx;
        state.actionDebugger = ActionDebugger.forConnection(ctx.getConnectionName());
        log.info("Xapi action debugger v2.1 started");

        // Initialize helpers
        nameResolver = new NameResolver(state);
        miniMenuMatcher = new MiniMenuMatcher(state, nameResolver);
        sessionManager = new SessionManager(state);
        replayController = new ReplayController(state);
        inspectorCollector = new InspectorCollector(state);
        inventoryTracker = new InventoryTracker(state);
        dataPoller = new DataPoller(state, nameResolver, miniMenuMatcher, inspectorCollector, inventoryTracker);

        // Create tab instances
        actionsTab = new ActionsTab(state);
        variablesTab = new VariablesTab(state);
        chatTab = new ChatTab(state);
        playerTab = new PlayerTab(state);
        entitiesTab = new EntitiesTab(state);
        interfacesTab = new InterfacesTab(state);
        inventoryTab = new InventoryTab(state);
        productionTab = new ProductionTab(state);
        smithingTab = new SmithingTab(state);

        // Initialize entity facades
        GameAPI gameApi = ctx.getGameAPI();
        state.npcs = new Npcs(gameApi);
        state.players = new Players(gameApi);
        state.sceneObjects = new SceneObjects(gameApi);

        // Load persistent settings and restore last session
        sessionManager.loadSettings();
        sessionManager.loadAutoSave();

        // Load offline game cache (locations, items, NPCs) for timing-proof name resolution.
        // Runs on a background thread so script start isn't blocked by ~96MB of JSON parsing.
        Thread.ofVirtual().name("xapi-cache-loader").start(() -> {
            try {
                state.gameCache.load();
            } catch (Exception e) {
                log.error("Failed to load game cache: {}", e.getMessage());
            }
        });

        // Force-sync blocking state to game client on start (selective blocking is per-action, not client-wide)
        try {
            gameApi.setActionsBlocked(state.blocking);
            lastBlockingSentToClient = state.blocking;
            log.info("Initial blocking state synced to client: {}", state.blocking);
        } catch (Exception e) {
            log.warn("Failed to sync initial blocking state: {}", e.getMessage());
        }

        EventBus events = ctx.getEventBus();
        events.subscribe(ActionExecutedEvent.class, this::onActionExecuted);
        events.subscribe(TickEvent.class, this::onTick);
        events.subscribe(VarbitChangeEvent.class, this::onVarbitChange);
        events.subscribe(VarChangeEvent.class, this::onVarChange);
        events.subscribe(ChatMessageEvent.class, this::onChatMessage);

        try { Files.createDirectories(XapiState.SESSION_DIR); } catch (IOException ignored) {}

        // Last-resort save for hard kills (IntelliJ stop, SIGTERM)
        sessionManager.installShutdownHook();
        log.info("onStart completed successfully");
    }

    // ── Event handlers (event thread -- RPC safe) ────────────────────────

    private void onTick(TickEvent e) {
        state.currentTick = e.getTick();
        // Invalidate per-tick entity caches
        nameResolver.invalidateTickCache();
        // Data collection is done in onLoop() to avoid backpressure —
        // onTick fires every 600ms regardless of whether previous RPC calls finished,
        // whereas onLoop naturally waits for the current iteration to complete.
    }

    private void onActionExecuted(ActionExecutedEvent e) {
        if (state.recording || state.blocking) {
            log.debug("ACTION EVENT: id={} p1={} p2={} p3={} type={}",
                    e.getActionId(), e.getParam1(), e.getParam2(), e.getParam3(),
                    ActionTypes.nameOf(e.getActionId()));

            // 0. Capture the mini menu RIGHT NOW — cursor is still over the clicked item.
            //    This is the most reliable source for CS2-dynamic option text (Withdraw-1, Deposit-1, etc.)
            //    because getComponentOptions() returns EMPTY for container widgets.
            try {
                var liveMenu = state.ctx.getGameAPI().getMiniMenu();
                if (liveMenu != null && !liveMenu.isEmpty()) {
                    state.lastMiniMenu = liveMenu;
                    // Also pre-resolve all entries while data is fresh
                    for (var entry : liveMenu) {
                        var mk = new XapiState.ActionCacheKey(entry.actionId(), entry.param1(), entry.param2(), entry.param3());
                        String optText = entry.optionText();
                        String entName = null;
                        if (entry.itemId() > 0) {
                            entName = nameResolver.lookupItemName(state.ctx.getGameAPI(), entry.itemId());
                        }
                        state.preResolvedActions.put(mk, new String[]{entName, optText});
                    }
                }
            } catch (Exception ignored) {}

            // 1. Check pre-resolved cache (now includes the live menu we just captured)
            var aKey = new XapiState.ActionCacheKey(e.getActionId(), e.getParam1(), e.getParam2(), e.getParam3());
            String[] preResolved = state.preResolvedActions.get(aKey);

            // 2. Live resolution as fallback (works for objects, NPCs, or cache miss)
            String[] names = nameResolver.resolveNames(state.ctx.getGameAPI(),
                    e.getActionId(), e.getParam1(), e.getParam2(), e.getParam3());
            log.debug("RESOLVED: entity='{}' option='{}'", names[0], names[1]);

            // 3. Pre-resolved data fills gaps (entity name, option text from mini menu)
            if (preResolved != null) {
                log.debug("PRE-RESOLVED: entity='{}' option='{}'", preResolved[0], preResolved[1]);
                if (names[0] == null || names[0].isEmpty()) names[0] = preResolved[0];
                if (names[1] == null || names[1].isEmpty()) names[1] = preResolved[1];
            }

            // 4. Mini menu match as additional fallback (searches current + recent snapshots)
            if (names[1] == null || names[1].isEmpty() || names[0] == null || names[0].isEmpty()) {
                String[] menuMatch = miniMenuMatcher.matchMiniMenu(e.getActionId(), e.getParam1(), e.getParam2(), e.getParam3());
                if (menuMatch != null) {
                    log.debug("MENU MATCH: optionText='{}' itemName='{}'", menuMatch[0], menuMatch[1]);
                    if (names[1] == null || names[1].isEmpty()) names[1] = menuMatch[0];
                    if (names[0] == null || names[0].isEmpty()) names[0] = menuMatch[1];
                }
            }

            // 5. For component actions where entity is still null, use interface name as context
            if ((names[0] == null || names[0].isEmpty()) && NameResolver.isComponentAction(e.getActionId()) && state.gameCache.isLoaded()) {
                int packed = e.getParam3();
                int ifaceId = packed >>> 16;
                names[0] = state.gameCache.getInterfaceName(ifaceId);
            }
            log.debug("FINAL NAMES: entity='{}' option='{}'", names[0], names[1]);

            int px = 0, py = 0, pp = 0, pa = -1;
            boolean pm = false;
            try {
                LocalPlayer lp = state.ctx.getGameAPI().getLocalPlayer();
                if (lp != null) {
                    px = lp.tileX(); py = lp.tileY(); pp = lp.plane();
                    pa = lp.animationId(); pm = lp.isMoving();
                }
            } catch (Exception ignored) {}

            state.actionLog.add(new LogEntry(
                    e.getActionId(), e.getParam1(), e.getParam2(), e.getParam3(),
                    System.currentTimeMillis(), state.currentTick, false, "client",
                    names[0], names[1], px, py, pp, pa, pm
            ));
            state.actionsDirty = true;

            // Build interaction snapshot from cached state (no RPC calls)
            try {
                BackpackSnapshot bp = state.cachedBackpack;
                int openIface = inventoryTracker.resolveTopInterfaceId();
                TriggerSignals triggers = inventoryTracker.computeTriggers(bp, pa, pm);
                IntentHypothesis intent = IntentEngine.infer(
                        e.getActionId(), names[0], names[1], bp, triggers, openIface);
                state.snapshotLog.add(new ActionSnapshot(bp, triggers, intent, openIface));

                inventoryTracker.updatePreviousState(bp, openIface, pa, pm);
            } catch (Exception ignored) {
                state.snapshotLog.add(null); // maintain index alignment
            }
        }
    }

    private void onVarbitChange(VarbitChangeEvent e) {
        try {
            VarChange vc = new VarChange("varbit", e.getVarId(), e.getOldValue(), e.getNewValue(),
                    System.currentTimeMillis(), state.currentTick);
            state.varLog.add(vc);
            state.varsByTick.computeIfAbsent(state.currentTick, k -> new CopyOnWriteArrayList<>()).add(vc);
            state.varChangeCount.merge("varbit:" + e.getVarId(), 1, Integer::sum);
        } catch (Exception ex) { log.debug("varbit event error: {}", ex.getMessage()); }
    }

    private void onVarChange(VarChangeEvent e) {
        try {
            VarChange vc = new VarChange("varp", e.getVarId(), e.getOldValue(), e.getNewValue(),
                    System.currentTimeMillis(), state.currentTick);
            state.varLog.add(vc);
            state.varsByTick.computeIfAbsent(state.currentTick, k -> new CopyOnWriteArrayList<>()).add(vc);
            state.varChangeCount.merge("varp:" + e.getVarId(), 1, Integer::sum);
        } catch (Exception ex) { log.debug("varp event error: {}", ex.getMessage()); }
    }

    private void onChatMessage(ChatMessageEvent e) {
        try {
            var msg = e.getMessage();
            String text = msg.text() != null ? msg.text().replaceAll("<img=\\d+>", "").replaceAll("<col=[0-9a-fA-F]+>", "").replaceAll("</col>", "").trim() : "";
            state.chatLog.add(new ChatEntry(msg.messageType(), text,
                    msg.playerName(), System.currentTimeMillis(), state.currentTick));
        } catch (Exception ex) { log.debug("chat event error: {}", ex.getMessage()); }
    }

    // ── Script loop ──────────────────────────────────────────────────────

    @Override
    public int onLoop() {
        try {
            return doLoop();
        } catch (Throwable e) {
            log.error("onLoop crashed: {}", e.getMessage(), e);
            return 600;
        }
    }

    private int doLoop() {
        GameAPI api = state.ctx.getGameAPI();
        ActionDebugger debugger = state.actionDebugger;
        debugger.setRecording(state.recording);
        debugger.setBlocking(state.blocking);

        // Sync state.blocking to game client
        if (state.blocking != lastBlockingSentToClient) {
            try {
                api.setActionsBlocked(state.blocking);
                lastBlockingSentToClient = state.blocking;
                log.info("Actions blocked on client: {}", state.blocking);
            } catch (Exception e) {
                log.warn("Failed to sync blocking state to client: {}", e.getMessage());
            }
        }

        long now = System.currentTimeMillis();

        // Export session
        if (state.exportRequested) {
            state.exportRequested = false;
            sessionManager.doExport();
        }

        // Import session
        String impPath = state.importPath;
        if (impPath != null) {
            state.importPath = null;
            sessionManager.doImport(impPath);
        }

        // Replay
        if (state.replaying) {
            replayController.doReplayStep(api);
        }

        // Lightweight data collection (every loop iteration — ~600ms)
        try { state.localPlayerData = api.getLocalPlayer(); } catch (Exception ignored) {}
        try { state.playerStats = api.getPlayerStats(); } catch (Exception ignored) {}
        try { state.openInterfaces = api.getOpenInterfaces(); } catch (Exception ignored) { state.openInterfaces = List.of(); }

        // Pre-cache component options for open interfaces (timing-proof).
        // Caches at most ONE new interface per loop iteration to keep the loop responsive.
        // This ensures onActionExecuted finds cached data even if the interface closes on click.
        try {
            Set<Integer> currentOpen = new java.util.HashSet<>();
            for (var oi : state.openInterfaces) currentOpen.add(oi.interfaceId());
            for (int ifaceId : currentOpen) {
                if (state.cachedInterfaceOptions.add(ifaceId)) { // Only on first open
                    try {
                        List<Component> comps = api.queryComponents(
                                ComponentFilter.builder().interfaceId(ifaceId).build());
                        if (comps != null && comps.size() <= 300) { // Skip large data interfaces
                            for (Component c : comps) {
                                String key = c.interfaceId() + ":" + c.componentId();
                                if (!state.componentOptionsCache.containsKey(key)) {
                                    try {
                                        List<String> opts = api.getComponentOptions(c.interfaceId(), c.componentId());
                                        if (opts != null && !opts.isEmpty()) {
                                            state.componentOptionsCache.put(key, opts);
                                        }
                                    } catch (Exception ignored) {}
                                }
                            }
                        }
                    } catch (Exception ignored) {}
                    break; // One interface per iteration — keep the loop responsive
                }
            }
            state.cachedInterfaceOptions.retainAll(currentOpen);
        } catch (Exception ignored) {}

        try { inspectorCollector.collectProductionData(api); } catch (Exception e) { log.debug("collectProductionData failed: {}", e.getMessage()); }
        try { inspectorCollector.collectProductionProgressData(api); } catch (Exception e) { log.debug("collectProductionProgressData failed: {}", e.getMessage()); }
        try { inspectorCollector.collectSmithingData(api); } catch (Exception e) { log.debug("collectSmithingData failed: {}", e.getMessage()); }
        try { inspectorCollector.collectActiveSmithingData(api); } catch (Exception e) { log.debug("collectActiveSmithingData failed: {}", e.getMessage()); }
        try { inventoryTracker.collectInventoryDiff(api); } catch (Exception e) { log.debug("collectInventoryDiff failed: {}", e.getMessage()); }

        // Mini menu poll + pre-resolve action names while interfaces are still open
        try {
            var menu = api.getMiniMenu();
            if (menu != null && !menu.isEmpty()) {
                state.lastMiniMenu = menu;
                int hash = menu.stream()
                        .mapToInt(e -> Objects.hash(e.optionText(), e.actionId(), e.param1(), e.param2(), e.param3()))
                        .sum();
                if (hash != lastMiniMenuHash) {
                    lastMiniMenuHash = hash;
                    state.menuLog.add(new MenuSnapshot(System.currentTimeMillis(), state.currentTick, List.copyOf(menu)));
                    while (state.menuLog.size() > 500) state.menuLog.remove(0);
                }
                // Pre-resolve names for EVERY menu entry while interfaces are open.
                // This eliminates timing issues: onActionExecuted just does a cache lookup.
                for (var entry : menu) {
                    var aKey = new XapiState.ActionCacheKey(entry.actionId(), entry.param1(), entry.param2(), entry.param3());
                    String optionText = entry.optionText();
                    String entityName = null;

                    int aid = entry.actionId();
                    if (NameResolver.isComponentAction(aid)) {
                        int packed = entry.param3();
                        int iface = packed >>> 16;
                        int comp = packed & 0xFFFF;
                        int sub = entry.param2();

                        // Cache component options while interface is alive
                        String optKey = iface + ":" + comp;
                        if (!state.componentOptionsCache.containsKey(optKey)) {
                            try {
                                List<String> opts = api.getComponentOptions(iface, comp);
                                if (opts != null && !opts.isEmpty()) state.componentOptionsCache.put(optKey, opts);
                            } catch (Exception ignored) {}
                        }

                        // Resolve entity name
                        if (sub >= 0) {
                            // Sub-component: get item via getComponentItem RPC
                            try {
                                var compItem = api.getComponentItem(iface, comp, sub);
                                if (compItem != null && compItem.itemId() > 0) {
                                    entityName = nameResolver.lookupItemName(api, compItem.itemId());
                                }
                            } catch (Exception ignored) {}
                        } else {
                            // No sub: try component text
                            try {
                                String text = api.getComponentText(iface, comp);
                                if (text != null && !text.isEmpty()) {
                                    entityName = text.replaceAll("<[^>]+>", "").trim();
                                }
                            } catch (Exception ignored) {}
                        }
                    }
                    // Fallback: mini menu's own itemId for entity name
                    if (entityName == null && entry.itemId() > 0) {
                        entityName = nameResolver.lookupItemName(api, entry.itemId());
                    }

                    state.preResolvedActions.put(aKey, new String[]{entityName, optionText});
                }
            }
        } catch (Exception ignored) {}

        try { dataPoller.pollPinnedVars(api); } catch (Exception e) { log.debug("pollPinnedVars failed: {}", e.getMessage()); }
        try { dataPoller.pollChatHistory(api); } catch (Exception e) { log.debug("pollChatHistory failed: {}", e.getMessage()); }
        try { dataPoller.pollActionHistory(api); } catch (Exception e) { log.debug("pollActionHistory failed: {}", e.getMessage()); }
        try { dataPoller.pollInterfaceEvents(api); } catch (Exception e) { log.debug("pollInterfaceEvents failed: {}", e.getMessage()); }

        // Heavy inspector data collection (entity queries — every ~3 ticks / 1.8s)
        if (now - lastInspectorUpdate > 1800) {
            lastInspectorUpdate = now;
            try {
                inspectorCollector.collectInspectorData(api);
            } catch (Exception e) {
                log.debug("collectInspectorData failed: {}", e.getMessage());
            }
        }

        // Prune state.varsByTick to prevent unbounded growth
        if (state.varsByTick.size() > 5000) {
            List<Integer> keys = new ArrayList<>(state.varsByTick.keySet());
            keys.sort(Comparator.naturalOrder());
            int removeCount = keys.size() - 4000;
            for (int i = 0; i < removeCount; i++) {
                state.varsByTick.remove(keys.get(i));
            }
        }

        // Persist settings periodically (every 30s) or on request
        sessionManager.shouldPersistSettings(now);

        // Trim logs if over max entry cap
        if (state.maxLogEntries > 0) {
            state.trimmedActionCount += SessionManager.trimLog(state.actionLog, state.maxLogEntries);
            state.trimmedVarCount += SessionManager.trimLog(state.varLog, state.maxLogEntries);
            state.trimmedChatCount += SessionManager.trimLog(state.chatLog, state.maxLogEntries);
            SessionManager.trimLog(state.snapshotLog, state.maxLogEntries);
            SessionManager.trimLog(state.inventoryLog, state.maxLogEntries);
        }

        // Auto-save when dirty, debounced to avoid excessive I/O
        sessionManager.shouldAutoSave(now);

        return 600;
    }

    @Override
    public void onStop() {
        log.info("Xapi stopped — {} actions, {} var changes, {} chat messages logged",
                state.actionLog.size(), state.varLog.size(), state.chatLog.size());

        // Remove shutdown hook — we're doing a clean stop, no need for the fallback
        sessionManager.removeShutdownHook();

        // Save settings and actions on stop
        sessionManager.saveSettings();
        sessionManager.doAutoSave();

        // Always unblock on stop
        state.blocking = false;
        state.actionDebugger.setBlocking(false);
        state.actionDebugger.setRecording(false);
        try {
            state.ctx.getGameAPI().setActionsBlocked(false);
        } catch (Exception e) {
            log.debug("Failed to unblock on stop: {}", e.getMessage());
        }
        state.replaying = false;
    }

    // =================================================================
    // == UI (render thread -- NO RPC calls) ===========================
    // =================================================================

    private final ScriptUI ui = this::renderUI;

    @Override
    public ScriptUI getUI() { return ui; }

    private void renderUI() {
        if (actionsTab == null) { ImGui.text("Initialising..."); return; }
        try {
            // Evenly space tabs across the full width using custom buttons as tabs
            String[] tabNames = {"Actions", "Variables", "Chat", "Player", "Entities",
                    "Interfaces", "Inventory", "Production", "Smithing", "Settings"};
            float availWidth = ImGui.getContentRegionAvailX();
            float tabWidth = availWidth / tabNames.length;

            for (int i = 0; i < tabNames.length; i++) {
                if (i > 0) ImGui.sameLine(0, 0);
                boolean isSelected = (selectedTab == i);
                if (isSelected) {
                    ImGui.pushStyleColor(ImGuiCol.Button, 0.2f, 0.45f, 0.65f, 1f);
                    ImGui.pushStyleColor(ImGuiCol.ButtonHovered, 0.25f, 0.5f, 0.7f, 1f);
                    ImGui.pushStyleColor(ImGuiCol.ButtonActive, 0.2f, 0.45f, 0.65f, 1f);
                } else {
                    ImGui.pushStyleColor(ImGuiCol.Button, 0.15f, 0.15f, 0.18f, 1f);
                    ImGui.pushStyleColor(ImGuiCol.ButtonHovered, 0.25f, 0.25f, 0.3f, 1f);
                    ImGui.pushStyleColor(ImGuiCol.ButtonActive, 0.2f, 0.4f, 0.6f, 1f);
                }
                if (ImGui.button(tabNames[i] + "##tab" + i, tabWidth, 0)) {
                    selectedTab = i;
                }
                ImGui.popStyleColor(3);
            }

            ImGui.separator();

            // Render selected tab content
            switch (selectedTab) {
                case 0 -> { try { actionsTab.render(); } catch (Exception e) { log.error("Actions tab error", e); ImGui.text("Error: " + e.getMessage()); } }
                case 1 -> { try { variablesTab.render(); } catch (Exception e) { log.error("Variables tab error", e); ImGui.text("Error: " + e.getMessage()); } }
                case 2 -> { try { chatTab.render(); } catch (Exception e) { log.error("Chat tab error", e); ImGui.text("Error: " + e.getMessage()); } }
                case 3 -> { try { playerTab.render(); } catch (Exception e) { log.error("Player tab error", e); ImGui.text("Error: " + e.getMessage()); } }
                case 4 -> { try { entitiesTab.render(); } catch (Exception e) { log.error("Entities tab error", e); ImGui.text("Error: " + e.getMessage()); } }
                case 5 -> { try { interfacesTab.render(); } catch (Exception e) { log.error("Interfaces tab error", e); ImGui.text("Error: " + e.getMessage()); } }
                case 6 -> { try { inventoryTab.render(); } catch (Exception e) { log.error("Inventory tab error", e); ImGui.text("Error: " + e.getMessage()); } }
                case 7 -> { try { productionTab.render(); } catch (Exception e) { log.error("Production tab error", e); ImGui.text("Error: " + e.getMessage()); } }
                case 8 -> { try { smithingTab.render(); } catch (Exception e) { log.error("Smithing tab error", e); ImGui.text("Error: " + e.getMessage()); } }
                case 9 -> { try { renderControls(); } catch (Exception e) { log.error("Settings tab error", e); ImGui.text("Error: " + e.getMessage()); } }
            }

            // Status line below tabs
            ImGui.textColored(0.6f, 0.6f, 0.6f, 0.8f,
                    String.format("Actions: %d  |  Variables: %d  |  Chat: %d",
                            state.actionLog.size(), state.varLog.size(), state.chatLog.size()));
        } catch (Exception e) {
            log.error("renderUI error", e);
            try { ImGui.text("UI Error: " + e.getMessage()); } catch (Exception ignored) {}
        }
    }

    // ── Controls ────────────────────────────────────────────────────────

    private void renderControls() {
        if (ImGui.collapsingHeader("Controls", ImGuiTreeNodeFlags.DefaultOpen)) {
            // Row 1: Record, Block, Clear, Tick
            boolean wasRecording = state.recording;
            if (wasRecording) {
                ImGui.pushStyleColor(ImGuiCol.Button, 0.2f, 0.8f, 0.4f, 0.6f);
                ImGui.pushStyleColor(ImGuiCol.ButtonHovered, 0.2f, 0.8f, 0.4f, 0.8f);
            }
            if (ImGui.button(wasRecording ? "Recording" : "Record")) { state.recording = !state.recording; state.settingsDirty = true; }
            if (wasRecording) ImGui.popStyleColor(2);

            ImGui.sameLine();
            boolean wasBlocking = state.blocking;
            if (wasBlocking) {
                ImGui.pushStyleColor(ImGuiCol.Button, 0.9f, 0.2f, 0.2f, 0.6f);
                ImGui.pushStyleColor(ImGuiCol.ButtonHovered, 0.9f, 0.2f, 0.2f, 0.8f);
            }
            if (ImGui.button(wasBlocking ? "BLOCKING" : "Block")) {
                state.blocking = !state.blocking;
                if (state.blocking && !state.recording) state.recording = true;
                state.settingsDirty = true;
            }
            if (wasBlocking) ImGui.popStyleColor(2);

            ImGui.sameLine();
            if (ImGui.button("Clear All")) {
                ImGui.openPopup("##clear_confirm");
            }
            if (ImGui.beginPopup("##clear_confirm")) {
                int total = state.actionLog.size() + state.varLog.size() + state.chatLog.size();
                ImGui.text("Clear all " + total + " entries? This cannot be undone.");
                if (ImGui.button("Yes, clear")) {
                    state.actionLog.clear(); state.varLog.clear(); state.chatLog.clear();
                    state.snapshotLog.clear();
                    state.varsByTick.clear();
                    state.actionDebugger.clear();
                    state.lastActionSize = -1; state.lastVarSize = -1; state.lastChatSize = -1;
                    state.inventoryLog.clear();
                    state.interfaceEventLog.clear();
                    state.trimmedActionCount = 0; state.trimmedVarCount = 0; state.trimmedChatCount = 0;
                    // Don't clear state.pinnedVars, state.varChangeCount, or state.varAnnotations
                    state.actionsDirty = true; // triggers autosave of empty state
                    ImGui.closeCurrentPopup();
                }
                ImGui.sameLine();
                if (ImGui.button("Cancel")) {
                    ImGui.closeCurrentPopup();
                }
                ImGui.endPopup();
            }

            ImGui.sameLine();
            ImGui.text("Tick: " + state.currentTick);

            if (state.blocking) { ImGui.sameLine(); ImGui.textColored(0.9f, 0.2f, 0.2f, 1f, "  ACTIONS BLOCKED"); }

        }

        // Game Cache status
        if (ImGui.collapsingHeader("Game Cache", ImGuiTreeNodeFlags.DefaultOpen)) {
            if (state.gameCache.isLoaded()) {
                ImGui.textColored(0.2f, 0.9f, 0.4f, 1f, "Loaded");
                ImGui.sameLine();
                ImGui.text(String.format("  %,d locations | %,d items | %,d NPCs | %,d interfaces (%,d widgets)",
                        state.gameCache.locationCount(), state.gameCache.itemCount(), state.gameCache.npcCount(),
                        state.gameCache.interfaceCount(), state.gameCache.widgetCount()));
            } else if (state.gameCache.getLoadError() != null) {
                ImGui.textColored(0.9f, 0.3f, 0.2f, 1f, "Error: " + state.gameCache.getLoadError());
            } else {
                ImGui.textColored(0.9f, 0.9f, 0.2f, 1f, "Loading...");
            }
        }

        // Session management
        if (ImGui.collapsingHeader("Session Export/Import")) {
            if (ImGui.button("Export Session")) state.exportRequested = true;
            ImGui.sameLine();

            // Scan session files periodically
            long now = System.currentTimeMillis();
            if (now - lastSessionScan > 5000) {
                lastSessionScan = now;
                actionsTab.scanSessionFiles();
            }

            if (state.sessionFiles.length > 0) {
                ImGui.text("Import:");
                ImGui.sameLine();
                for (String file : state.sessionFiles) {
                    if (ImGui.smallButton(file)) {
                        state.importPath = file;
                    }
                    ImGui.sameLine();
                }
                ImGui.newLine();
            }

            if (!state.lastExportStatus.isEmpty()) {
                ImGui.textColored(0.5f, 0.8f, 1f, 1f, state.lastExportStatus);
            }
        }

        // Script generation
        if (ImGui.collapsingHeader("Generate Script")) {
            ImGui.text("Class Name:");
            ImGui.sameLine();
            ImGui.pushItemWidth(200);
            ImGui.inputText("##className", state.scriptClassName);
            ImGui.popItemWidth();
            ImGui.sameLine();
            boolean wasUseNames = state.useNamesForGeneration;
            if (ImGui.checkbox("Use Names (skeleton)", wasUseNames)) { state.useNamesForGeneration = !wasUseNames; state.settingsDirty = true; }

            if (ImGui.button("Generate & Copy to Clipboard")) {
                actionsTab.generateAndCopyScript();
            }
        }

        // Replay controls
        if (ImGui.collapsingHeader("Replay")) {
            if (!state.replaying) {
                if (ImGui.button("Start Replay") && !state.actionLog.isEmpty()) {
                    state.replaying = true;
                    state.replayIndex = 0;
                    replayController.setReplayNextTime(System.currentTimeMillis());
                    state.replaySpeed = state.replaySpeedArr[0];
                }
            } else {
                ImGui.textColored(0.3f, 0.9f, 0.3f, 1f,
                        "Replaying: step " + state.replayIndex + "/" + state.actionLog.size());
                ImGui.sameLine();
                if (ImGui.button("Stop Replay")) state.replaying = false;
            }
            ImGui.sameLine();
            ImGui.pushItemWidth(150);
            ImGui.sliderFloat("Speed##replay", state.replaySpeedArr, 0.25f, 4f);
            ImGui.popItemWidth();
            state.replaySpeed = state.replaySpeedArr[0];
        }
    }
}
