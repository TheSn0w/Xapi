package com.botwithus.bot.api.inventory;

import com.botwithus.bot.api.GameAPI;
import com.botwithus.bot.api.log.BotLogger;
import com.botwithus.bot.api.log.LoggerFactory;
import com.botwithus.bot.api.model.Component;
import com.botwithus.bot.api.model.GameAction;


/**
 * Provides access to the gem bag family of portable gem storage items.
 * <p>Four variants exist:
 * <ul>
 *   <li><b>Gem bag</b> (18338) — 4 gem types (sapphire–diamond), 100 total, bit-packed
 *       into varclient 2154 (4× 8-bit counts).</li>
 *   <li><b>Gem bag (upgraded)</b> (31455) — 5 gem types (adds dragonstone), 60 each,
 *       stored via 6-bit varbits 22581–22585.</li>
 *   <li><b>Artificer's measure</b> (50805) — 5 gem types, 500 each, equippable shield slot,
 *       stored via 9-bit varbits 48172–48176.</li>
 *   <li><b>Spirit gem bag</b> (38934) — 6 spirit gem types, 60 each,
 *       stored via 10-bit varbits 44598–44603.</li>
 * </ul>
 *
 * <pre>{@code
 * GemBag gemBag = new GemBag(ctx.getGameAPI());
 * GemBag.Variant variant = gemBag.getEquippedVariant();
 * if (variant != null && !gemBag.isFull(GemBag.GemType.RUBY)) {
 *     gemBag.fill();
 * }
 * }</pre>
 */
public final class GemBag {

    private static final BotLogger log = LoggerFactory.getLogger(GemBag.class);

    // Bank backpack component (for empty action)
    private static final int BANK_INTERFACE_ID = 517;
    private static final int BANK_BACKPACK_COMPONENT = 15;
    private static final int OPTION_EMPTY = 8;
    private static final int OPTION_DEPOSIT = 2;
    private static final int ACTION_EMPTY = 1007;

    /** Varclient used for base gem bag bit-packed storage. */
    private static final int BASE_BAG_VARCLIENT = 2154;

    /** Varbit that tracks gem bag tier/state (0=none, 1=base, 2+=upgraded). */
    public static final int VARBIT_GEM_BAG_TIER = 18340;
    /** Varbit for gem bag upgrade achievement flag. */
    public static final int VARBIT_UPGRADE_FLAG = 59201;

    private final GameAPI api;
    private final Backpack backpack;
    private final Equipment equipment;

    public GemBag(GameAPI api) {
        this.api = api;
        this.backpack = new Backpack(api);
        this.equipment = new Equipment(api);
    }

    // ========================== Variant Detection ==========================

    /**
     * Check if the player has any gem bag variant in their backpack or equipment.
     */
    public boolean hasGemBag() {
        return getEquippedVariant() != null;
    }

    /**
     * Detect which gem bag variant the player currently has.
     * <p>Checks backpack first, then equipment (for Artificer's Measure shield slot).</p>
     *
     * @return the detected {@link Variant}, or {@code null} if none found
     */
    public Variant getEquippedVariant() {
        for (Variant v : Variant.values()) {
            if (backpack.contains(v.itemId)) {
                return v;
            }
        }
        // Artificer's Measure can be equipped in shield slot
        if (equipment.contains(Variant.ARTIFICER.itemId)) {
            return Variant.ARTIFICER;
        }
        return null;
    }

    /**
     * Check if the current variant can store the given gem type.
     */
    public boolean canStore(GemType gemType) {
        Variant variant = getEquippedVariant();
        if (variant == null) return false;
        return gemType.supportsVariant(variant);
    }

    // ========================== Count & Capacity ==========================

    /**
     * Get the stored quantity of a gem type for the current variant.
     * <p>For the base gem bag, unpacks from the bit-packed varclient.
     * For upgraded/Artificer's/spirit, reads the dedicated varbit.</p>
     *
     * @return the stored count, or 0 if no gem bag is found or gem not supported
     */
    public int count(GemType gemType) {
        Variant variant = getEquippedVariant();
        if (variant == null) return 0;
        return countForVariant(gemType, variant);
    }

    /**
     * Get the stored quantity of a gem type for a specific variant.
     */
    public int countForVariant(GemType gemType, Variant variant) {
        int varbit = gemType.varbitFor(variant);
        if (varbit == -1) {
            // Base bag: bit-packed in varclient 2154
            if (variant == Variant.BASE && gemType.baseBagBitShift >= 0) {
                int packed = api.getVarcInt(BASE_BAG_VARCLIENT);
                return (packed >> gemType.baseBagBitShift) & 0xFF;
            }
            return 0;
        }
        return api.getVarbit(varbit);
    }

    /**
     * Get the max capacity per gem type for the current variant.
     *
     * @return the per-gem capacity, or 0 if no gem bag found
     */
    public int getCapacity(GemType gemType) {
        Variant variant = getEquippedVariant();
        if (variant == null) return 0;
        return variant.capacityPerGem;
    }

    /**
     * Check if a gem type has reached capacity in the current variant.
     */
    public boolean isFull(GemType gemType) {
        return count(gemType) >= getCapacity(gemType);
    }

    /**
     * Check if the gem bag has no stored gems for the current variant.
     */
    public boolean isEmpty() {
        Variant variant = getEquippedVariant();
        if (variant == null) return true;
        for (GemType gem : GemType.values()) {
            if (gem.supportsVariant(variant) && countForVariant(gem, variant) > 0) {
                return false;
            }
        }
        return true;
    }

    /**
     * Get the total number of gems stored across all types for the current variant.
     */
    public int getTotalStored() {
        Variant variant = getEquippedVariant();
        if (variant == null) return 0;
        int total = 0;
        for (GemType gem : GemType.values()) {
            if (gem.supportsVariant(variant)) {
                total += countForVariant(gem, variant);
            }
        }
        return total;
    }

    // ========================== Fill & Empty ==========================

    /**
     * Fill the gem bag from the backpack.
     *
     * @return {@code true} if the fill action was queued
     */
    public boolean fill() {
        Variant variant = getEquippedVariant();
        if (variant == null) {
            log.warn("[GemBag] Cannot fill: no gem bag found");
            return false;
        }
        log.info("[GemBag] Filling {}", variant.name);
        return backpack.interact(variant.name, "Fill");
    }

    /**
     * Withdraw gems from the gem bag into the backpack.
     *
     * @return {@code true} if the withdraw action was queued
     */
    public boolean withdraw() {
        Variant variant = getEquippedVariant();
        if (variant == null) {
            log.warn("[GemBag] Cannot withdraw: no gem bag found");
            return false;
        }
        log.info("[GemBag] Withdrawing from {}", variant.name);
        return backpack.interact(variant.name, "Withdraw");
    }

    /**
     * Empty the gem bag into the bank.
     * <p>Must be performed while the bank interface is open.</p>
     *
     * @return {@code true} if the empty action was queued
     */
    public boolean empty() {
        Component comp = findGemBagInBank("Empty");
        if (comp == null) return false;
        api.queueAction(new GameAction(ACTION_EMPTY, OPTION_EMPTY, comp.subComponentId(),
                ComponentHelper.componentHash(comp)));
        log.info("[GemBag] Emptying gems (slot {})", comp.subComponentId());
        return true;
    }

    /**
     * Deposit the gem bag itself into the bank (action type 57, option 2).
     * <p>Must be performed while the bank interface is open.</p>
     *
     * @return {@code true} if the deposit action was queued
     */
    public boolean deposit() {
        Component comp = findGemBagInBank("deposit");
        if (comp == null) return false;
        ComponentHelper.queueComponentAction(api, comp, OPTION_DEPOSIT);
        log.info("[GemBag] Depositing gem bag (slot {})", comp.subComponentId());
        return true;
    }

    // ========================== Helpers ==========================

    private Component findGemBagInBank(String action) {
        if (!api.isInterfaceOpen(BANK_INTERFACE_ID)) {
            log.warn("[GemBag] Cannot {}: bank is not open", action);
            return null;
        }
        Variant variant = getEquippedVariant();
        if (variant == null) {
            log.warn("[GemBag] Cannot {}: no gem bag found", action);
            return null;
        }
        Component comp = api.getComponentChildren(BANK_INTERFACE_ID, BANK_BACKPACK_COMPONENT).stream()
                .filter(c -> c.itemId() == variant.itemId)
                .findFirst().orElse(null);
        if (comp == null) {
            log.warn("[GemBag] Cannot {}: gem bag not found in bank backpack view", action);
        }
        return comp;
    }

    // ========================== Enums ==========================

    /**
     * Gem bag variants.
     */
    public enum Variant {
        /** Base gem bag — 4 gem types, 100 total, bit-packed in varclient 2154. */
        BASE      (18338, "Gem bag",              100),
        /** Upgraded gem bag — 5 gem types, 60 each, individual varbits. */
        UPGRADED  (31455, "Gem bag (upgraded)",     60),
        /** Artificer's measure — 5 gem types, 500 each, equippable shield. */
        ARTIFICER (50805, "Artificer's measure",   500),
        /** Spirit gem bag — 6 spirit gem types, 60 each. */
        SPIRIT    (38934, "Spirit gem bag",         60);

        /** In-game item ID. */
        public final int itemId;
        /** Display name. */
        public final String name;
        /** Max capacity per gem type. */
        public final int capacityPerGem;

        Variant(int itemId, String name, int capacityPerGem) {
            this.itemId = itemId;
            this.name = name;
            this.capacityPerGem = capacityPerGem;
        }
    }

    /**
     * Gem types that can be stored across all gem bag variants.
     * <p>Each gem type stores which variants it supports and the varbit IDs for
     * each variant's storage. The base bag uses bit-packed varclient storage
     * instead of varbits.</p>
     */
    public enum GemType {
        // Standard gems (base + upgraded + artificer's)
        SAPPHIRE       (1623, "Uncut sapphire",      0,  22581, 48172, -1),
        EMERALD        (1621, "Uncut emerald",        8,  22582, 48173, -1),
        RUBY           (1619, "Uncut ruby",          16,  22583, 48174, -1),
        DIAMOND        (1617, "Uncut diamond",       24,  22584, 48175, -1),
        DRAGONSTONE    (1631, "Uncut dragonstone",   -1,  22585, 48176, -1),

        // Spirit gems (spirit gem bag only)
        SPIRIT_SAPPHIRE   (30139, "Spirit sapphire",    -1, -1, -1, 44598),
        SPIRIT_EMERALD    (30140, "Spirit emerald",     -1, -1, -1, 44599),
        SPIRIT_RUBY       (30141, "Spirit ruby",        -1, -1, -1, 44600),
        SPIRIT_DIAMOND    (30142, "Spirit diamond",     -1, -1, -1, 44601),
        SPIRIT_DRAGONSTONE(30143, "Spirit dragonstone", -1, -1, -1, 44602),
        SPIRIT_ONYX       (30144, "Spirit onyx",        -1, -1, -1, 44603);

        /** In-game item ID (display ID for spirit gems). */
        public final int itemId;
        /** Display name. */
        public final String name;
        /** Bit shift in the base bag's packed varclient (0/8/16/24), or -1 if not stored in base. */
        public final int baseBagBitShift;
        /** Varbit for upgraded gem bag count, or -1 if not applicable. */
        public final int upgradedVarbit;
        /** Varbit for Artificer's Measure count, or -1 if not applicable. */
        public final int artificerVarbit;
        /** Varbit for spirit gem bag count, or -1 if not applicable. */
        public final int spiritVarbit;

        GemType(int itemId, String name, int baseBagBitShift,
                int upgradedVarbit, int artificerVarbit, int spiritVarbit) {
            this.itemId = itemId;
            this.name = name;
            this.baseBagBitShift = baseBagBitShift;
            this.upgradedVarbit = upgradedVarbit;
            this.artificerVarbit = artificerVarbit;
            this.spiritVarbit = spiritVarbit;
        }

        /**
         * Check if this gem type can be stored in the given variant.
         */
        public boolean supportsVariant(Variant variant) {
            return varbitFor(variant) != -1
                    || (variant == Variant.BASE && baseBagBitShift >= 0);
        }

        /**
         * Get the varbit ID for reading this gem's count from the given variant.
         *
         * @return the varbit ID, or -1 if this variant uses bit-packing or doesn't support this gem
         */
        public int varbitFor(Variant variant) {
            return switch (variant) {
                case BASE -> -1; // bit-packed, no varbit
                case UPGRADED -> upgradedVarbit;
                case ARTIFICER -> artificerVarbit;
                case SPIRIT -> spiritVarbit;
            };
        }
    }
}
