package com.xapi.debugger;

import static com.xapi.debugger.XapiData.*;

import com.botwithus.bot.api.GameAPI;
import com.botwithus.bot.api.cache.ItemVarbitDef;
import com.botwithus.bot.api.entities.EntityContext;
import com.botwithus.bot.api.inventory.Smithing;
import com.botwithus.bot.api.model.*;
import com.botwithus.bot.api.query.ComponentFilter;
import com.botwithus.bot.api.query.EntityFilter;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Collects inspector data: entities, interfaces, item varbits, production, and smithing state.
 */
final class InspectorCollector {

    private static final Logger log = LoggerFactory.getLogger(InspectorCollector.class);

    private final XapiState state;

    private static final Path ITEMVAR_ACCOUNTS_FILE = Path.of("xapi_itemvar_accounts.json");
    private static final int ITEM_VAR_ERROR_LOG_MAX = 20;

    // Live polling state: tracks decoded values per varbitId for change detection
    private volatile Map<Integer, Integer> liveSnapshot = null; // varbitId -> decoded value
    private final Map<Integer, Long> liveChangeTimes = new HashMap<>(); // varbitId -> last change timestamp


    InspectorCollector(XapiState state) {
        this.state = state;
    }

    void collectInspectorData(GameAPI api) {
        int dist = state.entityDistanceFilter;

        // Use entity facades for querying (SceneObjects uses "location" internally)
        try {
            var npcQuery = state.npcs.query().limit(100);
            if (dist > 0 && dist < 200) npcQuery.withinDistance(dist);
            state.nearbyNpcs = npcQuery.all().stream().map(EntityContext::raw).toList();
        } catch (Exception e) { log.debug("NPC query failed: {}", e.getMessage()); state.nearbyNpcs = List.of(); }

        try {
            var playerQuery = state.players.query().limit(50);
            if (dist > 0 && dist < 200) playerQuery.withinDistance(dist);
            state.nearbyPlayers = playerQuery.all().stream().map(EntityContext::raw).toList();
        } catch (Exception e) { log.debug("Player query failed: {}", e.getMessage()); state.nearbyPlayers = List.of(); }

        try {
            var objQuery = state.sceneObjects.query().limit(100);
            if (dist > 0 && dist < 200) objQuery.withinDistance(dist);
            state.nearbyObjects = objQuery.all().stream().map(EntityContext::raw).toList();
        } catch (Exception e) { log.debug("Object query failed: {}", e.getMessage()); state.nearbyObjects = List.of(); }

        // Ground items -- keep using raw API (facade returns different type)
        try {
            EntityFilter.Builder giF = EntityFilter.builder().sortByDistance(true).maxResults(50);
            LocalPlayer lp = state.localPlayerData;
            if (lp != null && dist > 0 && dist < 200) {
                giF.tileX(lp.tileX()).tileY(lp.tileY()).radius(dist);
            }
            state.nearbyGroundItems = api.queryGroundItems(giF.build());
        } catch (Exception e) { log.debug("Ground item query failed: {}", e.getMessage()); state.nearbyGroundItems = List.of(); }

        try { state.openInterfaces = api.getOpenInterfaces(); } catch (Exception ignored) { state.openInterfaces = List.of(); }

        log.debug("Inspector: NPCs={} Players={} Objects={} GroundItems={} dist={}",
                state.nearbyNpcs.size(), state.nearbyPlayers.size(), state.nearbyObjects.size(),
                state.nearbyGroundItems.size(), dist);

        // Batch-resolve ground item names (cap 50 unique IDs)
        try {
            Set<Integer> itemIds = new HashSet<>();
            for (GroundItemStack stack : state.nearbyGroundItems) {
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
            state.groundItemTypeCache = Map.copyOf(typeMap);
        } catch (Exception e) {
            log.debug("Ground item type resolution failed: {}", e.getMessage());
        }

        // Batch-fetch EntityInfo for hover tooltips (cap to 30 per type)
        try {
            Map<Integer, EntityInfo> infoMap = new HashMap<>();
            fetchEntityInfoBatch(api, state.nearbyNpcs, infoMap, 30);
            fetchEntityInfoBatch(api, state.nearbyPlayers, infoMap, 20);
            fetchEntityInfoBatch(api, state.nearbyObjects, infoMap, 30);
            state.entityInfoCache = Map.copyOf(infoMap);
        } catch (Exception e) {
            log.debug("EntityInfo batch fetch failed: {}", e.getMessage());
        }

        // Fetch children for inspected interface
        int inspId = state.inspectInterfaceId;
        if (inspId >= 0) {
            try {
                List<Component> children = api.getComponentChildren(inspId, 0);
                state.componentCache.put(inspId, children != null ? children : List.of());
                // Fetch text and options for each child
                if (children != null) {
                    for (Component c : children) {
                        String key = c.interfaceId() + ":" + c.componentId();
                        try {
                            String text = api.getComponentText(c.interfaceId(), c.componentId());
                            if (text != null && !text.isEmpty()) state.componentTextCache.put(key, text);
                        } catch (Exception ignored) {}
                        try {
                            List<String> opts = api.getComponentOptions(c.interfaceId(), c.componentId());
                            if (opts != null && !opts.isEmpty()) state.componentOptionsCache.put(key, opts);
                        } catch (Exception ignored) {}
                    }
                }
            } catch (Exception ignored) {}
            state.inspectInterfaceId = -1; // Reset -- one-shot fetch
        }

    }

    /**
     * Collects production interface state for the Production debug tab.
     * All data is pre-fetched here (onLoop thread) so the render thread never calls RPC.
     */
    void collectProductionData(GameAPI api) {
        try {
            boolean isOpen = api.isInterfaceOpen(1370);
            state.prodOpen = isOpen;
            if (!isOpen) {
                state.prodSelectedItem = -1;
                state.prodMaxQty = -1;
                state.prodChosenQty = -1;
                state.prodCategoryEnum = -1;
                state.prodProductListEnum = -1;
                state.prodCategoryDropdown = -1;
                state.prodHasCategories = false;
                state.prodSelectedName = null;
                state.prodGridEntries = List.of();
                state.prodCategoryNames = List.of();
                return;
            }

            // Read varps
            state.prodCategoryEnum = api.getVarp(1168);
            state.prodProductListEnum = api.getVarp(1169);
            state.prodSelectedItem = api.getVarp(1170);
            state.prodCategoryDropdown = api.getVarp(7881);
            state.prodMaxQty = api.getVarp(8846);
            state.prodChosenQty = api.getVarp(8847);
            state.prodHasCategories = state.prodCategoryEnum != -1 && state.prodCategoryDropdown != -1;

            // Resolve selected product name
            if (state.prodSelectedItem > 0) {
                try {
                    ItemType type = api.getItemType(state.prodSelectedItem);
                    state.prodSelectedName = type != null ? type.name() : null;
                } catch (Exception e) { state.prodSelectedName = null; }
            } else {
                state.prodSelectedName = null;
            }

            // Collect product grid entries
            try {
                List<Component> children = api.getComponentChildren(1371, 22);
                List<ProductionTab.ProductionTabEntry> entries = new ArrayList<>();
                for (int i = 0; i < children.size(); i++) {
                    if (i % 4 == 2) { // Icon sub-component carries the itemId
                        Component c = children.get(i);
                        if (c.itemId() > 0) {
                            String name = null;
                            try {
                                ItemType type = api.getItemType(c.itemId());
                                if (type != null) name = type.name();
                            } catch (Exception ignored) {}
                            entries.add(new ProductionTab.ProductionTabEntry(i / 4, c.itemId(), name));
                        }
                    }
                }
                state.prodGridEntries = List.copyOf(entries);
            } catch (Exception e) {
                state.prodGridEntries = List.of();
            }

            // Collect category names from enum
            if (state.prodHasCategories && state.prodCategoryDropdown > 0) {
                try {
                    var enumType = api.getEnumType(state.prodCategoryDropdown);
                    if (enumType != null && enumType.entries() != null) {
                        List<String> names = new ArrayList<>();
                        for (var entry : enumType.entries().values()) {
                            names.add(entry != null ? entry.toString() : "???");
                        }
                        state.prodCategoryNames = List.copyOf(names);
                    } else {
                        state.prodCategoryNames = List.of();
                    }
                } catch (Exception e) {
                    state.prodCategoryNames = List.of();
                }
            } else {
                state.prodCategoryNames = List.of();
            }
        } catch (Exception e) {
            log.debug("Production data collection failed: {}", e.getMessage());
            state.prodOpen = false;
        }
    }

    /**
     * Collects production progress state (interface 1251) for the Production debug tab.
     */
    void collectProductionProgressData(GameAPI api) {
        try {
            boolean isOpen = api.isInterfaceOpen(1251);
            int visibility = isOpen ? api.getVarp(3034) : 1;
            boolean producing = isOpen && visibility != 1;
            state.progressOpen = producing;
            state.progressVisibility = visibility;

            if (!producing) {
                state.progressTotal = -1;
                state.progressRemaining = -1;
                state.progressSpeedModifier = -1;
                state.progressProductId = -1;
                state.progressProductName = null;
                state.progressTimeText = null;
                state.progressCounterText = null;
                state.progressPercent = -1;
                return;
            }

            state.progressTotal = api.getVarcInt(2228);
            state.progressRemaining = api.getVarcInt(2229);
            state.progressSpeedModifier = api.getVarcInt(2227);
            state.progressProductId = api.getVarp(1175);
            state.progressPercent = state.progressTotal > 0 ? ((state.progressTotal - state.progressRemaining) * 100) / state.progressTotal : 0;

            if (state.progressProductId > 0) {
                try {
                    ItemType type = api.getItemType(state.progressProductId);
                    state.progressProductName = type != null ? type.name() : null;
                } catch (Exception e) { state.progressProductName = null; }
            } else {
                state.progressProductName = null;
            }

            try { state.progressTimeText = api.getComponentText(1251, 10); } catch (Exception e) { state.progressTimeText = null; }
            try { state.progressCounterText = api.getComponentText(1251, 27); } catch (Exception e) { state.progressCounterText = null; }
        } catch (Exception e) {
            log.debug("Production progress data collection failed: {}", e.getMessage());
            state.progressOpen = false;
        }
    }

    /**
     * Collects smithing/smelting interface state (interface 37) for the Smithing debug tab.
     */
    void collectSmithingData(GameAPI api) {
        try {
            boolean isOpen = api.isInterfaceOpen(37);
            state.smithOpen = isOpen;
            if (!isOpen) {
                state.smithIsSmelting = false;
                state.smithMaterialDbrow = -1;
                state.smithProductDbrow = -1;
                state.smithSelectedItem = -1;
                state.smithLocation = -1;
                state.smithQuantity = -1;
                state.smithQualityTier = -1;
                state.smithOutfitBonus1 = 0;
                state.smithOutfitBonus2 = 0;
                state.smithHeatEfficiency = 0;
                state.smithProductName = null;
                state.smithQualityName = null;
                state.smithMaterialEntries = List.of();
                state.smithProductEntries = List.of();
                state.smithActiveBonuses = List.of();
                state.smithExceedsBackpack = false;
                state.smithFullOutfit = false;
                state.smithVarrockArmour = false;
                return;
            }

            // Read varps
            state.smithMaterialDbrow = api.getVarp(8331);
            state.smithProductDbrow = api.getVarp(8332);
            state.smithSelectedItem = api.getVarp(8333);
            state.smithLocation = api.getVarp(8334);
            state.smithQuantity = api.getVarp(8336);

            // Read varbits
            state.smithQualityTier = api.getVarbit(43239);
            state.smithOutfitBonus1 = api.getVarbit(47760);
            state.smithOutfitBonus2 = api.getVarbit(47761);
            state.smithHeatEfficiency = api.getVarbit(20138);

            // Resolve quality name
            state.smithQualityName = switch (state.smithQualityTier) {
                case 0 -> "Base";
                case 1 -> "+1";
                case 2 -> "+2";
                case 3 -> "+3";
                case 4 -> "+4";
                case 5 -> "+5";
                case 50 -> "Burial";
                default -> "Unknown (" + state.smithQualityTier + ")";
            };

            // Detect smelting mode -- script 2600 sets comp(37,30) Y=39 for smelting, Y=69 for smithing
            try {
                var pos = api.getComponentPosition(37, 30);
                state.smithIsSmelting = pos != null && pos.y() == 39;
            } catch (Exception e) {
                state.smithIsSmelting = false;
            }

            // Resolve product name from comp text
            try {
                state.smithProductName = api.getComponentText(37, 40);
            } catch (Exception e) {
                state.smithProductName = null;
            }

            // Collect material grid entries
            int[] matGrids = {52, 62, 72, 82, 92};
            List<SmithingTab.SmithingTabEntry> matEntries = new ArrayList<>();
            for (int g = 0; g < matGrids.length; g++) {
                try {
                    List<Component> children = api.getComponentChildren(37, matGrids[g]);
                    for (Component c : children) {
                        if (c.itemId() > 0) {
                            String name = null;
                            try {
                                ItemType type = api.getItemType(c.itemId());
                                if (type != null) name = type.name();
                            } catch (Exception ignored) {}
                            matEntries.add(new SmithingTab.SmithingTabEntry(g, c.subComponentId(), c.itemId(), name));
                        }
                    }
                } catch (Exception ignored) {}
            }
            state.smithMaterialEntries = List.copyOf(matEntries);

            // Collect product grid entries
            int[] prodGrids = {103, 114, 125, 136, 147};
            List<SmithingTab.SmithingTabEntry> prodEntries = new ArrayList<>();
            for (int g = 0; g < prodGrids.length; g++) {
                try {
                    List<Component> children = api.getComponentChildren(37, prodGrids[g]);
                    for (Component c : children) {
                        if (c.itemId() > 0) {
                            String name = null;
                            try {
                                ItemType type = api.getItemType(c.itemId());
                                if (type != null) name = type.name();
                            } catch (Exception ignored) {}
                            prodEntries.add(new SmithingTab.SmithingTabEntry(g, c.subComponentId(), c.itemId(), name));
                        }
                    }
                } catch (Exception ignored) {}
            }
            state.smithProductEntries = List.copyOf(prodEntries);

            // Detect active bonus items via equipment inventory queries
            int[] bonusItems = {775, 25120, 25121, 25122, 25123, 25124, 32194, 11750, 11751, 11752, 19682};
            List<Integer> active = new ArrayList<>();
            for (int itemId : bonusItems) {
                try {
                    var items = api.queryInventoryItems(
                            com.botwithus.bot.api.query.InventoryFilter.builder()
                                    .inventoryId(94).itemId(itemId).build());
                    if (items != null && !items.isEmpty()) active.add(itemId);
                } catch (Exception ignored) {}
            }
            state.smithActiveBonuses = List.copyOf(active);

            // Exceeds backpack, outfit, armour checks
            Smithing smithingApi = new Smithing(api);
            state.smithExceedsBackpack = smithingApi.canExceedBackpackLimit();
            state.smithFullOutfit = smithingApi.isWearingBlacksmithOutfit();
            state.smithVarrockArmour = smithingApi.isWearingVarrockArmour();

        } catch (Exception e) {
            log.debug("Smithing data collection failed: {}", e.getMessage());
            state.smithOpen = false;
        }
    }

    void collectActiveSmithingData(GameAPI api) {
        try {
            Smithing smithingApi = new Smithing(api);

            // Scan backpack for all unfinished items (uses getItemVars)
            List<Smithing.UnfinishedItem> unfinished = smithingApi.getAllUnfinishedItems();
            state.allUnfinishedItems = unfinished;

            boolean isActive = !unfinished.isEmpty();
            state.activelySmithing = isActive;

            if (!isActive) {
                state.activeSmithingItem = null;
                state.smithMaxHeat = 0;
                state.smithHeatPercent = 0;
                state.smithHeatBand = "Zero";
                state.smithProgressPerStrike = 10;
                state.smithReheatRate = 0;
                return;
            }

            // Active item = first unfinished item in backpack
            Smithing.UnfinishedItem active = unfinished.get(0);
            state.activeSmithingItem = active;

            // Heat calculations -- use the active item's creating ID for max heat
            int maxHeat = 0;
            if (active.creatingItemId() > 0) {
                maxHeat = smithingApi.getMaxHeatForItem(active.creatingItemId());
            } else {
                maxHeat = smithingApi.getMaxHeat();
            }
            state.smithMaxHeat = maxHeat;

            int heatPct = maxHeat > 0 ? (active.currentHeat() * 100) / maxHeat : 0;
            state.smithHeatPercent = heatPct;

            if (heatPct >= 67) {
                state.smithHeatBand = "High";
                state.smithProgressPerStrike = 20;
            } else if (heatPct >= 34) {
                state.smithHeatBand = "Medium";
                state.smithProgressPerStrike = 16;
            } else if (heatPct >= 1) {
                state.smithHeatBand = "Low";
                state.smithProgressPerStrike = 13;
            } else {
                state.smithHeatBand = "Zero";
                state.smithProgressPerStrike = 10;
            }

            state.smithReheatRate = smithingApi.getReheatingRate();

        } catch (Exception e) {
            log.debug("Active smithing data collection failed: {}", e.getMessage());
            state.activelySmithing = false;
        }
    }

    /**
     * Continuously polls the inventory slot for item varbit values every loop iteration.
     * Decodes all varbit defs, detects changes from the previous poll, highlights them
     * in the live results, and logs changes to the change history.
     */
    void pollInvVarLive(GameAPI api) {
        if (!state.invVarLiveEnabled) {
            if (!state.invVarLiveResults.isEmpty()) {
                state.invVarLiveResults = List.of();
                state.invVarSearchStatus = "";
            }
            liveSnapshot = null;
            return;
        }

        if (Boolean.FALSE.equals(state.itemVarSystemAvailable)) {
            state.invVarLiveResults = List.of();
            state.invVarSearchStatus = "Item var system not available for this account";
            return;
        }

        int invId = state.invVarSearchInvId.get();
        int slot = state.invVarSearchSlot.get();

        try {
            var rawVars = api.getItemVars(invId, slot);
            if (rawVars == null || rawVars.isEmpty()) {
                state.invVarLiveResults = List.of();
                state.invVarSearchStatus = "No item vars at inv " + invId + " slot " + slot;
                liveSnapshot = null;
                return;
            }

            Map<Integer, Integer> packedByItemVarId = new HashMap<>();
            List<ItemVarbitDef> allDefs = new ArrayList<>();
            for (var iv : rawVars) {
                packedByItemVarId.put(iv.varId(), iv.value());
                allDefs.addAll(state.gameCache.getItemVarbitDefs(iv.varId()));
            }

            if (allDefs.isEmpty()) {
                state.invVarLiveResults = List.of();
                state.invVarSearchStatus = "No varbit definitions for item vars in this slot";
                liveSnapshot = null;
                return;
            }

            // Build previous decoded values map (varbitId -> decodedValue) from last poll
            Map<Integer, Integer> prevDecoded = liveSnapshot != null ? liveSnapshot : Map.of();
            long now = System.currentTimeMillis();

            // Decode all, skip zeros, collect all varbit IDs per unique decoded value
            // Keep one representative entry (narrowest bit width) per value
            Map<Integer, XapiData.InvVarLiveEntry> byValue = new LinkedHashMap<>();
            Map<Integer, List<Integer>> allIdsByValue = new LinkedHashMap<>();
            for (var def : allDefs) {
                Integer packed = packedByItemVarId.get(def.itemVarId());
                if (packed == null) continue;
                int decoded = def.decode(packed);
                int bits = def.highBit() - def.lowBit() + 1;

                allIdsByValue.computeIfAbsent(decoded, k -> new ArrayList<>()).add(def.varbitId());

                Integer prev = prevDecoded.get(def.varbitId());
                int prevVal = prev != null ? prev : decoded;
                // Preserve existing change time if value hasn't changed since last detected change
                long changeTime = (prev != null && prev != decoded) ? now : getLastChangeTime(def.varbitId());

                var entry = new XapiData.InvVarLiveEntry(def.varbitId(), def.itemVarId(), decoded, bits, prevVal, changeTime, List.of());
                byValue.merge(decoded, entry,
                        (existing, candidate) -> candidate.bits() < existing.bits() ? candidate : existing);
            }
            // Attach all varbit IDs to each representative entry
            for (var e : byValue.entrySet()) {
                List<Integer> ids = allIdsByValue.getOrDefault(e.getKey(), List.of());
                var old = e.getValue();
                e.setValue(new XapiData.InvVarLiveEntry(old.varbitId(), old.itemVarId(), old.decodedValue(),
                        old.bits(), old.previousValue(), old.lastChangeTime(), List.copyOf(ids)));
            }

            List<XapiData.InvVarLiveEntry> results = new ArrayList<>(byValue.values());
            results.sort(Comparator.comparingInt(XapiData.InvVarLiveEntry::bits)
                    .thenComparingInt(XapiData.InvVarLiveEntry::varbitId));

            // Detect changes and log them
            if (!prevDecoded.isEmpty()) {
                for (var entry : results) {
                    Integer prev = prevDecoded.get(entry.varbitId());
                    if (prev != null && prev != entry.decodedValue()) {
                        state.invVarChangeLog.add(new XapiData.InvVarChangeEntry(
                                entry.varbitId(), entry.itemVarId(), prev, entry.decodedValue(), now, entry.bits()));
                        // Cap change log at 200
                        while (state.invVarChangeLog.size() > 200) state.invVarChangeLog.remove(0);
                    }
                }
            }

            // Build new snapshot (varbitId -> decoded value) for next poll
            Map<Integer, Integer> newSnapshot = new HashMap<>();
            for (var entry : results) {
                newSnapshot.put(entry.varbitId(), entry.decodedValue());
            }
            // Store change times for entries that just changed
            for (var entry : results) {
                if (entry.lastChangeTime() > 0) {
                    liveChangeTimes.put(entry.varbitId(), entry.lastChangeTime());
                }
            }
            liveSnapshot = newSnapshot;

            state.invVarLiveResults = List.copyOf(results);
            state.invVarSearchStatus = results.size() + " varbits | inv " + invId + " slot " + slot;
        } catch (Exception e) {
            state.invVarLiveResults = List.of();
            state.invVarSearchStatus = "Error: " + e.getMessage();
            logItemVarError("pollInvVarLive inv=" + invId + " slot=" + slot, e);
        }
    }

    private long getLastChangeTime(int varbitId) {
        Long t = liveChangeTimes.get(varbitId);
        return t != null ? t : 0;
    }

    @SuppressWarnings("unchecked")
    private Boolean loadItemVarStatus(String playerName) {
        try {
            if (!Files.exists(ITEMVAR_ACCOUNTS_FILE)) return null;
            String json = Files.readString(ITEMVAR_ACCOUNTS_FILE);
            Map<String, Map<String, Object>> accounts = XapiState.GSON.fromJson(json, Map.class);
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
                accounts = XapiState.GSON.fromJson(json, Map.class);
                if (accounts == null) accounts = new LinkedHashMap<>();
            } else {
                accounts = new LinkedHashMap<>();
            }
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("available", available);
            entry.put("lastChecked", LocalDateTime.now().toString());
            accounts.put(playerName, entry);
            Files.writeString(ITEMVAR_ACCOUNTS_FILE, XapiState.GSON.toJson(accounts));
        } catch (Exception e) {
            log.debug("Failed to save item var account status: {}", e.getMessage());
        }
    }

    void logItemVarError(String context, Exception e) {
        int count = ++state.itemVarErrorLogCount;
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

    void fetchEntityInfoBatch(GameAPI api, List<Entity> entities, Map<Integer, EntityInfo> target, int cap) {
        int limit = Math.min(cap, entities.size());
        for (int i = 0; i < limit; i++) {
            try {
                Entity e = entities.get(i);
                EntityInfo info = api.getEntityInfo(e.handle());
                if (info != null) target.put(e.handle(), info);
            } catch (Exception ignored) {}
        }
    }
}
