package com.botwithus.bot.api.inventory;

import com.botwithus.bot.api.GameAPI;
import com.botwithus.bot.api.log.BotLogger;
import com.botwithus.bot.api.log.LoggerFactory;
import com.botwithus.bot.api.model.Component;
import com.botwithus.bot.api.model.InventoryItem;

import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Provides access to the Archaeological soil box storage system.
 * <p>The soil box stores up to 6 types of archaeological soil while excavating.
 * Players fill it from the backpack and can empty it back into the backpack or
 * screen soil directly from the box at a screening station.</p>
 *
 * <p>Unlike the wood box, the soil box is a single item with upgradeable capacity.
 * Capacity upgrades are purchased from the Archaeology Guild Shop with Chronotes,
 * gated by qualification tier (Intern → Assistant → Associate → Professor).</p>
 *
 * <p>Stored quantities are tracked per soil type via player variables (varps).
 * The upgrade level is tracked by varbit {@value #UPGRADE_VARBIT}.
 * Item contents are also readable from inventory ID {@value #STORAGE_INVENTORY_ID}.</p>
 *
 * <pre>{@code
 * SoilBox soilBox = new SoilBox(ctx.getGameAPI());
 * if (soilBox.hasSoilBox() && !soilBox.isFull()) {
 *     soilBox.fill();
 * }
 * }</pre>
 */
public final class SoilBox {

    private static final BotLogger log = LoggerFactory.getLogger(SoilBox.class);

    /** Item ID for the Archaeological soil box. */
    public static final int ITEM_ID = 49538;
    /** Display name of the soil box item. */
    public static final String ITEM_NAME = "Archaeological soil box";
    /** Inventory ID for soil box stored items. */
    public static final int STORAGE_INVENTORY_ID = 884;

    /** Varbit that tracks the soil box upgrade level (0–3). */
    public static final int UPGRADE_VARBIT = 47021;
    /** Capacity per soil type at each upgrade level. */
    private static final int[] CAPACITIES = {50, 100, 250, 500};

    // Bank backpack component (for empty action at bank)
    private static final int BANK_INTERFACE_ID = 517;
    private static final int BANK_BACKPACK_COMPONENT = 15;
    /** Option index for "Empty" on soil box in bank backpack (action type 57). */
    private static final int OPTION_EMPTY_AT_BANK = 9;

    private final GameAPI api;
    private final InventoryContainer storage;
    private final Backpack backpack;

    /**
     * Creates a new soil box API wrapper.
     *
     * @param api the game API instance
     */
    public SoilBox(GameAPI api) {
        this.api = api;
        this.storage = new InventoryContainer(api, STORAGE_INVENTORY_ID);
        this.backpack = new Backpack(api);
    }

    // ========================== Detection ==========================

    /**
     * Check if the player has a soil box in their backpack.
     *
     * @return {@code true} if the soil box is found
     */
    public boolean hasSoilBox() {
        return backpack.contains(ITEM_ID);
    }

    // ========================== Capacity & Upgrades ==========================

    /**
     * Get the current upgrade level of the soil box (0–3).
     * <ul>
     *   <li>0 = Intern (50 per type)</li>
     *   <li>1 = Assistant (100 per type)</li>
     *   <li>2 = Associate (250 per type)</li>
     *   <li>3 = Professor (500 per type)</li>
     * </ul>
     *
     * @return the upgrade level
     */
    public int getUpgradeLevel() {
        return api.getVarbit(UPGRADE_VARBIT);
    }

    /**
     * Get the capacity per soil type based on the current upgrade level.
     *
     * @return the capacity per type, or 0 if the varbit value is out of range
     */
    public int getCapacityPerType() {
        int level = getUpgradeLevel();
        if (level >= 0 && level < CAPACITIES.length) {
            return CAPACITIES[level];
        }
        return 0;
    }

    /**
     * Get the maximum total capacity across all soil types.
     *
     * @return capacity per type × number of soil types
     */
    public int getTotalCapacity() {
        return getCapacityPerType() * SoilType.values().length;
    }

    /**
     * Get the qualification name for the current upgrade level.
     *
     * @return the qualification name
     */
    public String getQualificationName() {
        return switch (getUpgradeLevel()) {
            case 0 -> "Intern";
            case 1 -> "Assistant";
            case 2 -> "Associate";
            case 3 -> "Professor";
            default -> "Unknown";
        };
    }

    // ========================== Stored Soil Queries (via Varps) ==========================

    /**
     * Get the stored quantity of a specific soil type.
     * <p>Reads the player variable (varp) that tracks this soil type's count.</p>
     *
     * @param soilType the soil type
     * @return the stored quantity
     */
    public int count(SoilType soilType) {
        return api.getVarp(soilType.varpId);
    }

    /**
     * Get the stored quantity of a soil type by name (case-insensitive).
     *
     * @param name the soil name (e.g. "Ancient gravel")
     * @return the stored quantity, or 0 if the name is unknown
     */
    public int count(String name) {
        SoilType type = SoilType.fromName(name);
        if (type == null) {
            log.warn("[SoilBox] Unknown soil type: '{}'", name);
            return 0;
        }
        return count(type);
    }

    /**
     * Get the total number of soil items stored across all types.
     *
     * @return the total stored count
     */
    public int getTotalStored() {
        int total = 0;
        for (SoilType type : SoilType.values()) {
            total += count(type);
        }
        return total;
    }

    /**
     * Check if the soil box has no stored items.
     *
     * @return {@code true} if all soil types have 0 stored
     */
    public boolean isEmpty() {
        for (SoilType type : SoilType.values()) {
            if (count(type) > 0) return false;
        }
        return true;
    }

    /**
     * Check if any soil type has reached the capacity limit.
     *
     * @return {@code true} if at least one soil type is at capacity
     */
    public boolean isFull() {
        int capacity = getCapacityPerType();
        if (capacity <= 0) return true;
        for (SoilType type : SoilType.values()) {
            if (count(type) >= capacity) return true;
        }
        return false;
    }

    /**
     * Check if ALL soil types have reached the capacity limit.
     *
     * @return {@code true} if every soil type is at capacity
     */
    public boolean isCompletelyFull() {
        int capacity = getCapacityPerType();
        if (capacity <= 0) return true;
        for (SoilType type : SoilType.values()) {
            if (count(type) < capacity) return false;
        }
        return true;
    }

    /**
     * Get the remaining space for a specific soil type.
     *
     * @param soilType the soil type
     * @return the number of additional items that can be stored
     */
    public int remainingSpace(SoilType soilType) {
        return Math.max(0, getCapacityPerType() - count(soilType));
    }

    /**
     * Get a breakdown of stored amounts for each soil type.
     * Format: {@code "Ancient gravel: 45/100"}
     *
     * @return an array of formatted strings, one per soil type
     */
    public String[] getBreakdown() {
        SoilType[] types = SoilType.values();
        String[] result = new String[types.length];
        int cap = getCapacityPerType();
        for (int i = 0; i < types.length; i++) {
            result[i] = types[i].name + ": " + count(types[i]) + "/" + cap;
        }
        return result;
    }

    // ========================== Stored Item Queries (via Inventory) ==========================

    /**
     * Get all items currently stored in the soil box via inventory lookup.
     *
     * @return a list of stored items (may be empty)
     */
    public List<InventoryItem> getStoredItems() {
        return storage.getItems();
    }

    /**
     * Check if the soil box contains a specific item by ID (quantity &gt; 0).
     *
     * @param itemId the item ID
     * @return {@code true} if the item is stored
     */
    public boolean contains(int itemId) {
        return storage.count(itemId) > 0;
    }

    /**
     * Check if the soil box contains a specific item by exact name (case-insensitive).
     *
     * @param name the exact item name
     * @return {@code true} if the item is stored
     */
    public boolean contains(String name) {
        return storage.countExact(name) > 0;
    }

    // ========================== Fill ==========================

    /**
     * Fill the soil box from the backpack.
     * <p>Right-clicks the soil box in the backpack and selects "Fill".
     * This deposits all compatible soil from the backpack into the box.
     * Filling does not interrupt excavation.</p>
     *
     * @return {@code true} if the fill action was queued
     */
    public boolean fill() {
        if (!hasSoilBox()) {
            log.warn("[SoilBox] Cannot fill: no soil box found in backpack");
            return false;
        }
        log.info("[SoilBox] Filling soil box");
        return backpack.interact(ITEM_NAME, "Fill");
    }

    // ========================== Empty ==========================

    /**
     * Empty the soil box contents back into the backpack.
     * <p>Right-clicks the soil box in the backpack and selects "Empty".
     * This returns soil to the backpack. Note: emptying DOES interrupt excavation.</p>
     *
     * @return {@code true} if the empty action was queued
     */
    public boolean empty() {
        if (!hasSoilBox()) {
            log.warn("[SoilBox] Cannot empty: no soil box found in backpack");
            return false;
        }
        if (isEmpty()) {
            log.warn("[SoilBox] Cannot empty: soil box is already empty");
            return false;
        }
        log.info("[SoilBox] Emptying soil box into backpack");
        return backpack.interact(ITEM_NAME, "Empty");
    }

    /**
     * Empty the soil box contents into the bank.
     * <p>Must be performed while the bank interface is open. Uses the same
     * bank backpack component interaction as WoodBox (interface 517, component 15).</p>
     *
     * @return {@code true} if the empty action was queued
     */
    public boolean emptyAtBank() {
        if (!api.isInterfaceOpen(BANK_INTERFACE_ID)) {
            log.warn("[SoilBox] Cannot empty at bank: bank is not open");
            return false;
        }
        if (!hasSoilBox()) {
            log.warn("[SoilBox] Cannot empty at bank: no soil box found in backpack");
            return false;
        }
        if (isEmpty()) {
            log.warn("[SoilBox] Cannot empty at bank: soil box is already empty");
            return false;
        }
        Component comp = findSoilBoxInBankBackpack();
        if (comp == null) {
            log.warn("[SoilBox] Cannot empty at bank: soil box not found in bank backpack view");
            return false;
        }
        // Use standard COMPONENT action (57) with option 8, same pattern as WoodBox.
        // The bank backpack component (517, 15) reports 0 options via getComponentOptions
        // but still processes actions via type 57.
        ComponentHelper.queueComponentAction(api, comp, OPTION_EMPTY_AT_BANK);
        log.info("[SoilBox] Emptying soil box at bank (slot {})", comp.subComponentId());
        return true;
    }

    // ========================== Helpers ==========================

    /**
     * Find the soil box component in the bank's backpack view.
     */
    private Component findSoilBoxInBankBackpack() {
        return api.getComponentChildren(BANK_INTERFACE_ID, BANK_BACKPACK_COMPONENT).stream()
                .filter(c -> c.itemId() == ITEM_ID)
                .findFirst().orElse(null);
    }

    /** Random delay between 400–700ms for human-like interaction pacing. */
    private static int randomDelay() {
        return ThreadLocalRandom.current().nextInt(400, 701);
    }

    /** Sleep the current (virtual) thread. */
    private static void sleep(int ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    // ========================== Enum ==========================

    /**
     * Soil types that can be stored in the Archaeological soil box.
     * Each type has a player variable (varp) that tracks the stored quantity.
     */
    public enum SoilType {
        ANCIENT_GRAVEL  ("Ancient gravel",    49517, 9370,  5),
        SALTWATER_MUD   ("Saltwater mud",     49519, 9371, 42),
        FIERY_BRIMSTONE ("Fiery brimstone",   49521, 9372, 20),
        AERATED_SEDIMENT("Aerated sediment",  49523, 9373, 70),
        EARTHEN_CLAY    ("Earthen clay",      49525, 9374, 76),
        VOLCANIC_ASH    ("Volcanic ash",      50696, 9578, 73);

        /** Display name of this soil type. */
        public final String name;
        /** In-game item ID for this soil type (backpack variant, category 4603). */
        public final int itemId;
        /** Player variable (varp) ID that tracks the stored quantity. */
        public final int varpId;
        /** Minimum Archaeology level required to obtain this soil. */
        public final int requiredLevel;

        SoilType(String name, int itemId, int varpId, int requiredLevel) {
            this.name = name;
            this.itemId = itemId;
            this.varpId = varpId;
            this.requiredLevel = requiredLevel;
        }

        /**
         * Resolve a soil type from its display name (case-insensitive).
         *
         * @param name the soil name
         * @return the matching soil type, or {@code null}
         */
        public static SoilType fromName(String name) {
            if (name == null) return null;
            String lower = name.toLowerCase();
            for (SoilType st : values()) {
                if (st.name.toLowerCase().equals(lower)) return st;
            }
            return null;
        }

        /**
         * Resolve a soil type from its item ID.
         *
         * @param itemId the item ID
         * @return the matching soil type, or {@code null}
         */
        public static SoilType fromItemId(int itemId) {
            for (SoilType st : values()) {
                if (st.itemId == itemId) return st;
            }
            return null;
        }
    }
}
