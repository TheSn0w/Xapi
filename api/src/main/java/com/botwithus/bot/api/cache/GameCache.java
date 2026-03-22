package com.botwithus.bot.api.cache;

import java.util.List;

/**
 * Read-only interface for offline game cache lookups.
 * Provides instant name/action/transform/component resolution without RPC calls.
 */
public interface GameCache {

    boolean isLoaded();

    CachedLocation getLocation(int id);

    CachedItem getItem(int id);

    CachedNpc getNpc(int id);

    /**
     * Returns a single item varbit definition by its varbit ID.
     */
    ItemVarbitDef getItemVarbitDef(int varbitId);

    /**
     * Returns all item varbit definitions for a given item var ID.
     */
    List<ItemVarbitDef> getItemVarbitDefs(int itemVarId);

    /**
     * Gets the cached menu options for an interface widget.
     */
    List<String> getWidgetOptions(int ifaceId, int compId);

    /**
     * Gets the cached text label for an interface widget.
     */
    String getWidgetText(int ifaceId, int compId);

    /**
     * Returns a known interface name for common interfaces.
     */
    String getInterfaceName(int ifaceId);

    /**
     * Resolves a location's name following its transform/morph chain.
     *
     * @param typeId   the base location type ID
     * @param varValue the current varbit/varp value (use -1 to skip transform)
     */
    CachedLocation resolveLocation(int typeId, int varValue);

    /**
     * Resolves an NPC's definition following its transform/morph chain.
     *
     * @param typeId   the base NPC type ID
     * @param varValue the current varbit/varp value (use -1 to skip transform)
     */
    CachedNpc resolveNpc(int typeId, int varValue);
}
