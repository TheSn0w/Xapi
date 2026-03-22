package com.xapi.debugger;


import com.botwithus.bot.api.model.MiniMenuEntry;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Shared data records and constants used across all Xapi tabs.
 */
public final class XapiData {

    private XapiData() {}

    // ── Data records ─────────────────────────────────────────────────────

    public record LogEntry(int actionId, int param1, int param2, int param3,
                           long timestamp, int gameTick, boolean wasBlocked, String source,
                           String entityName, String optionName,
                           int playerX, int playerY, int playerPlane,
                           int playerAnim, boolean playerMoving) {}

    public record VarChange(String type, int varId, int oldValue, int newValue,
                            long timestamp, int gameTick) {}

    public record ChatEntry(int messageType, String text, String playerName,
                            long timestamp, int gameTick) {}

    public record InterfaceComponentSnapshot(int componentId, int subComponentId, int type,
                                             String text, List<String> options, int itemId, int spriteId) {}

    public record InterfaceEvent(String type, int interfaceId, long timestamp, int gameTick,
                                 List<InterfaceComponentSnapshot> components) {}

    public record MenuSnapshot(long timestamp, int gameTick, List<MiniMenuEntry> entries) {}

    public record InventoryChange(int itemId, String itemName, int oldQty, int newQty,
                                  long timestamp, int gameTick) {}

    /** Live inventory varbit entry — current decoded value with change tracking. */
    public record InvVarLiveEntry(int varbitId, int itemVarId, int decodedValue, int bits,
                                  int previousValue, long lastChangeTime,
                                  List<Integer> allVarbitIds) {}

    /** Logged change from the live inventory varbit poller. */
    public record InvVarChangeEntry(int varbitId, int itemVarId, int oldDecoded, int newDecoded,
                                    long timestamp, int bits) {}

    // ── Interaction snapshot records ──────────────────────────────────────

    public record ItemSnapshot(int itemId, String name, int quantity) {}

    public record BackpackSnapshot(List<ItemSnapshot> items, int freeSlots, boolean full) {}

    public record TriggerSignals(boolean inventoryChanged, boolean animationEnded,
                                  boolean playerStopped, boolean varbitChanged,
                                  List<InventoryChange> recentItemChanges,
                                  List<VarChange> recentVarChanges) {}

    public record IntentHypothesis(String description, String confidence) {}

    public record ActionSnapshot(BackpackSnapshot backpack, TriggerSignals triggers,
                                  IntentHypothesis intent, int openInterfaceId) {}

    // ── Session export/import ─────────────────────────────────────────────

    public record SessionData(List<LogEntry> actions, List<VarChange> vars, List<ChatEntry> chat,
                              List<ActionSnapshot> snapshots,
                              long exportTime, String description,
                              long lastSeenActionTimestamp) {}

    public record XapiSettings(
            boolean recording, boolean blocking, boolean selectiveBlocking,
            boolean trackVars, boolean trackChat, boolean trackItemVarbits,
            boolean[] categoryFilters, boolean[] selectiveBlockCategories,
            boolean showVarbits, boolean showVarps, boolean showVarcs, boolean showVarcStrs, boolean showItemVarbits,
            String varFilterText, String varcWatchIds,
            Set<String> pinnedVars, Map<String, String> varAnnotations,
            boolean useNamesForGeneration, String scriptClassName,
            float replaySpeed,
            int entityDistanceFilter,
            boolean[] columnVisibility,
            boolean autoScroll
    ) {}

}
