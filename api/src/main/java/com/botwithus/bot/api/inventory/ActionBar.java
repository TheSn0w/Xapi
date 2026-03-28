package com.botwithus.bot.api.inventory;

import com.botwithus.bot.api.GameAPI;
import com.botwithus.bot.api.constants.InterfaceIds;
import com.botwithus.bot.api.model.Component;
import com.botwithus.bot.api.model.EnumType;
import com.botwithus.bot.api.model.GameAction;
import com.botwithus.bot.api.model.ItemType;
import com.botwithus.bot.api.model.StructType;
import com.botwithus.bot.api.query.ComponentFilter;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * Provides access to the player's action bar (interface 1477).
 *
 * <p>The RS3 action bar has 14 ability/item slots (0-13) and supports multiple
 * bar presets (1-15). Each slot can hold an ability, prayer, spell, item, or
 * emote. This wrapper provides methods to read slot contents, find slots by
 * sprite/item, activate slots, and switch between bar presets.</p>
 *
 * @see ComponentHelper
 * @see InterfaceIds#ACTION_BAR
 */
public final class ActionBar {

    /** RS3 action bar interface ID. */
    public static final int INTERFACE_ID = InterfaceIds.ACTION_BAR;

    /** Component ID of the slot container that holds all 14 ability slots. */
    public static final int SLOT_CONTAINER_COMPONENT = 7;

    /** Total number of slots on a single action bar. */
    public static final int SLOT_COUNT = 14;

    // ========================== Varbit/Varc IDs ==========================

    /** Varbit for the currently active action bar preset (1-15). */
    public static final int VARBIT_ACTIVE_BAR = 4020;

    /** Varbit for whether the action bar is locked (1 = locked). */
    public static final int VARBIT_BAR_LOCKED = 18994;

    /** Varbit for whether the action bar is minimized (1 = minimized). */
    public static final int VARBIT_BAR_MINIMIZED = 18995;

    /**
     * Base varbit for action bar slot type/id pairs.
     * Slot N uses varbits (BASE + N*2) for type and (BASE + N*2 + 1) for ability ID.
     */
    private static final int VARBIT_SLOT_BASE = 1747;

    /** Enum 13199: maps slot type varbit value to a category enum ID. */
    private static final int ENUM_TYPE_TO_CATEGORY = 13199;

    /** Struct param ID for the ability name string. */
    private static final int PARAM_ABILITY_NAME = 2794;

    /** Struct param ID for the ability sprite ID. */
    private static final int PARAM_ABILITY_SPRITE = 2802;

    /** Struct param ID for the ability description. */
    private static final int PARAM_ABILITY_DESCRIPTION = 2795;

    /** Slot type value that indicates an item shortcut (resolved via itemId, not ability cache). */
    private static final int SLOT_TYPE_ITEM = 10;

    // ========================== Component IDs ==========================

    /** Component ID for the adrenaline/special attack text. */
    public static final int COMPONENT_ADRENALINE_TEXT = 42;

    /** Component ID for the bar preset left arrow (previous bar). */
    public static final int COMPONENT_PREV_BAR = 50;

    /** Component ID for the bar preset right arrow (next bar). */
    public static final int COMPONENT_NEXT_BAR = 52;

    /** Component ID for the bar number text. */
    public static final int COMPONENT_BAR_NUMBER = 51;

    private final GameAPI api;

    /**
     * Creates a new action bar wrapper.
     *
     * @param api the game API instance
     */
    public ActionBar(GameAPI api) {
        this.api = api;
    }

    // ========================== State Methods ==========================

    /**
     * Checks if the action bar interface is currently open/visible.
     *
     * @return {@code true} if the action bar is open
     */
    public boolean isOpen() {
        return api.isInterfaceOpen(INTERFACE_ID);
    }

    /**
     * Returns the currently active action bar preset number (1-15).
     *
     * @return the active bar preset, or -1 if unavailable
     */
    public int getActiveBar() {
        return api.getVarbit(VARBIT_ACTIVE_BAR);
    }

    /**
     * Checks if the action bar is locked (slots cannot be dragged/modified).
     *
     * @return {@code true} if the bar is locked
     */
    public boolean isLocked() {
        return api.getVarbit(VARBIT_BAR_LOCKED) == 1;
    }

    /**
     * Checks if the action bar is minimized.
     *
     * @return {@code true} if the bar is minimized
     */
    public boolean isMinimized() {
        return api.getVarbit(VARBIT_BAR_MINIMIZED) == 1;
    }

    // ========================== Slot Name Resolution ==========================

    /**
     * Information about an action bar slot resolved from varbits, game cache, and component data.
     *
     * @param slot        the slot index (0-13)
     * @param slotType    the raw type varbit value (1=melee, 5=ranged, 6=magic, 10=item, 11=teleport, etc.)
     * @param abilityId   the raw ability/item ID from the varbit
     * @param name        the resolved name (ability name or item name), or empty if unresolved
     * @param description the ability description, or empty
     * @param spriteId    the ability sprite ID from the struct, or -1
     * @param compItemId  the item ID from the component (for items), or -1
     * @param compSpriteId the sprite ID from the component, or -1
     * @param empty       true if the slot has no content
     */
    public record SlotInfo(int slot, int slotType, int abilityId, String name,
                           String description, int spriteId, int compItemId,
                           int compSpriteId, boolean empty) {}

    /**
     * Resolves full slot information for all 14 slots using the varbit → enum → struct chain.
     *
     * @return list of 14 SlotInfo entries
     */
    public List<SlotInfo> getAllSlotInfo() {
        List<SlotInfo> infos = new ArrayList<>(SLOT_COUNT);
        for (int i = 0; i < SLOT_COUNT; i++) {
            infos.add(getSlotInfo(i));
        }
        return infos;
    }

    /**
     * Resolves slot information for a specific slot using the varbit → enum → struct chain.
     * <p>Resolution chain:
     * <ol>
     *   <li>Read slot type varbit (1747 + slot*2) and ability ID varbit (1748 + slot*2)</li>
     *   <li>Look up type in enum 13199 to get the category enum ID</li>
     *   <li>Look up ability ID in the category enum to get a struct ID</li>
     *   <li>Read struct param 2794 for the ability name, 2802 for sprite ID</li>
     * </ol>
     *
     * @param slot the slot index (0-13)
     * @return the resolved slot info
     */
    public SlotInfo getSlotInfo(int slot) {
        validateSlot(slot);

        // Read component data for this slot
        Component comp = getSlot(slot);
        int compItemId = (comp != null) ? comp.itemId() : -1;
        int compSpriteId = (comp != null) ? comp.spriteId() : -1;

        // Read varbits for this slot
        int typeVarbit = VARBIT_SLOT_BASE + slot * 2;
        int idVarbit = VARBIT_SLOT_BASE + slot * 2 + 1;

        int slotType = api.getVarbit(typeVarbit);
        int abilityId = api.getVarbit(idVarbit);

        // Determine emptiness from component data (most reliable)
        boolean empty = (compItemId <= 0 && compSpriteId < 0);
        if (empty && slotType == 0 && abilityId == 0) {
            return new SlotInfo(slot, 0, 0, "", "", -1, -1, -1, true);
        }

        // Item — resolve via component itemId first (full range), fall back to varbit
        if (compItemId > 0) {
            String itemName = "";
            ItemType itemType = api.getItemType(compItemId);
            if (itemType != null && itemType.name() != null) {
                itemName = itemType.name();
            }
            return new SlotInfo(slot, slotType, abilityId, itemName, "",
                    -1, compItemId, compSpriteId, false);
        }

        // Ability/spell/prayer/teleport — resolve via enum → struct chain
        String name = "";
        String description = "";
        int spriteId = -1;

        if (slotType > 0) {
            try {
                EnumType categoryEnum = resolveCategoryEnum(slotType);
                if (categoryEnum != null && categoryEnum.entries() != null) {
                    Object structIdObj = categoryEnum.entries().get(String.valueOf(abilityId));
                    if (structIdObj instanceof Number structIdNum) {
                        StructType struct = api.getStructType(structIdNum.intValue());
                        if (struct != null && struct.params() != null) {
                            Map<String, Object> params = struct.params();
                            Object nameObj = params.get(String.valueOf(PARAM_ABILITY_NAME));
                            if (nameObj instanceof String s) name = s;
                            Object descObj = params.get(String.valueOf(PARAM_ABILITY_DESCRIPTION));
                            if (descObj instanceof String s) description = s;
                            Object spriteObj = params.get(String.valueOf(PARAM_ABILITY_SPRITE));
                            if (spriteObj instanceof Number n) spriteId = n.intValue();
                        }
                    }
                }
            } catch (Exception ignored) {}
        }

        return new SlotInfo(slot, slotType, abilityId, name, description,
                spriteId, compItemId, compSpriteId, empty);
    }

    /**
     * Resolves the name of whatever is in a specific slot (ability, item, spell, etc.).
     *
     * @param slot the slot index (0-13)
     * @return the resolved name, or empty string if the slot is empty or unresolvable
     */
    public String getSlotName(int slot) {
        return getSlotInfo(slot).name();
    }

    /**
     * Builds a map of sprite ID → ability name by scanning all 15 action bar presets.
     * This covers abilities on any bar, not just the currently active one.
     *
     * @return map of sprite ID to ability name
     */
    public Map<Integer, String> buildSpriteNameMap() {
        Map<Integer, String> map = new java.util.HashMap<>();
        for (int bar = 0; bar < 15; bar++) {
            int barBase = VARBIT_SLOT_BASE + bar * (SLOT_COUNT * 2);
            for (int slot = 0; slot < SLOT_COUNT; slot++) {
                try {
                    int slotType = api.getVarbit(barBase + slot * 2);
                    int abilityId = api.getVarbit(barBase + slot * 2 + 1);
                    if (slotType == 0 && abilityId == 0) continue;
                    if (slotType == SLOT_TYPE_ITEM) continue;

                    String name = resolveAbilityName(slotType, abilityId);
                    int spriteId = resolveAbilitySpriteId(slotType, abilityId);
                    if (!name.isEmpty() && spriteId > 0) {
                        map.put(spriteId, name);
                    }
                } catch (Exception ignored) {}
            }
        }
        return map;
    }

    /**
     * Builds a complete sprite ID → ability name map by iterating ALL ability category enums
     * and ALL their entries from the game cache. This covers every ability in the game,
     * not just those currently assigned to action bars.
     *
     * <p>This is more expensive than {@link #buildSpriteNameMap()} but guarantees complete coverage.
     * Should be called once and cached.</p>
     *
     * @return map of sprite ID to ability name for all known abilities
     */
    public Map<Integer, String> buildFullSpriteNameMap() {
        Map<Integer, String> map = new java.util.HashMap<>();

        // Read the master enum to get all category enum IDs
        EnumType masterEnum = api.getEnumType(ENUM_TYPE_TO_CATEGORY);
        if (masterEnum == null || masterEnum.entries() == null) return map;

        // Iterate each category (melee, ranged, magic, constitution, other, teleport, familiar, necromancy)
        for (Object categoryEnumIdObj : masterEnum.entries().values()) {
            if (!(categoryEnumIdObj instanceof Number categoryEnumId)) continue;

            EnumType categoryEnum;
            try {
                categoryEnum = api.getEnumType(categoryEnumId.intValue());
            } catch (Exception ignored) { continue; }
            if (categoryEnum == null || categoryEnum.entries() == null) continue;

            // Iterate every ability in this category
            for (Object structIdObj : categoryEnum.entries().values()) {
                if (!(structIdObj instanceof Number structIdNum)) continue;

                try {
                    StructType struct = api.getStructType(structIdNum.intValue());
                    if (struct == null || struct.params() == null) continue;

                    Map<String, Object> params = struct.params();
                    Object nameObj = params.get(String.valueOf(PARAM_ABILITY_NAME));
                    Object spriteObj = params.get(String.valueOf(PARAM_ABILITY_SPRITE));

                    if (nameObj instanceof String name && spriteObj instanceof Number sprite) {
                        int spriteId = sprite.intValue();
                        if (!name.isEmpty() && spriteId > 0) {
                            map.put(spriteId, name);
                        }
                    }
                } catch (Exception ignored) {}
            }
        }

        return map;
    }

    /**
     * Resolves an ability name from a slot type and ability ID via the enum → struct chain.
     *
     * @param slotType  the slot type varbit value
     * @param abilityId the ability ID varbit value
     * @return the ability name, or empty string if unresolvable
     */
    public String resolveAbilityName(int slotType, int abilityId) {
        try {
            StructType struct = resolveAbilityStruct(slotType, abilityId);
            if (struct != null && struct.params() != null) {
                Object nameObj = struct.params().get(String.valueOf(PARAM_ABILITY_NAME));
                if (nameObj instanceof String s) return s;
            }
        } catch (Exception ignored) {}
        return "";
    }

    private int resolveAbilitySpriteId(int slotType, int abilityId) {
        try {
            StructType struct = resolveAbilityStruct(slotType, abilityId);
            if (struct != null && struct.params() != null) {
                Object spriteObj = struct.params().get(String.valueOf(PARAM_ABILITY_SPRITE));
                if (spriteObj instanceof Number n) return n.intValue();
            }
        } catch (Exception ignored) {}
        return -1;
    }

    private StructType resolveAbilityStruct(int slotType, int abilityId) {
        EnumType categoryEnum = resolveCategoryEnum(slotType);
        if (categoryEnum == null || categoryEnum.entries() == null) return null;
        Object structIdObj = categoryEnum.entries().get(String.valueOf(abilityId));
        if (structIdObj instanceof Number structIdNum) {
            return api.getStructType(structIdNum.intValue());
        }
        return null;
    }

    private EnumType resolveCategoryEnum(int slotType) {
        EnumType masterEnum = api.getEnumType(ENUM_TYPE_TO_CATEGORY);
        if (masterEnum == null || masterEnum.entries() == null) return null;

        Object categoryEnumIdObj = masterEnum.entries().get(String.valueOf(slotType));
        if (categoryEnumIdObj instanceof Number categoryEnumId) {
            return api.getEnumType(categoryEnumId.intValue());
        }
        return null;
    }

    // ========================== Slot Reading ==========================

    /**
     * Returns all slot components on the action bar.
     * Each component represents one of the 14 slots, identified by its
     * {@code subComponentId} (0-13).
     *
     * @return list of slot components, may be empty if the bar is not visible
     */
    public List<Component> getSlots() {
        List<Component> children = api.getComponentChildren(INTERFACE_ID, SLOT_CONTAINER_COMPONENT);
        if (children == null) return Collections.emptyList();
        return children.stream()
                .filter(c -> c.subComponentId() >= 0 && c.subComponentId() < SLOT_COUNT)
                .toList();
    }

    /**
     * Returns the component in a specific slot.
     *
     * @param slot the slot index (0-13)
     * @return the slot component, or {@code null} if not found
     * @throws IllegalArgumentException if slot is out of range
     */
    public Component getSlot(int slot) {
        validateSlot(slot);
        List<Component> children = api.getComponentChildren(INTERFACE_ID, SLOT_CONTAINER_COMPONENT);
        if (children == null) return null;
        return children.stream()
                .filter(c -> c.subComponentId() == slot)
                .findFirst().orElse(null);
    }

    /**
     * Checks if a slot is empty (no ability, item, or spell assigned).
     *
     * @param slot the slot index (0-13)
     * @return {@code true} if the slot has no content
     */
    public boolean isSlotEmpty(int slot) {
        Component comp = getSlot(slot);
        if (comp == null) return true;
        return comp.itemId() == -1 && comp.spriteId() == -1;
    }

    /**
     * Returns a list of non-empty slot components (slots that have something assigned).
     *
     * @return list of occupied slot components
     */
    public List<Component> getOccupiedSlots() {
        return getSlots().stream()
                .filter(c -> c.itemId() != -1 || c.spriteId() != -1)
                .toList();
    }

    /**
     * Returns a list of empty slot indices.
     *
     * @return list of empty slot indices (0-13)
     */
    public List<Integer> getEmptySlotIndices() {
        List<Component> slots = getSlots();
        List<Integer> empty = new ArrayList<>();
        for (Component c : slots) {
            if (c.itemId() == -1 && c.spriteId() == -1) {
                empty.add(c.subComponentId());
            }
        }
        return empty;
    }

    // ========================== Find Methods ==========================

    /**
     * Finds the first slot containing the specified item ID.
     *
     * @param itemId the item ID to search for
     * @return the slot component, or {@code null} if not found
     */
    public Component findByItemId(int itemId) {
        return getSlots().stream()
                .filter(c -> c.itemId() == itemId)
                .findFirst().orElse(null);
    }

    /**
     * Finds the first slot displaying the specified sprite ID.
     * Abilities and prayers are identified by their sprite.
     *
     * @param spriteId the sprite ID to search for
     * @return the slot component, or {@code null} if not found
     */
    public Component findBySpriteId(int spriteId) {
        return getSlots().stream()
                .filter(c -> c.spriteId() == spriteId)
                .findFirst().orElse(null);
    }

    /**
     * Finds all slots containing the specified item ID.
     *
     * @param itemId the item ID to search for
     * @return list of matching slot components
     */
    public List<Component> findAllByItemId(int itemId) {
        return getSlots().stream()
                .filter(c -> c.itemId() == itemId)
                .toList();
    }

    /**
     * Checks if the action bar contains a slot with the given item ID.
     *
     * @param itemId the item ID
     * @return {@code true} if found
     */
    public boolean containsItem(int itemId) {
        return findByItemId(itemId) != null;
    }

    /**
     * Checks if the action bar contains a slot with the given sprite ID.
     *
     * @param spriteId the sprite ID
     * @return {@code true} if found
     */
    public boolean containsSprite(int spriteId) {
        return findBySpriteId(spriteId) != null;
    }

    /**
     * Gets the slot index (0-13) of the first slot containing the given item.
     *
     * @param itemId the item ID
     * @return the slot index, or -1 if not found
     */
    public int slotOf(int itemId) {
        Component comp = findByItemId(itemId);
        return comp != null ? comp.subComponentId() : -1;
    }

    /**
     * Gets the slot index (0-13) of the first slot with the given sprite.
     *
     * @param spriteId the sprite ID
     * @return the slot index, or -1 if not found
     */
    public int slotOfSprite(int spriteId) {
        Component comp = findBySpriteId(spriteId);
        return comp != null ? comp.subComponentId() : -1;
    }

    /**
     * Finds the first slot containing an item or ability whose name contains the given string (case-insensitive).
     * Resolves both item names (via item cache) and ability names (via varbit → enum → struct chain).
     *
     * @param name the name substring to search for
     * @return the slot component, or {@code null} if not found
     */
    public Component findByName(String name) {
        String lowerName = name.toLowerCase();
        List<Component> slots = getSlots();
        for (int i = 0; i < slots.size(); i++) {
            Component c = slots.get(i);
            // Check item name
            if (c.itemId() > 0 && matchesName(c.itemId(), lowerName)) {
                return c;
            }
            // Check ability name via varbit chain
            if (c.spriteId() >= 0 && i < SLOT_COUNT) {
                SlotInfo info = getSlotInfo(i);
                if (!info.name().isEmpty() && info.name().toLowerCase().contains(lowerName)) {
                    return c;
                }
            }
        }
        return null;
    }

    /**
     * Finds the first slot containing an item or ability whose name exactly matches (case-insensitive).
     *
     * @param name the exact name to match
     * @return the slot component, or {@code null} if not found
     */
    public Component findByNameExact(String name) {
        String lowerName = name.toLowerCase();
        List<Component> slots = getSlots();
        for (int i = 0; i < slots.size(); i++) {
            Component c = slots.get(i);
            if (c.itemId() > 0 && matchesNameExact(c.itemId(), lowerName)) {
                return c;
            }
            if (c.spriteId() >= 0 && i < SLOT_COUNT) {
                SlotInfo info = getSlotInfo(i);
                if (!info.name().isEmpty() && info.name().toLowerCase().equals(lowerName)) {
                    return c;
                }
            }
        }
        return null;
    }

    /**
     * Checks if the action bar contains a slot with an item or ability matching the given name (case-insensitive).
     *
     * @param name the name substring to search for
     * @return {@code true} if found
     */
    public boolean contains(String name) {
        return findByName(name) != null;
    }

    // ========================== Interaction Methods ==========================

    /**
     * Activates (clicks) a specific slot on the action bar.
     *
     * @param slot the slot index (0-13)
     * @return {@code true} if the action was queued
     */
    public boolean activate(int slot) {
        validateSlot(slot);
        Component comp = getSlot(slot);
        if (comp == null) return false;
        return ComponentHelper.queueComponentAction(api, comp, 1);
    }

    /**
     * Activates a slot by its item ID.
     *
     * @param itemId the item ID in the slot to activate
     * @return {@code true} if the slot was found and the action queued
     */
    public boolean activateItem(int itemId) {
        Component comp = findByItemId(itemId);
        if (comp == null) return false;
        return ComponentHelper.queueComponentAction(api, comp, 1);
    }

    /**
     * Activates a slot by its sprite ID (for abilities/prayers/spells).
     *
     * @param spriteId the sprite ID in the slot to activate
     * @return {@code true} if the slot was found and the action queued
     */
    public boolean activateSprite(int spriteId) {
        Component comp = findBySpriteId(spriteId);
        if (comp == null) return false;
        return ComponentHelper.queueComponentAction(api, comp, 1);
    }

    /**
     * Interact with a slot by slot index and option string.
     *
     * @param slot   the slot index (0-13)
     * @param option the right-click option (e.g. "Activate", "Remove", "Customise-keybind")
     * @return {@code true} if the option was found and action queued
     */
    public boolean interact(int slot, String option) {
        validateSlot(slot);
        Component comp = getSlot(slot);
        if (comp == null) return false;
        return ComponentHelper.interactComponent(api, comp, option);
    }

    /**
     * Interact with a slot by slot index and option index (1-based).
     *
     * @param slot        the slot index (0-13)
     * @param optionIndex the 1-based option index
     * @return {@code true} if the action was queued
     */
    public boolean interact(int slot, int optionIndex) {
        validateSlot(slot);
        Component comp = getSlot(slot);
        if (comp == null) return false;
        return ComponentHelper.queueComponentAction(api, comp, optionIndex);
    }

    /**
     * Interact with a slot by item name and option string.
     * Finds the first slot containing an item whose name contains the given string
     * (case-insensitive), then activates the specified option.
     *
     * <p>Example: {@code actionBar.interact("Sapphire", "Drop")}</p>
     *
     * @param name   the item name substring to search for (case-insensitive)
     * @param option the right-click option (e.g. "Drop", "Activate", "Remove")
     * @return {@code true} if the item was found and the action queued
     */
    public boolean interact(String name, String option) {
        Component comp = findByName(name);
        if (comp == null) return false;
        return ComponentHelper.interactComponent(api, comp, option);
    }

    /**
     * Interact with a slot by exact item name and option string.
     *
     * @param name   the exact item name to match (case-insensitive)
     * @param option the right-click option
     * @return {@code true} if the item was found and the action queued
     */
    public boolean interactExact(String name, String option) {
        Component comp = findByNameExact(name);
        if (comp == null) return false;
        return ComponentHelper.interactComponent(api, comp, option);
    }

    /**
     * Interact with a slot by item name and option index (1-based).
     *
     * @param name        the item name substring to search for (case-insensitive)
     * @param optionIndex the 1-based option index
     * @return {@code true} if the item was found and the action queued
     */
    public boolean interact(String name, int optionIndex) {
        Component comp = findByName(name);
        if (comp == null) return false;
        return ComponentHelper.queueComponentAction(api, comp, optionIndex);
    }

    /**
     * Activates the first slot containing an item matching the given name.
     *
     * @param name the item name substring (case-insensitive)
     * @return {@code true} if found and activated
     */
    public boolean activateItem(String name) {
        Component comp = findByName(name);
        if (comp == null) return false;
        return ComponentHelper.queueComponentAction(api, comp, 1);
    }

    /**
     * Gets the right-click options available on a specific slot.
     *
     * @param slot the slot index (0-13)
     * @return list of option strings, or empty list if the slot is not found
     */
    public List<String> getSlotOptions(int slot) {
        validateSlot(slot);
        Component comp = getSlot(slot);
        if (comp == null) return Collections.emptyList();
        List<String> options = api.getComponentOptions(comp.interfaceId(), comp.componentId());
        return options != null ? options : Collections.emptyList();
    }

    // ========================== Bar Switching ==========================

    /**
     * Switches to the next action bar preset by clicking the right arrow.
     *
     * @return {@code true} if the action was queued
     */
    public boolean nextBar() {
        return clickBarComponent(COMPONENT_NEXT_BAR);
    }

    /**
     * Switches to the previous action bar preset by clicking the left arrow.
     *
     * @return {@code true} if the action was queued
     */
    public boolean previousBar() {
        return clickBarComponent(COMPONENT_PREV_BAR);
    }

    // ========================== Adrenaline ==========================

    /** Varp 679 stores adrenaline as 0-1200 (0.0%-120.0%, 120% with Conservation of Energy relic). */
    public static final int VARP_ADRENALINE = 679;

    /**
     * Returns the raw adrenaline value (0-1200). Divide by 10 for percentage.
     * Standard max is 1000 (100%), with Conservation of Energy relic max is 1200 (120%).
     *
     * @return the raw adrenaline value, or 0 if unavailable
     */
    public int getAdrenalineRaw() {
        return api.getVarp(VARP_ADRENALINE);
    }

    /**
     * Returns the adrenaline percentage (0.0-120.0).
     *
     * @return the adrenaline percentage
     */
    public float getAdrenalinePercent() {
        return getAdrenalineRaw() / 10f;
    }

    /**
     * Reads the adrenaline/special attack percentage from the action bar text.
     *
     * @return the adrenaline percentage (0-100), or -1 if unable to read
     * @deprecated Use {@link #getAdrenalineRaw()} or {@link #getAdrenalinePercent()} instead
     */
    @Deprecated
    public int getAdrenaline() {
        String text = api.getComponentText(INTERFACE_ID, COMPONENT_ADRENALINE_TEXT);
        if (text == null || text.isEmpty()) return -1;
        try {
            return Integer.parseInt(text.replaceAll("[^0-9]", ""));
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    // ========================== Helpers ==========================

    private boolean clickBarComponent(int componentId) {
        List<Component> comps = api.queryComponents(ComponentFilter.builder()
                .interfaceId(INTERFACE_ID)
                .maxResults(1)
                .build());
        // Build a synthetic component for the fixed UI element
        if (!api.isComponentValid(INTERFACE_ID, componentId, -1)) return false;
        api.queueAction(new GameAction(
                ActionTypes.COMPONENT, 1, -1, (INTERFACE_ID << 16) | componentId));
        return true;
    }

    private boolean matchesName(int itemId, String lowerName) {
        ItemType type = api.getItemType(itemId);
        return type != null && type.name() != null
                && type.name().toLowerCase().contains(lowerName);
    }

    private boolean matchesNameExact(int itemId, String lowerName) {
        ItemType type = api.getItemType(itemId);
        return type != null && type.name() != null
                && type.name().toLowerCase().equals(lowerName);
    }

    private static void validateSlot(int slot) {
        if (slot < 0 || slot >= SLOT_COUNT) {
            throw new IllegalArgumentException("Slot must be 0-" + (SLOT_COUNT - 1) + ", got: " + slot);
        }
    }
}
