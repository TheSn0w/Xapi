package com.xapi.debugger;

import static com.xapi.debugger.XapiData.*;

import com.botwithus.bot.api.BotScript;
import com.botwithus.bot.api.GameAPI;
import com.botwithus.bot.api.ScriptCategory;
import com.botwithus.bot.api.ScriptContext;
import com.botwithus.bot.api.ScriptManifest;
import com.botwithus.bot.api.event.*;
import com.botwithus.bot.api.inventory.ActionBar;
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

import java.util.Map;
import java.util.List;

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
    private ActionBarTab actionBarTab;

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
        actionBarTab = new ActionBarTab(state);

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

        // Always poll VarC 2092 (GCD) and ability varcs on tick when Action Bar tab is active
        if (state.activeTab == 10) {
            loadAbilityVarcs(); // Ensure varc mappings are loaded before polling
            try {
                GameAPI api = state.ctx.getGameAPI();

                // Poll GCD (VarC 2092)
                int gcdVal = api.getVarcInt(2092);
                Integer prevGcd = state.tickProbeVarcValues.put("varc:2092", gcdVal);
                boolean gcdFired = prevGcd != null && gcdVal > prevGcd;

                if (gcdFired) {
                    long now = System.currentTimeMillis();
                    state.lastAbilityActivationTime = now;
                    state.lastAbilityActivationTick = state.currentTick;

                    // Identify which ability by polling all known ability varcs
                    String abilityName = "";
                    int detectedVarc = 0;
                    for (var entry : varcToAbilityName.entrySet()) {
                        int varcId = entry.getKey();
                        try {
                            int val = api.getVarcInt(varcId);
                            String key = "ability_varc:" + varcId;
                            Integer prev = state.tickProbeVarcValues.put(key, val);
                            if (prev != null && val != prev && val > prev) {
                                abilityName = entry.getValue();
                                detectedVarc = varcId;
                                break; // Found the one that changed
                            }
                        } catch (Exception ignored) {}
                    }

                    // Fallback: VarC 2215 stores the struct ID for abilities using the generic path
                    if (abilityName.isEmpty()) {
                        try {
                            int genericStructId = api.getVarcInt(2215);
                            Integer prevGeneric = state.tickProbeVarcValues.put("ability_varc:2215", genericStructId);
                            if (prevGeneric != null && genericStructId != prevGeneric && genericStructId > 0) {
                                // Look up struct name from sprite cache
                                abilityName = resolveStructName(api, genericStructId);
                                detectedVarc = 2215;
                            }
                        } catch (Exception ignored) {}
                    }

                    // GCD fired but no ability varc changed — this is the auto-attack.
                    // Try to find the auto-attack name from the action bar entries.
                    if (abilityName.isEmpty()) {
                        for (var abEntry : state.actionBarEntries) {
                            if (abEntry.name().contains("Attack") || abEntry.name().contains("attack")) {
                                abilityName = abEntry.name();
                                break;
                            }
                        }
                        if (abilityName.isEmpty()) abilityName = "Auto-attack";
                    }

                    // Adrenaline delta as extra info
                    int adrenNow = api.getVarp(679);
                    int adrenDelta = adrenNow - state.lastPolledAdrenaline;
                    String option = "";
                    if (adrenDelta != 0) option = String.format("Adren: %+d", adrenDelta);
                    if (detectedVarc > 0) option += (option.isEmpty() ? "" : " | ") + "VarC:" + detectedVarc;

                    state.actionBarHistory.add(new XapiData.ActionBarActivation(
                            now, state.currentTick, 0, 0, 0, abilityName, option));
                    while (state.actionBarHistory.size() > 200) state.actionBarHistory.remove(0);
                }

                // Track adrenaline for delta detection
                state.lastPolledAdrenaline = api.getVarp(679);
            } catch (Exception ignored) {}
        }

        // Poll watched VarCs on every tick for change detection (varcs have no events)
        if (state.activeTab == 10 && state.probeVarcIds.length > 0) {
            try {
                GameAPI api = state.ctx.getGameAPI();
                for (int varcId : state.probeVarcIds) {
                    try {
                        int val = api.getVarcInt(varcId);
                        String key = "varc:" + varcId;
                        Integer prev = state.tickProbeVarcValues.put(key, val);
                        if (prev != null && prev != val) {
                            state.varcChangeLog.add(new XapiData.VarcChange(
                                    System.currentTimeMillis(), state.currentTick, varcId, prev, val));
                            while (state.varcChangeLog.size() > 200) state.varcChangeLog.remove(0);

                            // VarC 2092 increased = GCD just started (lasts 3 ticks / 1.8s)
                            if (varcId == 2092 && val > prev) {
                                state.lastAbilityActivationTime = System.currentTimeMillis();
                                state.lastAbilityActivationTick = state.currentTick;
                            }
                        }
                    } catch (Exception ignored) {}
                }
            } catch (Exception ignored) {}
        }
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

            long actionTime = System.currentTimeMillis();
            state.actionLog.add(new LogEntry(
                    e.getActionId(), e.getParam1(), e.getParam2(), e.getParam3(),
                    actionTime, state.currentTick, false, "client",
                    names[0], names[1], px, py, pp, pa, pm
            ));

            // Track action bar activations
            if (e.getActionId() == 57 || e.getActionId() == 58) { // COMPONENT or SELECT_COMPONENT_ITEM
                int packed = e.getParam3();
                int ifaceId = packed >>> 16;
                int compId = packed & 0xFFFF;
                if (ifaceId == 1430 || ifaceId == 1670 || ifaceId == 1671 || ifaceId == 1672 || ifaceId == 1673) {
                    int slot = resolveSlotNumber(ifaceId, compId);
                    String slotName = names[0] != null ? names[0] : "";
                    String optName = names[1] != null ? names[1] : "";
                    // Try to get better name from current action bar entries
                    for (var abEntry : state.actionBarEntries) {
                        if (abEntry.interfaceId() == ifaceId && abEntry.componentId() == compId && !abEntry.name().isEmpty()) {
                            slotName = abEntry.name();
                            break;
                        }
                    }
                    state.actionBarHistory.add(new ActionBarActivation(
                            actionTime, state.currentTick, ifaceId, compId, slot, slotName, optName));
                    while (state.actionBarHistory.size() > 200) state.actionBarHistory.remove(0);
                    // Track GCD
                    state.lastAbilityActivationTime = actionTime;
                    state.lastAbilityActivationTick = state.currentTick;
                }
            }

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
        if (!state.recording) return;
        try {
            VarChange vc = new VarChange("varbit", e.getVarId(), e.getOldValue(), e.getNewValue(),
                    System.currentTimeMillis(), state.currentTick);
            state.varLog.add(vc);
            state.varsByTick.computeIfAbsent(state.currentTick, k -> new CopyOnWriteArrayList<>()).add(vc);
            state.varChangeCount.merge("varbit:" + e.getVarId(), 1, Integer::sum);
        } catch (Exception ex) { log.debug("varbit event error: {}", ex.getMessage()); }
    }

    private void onVarChange(VarChangeEvent e) {
        if (!state.recording) return;
        try {
            VarChange vc = new VarChange("varp", e.getVarId(), e.getOldValue(), e.getNewValue(),
                    System.currentTimeMillis(), state.currentTick);
            state.varLog.add(vc);
            state.varsByTick.computeIfAbsent(state.currentTick, k -> new CopyOnWriteArrayList<>()).add(vc);
            state.varChangeCount.merge("varp:" + e.getVarId(), 1, Integer::sum);
        } catch (Exception ex) { log.debug("varp event error: {}", ex.getMessage()); }
    }

    private void onChatMessage(ChatMessageEvent e) {
        if (!state.recording) return;
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

        // Execute clear requests on the loop thread (requested by render thread)
        if (state.clearRequested) {
            state.clearRequested = false;
            state.actionLog.clear(); state.varLog.clear(); state.chatLog.clear();
            state.snapshotLog.clear();
            state.varsByTick.clear();
            state.actionDebugger.clear();
            state.lastActionSize = -1; state.lastVarSize = -1; state.lastChatSize = -1;
            state.inventoryLog.clear();
            state.interfaceEventLog.clear();
            state.trimmedActionCount = 0; state.trimmedVarCount = 0; state.trimmedChatCount = 0;
            state.varChangeCount.clear();
            sessionManager.doAutoSave();
            log.info("Clear All executed and saved");
        }
        if (state.clearActionsRequested) {
            state.clearActionsRequested = false;
            state.actionLog.clear();
            state.snapshotLog.clear();
            state.lastActionSize = -1;
            state.trimmedActionCount = 0;
            sessionManager.doAutoSave();
            log.info("Clear Actions executed and saved");
        }

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

        int tab = state.activeTab;

        // Pre-cache component options for open interfaces (timing-proof).
        // Only needed when Actions tab is active (for action name resolution).
        if (tab == 0) {
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
        }

        // Production collectors — only when Production tab (7) is active or interface is open
        if (tab == 7 || state.prodOpen) {
            try { inspectorCollector.collectProductionData(api); } catch (Exception e) { log.debug("collectProductionData failed: {}", e.getMessage()); }
            try { inspectorCollector.collectProductionProgressData(api); } catch (Exception e) { log.debug("collectProductionProgressData failed: {}", e.getMessage()); }
        }
        // Smithing collectors — only when Smithing tab (8) is active or interface is open
        if (tab == 8 || state.smithOpen) {
            try { inspectorCollector.collectSmithingData(api); } catch (Exception e) { log.debug("collectSmithingData failed: {}", e.getMessage()); }
            try { inspectorCollector.collectActiveSmithingData(api); } catch (Exception e) { log.debug("collectActiveSmithingData failed: {}", e.getMessage()); }
        }
        // Action bar collector — only when Action Bar tab (10) is active
        if (tab == 10) {
            try { collectActionBarData(api); } catch (Exception e) { log.debug("collectActionBarData failed: {}", e.getMessage()); }
        }

        try { inventoryTracker.collectInventoryDiff(api); } catch (Exception e) { log.debug("collectInventoryDiff failed: {}", e.getMessage()); }

        // Mini menu poll + pre-resolve action names — only when Actions tab (0) is active
        if (tab == 0) {
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
        }

        try { inspectorCollector.pollInvVarLive(api); } catch (Exception e) { log.debug("pollInvVarLive failed: {}", e.getMessage()); }

        try { dataPoller.pollVarLookup(api); } catch (Exception e) { log.debug("pollVarLookup failed: {}", e.getMessage()); }
        try { dataPoller.pollPinnedVars(api); } catch (Exception e) { log.debug("pollPinnedVars failed: {}", e.getMessage()); }
        try { dataPoller.pollChatHistory(api); } catch (Exception e) { log.debug("pollChatHistory failed: {}", e.getMessage()); }
        try { dataPoller.pollActionHistory(api); } catch (Exception e) { log.debug("pollActionHistory failed: {}", e.getMessage()); }
        try { dataPoller.pollInterfaceEvents(api); } catch (Exception e) { log.debug("pollInterfaceEvents failed: {}", e.getMessage()); }

        // Heavy inspector data collection (entity queries — every ~3 ticks / 1.8s)
        // Only when Entities (4) or Interfaces (5) tab is active
        if ((tab == 4 || tab == 5) && now - lastInspectorUpdate > 1800) {
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

        // Auto-save if data changed since last save
        sessionManager.doAutoSaveIfChanged();

        // Poll faster when debug probe is active on Action Bar tab
        if (state.activeTab == 10 && state.probeActive) {
            return 50; // ~20 reads/sec for catching fast-changing varcs
        }
        return 600;
    }

    @Override
    public void onStop() {
        log.info("Xapi stopped — {} actions, {} var changes, {} chat messages logged",
                state.actionLog.size(), state.varLog.size(), state.chatLog.size());

        // Remove shutdown hook — we're doing a clean stop, no need for the fallback
        sessionManager.removeShutdownHook();

        // Save current state to disk
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

    // ── Action bar data collection (onLoop thread, RPC-safe) ──────────

    private static final java.nio.file.Path SPRITE_CACHE_FILE = XapiState.SESSION_DIR.resolve("ability_cache.json");
    // Varc ID → ability name mapping (loaded from ability_varcs.json resource)
    private volatile Map<Integer, String> varcToAbilityName = Map.of();
    private volatile boolean abilityVarcsLoaded;

    private ActionBar actionBarApi;
    private final java.util.concurrent.ConcurrentHashMap<Integer, String> cachedSpriteNames = new java.util.concurrent.ConcurrentHashMap<>();
    private final java.util.concurrent.ConcurrentHashMap<Integer, String> cachedSpriteDescs = new java.util.concurrent.ConcurrentHashMap<>();
    private final java.util.concurrent.ConcurrentHashMap<Integer, String> cachedSpriteTypes = new java.util.concurrent.ConcurrentHashMap<>();
    // [baseAdrenCost(2798), adrenType(2799), specCost(4332), canCastDuringGCD(5550), requiresTarget(3394)]
    private final java.util.concurrent.ConcurrentHashMap<Integer, int[]> cachedSpriteStats = new java.util.concurrent.ConcurrentHashMap<>();
    private volatile boolean spriteMapBuilt;
    private volatile boolean spriteMapBuilding;
    private final java.util.Queue<int[]> pendingCategoryEnums = new java.util.concurrent.ConcurrentLinkedQueue<>();
    private volatile int spriteCategoriesTotal;
    private volatile int spriteCategoriesDone;
    // Previous slot snapshot for change detection (key: "ifaceId:slot")
    private final java.util.concurrent.ConcurrentHashMap<String, String> previousSlotNames = new java.util.concurrent.ConcurrentHashMap<>();

    // Slot type names from enum 13199 keys
    private static final Map<Integer, String> SLOT_TYPE_NAMES = Map.of(
            1, "Melee", 2, "Strength", 3, "Defence", 4, "Other",
            5, "Ranged", 6, "Magic", 10, "Item", 11, "Teleport",
            13, "Familiar", 17, "Necromancy"
    );

    private static int resolveSlotNumber(int interfaceId, int componentId) {
        int base = switch (interfaceId) {
            case 1430 -> 64;  case 1670 -> 21;  case 1671 -> 19;
            case 1672, 1673 -> 16;  default -> -1;
        };
        if (base < 0) return -1;
        int offset = componentId - base;
        if (offset < 0 || offset % 13 != 0) return -1;
        int slot = offset / 13 + 1;
        return slot <= 14 ? slot : -1;
    }

    @SuppressWarnings("unchecked")
    private void loadAbilityVarcs() {
        if (abilityVarcsLoaded) return;
        abilityVarcsLoaded = true;
        String json = null;

        // Try classpath resource first
        try {
            var is = getClass().getResourceAsStream("/ability_varcs.json");
            if (is != null) {
                json = new String(is.readAllBytes());
                is.close();
                log.debug("[ActionBar] Loaded ability_varcs.json from classpath");
            }
        } catch (Exception ignored) {}

        // Fallback: load from xapi_sessions directory
        if (json == null) {
            try {
                var path = XapiState.SESSION_DIR.resolve("ability_varcs.json");
                if (java.nio.file.Files.exists(path)) {
                    json = java.nio.file.Files.readString(path);
                    log.debug("[ActionBar] Loaded ability_varcs.json from {}", path);
                }
            } catch (Exception ignored) {}
        }

        if (json == null) {
            log.warn("[ActionBar] ability_varcs.json not found — ability detection in History will be limited");
            return;
        }

        try {
            Map<String, Object> data = XapiState.GSON.fromJson(json, Map.class);
            Map<String, String> varcNames = (Map<String, String>) data.get("varc_to_name");
            if (varcNames != null) {
                Map<Integer, String> map = new java.util.HashMap<>();
                varcNames.forEach((k, v) -> map.put(Integer.parseInt(k), v));
                varcToAbilityName = Map.copyOf(map);
                log.info("[ActionBar] Loaded {} ability varc mappings", varcToAbilityName.size());
            }
        } catch (Exception e) {
            log.warn("[ActionBar] Failed to parse ability varcs: {}", e.getMessage());
        }
    }

    private String resolveStructName(GameAPI api, int structId) {
        try {
            var struct = api.getStructType(structId);
            if (struct != null && struct.params() != null) {
                Object nameObj = struct.params().get("2794");
                if (nameObj instanceof String name && !name.isEmpty()) return name;
            }
        } catch (Exception ignored) {}
        return "Struct " + structId;
    }

    private void collectActionBarData(GameAPI api) {
        if (actionBarApi == null) actionBarApi = new ActionBar(api);
        loadAbilityVarcs();

        state.actionBarOpen = api.isInterfaceOpen(1430);
        try { state.activeBarPreset = actionBarApi.getActiveBar(); } catch (Exception ignored) {}

        // Adrenaline from varp 679 (0-1200, divide by 10 for percentage with 1 decimal)
        try {
            state.actionBarAdrenalineRaw = api.getVarp(679);
        } catch (Exception ignored) {}
        // Varbit 1892: 0=unlocked, 1=locked
        try { state.actionBarLocked = api.getVarbit(1892) == 1; } catch (Exception ignored) {}

        // Variable probe — read requested varcs/varps/varbits for debug panel
        try {
            java.util.concurrent.ConcurrentHashMap<String, Integer> results = new java.util.concurrent.ConcurrentHashMap<>();
            for (int id : state.probeVarcIds) {
                try { results.put("varc:" + id, api.getVarcInt(id)); } catch (Exception ignored) {}
            }
            for (int id : state.probeVarpIds) {
                try { results.put("varp:" + id, api.getVarp(id)); } catch (Exception ignored) {}
            }
            for (int id : state.probeVarbitIds) {
                try { results.put("varbit:" + id, api.getVarbit(id)); } catch (Exception ignored) {}
            }
            state.probeResults = Map.copyOf(results);
        } catch (Exception ignored) {}

        // Handle bar switch requests from UI
        int switchReq = state.barSwitchRequest;
        if (switchReq != 0) {
            state.barSwitchRequest = 0;
            try {
                if (switchReq > 0) actionBarApi.nextBar();
                else actionBarApi.previousBar();
            } catch (Exception ignored) {}
        }

        // Handle comparison snapshot request
        if (state.comparisonBarPreset == -2) { // -2 = take snapshot now
            state.comparisonSnapshot = List.copyOf(state.actionBarEntries);
            state.comparisonBarPreset = state.activeBarPreset;
        }

        // Incrementally build sprite caches (one category per loop tick)
        if (!spriteMapBuilt && !spriteMapBuilding) {
            // Try loading from disk first
            if (loadSpriteCache()) {
                spriteMapBuilt = true;
                state.spriteMapProgress = cachedSpriteNames.size() + " abilities loaded (cached)";
            } else {
                spriteMapBuilding = true;
                try {
                    var masterEnum = api.getEnumType(13199);
                    if (masterEnum != null && masterEnum.entries() != null) {
                        for (var e : masterEnum.entries().entrySet()) {
                            if (e.getValue() instanceof Number n) {
                                int typeKey = Integer.parseInt(e.getKey());
                                pendingCategoryEnums.add(new int[]{n.intValue(), typeKey});
                            }
                        }
                        spriteCategoriesTotal = pendingCategoryEnums.size();
                        spriteCategoriesDone = 0;
                    }
                } catch (Exception ignored) {}
                state.spriteMapProgress = "Loading ability data: 0/" + spriteCategoriesTotal + " categories...";
            }
        }

        if (spriteMapBuilding && !pendingCategoryEnums.isEmpty()) {
            int[] entry = pendingCategoryEnums.poll();
            if (entry != null) {
                int categoryEnumId = entry[0];
                int typeKey = entry[1];
                String typeName = SLOT_TYPE_NAMES.getOrDefault(typeKey, "Type " + typeKey);
                try {
                    var categoryEnum = api.getEnumType(categoryEnumId);
                    if (categoryEnum != null && categoryEnum.entries() != null) {
                        for (Object structIdObj : categoryEnum.entries().values()) {
                            if (!(structIdObj instanceof Number structIdNum)) continue;
                            try {
                                var struct = api.getStructType(structIdNum.intValue());
                                if (struct == null || struct.params() == null) continue;
                                var params = struct.params();
                                Object nameObj = params.get("2794");
                                Object spriteObj = params.get("2802");
                                Object descObj = params.get("2795");
                                if (nameObj instanceof String name && spriteObj instanceof Number sprite) {
                                    int spriteId = sprite.intValue();
                                    if (!name.isEmpty() && spriteId > 0) {
                                        cachedSpriteNames.put(spriteId, name);
                                        cachedSpriteTypes.put(spriteId, typeName);
                                        if (descObj instanceof String desc && !desc.isEmpty()) {
                                            cachedSpriteDescs.put(spriteId, desc);
                                        }
                                        // Cache ability stats from struct params
                                        int adrenCost = 0, adrenGain = 0, cooldownTicks = 0;
                                        int abilityType = 0, specCost = 0;
                                        int canCastGCD = -1, requiresTarget = -1; // -1 = absent
                                        Object p2796 = params.get("2796");
                                        if (p2796 instanceof Number n) cooldownTicks = n.intValue();
                                        Object p2798 = params.get("2798");
                                        if (p2798 instanceof Number n) adrenCost = n.intValue();
                                        Object p2799 = params.get("2799");
                                        if (p2799 instanceof Number n) abilityType = n.intValue();
                                        Object p2800 = params.get("2800");
                                        if (p2800 instanceof Number n) adrenGain = n.intValue();
                                        Object p4332 = params.get("4332");
                                        if (p4332 instanceof Number n) specCost = n.intValue();
                                        Object p5550 = params.get("5550");
                                        if (p5550 instanceof Number n) canCastGCD = n.intValue();
                                        Object p3394 = params.get("3394");
                                        if (p3394 instanceof Number n) requiresTarget = n.intValue();
                                        cachedSpriteStats.put(spriteId, new int[]{
                                                adrenCost, adrenGain, cooldownTicks, abilityType,
                                                specCost, canCastGCD, requiresTarget});
                                    }
                                }
                            } catch (Exception ignored) {}
                        }
                    }
                } catch (Exception ignored) {}
                spriteCategoriesDone++;
                state.spriteMapProgress = "Loading ability data: " + spriteCategoriesDone + "/" + spriteCategoriesTotal
                        + " categories (" + cachedSpriteNames.size() + " abilities)...";
            }
        }

        if (spriteMapBuilding && pendingCategoryEnums.isEmpty() && spriteCategoriesDone > 0) {
            spriteMapBuilt = true;
            spriteMapBuilding = false;
            saveSpriteCache();
            state.spriteMapProgress = cachedSpriteNames.size() + " abilities loaded";
        }

        // Query components from all action bar interfaces
        int[] actionBarInterfaces = {1430, 1670, 1671, 1672, 1673};
        List<XapiData.ActionBarEntry> entries = new java.util.ArrayList<>();

        for (int ifaceId : actionBarInterfaces) {
            if (!api.isInterfaceOpen(ifaceId)) continue;

            List<Component> comps = api.queryComponents(
                    ComponentFilter.builder().interfaceId(ifaceId).build());
            if (comps == null) continue;

            for (Component comp : comps) {
                int itemId = comp.itemId();
                int spriteId = comp.spriteId();

                // Resolve name, description, type, stats
                String name = "";
                String description = "";
                String slotType = "";
                int adrenCost = 0, adrenGain = 0, cooldownTicks = 0;
                int abilityType = 0, specCost = 0, canCastGCD = -1, requiresTarget = -1;

                if (itemId > 0) {
                    var type = api.getItemType(itemId);
                    name = (type != null && type.name() != null) ? type.name() : "Item " + itemId;
                    slotType = "Item";
                } else if (spriteId > 0) {
                    name = cachedSpriteNames.getOrDefault(spriteId, "");
                    description = cachedSpriteDescs.getOrDefault(spriteId, "");
                    slotType = cachedSpriteTypes.getOrDefault(spriteId, "");
                    int[] stats = cachedSpriteStats.get(spriteId);
                    if (stats != null) {
                        adrenCost = stats[0]; adrenGain = stats[1]; cooldownTicks = stats[2];
                        abilityType = stats[3]; specCost = stats[4];
                        canCastGCD = stats[5]; requiresTarget = stats[6];
                    }
                }

                // Fetch options, filter blanks
                List<String> rawOptions = api.getComponentOptions(comp.interfaceId(), comp.componentId());
                List<String> options = (rawOptions == null) ? List.of()
                        : rawOptions.stream().filter(o -> o != null && !o.isBlank()).toList();
                if (options.isEmpty()) continue;

                int slot = resolveSlotNumber(ifaceId, comp.componentId());

                // Change detection
                if (slot > 0) {
                    String key = ifaceId + ":" + slot;
                    String prev = previousSlotNames.put(key, name);
                    if (prev != null && !prev.equals(name)) {
                        state.actionBarChangeLog.add(new XapiData.ActionBarChange(
                                System.currentTimeMillis(), ifaceId, slot, prev, name));
                        while (state.actionBarChangeLog.size() > 200) state.actionBarChangeLog.remove(0);
                    }
                }

                entries.add(new XapiData.ActionBarEntry(
                        ifaceId, comp.componentId(), comp.subComponentId(),
                        itemId, spriteId, name, description, slotType, slot, options,
                        adrenCost, adrenGain, cooldownTicks, abilityType, specCost,
                        canCastGCD, requiresTarget));
            }
        }

        state.actionBarEntries = List.copyOf(entries);
    }

    @SuppressWarnings("unchecked")
    private boolean loadSpriteCache() {
        try {
            if (!java.nio.file.Files.exists(SPRITE_CACHE_FILE)) return false;
            String json = java.nio.file.Files.readString(SPRITE_CACHE_FILE);
            Map<String, Object> data = XapiState.GSON.fromJson(json, Map.class);
            if (data == null) return false;

            Map<String, String> names = (Map<String, String>) data.get("names");
            Map<String, String> descs = (Map<String, String>) data.get("descs");
            Map<String, String> types = (Map<String, String>) data.get("types");
            Map<String, List<Double>> stats = (Map<String, List<Double>>) data.get("stats");

            if (names == null || names.isEmpty()) return false;
            // Check if stats have the expected 7-value format — reject old caches
            if (stats != null && !stats.isEmpty()) {
                var firstVal = stats.values().iterator().next();
                if (firstVal instanceof List<?> list && list.size() < 7) {
                    log.info("[ActionBar] Stale cache format detected — will rebuild");
                    return false;
                }
            }

            for (var e : names.entrySet()) {
                int id = Integer.parseInt(e.getKey());
                cachedSpriteNames.put(id, e.getValue());
            }
            if (descs != null) for (var e : descs.entrySet()) {
                cachedSpriteDescs.put(Integer.parseInt(e.getKey()), e.getValue());
            }
            if (types != null) for (var e : types.entrySet()) {
                cachedSpriteTypes.put(Integer.parseInt(e.getKey()), e.getValue());
            }
            if (stats != null) for (var e : stats.entrySet()) {
                List<Double> vals = e.getValue();
                if (vals.size() < 7) continue; // Skip old format entries — will rebuild
                cachedSpriteStats.put(Integer.parseInt(e.getKey()), new int[]{
                        vals.get(0).intValue(), vals.get(1).intValue(), vals.get(2).intValue(),
                        vals.get(3).intValue(), vals.get(4).intValue(), vals.get(5).intValue(),
                        vals.get(6).intValue()
                });
            }
            log.info("[ActionBar] Loaded {} abilities from cache", cachedSpriteNames.size());
            return true;
        } catch (Exception e) {
            log.debug("[ActionBar] Failed to load sprite cache: {}", e.getMessage());
            return false;
        }
    }

    private void saveSpriteCache() {
        try {
            Map<String, Object> data = new java.util.LinkedHashMap<>();

            Map<String, String> names = new java.util.LinkedHashMap<>();
            cachedSpriteNames.forEach((k, v) -> names.put(String.valueOf(k), v));
            data.put("names", names);

            Map<String, String> descs = new java.util.LinkedHashMap<>();
            cachedSpriteDescs.forEach((k, v) -> descs.put(String.valueOf(k), v));
            data.put("descs", descs);

            Map<String, String> types = new java.util.LinkedHashMap<>();
            cachedSpriteTypes.forEach((k, v) -> types.put(String.valueOf(k), v));
            data.put("types", types);

            Map<String, int[]> stats = new java.util.LinkedHashMap<>();
            cachedSpriteStats.forEach((k, v) -> stats.put(String.valueOf(k), v));
            data.put("stats", stats);

            java.nio.file.Files.createDirectories(SPRITE_CACHE_FILE.getParent());
            java.nio.file.Files.writeString(SPRITE_CACHE_FILE, XapiState.GSON.toJson(data));
            log.info("[ActionBar] Saved {} abilities to cache", cachedSpriteNames.size());
        } catch (Exception e) {
            log.debug("[ActionBar] Failed to save sprite cache: {}", e.getMessage());
        }
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
                    "Interfaces", "Inventory", "Production", "Smithing", "Settings", "Action Bar"};
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
            state.activeTab = selectedTab;

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
                case 10 -> { try { actionBarTab.render(); } catch (Exception e) { log.error("Action Bar tab error", e); ImGui.text("Error: " + e.getMessage()); } }
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
                    state.clearRequested = true;
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
