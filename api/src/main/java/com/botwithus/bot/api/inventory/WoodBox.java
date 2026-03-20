package com.botwithus.bot.api.inventory;

import com.botwithus.bot.api.GameAPI;
import com.botwithus.bot.api.log.BotLogger;
import com.botwithus.bot.api.log.LoggerFactory;
import com.botwithus.bot.api.model.Component;
import com.botwithus.bot.api.model.GameAction;
import com.botwithus.bot.api.model.InventoryItem;

import java.util.Arrays;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Provides access to the wood box portable log storage system.
 * <p>Wood boxes store various types of logs while skilling. Players fill them
 * from the backpack and can only empty them at a bank. Each tier stores
 * progressively more log types with increasing capacity.</p>
 *
 * <p>Stored items are read from inventory ID 937. Fill is performed via the
 * backpack "Fill" option. Empty is performed at a bank via action type 1007
 * on the bank backpack component (interface 517, component 15).</p>
 *
 * <pre>{@code
 * WoodBox woodBox = new WoodBox(ctx.getGameAPI());
 * if (woodBox.hasWoodBox() && woodBox.canStore(WoodBox.LogType.MAGIC)) {
 *     woodBox.fill();
 * }
 * }</pre>
 */
public final class WoodBox {

    private static final BotLogger log = LoggerFactory.getLogger(WoodBox.class);

    /** Inventory ID for wood box stored items. */
    public static final int STORAGE_INVENTORY_ID = 937;
    /** Maximum number of different item types a wood box can hold. */
    public static final int MAX_ITEM_TYPES = 30;

    // Bank backpack component (for empty action)
    private static final int BANK_INTERFACE_ID = 517;
    private static final int BANK_BACKPACK_COMPONENT = 15;
    private static final int HASH_BANK_BACKPACK = BANK_INTERFACE_ID << 16 | BANK_BACKPACK_COMPONENT;
    /** Action type for "Empty" on woodbox in bank backpack. */
    private static final int ACTION_EMPTY = 1007;
    /** Option index for "Empty - logs and bird's nests". */
    private static final int OPTION_EMPTY = 8;

    private final GameAPI api;
    private final InventoryContainer storage;
    private final Backpack backpack;

    /**
     * Creates a new wood box API wrapper.
     *
     * @param api the game API instance
     */
    public WoodBox(GameAPI api) {
        this.api = api;
        this.storage = new InventoryContainer(api, STORAGE_INVENTORY_ID);
        this.backpack = new Backpack(api);
    }

    // ========================== Tier Detection ==========================

    /**
     * Check if the player has a wood box in their backpack.
     *
     * @return {@code true} if a wood box is found
     */
    public boolean hasWoodBox() {
        return getEquippedTier() != null;
    }

    /**
     * Detect the tier of wood box currently in the player's backpack.
     * Searches for any known wood box item ID.
     *
     * @return the detected {@link Tier}, or {@code null} if no wood box is found
     */
    public Tier getEquippedTier() {
        for (Tier tier : Tier.values()) {
            if (backpack.contains(tier.itemId)) {
                return tier;
            }
        }
        return null;
    }

    /**
     * Check if the equipped wood box can store the given log type.
     *
     * @param logType the log type to check
     * @return {@code true} if the equipped tier is high enough
     */
    public boolean canStore(LogType logType) {
        Tier tier = getEquippedTier();
        if (tier == null) {
            log.warn("[WoodBox] No wood box found in backpack");
            return false;
        }
        if (tier.level < logType.requiredTier) {
            log.warn("[WoodBox] Cannot store {}: requires tier {} ({}), current tier is {} ({})",
                    logType.name, getTierNameForLevel(logType.requiredTier), logType.requiredTier,
                    tier.name, tier.level);
            return false;
        }
        return true;
    }

    /**
     * Check if the equipped wood box can store logs by name.
     *
     * @param logName the exact log name (e.g. "Magic logs")
     * @return {@code true} if the equipped tier supports this log type
     */
    public boolean canStore(String logName) {
        LogType logType = LogType.fromName(logName);
        if (logType == null) {
            log.warn("[WoodBox] Unknown log type: '{}'", logName);
            return false;
        }
        return canStore(logType);
    }

    // ========================== Stored Item Queries ==========================

    /**
     * Get all items currently stored in the wood box.
     *
     * @return a list of stored items (may be empty)
     */
    public List<InventoryItem> getStoredItems() {
        return storage.getItems();
    }

    /**
     * Check if the wood box contains a specific item by ID (quantity &gt; 0).
     *
     * @param itemId the item ID
     * @return {@code true} if the item is stored
     */
    public boolean contains(int itemId) {
        return storage.count(itemId) > 0;
    }

    /**
     * Check if the wood box contains a specific item by exact name (case-insensitive).
     *
     * @param name the exact item name
     * @return {@code true} if the item is stored
     */
    public boolean contains(String name) {
        return storage.countExact(name) > 0;
    }

    /**
     * Count the quantity of a specific item stored in the wood box.
     *
     * @param itemId the item ID
     * @return the stored quantity
     */
    public int count(int itemId) {
        return storage.count(itemId);
    }

    /**
     * Count the quantity of a specific item by exact name (case-insensitive).
     *
     * @param name the exact item name
     * @return the stored quantity
     */
    public int count(String name) {
        return storage.countExact(name);
    }

    /**
     * Count the quantity of a specific log type stored.
     *
     * @param logType the log type
     * @return the stored quantity
     */
    public int count(LogType logType) {
        return storage.count(logType.itemId);
    }

    /**
     * Check if the wood box has no stored items.
     *
     * @return {@code true} if empty
     */
    public boolean isEmpty() {
        return storage.isEmpty();
    }

    /**
     * Count the number of distinct item types currently stored.
     * Wood boxes can hold up to {@value #MAX_ITEM_TYPES} different types.
     *
     * @return the number of distinct stored item types
     */
    public int storedTypes() {
        return (int) getStoredItems().stream()
                .map(InventoryItem::itemId)
                .distinct()
                .count();
    }

    /**
     * Get the total number of items stored across all types.
     *
     * @return the total stored count
     */
    public int getTotalStored() {
        return getStoredItems().stream()
                .mapToInt(InventoryItem::quantity)
                .sum();
    }

    // ========================== Capacity ==========================

    /**
     * Get the base capacity for the equipped wood box tier.
     *
     * @return the base capacity, or 0 if no wood box is found
     */
    public int getBaseCapacity() {
        Tier tier = getEquippedTier();
        return tier != null ? tier.baseCapacity : 0;
    }

    // ========================== Fill ==========================

    /**
     * Fill the wood box from the backpack.
     * <p>Right-clicks the wood box in the backpack and selects "Fill".
     * This deposits all compatible logs from the backpack into the wood box.</p>
     *
     * @return {@code true} if the fill action was queued
     */
    public boolean fill() {
        Tier tier = getEquippedTier();
        if (tier == null) {
            log.warn("[WoodBox] Cannot fill: no wood box found in backpack");
            return false;
        }
        log.info("[WoodBox] Filling {}", tier.name);
        return backpack.interact(tier.name, "Fill");
    }

    // ========================== Empty (at bank) ==========================

    /**
     * Empty the wood box contents into the bank.
     * <p>Must be performed while the bank interface is open. Finds the wood box
     * in the bank's backpack view (interface 517, component 15) and queues the
     * "Empty - logs and bird's nests" action (type 1007, option 8).</p>
     *
     * @return {@code true} if the empty action was queued
     */
    public boolean empty() {
        if (!api.isInterfaceOpen(BANK_INTERFACE_ID)) {
            log.warn("[WoodBox] Cannot empty: bank is not open");
            return false;
        }
        Tier tier = getEquippedTier();
        if (tier == null) {
            log.warn("[WoodBox] Cannot empty: no wood box found in backpack");
            return false;
        }
        // Find the woodbox component in the bank's backpack view
        Component comp = findWoodBoxInBankBackpack(tier);
        if (comp == null) {
            log.warn("[WoodBox] Cannot empty: wood box not found in bank backpack view");
            return false;
        }
        if (isEmpty()) {
            log.warn("[WoodBox] Cannot empty: wood box is already empty");
            return false;
        }
        int slot = comp.subComponentId();
        api.queueAction(new GameAction(ACTION_EMPTY, OPTION_EMPTY, slot, HASH_BANK_BACKPACK));
        log.info("[WoodBox] Emptying {} (slot {})", tier.name, slot);
        return true;
    }

    // ========================== Helpers ==========================

    /**
     * Find the wood box component in the bank's backpack view.
     */
    private Component findWoodBoxInBankBackpack(Tier tier) {
        return api.getComponentChildren(BANK_INTERFACE_ID, BANK_BACKPACK_COMPONENT).stream()
                .filter(c -> c.itemId() == tier.itemId)
                .findFirst().orElse(null);
    }

    /**
     * Get the tier name for a given tier level.
     */
    private static String getTierNameForLevel(int level) {
        return Arrays.stream(Tier.values())
                .filter(t -> t.level == level)
                .map(t -> t.name)
                .findFirst().orElse("Unknown");
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

    // ========================== Enums ==========================

    /**
     * Wood box tiers, from basic to elder. Each tier can store all logs from
     * lower tiers plus its own tier's logs.
     */
    public enum Tier {
        WOOD      (0, "Wood box",            54895, 70),
        OAK       (1, "Oak wood box",        54897, 80),
        WILLOW    (2, "Willow wood box",     54899, 90),
        TEAK      (3, "Teak wood box",       54901, 100),
        MAPLE     (4, "Maple wood box",      54903, 110),
        ACADIA    (5, "Acadia wood box",     54905, 120),
        MAHOGANY  (6, "Mahogany wood box",   54907, 130),
        YEW       (7, "Yew wood box",        54909, 140),
        MAGIC     (8, "Magic wood box",      54911, 150),
        ELDER     (9, "Elder wood box",      54913, 160);

        /** Tier level (0 = lowest, 9 = highest). */
        public final int level;
        /** Display name of this wood box. */
        public final String name;
        /** In-game item ID for this wood box. */
        public final int itemId;
        /** Base storage capacity (before WC level / achievement bonuses). */
        public final int baseCapacity;

        Tier(int level, String name, int itemId, int baseCapacity) {
            this.level = level;
            this.name = name;
            this.itemId = itemId;
            this.baseCapacity = baseCapacity;
        }

        /**
         * Resolve a tier from an item ID.
         *
         * @param itemId the item ID
         * @return the matching tier, or {@code null}
         */
        public static Tier fromItemId(int itemId) {
            for (Tier t : values()) {
                if (t.itemId == itemId) return t;
            }
            return null;
        }

        /**
         * Resolve a tier from its display name (case-insensitive).
         *
         * @param name the wood box name
         * @return the matching tier, or {@code null}
         */
        public static Tier fromName(String name) {
            if (name == null) return null;
            String lower = name.toLowerCase();
            for (Tier t : values()) {
                if (t.name.toLowerCase().equals(lower)) return t;
            }
            return null;
        }
    }

    /**
     * Log types that can be stored in a wood box, mapped to their required tier.
     * A wood box can store any log whose required tier is &le; the box's tier.
     */
    public enum LogType {
        LOGS         (0, "Logs",               1511),
        ACHEY        (0, "Achey tree logs",    2862),
        OAK          (1, "Oak logs",           1521),
        WILLOW       (2, "Willow logs",        1519),
        TEAK         (3, "Teak logs",          6333),
        MAPLE        (4, "Maple logs",         1517),
        ACADIA       (5, "Acadia logs",        40285),
        ARCTIC_PINE  (5, "Arctic pine logs",   10810),
        EUCALYPTUS   (5, "Eucalyptus logs",    12581),
        MAHOGANY     (6, "Mahogany logs",      6332),
        YEW          (7, "Yew logs",           1515),
        BLISTERWOOD  (7, "Blisterwood logs",   21600),
        MAGIC        (8, "Magic logs",         1513),
        CURSED_MAGIC (8, "Cursed magic logs",  13567),
        ELDER        (9, "Elder logs",         29556);

        /** Minimum wood box tier level required to store this log type. */
        public final int requiredTier;
        /** Display name of this log type. */
        public final String name;
        /** In-game item ID for this log type. */
        public final int itemId;

        LogType(int requiredTier, String name, int itemId) {
            this.requiredTier = requiredTier;
            this.name = name;
            this.itemId = itemId;
        }

        /**
         * Resolve a log type from its display name (case-insensitive).
         *
         * @param name the log name
         * @return the matching log type, or {@code null}
         */
        public static LogType fromName(String name) {
            if (name == null) return null;
            String lower = name.toLowerCase();
            for (LogType lt : values()) {
                if (lt.name.toLowerCase().equals(lower)) return lt;
            }
            return null;
        }

        /**
         * Resolve a log type from its item ID.
         *
         * @param itemId the item ID
         * @return the matching log type, or {@code null}
         */
        public static LogType fromItemId(int itemId) {
            for (LogType lt : values()) {
                if (lt.itemId == itemId) return lt;
            }
            return null;
        }
    }
}
