package com.botwithus.bot.api.inventory;

import com.botwithus.bot.api.GameAPI;
import com.botwithus.bot.api.log.BotLogger;
import com.botwithus.bot.api.log.LoggerFactory;
import com.botwithus.bot.api.model.Component;
import com.botwithus.bot.api.model.GameAction;
import com.botwithus.bot.api.model.InventoryItem;

import java.util.List;


/**
 * Provides access to the Archaeological soil box storage system.
 * <p>The soil box stores up to 6 types of archaeological soil while excavating.
 * Players fill it from the backpack and can empty it back into the backpack or
 * screen soil directly from the box at a screening station.</p>
 *
 * <p>Unlike the wood box, the soil box is a single item with upgradeable capacity.
 * Capacity upgrades are purchased from Ezreal's Archaeology Guild Shop with Chronotes,
 * gated by qualification tier (Intern → Assistant → Associate → Professor).</p>
 *
 * <p>Stored quantities are tracked per soil type via full 32-bit varplayers (raw varps,
 * not subdivided by varbits). The upgrade tier is tracked by varbit {@value #UPGRADE_VARBIT}.
 * Item contents are also readable from inventory ID {@value #STORAGE_INVENTORY_ID}
 * (6 slots). Soil types are enumerated in cache enum 14069.</p>
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

    /** Varbit that tracks the soil box upgrade tier (0–3). */
    public static final int UPGRADE_VARBIT = 47021;
    /** Varbit that tracks soil box state/flags (4-bit, max 15). */
    public static final int STATE_VARBIT = 49538;
    /** Varbit for Archaeology tutorial gate (0=locked, 1=unlocked). */
    public static final int TUTORIAL_VARBIT = 21748;

    /** Capacity per soil type at each upgrade tier: Intern(50), Assistant(100), Associate(250), Professor(500). */
    private static final int[] CAPACITIES = {50, 100, 250, 500};

    // Bank backpack component actions
    private static final int BANK_INTERFACE_ID = 517;
    private static final int BANK_BACKPACK_COMPONENT = 15;
    private static final int ACTION_CONTAINER = 1007;
    private static final int OPTION_FILL_AT_BANK = 8;
    private static final int OPTION_EMPTY_AT_BANK = 9;
    private static final int OPTION_DEPOSIT = 2;

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
     * <p>Must be performed while the bank interface is open. Uses action type 1007
     * with option 9 on the bank backpack component (517, 15).</p>
     *
     * @return {@code true} if the empty action was queued
     */
    public boolean emptyAtBank() {
        Component comp = findSoilBoxInBank("empty at bank");
        if (comp == null) return false;
        api.queueAction(new GameAction(
                ACTION_CONTAINER, OPTION_EMPTY_AT_BANK, comp.subComponentId(),
                ComponentHelper.componentHash(comp)));
        log.info("[SoilBox] Emptying soil box at bank (slot {})", comp.subComponentId());
        return true;
    }

    /**
     * Fill the soil box from the bank.
     * <p>Must be performed while the bank interface is open. Uses action type 1007
     * with option 8 on the bank backpack component (517, 15).</p>
     *
     * @return {@code true} if the fill action was queued
     */
    public boolean fillAtBank() {
        Component comp = findSoilBoxInBank("fill at bank");
        if (comp == null) return false;
        api.queueAction(new GameAction(
                ACTION_CONTAINER, OPTION_FILL_AT_BANK, comp.subComponentId(),
                ComponentHelper.componentHash(comp)));
        log.info("[SoilBox] Filling soil box at bank (slot {})", comp.subComponentId());
        return true;
    }

    /**
     * Deposit the soil box itself into the bank (action type 57, option 2).
     * <p>Must be performed while the bank interface is open.</p>
     *
     * @return {@code true} if the deposit action was queued
     */
    public boolean deposit() {
        Component comp = findSoilBoxInBank("deposit");
        if (comp == null) return false;
        ComponentHelper.queueComponentAction(api, comp, OPTION_DEPOSIT);
        log.info("[SoilBox] Depositing soil box (slot {})", comp.subComponentId());
        return true;
    }

    // ========================== Helpers ==========================

    private Component findSoilBoxInBank(String action) {
        if (!api.isInterfaceOpen(BANK_INTERFACE_ID)) {
            log.warn("[SoilBox] Cannot {}: bank is not open", action);
            return null;
        }
        if (!hasSoilBox()) {
            log.warn("[SoilBox] Cannot {}: no soil box found in backpack", action);
            return null;
        }
        Component comp = api.getComponentChildren(BANK_INTERFACE_ID, BANK_BACKPACK_COMPONENT).stream()
                .filter(c -> c.itemId() == ITEM_ID)
                .findFirst().orElse(null);
        if (comp == null) {
            log.warn("[SoilBox] Cannot {}: soil box not found in bank backpack view", action);
        }
        return comp;
    }

    // ========================== Enum ==========================

    /**
     * Soil types that can be stored in the Archaeological soil box.
     * <p>Each type has a full 32-bit varplayer (raw varp, not subdivided by varbits)
     * that tracks the stored quantity. Order matches cache enum 14069.</p>
     */
    public enum SoilType {
        // Order matches enum 14069 (slot 0–5)
        ANCIENT_GRAVEL  ("Ancient gravel",   49517, 49528, 9370,  5),
        FIERY_BRIMSTONE ("Fiery brimstone",  49521, 49530, 9372, 20),
        SALTWATER_MUD   ("Saltwater mud",    49519, 49529, 9371, 42),
        AERATED_SEDIMENT("Aerated sediment", 49523, 49531, 9373, 70),
        VOLCANIC_ASH    ("Volcanic ash",     50696, 50698, 9578, 73),
        EARTHEN_CLAY    ("Earthen clay",     49525, 49532, 9374, 76);

        /** Display name of this soil type. */
        public final String name;
        /** In-game item ID for this soil type (backpack variant, category 4603). */
        public final int itemId;
        /** Screening dummy item ID (category 4717, used in screening interface). */
        public final int screeningDummyId;
        /** Player variable (varp) ID that tracks the stored quantity (full 32-bit). */
        public final int varpId;
        /** Minimum Archaeology level required to screen this soil. */
        public final int requiredLevel;

        SoilType(String name, int itemId, int screeningDummyId, int varpId, int requiredLevel) {
            this.name = name;
            this.itemId = itemId;
            this.screeningDummyId = screeningDummyId;
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
