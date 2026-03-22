package com.xapi.debugger;

import com.botwithus.bot.api.GameAPI;
import com.botwithus.bot.api.inventory.ActionTypes;
import com.botwithus.bot.api.model.*;
import com.botwithus.bot.api.query.EntityFilter;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

/**
 * Resolves entity and option names for game actions using live queries,
 * offline cache, and RPC fallback.
 */
final class NameResolver {

    private static final Logger log = LoggerFactory.getLogger(NameResolver.class);

    private final XapiState state;

    // Per-tick entity caches for action resolution
    private volatile int resolveTickCache = -1;
    private volatile List<Entity> cachedNpcs;
    private volatile List<Entity> cachedPlayers;

    NameResolver(XapiState state) {
        this.state = state;
    }

    void invalidateTickCache() {
        resolveTickCache = -1;
        cachedNpcs = null;
        cachedPlayers = null;
    }

    String[] resolveNames(GameAPI api, int actionId, int p1, int p2, int p3) {
        try {
            int npcSlot = findSlot(ActionTypes.NPC_OPTIONS, actionId);
            if (npcSlot > 0) { log.debug("resolveNames: NPC path, slot={}", npcSlot); return resolveNpc(api, p1, npcSlot); }

            int objSlot = findSlot(ActionTypes.OBJECT_OPTIONS, actionId);
            if (objSlot > 0) { log.debug("resolveNames: OBJECT path, slot={}, typeId={}", objSlot, p1); return resolveObject(api, p1, objSlot); }

            int giSlot = findSlot(ActionTypes.GROUND_ITEM_OPTIONS, actionId);
            if (giSlot > 0) { log.debug("resolveNames: GROUND_ITEM path, slot={}", giSlot); return resolveGroundItem(api, p1, giSlot); }

            int playerSlot = findSlot(ActionTypes.PLAYER_OPTIONS, actionId);
            if (playerSlot > 0) { log.debug("resolveNames: PLAYER path"); return resolvePlayer(api, p1); }

            if (actionId == ActionTypes.COMPONENT) { log.debug("resolveNames: COMPONENT path"); return resolveComponent(api, p1, p2, p3); }
            if (actionId == ActionTypes.SELECT_COMPONENT_ITEM) { log.debug("resolveNames: SELECT_COMP_ITEM path"); return resolveComponent(api, p1, p2, p3); }
            if (actionId == ActionTypes.CONTAINER_ACTION) { log.debug("resolveNames: CONTAINER_ACTION path"); return resolveComponent(api, p1, p2, p3); }
            if (actionId == ActionTypes.DIALOGUE) { log.debug("resolveNames: DIALOGUE path"); return resolveComponent(api, p1, p2, p3); }
            log.debug("resolveNames: NO MATCH for actionId={}", actionId);
        } catch (Exception e) {
            log.debug("Name resolution failed for action {}: {}", actionId, e.getMessage());
        }
        return new String[]{null, null};
    }

    private String[] resolveNpc(GameAPI api, int serverIndex, int optionSlot) {
        try {
            if (cachedNpcs == null || resolveTickCache != state.currentTick) {
                cachedNpcs = api.queryEntities(
                        EntityFilter.builder().type("npc").maxResults(500).build());
                resolveTickCache = state.currentTick;
            }
            for (Entity npc : cachedNpcs) {
                if (npc.serverIndex() == serverIndex) {
                    String name = npc.name();
                    String option = null;

                    // 1. Try offline cache first (instant, timing-proof)
                    if (state.gameCache.isLoaded()) {
                        try {
                            var baseNpc = state.gameCache.getNpc(npc.typeId());
                            if (baseNpc != null) {
                                int varValue = -1;
                                if (baseNpc.varbitId() != -1) {
                                    try { varValue = api.getVarbit(baseNpc.varbitId()); } catch (Exception ignored) {}
                                } else if (baseNpc.varpId() != -1) {
                                    try { varValue = api.getVarp(baseNpc.varpId()); } catch (Exception ignored) {}
                                }
                                var resolved = state.gameCache.resolveNpc(npc.typeId(), varValue);
                                if (resolved != null) {
                                    if (name == null || name.isEmpty()) name = resolved.name();
                                    if (resolved.actions() != null && optionSlot - 1 >= 0
                                            && optionSlot - 1 < resolved.actions().size()) {
                                        option = resolved.actions().get(optionSlot - 1);
                                    }
                                    if (name != null && !name.isEmpty()) {
                                        return new String[]{name, option};
                                    }
                                }
                            }
                        } catch (Exception ignored) {}
                    }

                    // 2. Fallback to RPC
                    try {
                        NpcType type = state.npcTypeCache.computeIfAbsent(npc.typeId(), id -> {
                            try { return api.getNpcType(id); } catch (Exception e) { return null; }
                        });
                        if (type != null && (type.options() == null || type.options().stream().allMatch(s -> s == null || s.isEmpty()))
                                && (type.varbitId() != -1 || type.varpId() != -1)
                                && type.transforms() != null && !type.transforms().isEmpty()) {
                            int value = -1;
                            if (type.varbitId() != -1) {
                                try { value = api.getVarbit(type.varbitId()); } catch (Exception ignored) {}
                            } else if (type.varpId() != -1) {
                                try { value = api.getVarp(type.varpId()); } catch (Exception ignored) {}
                            }
                            if (value >= 0) {
                                int transformedId = value < type.transforms().size()
                                        ? type.transforms().get(value) : type.transforms().getLast();
                                if (transformedId != -1 && transformedId != npc.typeId()) {
                                    NpcType resolved = state.npcTypeCache.computeIfAbsent(transformedId, id -> {
                                        try { return api.getNpcType(id); } catch (Exception e) { return null; }
                                    });
                                    if (resolved != null) {
                                        type = resolved;
                                        if (name == null || name.isEmpty()) name = resolved.name();
                                    }
                                }
                            }
                        }
                        if (type != null && type.options() != null && optionSlot - 1 >= 0 && optionSlot - 1 < type.options().size()) {
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
        log.info("resolveObject: typeId={} optionSlot={}", typeId, optionSlot);

        // 1. Try offline cache first (instant, no RPC, timing-proof)
        if (state.gameCache.isLoaded()) {
            try {
                var baseLoc = state.gameCache.getLocation(typeId);
                if (baseLoc != null) {
                    // Resolve transform using var value from RPC (cheap call, always available)
                    int varValue = -1;
                    if (baseLoc.varbitId() != -1) {
                        try { varValue = api.getVarbit(baseLoc.varbitId()); } catch (Exception ignored) {}
                    } else if (baseLoc.varpId() != -1) {
                        try { varValue = api.getVarp(baseLoc.varpId()); } catch (Exception ignored) {}
                    }
                    var resolved = state.gameCache.resolveLocation(typeId, varValue);
                    if (resolved != null) {
                        String name = resolved.name();
                        String option = null;
                        if (resolved.actions() != null && optionSlot - 1 >= 0
                                && optionSlot - 1 < resolved.actions().size()) {
                            option = resolved.actions().get(optionSlot - 1);
                        }
                        if (name != null && !name.isEmpty()) {
                            log.info("resolveObject (cache): name='{}' option='{}'", name, option);
                            return new String[]{name, option};
                        }
                    }
                }
            } catch (Exception e) {
                log.debug("resolveObject cache error: {}", e.getMessage());
            }
        }

        // 2. Fallback to RPC (for cache miss or cache not loaded yet)
        try {
            LocationType type = state.locTypeCache.computeIfAbsent(typeId, id -> {
                try { return api.getLocationType(id); } catch (Exception e) { return null; }
            });
            if (type != null) {
                // Follow transform chain if base type has empty name (morphing objects)
                if ((type.name() == null || type.name().isEmpty())
                        && (type.varbitId() != -1 || type.varpId() != -1)
                        && type.transforms() != null && !type.transforms().isEmpty()) {
                    int value = -1;
                    if (type.varbitId() != -1) {
                        try { value = api.getVarbit(type.varbitId()); } catch (Exception ignored) {}
                    } else if (type.varpId() != -1) {
                        try { value = api.getVarp(type.varpId()); } catch (Exception ignored) {}
                    }
                    if (value >= 0) {
                        int transformedId = value < type.transforms().size()
                                ? type.transforms().get(value) : type.transforms().getLast();
                        if (transformedId != -1 && transformedId != typeId) {
                            LocationType resolved = state.locTypeCache.computeIfAbsent(transformedId, id -> {
                                try { return api.getLocationType(id); } catch (Exception e) { return null; }
                            });
                            if (resolved != null && resolved.name() != null && !resolved.name().isEmpty()) {
                                type = resolved;
                            }
                        }
                    }
                }
                String name = type.name();
                String option = null;
                if (type.options() != null && optionSlot - 1 >= 0 && optionSlot - 1 < type.options().size()) {
                    option = type.options().get(optionSlot - 1);
                }
                log.info("resolveObject (RPC): name='{}' option='{}'", name, option);
                return new String[]{name, option};
            }
        } catch (Exception e) {
            log.debug("resolveObject RPC error: {}", e.getMessage());
        }
        return new String[]{null, null};
    }

    private String[] resolveGroundItem(GameAPI api, int itemId, int optionSlot) {
        // 1. Try offline cache first (instant, no RPC)
        if (state.gameCache.isLoaded()) {
            var cached = state.gameCache.getItem(itemId);
            if (cached != null && cached.name() != null && !cached.name().isEmpty()) {
                String option = null;
                if (cached.groundActions() != null && optionSlot - 1 >= 0
                        && optionSlot - 1 < cached.groundActions().size()) {
                    option = cached.groundActions().get(optionSlot - 1);
                }
                return new String[]{cached.name(), option};
            }
        }

        // 2. Fallback to RPC
        try {
            ItemType type = state.itemTypeCache.computeIfAbsent(itemId, id -> {
                try { return api.getItemType(id); } catch (Exception e) { return null; }
            });
            if (type != null) {
                String name = type.name();
                String option = null;
                if (type.groundOptions() != null && optionSlot - 1 >= 0
                        && optionSlot - 1 < type.groundOptions().size()) {
                    option = type.groundOptions().get(optionSlot - 1);
                }
                return new String[]{name, option};
            }
        } catch (Exception ignored) {}
        return new String[]{null, null};
    }

    private String[] resolvePlayer(GameAPI api, int serverIndex) {
        try {
            if (cachedPlayers == null || resolveTickCache != state.currentTick) {
                cachedPlayers = api.queryEntities(
                        EntityFilter.builder().type("player").maxResults(200).build());
                resolveTickCache = state.currentTick;
            }
            for (Entity player : cachedPlayers) {
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

            // Resolve option name (cache survives interface closing)
            String optionName = null;
            String cacheKey = ifaceId + ":" + compId;
            List<String> options = null;
            try { options = api.getComponentOptions(ifaceId, compId); } catch (Exception ignored) {}
            log.debug("resolveComponent iface:{} comp:{} sub:{} optIdx:{} liveOptions:{}",
                    ifaceId, compId, subComponent, optionIndex,
                    options != null ? options : "null");
            if (options != null && !options.isEmpty()) {
                state.componentOptionsCache.put(cacheKey, options);
            } else {
                options = state.componentOptionsCache.get(cacheKey);
                log.debug("resolveComponent cached options for {}:{} = {}", ifaceId, compId,
                        options != null ? options : "null");
            }
            log.debug("resolveComponent iface:{} comp:{} sub:{} optIdx:{} options:{}",
                    ifaceId, compId, subComponent, optionIndex, options);
            if (options != null && optionIndex - 1 >= 0 && optionIndex - 1 < options.size()) {
                optionName = options.get(optionIndex - 1);
            }
            // Fallback: DIALOGUE (30) uses 0-based option index
            if (optionName == null && options != null && optionIndex >= 0 && optionIndex < options.size()) {
                optionName = options.get(optionIndex);
            }
            // Fallback: offline interface cache (timing-proof -- loaded from game cache dump)
            if (optionName == null && state.gameCache.isLoaded()) {
                List<String> cachedOpts = state.gameCache.getWidgetOptions(ifaceId, compId);
                if (cachedOpts != null) {
                    if (optionIndex - 1 >= 0 && optionIndex - 1 < cachedOpts.size()) {
                        optionName = cachedOpts.get(optionIndex - 1);
                    } else if (optionIndex >= 0 && optionIndex < cachedOpts.size()) {
                        optionName = cachedOpts.get(optionIndex);
                    }
                }
            }

            // Resolve entity name from the sub-component (slot) or component text
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
                                    entityName = lookupItemName(api, child.itemId());
                                }
                                break;
                            }
                        }
                    }
                } catch (Exception ignored) {}
            }
            // Fallback: getComponentItem RPC for sub-components with itemId=0
            if (entityName == null && subComponent >= 0) {
                try {
                    var compItem = api.getComponentItem(ifaceId, compId, subComponent);
                    if (compItem != null && compItem.itemId() > 0) {
                        entityName = lookupItemName(api, compItem.itemId());
                    }
                } catch (Exception ignored) {}
            }
            // For non-container components (sub=-1), try component text as entity name
            if (entityName == null && subComponent < 0) {
                try {
                    String text = api.getComponentText(ifaceId, compId);
                    if (text != null && !text.isEmpty()) {
                        entityName = text.replaceAll("<[^>]+>", "").trim(); // strip HTML tags
                    }
                } catch (Exception ignored) {}
                // Fallback: offline interface cache for component text
                if (entityName == null && state.gameCache.isLoaded()) {
                    String cachedText = state.gameCache.getWidgetText(ifaceId, compId);
                    if (cachedText != null && !cachedText.isEmpty()) {
                        entityName = cachedText.replaceAll("<[^>]+>", "").trim();
                    }
                }
            }

            // Final fallback: use interface name as entity context when we have an option but no entity
            // e.g. "Close" on bank -> "Close -> Bank"
            if (entityName == null && optionName != null && state.gameCache.isLoaded()) {
                entityName = state.gameCache.getInterfaceName(ifaceId);
            }

            log.debug("resolveComponent result: entity={} option={}", entityName, optionName);
            return new String[]{entityName, optionName};
        } catch (Exception ignored) {}
        return new String[]{null, null};
    }

    /**
     * Looks up an item name, trying the offline cache first then RPC fallback.
     */
    String lookupItemName(GameAPI api, int itemId) {
        // 1. Offline cache (instant)
        if (state.gameCache.isLoaded()) {
            var cached = state.gameCache.getItem(itemId);
            if (cached != null && cached.name() != null && !cached.name().isEmpty()) {
                return cached.name();
            }
        }
        // 2. RPC fallback
        try {
            ItemType type = state.itemTypeCache.computeIfAbsent(itemId, id -> {
                try { return api.getItemType(id); } catch (Exception e) { return null; }
            });
            if (type != null && type.name() != null) return type.name();
        } catch (Exception ignored) {}
        return null;
    }

    static boolean isComponentAction(int actionId) {
        return actionId == ActionTypes.COMPONENT
                || actionId == ActionTypes.SELECT_COMPONENT_ITEM
                || actionId == ActionTypes.CONTAINER_ACTION
                || actionId == ActionTypes.DIALOGUE;
    }

    static int findSlot(int[] options, int actionId) {
        return ActionTypes.findSlot(options, actionId);
    }
}
