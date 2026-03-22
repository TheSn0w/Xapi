package com.botwithus.bot.api.util;

import com.botwithus.bot.api.GameAPI;
import com.botwithus.bot.api.cache.GameCache;
import com.botwithus.bot.api.cache.ItemVarbitDef;

/**
 * Convenience utility for reading inventory varbits (item-level varbits
 * packed inside item var values). Wraps the raw getItemVarValue + cache
 * decode into a single call.
 */
public final class VarManager {

    private final GameAPI api;
    private final GameCache cache;

    public VarManager(GameAPI api, GameCache cache) {
        this.api = api;
        this.cache = cache;
    }

    /**
     * Reads a specific inventory varbit by decoding the packed item var value.
     *
     * @param inventoryId the inventory container (e.g., 94 for equipment)
     * @param slot        the slot index within the inventory
     * @param varbitId    the varbit ID from the game cache
     * @return the decoded varbit value, or -1 if the varbit definition is not found
     */
    public int getInvVarbit(int inventoryId, int slot, int varbitId) {
        ItemVarbitDef def = cache.getItemVarbitDef(varbitId);
        if (def == null) return -1;
        int packed = api.getItemVarValue(inventoryId, slot, def.itemVarId());
        return def.decode(packed);
    }
}
