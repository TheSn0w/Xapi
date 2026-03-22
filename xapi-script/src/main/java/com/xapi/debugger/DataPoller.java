package com.xapi.debugger;

import static com.xapi.debugger.XapiData.*;

import com.botwithus.bot.api.GameAPI;
import com.botwithus.bot.api.inventory.ActionTypes;
import com.botwithus.bot.api.model.*;
import com.botwithus.bot.api.query.ComponentFilter;
import com.botwithus.bot.api.query.EntityFilter;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;

/**
 * Polls game state for pinned variables, chat history, action history, and interface events.
 */
final class DataPoller {

    private static final Logger log = LoggerFactory.getLogger(DataPoller.class);

    private final XapiState state;
    private final NameResolver nameResolver;
    private final MiniMenuMatcher matcher;
    private final InspectorCollector inspectorCollector;
    private final InventoryTracker inventoryTracker;

    // Chat history polling fallback
    private volatile int lastChatHistorySize;

    // Action history polling (captures manual player actions not seen by events)
    private volatile boolean actionHistoryAvailable = true;
    private volatile long lastActionHistoryPoll;

    // Interface event tracking private state
    private volatile Set<Integer> previousOpenInterfaceIds = Set.of();

    DataPoller(XapiState state, NameResolver nameResolver, MiniMenuMatcher matcher,
               InspectorCollector inspectorCollector, InventoryTracker inventoryTracker) {
        this.state = state;
        this.nameResolver = nameResolver;
        this.matcher = matcher;
        this.inspectorCollector = inspectorCollector;
        this.inventoryTracker = inventoryTracker;
    }

    void pollPinnedVars(GameAPI api) {
        for (String key : state.pinnedVars) {
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
                state.pinnedCurrentValues.put(key, value);
            } catch (Exception e) {
                inspectorCollector.logItemVarError("pollPinnedVars key=" + key, e);
            }
        }
    }

    void pollChatHistory(GameAPI api) {
        try {
            List<ChatMessage> history = api.queryChatHistory(-1, 100);
            if (history != null && history.size() < lastChatHistorySize) {
                lastChatHistorySize = 0; // Server reset chat history
            }
            if (history != null && history.size() > lastChatHistorySize) {
                for (int i = lastChatHistorySize; i < history.size(); i++) {
                    ChatMessage msg = history.get(i);
                    if (msg == null) continue;
                    // Avoid duplicates by checking if state.chatLog already has this message
                    String text = msg.text() != null ? msg.text().replaceAll("<img=\\d+>", "").replaceAll("<col=[0-9a-fA-F]+>", "").replaceAll("</col>", "").trim() : "";
                    String player = msg.playerName() != null ? msg.playerName() : "";
                    boolean duplicate = false;
                    // Check last few entries for duplicate
                    for (int j = Math.max(0, state.chatLog.size() - 10); j < state.chatLog.size(); j++) {
                        ChatEntry ce = state.chatLog.get(j);
                        if (ce.text().equals(text) && ce.playerName().equals(player)
                                && ce.messageType() == msg.messageType()) {
                            duplicate = true;
                            break;
                        }
                    }
                    if (!duplicate) {
                        state.chatLog.add(new ChatEntry(msg.messageType(), text, player,
                                System.currentTimeMillis(), state.currentTick));
                    }
                }
                lastChatHistorySize = history.size();
            }
        } catch (Exception ignored) {}
    }

    void pollActionHistory(GameAPI api) {
        if (!actionHistoryAvailable || !state.recording) return;
        long now = System.currentTimeMillis();
        if (now - lastActionHistoryPoll < 600) return; // ~1 tick
        lastActionHistoryPoll = now;
        try {
            var history = api.getActionHistory(50, -1);
            if (history == null || history.isEmpty()) return;

            for (var entry : history) {
                // Only process entries newer than what we've already seen
                if (entry.timestamp() <= state.lastSeenActionTimestamp) continue;
                state.lastSeenActionTimestamp = entry.timestamp();

                // Check if this action was already captured by ActionExecutedEvent
                boolean duplicate = false;
                for (int j = Math.max(0, state.actionLog.size() - 20); j < state.actionLog.size(); j++) {
                    LogEntry existing = state.actionLog.get(j);
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

                // New action from history -- resolve names and log it
                String[] names = nameResolver.resolveNames(api,
                        entry.actionId(), entry.param1(), entry.param2(), entry.param3());

                // Try mini menu cache for option text and item name
                String[] menuMatch = matcher.matchMiniMenu(entry.actionId(), entry.param1(),
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

                state.actionLog.add(new LogEntry(
                        entry.actionId(), entry.param1(), entry.param2(), entry.param3(),
                        entry.timestamp(), state.currentTick, false, "history",
                        names[0], names[1], px, py, pp, pa, pm
                ));

                // Build snapshot to maintain index alignment with state.actionLog
                try {
                    BackpackSnapshot bp = state.cachedBackpack;
                    int openIface = inventoryTracker.resolveTopInterfaceId();
                    TriggerSignals triggers = inventoryTracker.computeTriggers(bp, pa, pm);
                    IntentHypothesis intent = IntentEngine.infer(
                            entry.actionId(), names[0], names[1], bp, triggers, openIface);
                    state.snapshotLog.add(new ActionSnapshot(bp, triggers, intent, openIface));
                    inventoryTracker.updatePreviousState(bp, openIface, pa, pm);
                } catch (Exception ignored) {
                    state.snapshotLog.add(null); // maintain index alignment
                }
            }
        } catch (NoClassDefFoundError e) {
            actionHistoryAvailable = false;
            log.warn("Action history polling unavailable (classloader issue) -- using event-based capture only");
        } catch (Throwable t) {
            log.debug("pollActionHistory error: {}", t.getMessage());
        }
    }

    void pollInterfaceEvents(GameAPI api) {
        try {
            List<OpenInterface> current = api.getOpenInterfaces();
            if (current == null) current = List.of();

            Set<Integer> currentIfaceIds = new HashSet<>();
            for (OpenInterface oi : current) currentIfaceIds.add(oi.interfaceId());
            Set<Integer> prevIds = previousOpenInterfaceIds;

            // Newly opened interfaces
            for (int id : currentIfaceIds) {
                if (!prevIds.contains(id)) {
                    // Check visibility -- only log if the interface has at least one visible component
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
                    state.interfaceEventLog.add(new InterfaceEvent("OPENED", id,
                            System.currentTimeMillis(), state.currentTick, snapshots));
                }
            }
            // Closed interfaces
            for (int id : prevIds) {
                if (!currentIfaceIds.contains(id)) {
                    state.interfaceEventLog.add(new InterfaceEvent("CLOSED", id,
                            System.currentTimeMillis(), state.currentTick, List.of()));
                }
            }
            previousOpenInterfaceIds = Set.copyOf(currentIfaceIds);
        } catch (Exception e) {
            log.debug("Interface event poll error: {}", e.getMessage());
        }
    }
}
