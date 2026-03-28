package com.botwithus.bot.api.inventory;

import com.botwithus.bot.api.GameAPI;
import com.botwithus.bot.api.log.BotLogger;
import com.botwithus.bot.api.log.LoggerFactory;
import com.botwithus.bot.api.model.Component;
import com.botwithus.bot.api.model.GameAction;
import com.botwithus.bot.api.model.InventoryItem;
import com.botwithus.bot.api.util.Skills;

import java.util.List;


/**
 * Provides access to the wood box portable log storage system.
 * <p>Wood boxes store various types of logs, bird's nests, and wood spirits while
 * skilling. Players fill them from the backpack and can empty them at a bank.
 * There are 11 tiers from basic Wood box (Fletching 1) to Eternal magic wood box
 * (Fletching 100), each storing progressively more log types.</p>
 *
 * <p>Stored items are read from container 937 (30 slots). Capacity per log type is
 * {@code 70 + (tierLevel × 10) + wcLevelBonus} where the WC bonus adds +10 per
 * milestone at WC 5, 15, 25, ... 105. Achievement bonuses (+20/+10) are not
 * detectable and not included.</p>
 *
 * <pre>{@code
 * WoodBox woodBox = new WoodBox(ctx.getGameAPI());
 * if (woodBox.hasWoodBox() && !woodBox.isFull(WoodBox.LogType.MAGIC)) {
 *     woodBox.fill();
 * }
 * }</pre>
 */
public final class WoodBox {

    private static final BotLogger log = LoggerFactory.getLogger(WoodBox.class);

    /** Inventory ID for wood box stored items (UI container, 30 slots). */
    public static final int STORAGE_INVENTORY_ID = 937;

    // Bank backpack component (for empty action)
    private static final int BANK_INTERFACE_ID = 517;
    private static final int BANK_BACKPACK_COMPONENT = 15;
    private static final int OPTION_EMPTY_LOGS = 8;
    /** Action type 1007 is used for wood box empty actions at the bank. */
    private static final int ACTION_EMPTY = 1007;

    private final GameAPI api;
    private final InventoryContainer storage;
    private final Backpack backpack;

    public WoodBox(GameAPI api) {
        this.api = api;
        this.storage = new InventoryContainer(api, STORAGE_INVENTORY_ID);
        this.backpack = new Backpack(api);
    }

    // ========================== Tier Detection ==========================

    /**
     * Check if the player has a wood box in their backpack.
     */
    public boolean hasWoodBox() {
        return getEquippedTier() != null;
    }

    /**
     * Detect the tier of wood box currently in the player's backpack.
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
     * @return {@code true} if the equipped tier is high enough
     */
    public boolean canStore(LogType logType) {
        Tier tier = getEquippedTier();
        if (tier == null) {
            log.warn("[WoodBox] No wood box found in backpack");
            return false;
        }
        return tier.level >= logType.requiredTier;
    }

    // ========================== Count & Capacity ==========================

    /**
     * Get the stored quantity of a specific log type from container 937.
     */
    public int count(LogType logType) {
        return storage.count(logType.itemId);
    }

    /**
     * Get the capacity per log type for the equipped wood box.
     * <p>Formula: {@code 70 + (tierLevel × 10) + wcLevelBonus}.
     * Same for every log type — determined by box tier and Woodcutting level.
     * Achievement bonuses (+20/+10) are not detectable and not included.</p>
     *
     * @return the capacity, or 0 if no wood box is equipped
     */
    public int getCapacity() {
        Tier tier = getEquippedTier();
        if (tier == null) return 0;
        int wcLevel = Skills.getLevel(api, Skills.WOODCUTTING);
        return 70 + (tier.level * 10) + wcLevelBonus(wcLevel);
    }

    /**
     * Check if a specific log type has reached its capacity.
     */
    public boolean isFull(LogType logType) {
        return count(logType) >= getCapacity();
    }

    /**
     * Check if the wood box has no stored items.
     */
    public boolean isEmpty() {
        return storage.isEmpty();
    }

    /**
     * Get the total number of items stored across all types.
     */
    public int getTotalStored() {
        return storage.getItems().stream()
                .mapToInt(InventoryItem::quantity)
                .sum();
    }

    /**
     * Calculates the WC-level bonus: +10 for each milestone reached.
     * Milestones: 5, 15, 25, 35, 45, 55, 65, 75, 85, 95, 105.
     */
    private static int wcLevelBonus(int wcLevel) {
        if (wcLevel < 5) return 0;
        int milestones = Math.min(11, (wcLevel - 5) / 10 + 1);
        return milestones * 10;
    }

    // ========================== Fill & Empty ==========================

    /**
     * Fill the wood box from the backpack.
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

    /**
     * Empty logs and bird's nests from the wood box into the bank.
     * <p>Must be performed while the bank interface is open. Uses action type 1007
     * with option 8 on the bank backpack component (517, 15).</p>
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
        if (isEmpty()) {
            log.warn("[WoodBox] Cannot empty: wood box is already empty");
            return false;
        }

        Component comp = findWoodBoxInBankBackpack(tier);
        if (comp == null) {
            log.warn("[WoodBox] Cannot empty: wood box not found in bank backpack view");
            return false;
        }
        api.queueAction(new GameAction(ACTION_EMPTY, OPTION_EMPTY_LOGS, comp.subComponentId(),
                ComponentHelper.componentHash(comp)));
        log.info("[WoodBox] Emptying logs from {} (slot {})", tier.name, comp.subComponentId());
        return true;
    }

    // ========================== Helpers ==========================

    private Component findWoodBoxInBankBackpack(Tier tier) {
        return api.getComponentChildren(BANK_INTERFACE_ID, BANK_BACKPACK_COMPONENT).stream()
                .filter(c -> c.itemId() == tier.itemId)
                .findFirst().orElse(null);
    }

    // ========================== Enums ==========================

    /**
     * Wood box tiers, from basic to eternal.
     */
    public enum Tier {
        WOOD      (0,  "Wood box",               54895),
        OAK       (1,  "Oak wood box",           54897),
        WILLOW    (2,  "Willow wood box",        54899),
        TEAK      (3,  "Teak wood box",          54901),
        MAPLE     (4,  "Maple wood box",         54903),
        ACADIA    (5,  "Acadia wood box",        54905),
        MAHOGANY  (6,  "Mahogany wood box",      54907),
        YEW       (7,  "Yew wood box",           54909),
        MAGIC     (8,  "Magic wood box",         54911),
        ELDER     (9,  "Elder wood box",         54913),
        ETERNAL   (10, "Eternal magic wood box", 58253);

        /** Tier level (0 = lowest, 10 = highest). */
        public final int level;
        /** Display name of this wood box. */
        public final String name;
        /** In-game item ID. */
        public final int itemId;

        Tier(int level, String name, int itemId) {
            this.level = level;
            this.name = name;
            this.itemId = itemId;
        }
    }

    /**
     * Log types that can be stored in a wood box.
     * <p>Each log type has a required tier level — a wood box can store any log whose
     * required tier is &le; the box's tier.</p>
     */
    public enum LogType {
        LOGS         (0,  "Logs",               1511),
        OAK          (1,  "Oak logs",           1521),
        ACHEY        (0,  "Achey tree logs",    2862),
        WILLOW       (2,  "Willow logs",        1519),
        TEAK         (3,  "Teak logs",          6333),
        ARCTIC_PINE  (5,  "Arctic pine logs",   10810),
        MAPLE        (4,  "Maple logs",         1517),
        ACADIA       (5,  "Acadia logs",        40285),
        MAHOGANY     (6,  "Mahogany logs",      6332),
        EUCALYPTUS   (5,  "Eucalyptus logs",    12581),
        YEW          (7,  "Yew logs",           1515),
        BLISTERWOOD  (7,  "Blisterwood logs",   21600),
        MAGIC        (8,  "Magic logs",         1513),
        CURSED_MAGIC (8,  "Cursed magic logs",  13567),
        ELDER        (9,  "Elder logs",         29556),
        ETERNAL_MAGIC(10, "Eternal magic logs", 58250);

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
    }
}
