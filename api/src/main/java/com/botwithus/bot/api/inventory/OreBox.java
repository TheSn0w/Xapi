package com.botwithus.bot.api.inventory;

import com.botwithus.bot.api.GameAPI;
import com.botwithus.bot.api.log.BotLogger;
import com.botwithus.bot.api.log.LoggerFactory;
import com.botwithus.bot.api.model.Component;
import com.botwithus.bot.api.model.GameAction;
import com.botwithus.bot.api.util.Skills;


/**
 * Provides access to the ore box portable ore storage system.
 * <p>Ore boxes are Smithing-crafted containers that store ores and stone spirits while
 * mining. Players fill them from the backpack and can empty them at a bank, deposit box,
 * or metal bank. There are 11 tiers from Bronze ore box (Smithing 7) to Primal ore box
 * (Smithing 102), each storing progressively more ore types.</p>
 *
 * <p>Each ore type has a dedicated varbit that stores its count (bits 0–13, range 0–16,383).
 * Capacity is per ore type: base 100, +20 once Mining reaches the ore's threshold level,
 * plus achievement bonuses (+20 for standard ores, +10 for DG ores). Gold, Silver, and
 * Platinum are fixed at 100.</p>
 *
 * <pre>{@code
 * OreBox oreBox = new OreBox(ctx.getGameAPI());
 * if (oreBox.hasOreBox() && !oreBox.isFull(OreBox.OreType.IRON)) {
 *     oreBox.fill();
 * }
 * }</pre>
 */
public final class OreBox {

    private static final BotLogger log = LoggerFactory.getLogger(OreBox.class);

    // Bank backpack component (for empty action)
    private static final int BANK_INTERFACE_ID = 517;
    private static final int BANK_BACKPACK_COMPONENT = 15;
    private static final int OPTION_EMPTY_ORES = 8;
    private static final int OPTION_EMPTY_SPIRITS = 9;
    private static final int OPTION_DEPOSIT = 2;
    /** Action type 1007 is used for ore box empty actions at the bank. */
    private static final int ACTION_EMPTY = 1007;

    /** Base capacity per ore type before any bonuses. */
    private static final int BASE_CAPACITY = 100;
    /** Bonus applied once Mining level reaches the ore's threshold. */
    private static final int MINING_MILESTONE_BONUS = 20;
    /** Bonus from achievement 2783 "Everything Is Oresome" (standard ores). */
    private static final int ACHIEVEMENT_ORESOME_BONUS = 20;
    /** Bonus from achievement 3517 "Everything Is Still Oresome" (DG ores). */
    private static final int ACHIEVEMENT_DG_ORESOME_BONUS = 10;

    /** Achievement 2783 — "Everything Is Oresome" (standard ore +20 capacity). */
    public static final int ACHIEVEMENT_ORESOME = 2783;
    /** Achievement 3517 — "Everything Is Still Oresome" (DG ore +10 capacity). */
    public static final int ACHIEVEMENT_DG_ORESOME = 3517;

    private final GameAPI api;
    private final Backpack backpack;

    public OreBox(GameAPI api) {
        this.api = api;
        this.backpack = new Backpack(api);
    }

    // ========================== Tier Detection ==========================

    /**
     * Check if the player has an ore box in their backpack.
     */
    public boolean hasOreBox() {
        return getEquippedTier() != null;
    }

    /**
     * Detect the tier of ore box currently in the player's backpack.
     *
     * @return the detected {@link Tier}, or {@code null} if no ore box is found
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
     * Check if the equipped ore box can store the given ore type.
     *
     * @return {@code true} if the equipped tier is high enough
     */
    public boolean canStore(OreType oreType) {
        Tier tier = getEquippedTier();
        if (tier == null) {
            log.warn("[OreBox] No ore box found in backpack");
            return false;
        }
        return tier.miningLevel >= oreType.requiredMiningLevel;
    }

    // ========================== Count & Capacity ==========================

    /**
     * Get the stored quantity of a specific ore type via its dedicated varbit.
     */
    public int count(OreType oreType) {
        return api.getVarbit(oreType.countVarbit);
    }

    /**
     * Get the capacity for a specific ore type.
     * <p>For most ores: {@code 100 (base) + 20 (if Mining >= ore's threshold)}.
     * Gold, Silver, and Platinum are always fixed at 100.
     * Achievement bonuses are not detectable and not included.</p>
     *
     * @return the capacity for this ore type
     */
    public int getCapacity(OreType oreType) {
        if (oreType.fixedCapacity) {
            return BASE_CAPACITY;
        }
        int miningLevel = Skills.getLevel(api, Skills.MINING);
        int capacity = BASE_CAPACITY;
        if (miningLevel >= oreType.miningMilestone) {
            capacity += MINING_MILESTONE_BONUS;
        }
        return capacity;
    }

    /**
     * Check if a specific ore type has reached its capacity.
     */
    public boolean isFull(OreType oreType) {
        return count(oreType) >= getCapacity(oreType);
    }

    /**
     * Check if the ore box has no stored ores (all varbit counts are zero).
     */
    public boolean isEmpty() {
        for (OreType ore : OreType.values()) {
            if (canStore(ore) && count(ore) > 0) {
                return false;
            }
        }
        return true;
    }

    /**
     * Get the total number of ores stored across all types.
     */
    public int getTotalStored() {
        int total = 0;
        for (OreType ore : OreType.values()) {
            total += count(ore);
        }
        return total;
    }

    /**
     * Get the achievement mining progress for an ore (0–100, for "Everything Is Oresome").
     *
     * @return the progress count, or -1 if the ore has no achievement varbit
     */
    public int getAchievementProgress(OreType oreType) {
        if (oreType.achievementVarbit == -1) return -1;
        return api.getVarbit(oreType.achievementVarbit);
    }

    // ========================== Stone Spirits ==========================

    /**
     * Get the stored quantity of a specific stone spirit type.
     *
     * @return the stored count, or -1 if this spirit type has no varplayer mapping
     */
    public int getStoneSpiritCount(StoneSpiritType spiritType) {
        return api.getVarp(spiritType.varplayerId);
    }

    // ========================== Fill & Empty ==========================

    /**
     * Fill the ore box from the backpack.
     *
     * @return {@code true} if the fill action was queued
     */
    public boolean fill() {
        Tier tier = getEquippedTier();
        if (tier == null) {
            log.warn("[OreBox] Cannot fill: no ore box found in backpack");
            return false;
        }
        log.info("[OreBox] Filling {}", tier.name);
        return backpack.interact(tier.name, "Fill");
    }

    /**
     * Empty ores from the ore box into the bank.
     * <p>Must be performed while the bank interface is open. Uses action type 1007
     * with option 8 on the bank backpack component (517, 15).</p>
     *
     * @return {@code true} if the empty action was queued
     */
    public boolean emptyOres() {
        Component comp = findOreBoxInBank("empty ores");
        if (comp == null) return false;
        api.queueAction(new GameAction(ACTION_EMPTY, OPTION_EMPTY_ORES, comp.subComponentId(),
                ComponentHelper.componentHash(comp)));
        log.info("[OreBox] Emptying ores (slot {})", comp.subComponentId());
        return true;
    }

    /**
     * Empty stone spirits from the ore box into the bank.
     * <p>Must be performed while the bank interface is open. Uses action type 1007
     * with option 9 on the bank backpack component (517, 15).</p>
     *
     * @return {@code true} if the empty action was queued
     */
    public boolean emptyStoneSpirits() {
        Component comp = findOreBoxInBank("empty stone spirits");
        if (comp == null) return false;
        api.queueAction(new GameAction(ACTION_EMPTY, OPTION_EMPTY_SPIRITS, comp.subComponentId(),
                ComponentHelper.componentHash(comp)));
        log.info("[OreBox] Emptying stone spirits (slot {})", comp.subComponentId());
        return true;
    }

    /**
     * Deposit the ore box itself into the bank (action type 57, option 2).
     * <p>Must be performed while the bank interface is open.</p>
     *
     * @return {@code true} if the deposit action was queued
     */
    public boolean deposit() {
        Component comp = findOreBoxInBank("deposit");
        if (comp == null) return false;
        ComponentHelper.queueComponentAction(api, comp, OPTION_DEPOSIT);
        log.info("[OreBox] Depositing ore box (slot {})", comp.subComponentId());
        return true;
    }

    // ========================== Helpers ==========================

    private Component findOreBoxInBank(String action) {
        if (!api.isInterfaceOpen(BANK_INTERFACE_ID)) {
            log.warn("[OreBox] Cannot {}: bank is not open", action);
            return null;
        }
        Tier tier = getEquippedTier();
        if (tier == null) {
            log.warn("[OreBox] Cannot {}: no ore box found in backpack", action);
            return null;
        }
        Component comp = api.getComponentChildren(BANK_INTERFACE_ID, BANK_BACKPACK_COMPONENT).stream()
                .filter(c -> c.itemId() == tier.itemId)
                .findFirst().orElse(null);
        if (comp == null) {
            log.warn("[OreBox] Cannot {}: ore box not found in bank backpack view", action);
        }
        return comp;
    }

    // ========================== Enums ==========================

    /**
     * Ore box tiers, from bronze to primal.
     */
    public enum Tier {
        BRONZE    (7,   "Bronze ore box",      44779),
        IRON      (18,  "Iron ore box",        44781),
        STEEL     (29,  "Steel ore box",       44783),
        MITHRIL   (37,  "Mithril ore box",     44785),
        ADAMANT   (41,  "Adamant ore box",     44787),
        RUNE      (55,  "Rune ore box",        44789),
        ORIKALKUM (66,  "Orikalkum ore box",   44791),
        NECRONIUM (72,  "Necronium ore box",   44793),
        BANE      (85,  "Bane ore box",        44795),
        ELDER_RUNE(95,  "Elder rune ore box",  44797),
        PRIMAL    (102, "Primal ore box",      57172);

        /** Smithing/Mining level required for this tier. */
        public final int miningLevel;
        /** Display name of this ore box. */
        public final String name;
        /** In-game item ID. */
        public final int itemId;

        Tier(int miningLevel, String name, int itemId) {
            this.miningLevel = miningLevel;
            this.name = name;
            this.itemId = itemId;
        }
    }

    /**
     * Ore types that can be stored in an ore box.
     * <p>Each ore has a dedicated varbit for its stored count (14-bit, bits 0–13) and
     * an optional achievement progress varbit (7-bit, bits 14–20). The required Mining
     * level determines which box tier can first store it. The mining milestone level
     * triggers a +20 capacity bonus when reached.</p>
     */
    public enum OreType {
        // Standard ores (varbits 43188–43221)
        COPPER       (436,   7,   7,   43188, 43189, false),
        TIN          (438,   7,   7,   43190, 43191, false),
        IRON         (440,   10,  18,  43192, 43193, false),
        COAL         (453,   20,  29,  43194, 43195, false),
        SILVER       (442,   20,  29,  43196, 43197, true),
        MITHRIL      (449,   30,  37,  43198, 43199, false),
        ADAMANTITE   (444,   40,  41,  43200, 43201, false),
        LUMINITE     (44820, 40,  41,  43202, 43203, false),
        GOLD         (447,   40,  41,  43204, 43205, true),
        RUNITE       (451,   50,  55,  43206, 43207, false),
        ORICHALCITE  (44822, 60,  66,  43208, 43209, false),
        DRAKOLITH    (44824, 60,  66,  43210, 43211, false),
        NECRITE      (44826, 70,  72,  43212, 43213, false),
        PHASMATITE   (44828, 70,  72,  43214, 43215, false),
        BANITE       (21778, 80,  85,  43216, 43217, false),
        LIGHT_ANIMICA(44830, 90,  95,  43218, 43219, false),
        DARK_ANIMICA (44832, 90,  95,  43220, 43221, false),

        // DG ores (varbits 55880–55908)
        NOVITE       (57175, 100, 102, 55880, 55881, false),
        BATHUS       (57177, 100, 102, 55883, 55884, false),
        MARMAROS     (57179, 100, 102, 55886, 55887, false),
        KRATONIUM    (57181, 100, 102, 55889, 55890, false),
        FRACTITE     (57183, 100, 102, 55892, 55893, false),
        ZEPHYRIUM    (57185, 100, 102, 55895, 55896, false),
        ARGONITE     (57187, 100, 102, 55898, 55899, false),
        KATAGON      (57189, 100, 102, 55901, 55902, false),
        GORGONITE    (57191, 100, 102, 55904, 55905, false),
        PROMETHIUM   (57193, 100, 102, 55907, 55908, false),

        // Platinum (varbit 58113)
        PLATINUM     (59207, 100, 102, 58113, 58114, true);

        /** In-game item ID for this ore. */
        public final int itemId;
        /** Mining level required to mine this ore. */
        public final int requiredMiningLevel;
        /** Mining level at which this ore gets +20 capacity (matches ore box tier level). */
        public final int miningMilestone;
        /** Varbit ID that stores the count of this ore (14-bit, bits 0–13). */
        public final int countVarbit;
        /** Varbit ID for achievement mining progress (7-bit, bits 14–20), or -1 if none. */
        public final int achievementVarbit;
        /** Whether this ore has a fixed capacity of 100 (Gold, Silver, Platinum). */
        public final boolean fixedCapacity;

        OreType(int itemId, int requiredMiningLevel, int miningMilestone,
                int countVarbit, int achievementVarbit, boolean fixedCapacity) {
            this.itemId = itemId;
            this.requiredMiningLevel = requiredMiningLevel;
            this.miningMilestone = miningMilestone;
            this.countVarbit = countVarbit;
            this.achievementVarbit = achievementVarbit;
            this.fixedCapacity = fixedCapacity;
        }
    }

    /**
     * Stone spirit types that can be stored in the ore box.
     * <p>Each stone spirit is stored in a dedicated varplayer (max 4000 per type),
     * mapped via script-18310.</p>
     */
    public enum StoneSpiritType {
        COPPER     (44799, 11514),
        TIN        (44800, 11515),
        IRON       (44801, 11516),
        COAL       (44804, 11517),
        SILVER     (44802, 11518),
        GOLD       (44803, 11519),
        MITHRIL    (44805, 11520),
        ADAMANTITE (44807, 11521),
        RUNITE     (44808, 11522),
        LUMINITE   (44806, 11523),
        ORICHALCITE(44809, 11524),
        DRAKOLITH  (44810, 11525),
        NECRITE    (44811, 11526),
        PHASMATITE (44812, 11527),
        BANITE     (44813, 11528),
        LIGHT_ANIMICA(44814, 11529),
        DARK_ANIMICA(44815, 11530),
        PRIMAL     (57174, 11809),
        PLATINUM   (59209, 12234);

        /** In-game item ID for this stone spirit. */
        public final int itemId;
        /** Varplayer ID that stores the count. */
        public final int varplayerId;

        StoneSpiritType(int itemId, int varplayerId) {
            this.itemId = itemId;
            this.varplayerId = varplayerId;
        }
    }
}
