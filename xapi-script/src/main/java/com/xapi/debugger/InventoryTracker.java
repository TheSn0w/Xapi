package com.xapi.debugger;

import static com.xapi.debugger.XapiData.*;

import com.botwithus.bot.api.GameAPI;
import com.botwithus.bot.api.model.*;
import com.botwithus.bot.api.query.InventoryFilter;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Tracks inventory changes and manages interaction snapshot state.
 */
final class InventoryTracker {

    private static final Logger log = LoggerFactory.getLogger(InventoryTracker.class);

    private final XapiState state;

    // Interaction snapshot state (private to event handler / onLoop)
    private volatile BackpackSnapshot previousBackpack;
    private volatile int previousOpenInterfaceId = -1;
    private volatile int previousPlayerAnim = -1;
    private volatile boolean previousPlayerMoving = false;
    private volatile int lastActionInventoryLogSize = 0;

    InventoryTracker(XapiState state) {
        this.state = state;
    }

    void collectInventoryDiff(GameAPI api) {
        try {
            List<InventoryItem> items = api.queryInventoryItems(
                    InventoryFilter.builder().inventoryId(93).nonEmpty(true).build());
            Map<Integer, Integer> current = new HashMap<>();
            for (InventoryItem item : items) {
                current.merge(item.itemId(), item.quantity(), Integer::sum);
            }

            Map<Integer, Integer> prev = state.lastInventorySnapshot;
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
                        state.inventoryLog.add(new InventoryChange(id, name, oldQty, newQty, now, state.currentTick));
                    }
                }
                // Cap at 2000 entries
                while (state.inventoryLog.size() > 2000) state.inventoryLog.remove(0);
            }
            state.lastInventorySnapshot = current;
            state.lastInventorySlotCount = items.size();

            // Build cached backpack snapshot (no extra RPC -- reuses data just queried)
            int occupied = items.size();
            int free = 28 - occupied;
            List<ItemSnapshot> snapItems = new ArrayList<>(current.size());
            for (var entry : current.entrySet()) {
                String name;
                try { name = api.getItemType(entry.getKey()).name(); } catch (Exception e) { name = "item:" + entry.getKey(); }
                snapItems.add(new ItemSnapshot(entry.getKey(), name, entry.getValue()));
            }
            state.cachedBackpack = new BackpackSnapshot(List.copyOf(snapItems), free, free <= 0);
        } catch (Exception ignored) {}
    }

    int resolveTopInterfaceId() {
        List<OpenInterface> ifaces = state.openInterfaces;
        if (ifaces == null || ifaces.isEmpty()) return -1;
        return ifaces.get(0).interfaceId();
    }

    TriggerSignals computeTriggers(BackpackSnapshot currentBp, int currentAnim, boolean currentMoving) {
        boolean invChanged = false;
        if (currentBp != null && previousBackpack != null) {
            invChanged = currentBp.freeSlots() != previousBackpack.freeSlots()
                    || currentBp.items().size() != previousBackpack.items().size();
        } else if (currentBp != null && previousBackpack == null) {
            invChanged = !currentBp.items().isEmpty();
        }

        boolean animEnded = previousPlayerAnim != -1 && currentAnim == -1;
        boolean playerStopped = previousPlayerMoving && !currentMoving;
        boolean varChanged = !state.varsByTick.getOrDefault(state.currentTick, List.of()).isEmpty();

        // Slice recent inventory changes since last action
        int logSize = state.inventoryLog.size();
        List<InventoryChange> recentItems = lastActionInventoryLogSize < logSize
                ? List.copyOf(state.inventoryLog.subList(lastActionInventoryLogSize, logSize))
                : List.of();

        List<VarChange> recentVars = state.varsByTick.getOrDefault(state.currentTick, List.of());

        return new TriggerSignals(invChanged, animEnded, playerStopped, varChanged,
                recentItems, List.copyOf(recentVars));
    }

    void updatePreviousState(BackpackSnapshot bp, int openIface, int anim, boolean moving) {
        previousBackpack = bp;
        previousOpenInterfaceId = openIface;
        previousPlayerAnim = anim;
        previousPlayerMoving = moving;
        lastActionInventoryLogSize = state.inventoryLog.size();
    }
}
