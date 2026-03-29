package com.botwithus.bot.api.inventory;

import com.botwithus.bot.api.GameAPI;
import com.botwithus.bot.api.log.BotLogger;
import com.botwithus.bot.api.log.LoggerFactory;

/**
 * Provides access to the incense sticks system for reading overload stacks and duration timers.
 * <p>Each of the 18 herb incense types has two paired varbits:</p>
 * <ul>
 *   <li><b>Overload varbit</b> (4-bit, 0-4) — current overload stack count</li>
 *   <li><b>Duration varbit</b> (8-bit, 0-255) — time remaining in game ticks</li>
 * </ul>
 * <p>Overload stacks (0-4) multiply the incense effect. At 4 stacks the effect is at maximum.
 * Duration decreases over time; when it reaches 0 stacks begin to decay.</p>
 *
 * <p>The varbit pairing rule is: {@code overload_varbit = duration_varbit - 18}.</p>
 *
 * <pre>{@code
 * IncenseSticks incense = new IncenseSticks(ctx.getGameAPI());
 * int stacks = incense.getOverloadStacks(IncenseSticks.HerbType.IRIT);
 * int duration = incense.getDuration(IncenseSticks.HerbType.IRIT);
 * if (!incense.isActive(IncenseSticks.HerbType.LANTADYME)) {
 *     backpack.interact("Lantadyme incense sticks", "Light");
 * }
 * }</pre>
 */
public final class IncenseSticks {

    private static final BotLogger log = LoggerFactory.getLogger(IncenseSticks.class);

    /** Maximum overload stacks for any incense type. */
    public static final int MAX_OVERLOAD_STACKS = 4;

    /** Varbit for the incense system flag (2-bit on varp 7903). */
    public static final int SYSTEM_FLAG_VARBIT = 43690;

    /** Varbit for incense unlock/toggle (1-bit on varp 1100). */
    public static final int UNLOCK_VARBIT = 43691;

    private final GameAPI api;
    private final Backpack backpack;

    public IncenseSticks(GameAPI api) {
        this.api = api;
        this.backpack = new Backpack(api);
    }

    // ========================== Overload Stacks ==========================

    /**
     * Get the current overload stack count for a herb type (0-4).
     */
    public int getOverloadStacks(HerbType type) {
        return api.getVarbit(type.overloadVarbit);
    }

    /**
     * Check if a herb incense is at maximum overload stacks (4).
     */
    public boolean isMaxOverload(HerbType type) {
        return getOverloadStacks(type) >= MAX_OVERLOAD_STACKS;
    }

    // ========================== Duration ==========================

    /**
     * Get the remaining duration for a herb type in raw varbit value (0-255).
     * <p>This value decreases over time. When it reaches 0, stacks begin to decay.</p>
     */
    public int getDuration(HerbType type) {
        return api.getVarbit(type.durationVarbit);
    }

    /**
     * Get the remaining duration in approximate minutes.
     * <p>Each varbit unit represents roughly 10 seconds of real time (approximately
     * 16-17 game ticks). The maximum value of 255 corresponds to roughly 30 minutes
     * of potency before overload starts decaying.</p>
     */
    public double getDurationMinutes(HerbType type) {
        return getDuration(type) * 10.0 / 60.0;
    }

    // ========================== Status Checks ==========================

    /**
     * Check if a herb incense is currently active (has both stacks and duration remaining).
     */
    public boolean isActive(HerbType type) {
        return getOverloadStacks(type) > 0 && getDuration(type) > 0;
    }

    /**
     * Check if a herb incense has duration remaining (even if stacks are 0).
     */
    public boolean hasDuration(HerbType type) {
        return getDuration(type) > 0;
    }

    /**
     * Check if the incense system is unlocked.
     */
    public boolean isUnlocked() {
        return api.getVarbit(UNLOCK_VARBIT) == 1;
    }

    // ========================== Backpack Interaction ==========================

    /**
     * Check if the player has a specific herb incense stick in their backpack.
     */
    public boolean hasInBackpack(HerbType type) {
        return backpack.contains(type.itemId);
    }

    /**
     * Count how many of a specific herb incense stick are in the backpack.
     */
    public int countInBackpack(HerbType type) {
        return backpack.count(type.itemId);
    }

    /**
     * Light an incense stick from the backpack to add an overload stack.
     *
     * @return {@code true} if the light action was queued
     */
    public boolean light(HerbType type) {
        if (!hasInBackpack(type)) {
            log.warn("[IncenseSticks] Cannot light: no {} in backpack", type.herbName);
            return false;
        }
        log.info("[IncenseSticks] Lighting {} incense sticks", type.herbName);
        return backpack.interact(type.itemId, "Light");
    }

    /**
     * Overload an incense stick to instantly set stacks to maximum (4).
     * <p>Consumes additional sticks. The incense must already be lit.</p>
     *
     * @return {@code true} if the overload action was queued
     */
    public boolean overload(HerbType type) {
        if (!hasInBackpack(type)) {
            log.warn("[IncenseSticks] Cannot overload: no {} in backpack", type.herbName);
            return false;
        }
        if (!isActive(type)) {
            log.warn("[IncenseSticks] Cannot overload: {} is not currently active", type.herbName);
            return false;
        }
        log.info("[IncenseSticks] Overloading {} incense sticks", type.herbName);
        return backpack.interact(type.itemId, "Overload");
    }

    // ========================== Enums ==========================

    /**
     * The 18 herb incense types with their item IDs and paired overload/duration varbits.
     * <p>Each type has a burner type (1-18) used by client scripts 3152/3153/12675.
     * The overload varbit is always {@code durationVarbit - 18}.</p>
     */
    public enum HerbType {
        GUAM         ( 1, "Guam",         47699, 43703, 43721, false),
        TARROMIN     ( 2, "Tarromin",     47700, 43704, 43722, false),
        MARRENTILL   ( 3, "Marrentill",   47701, 43692, 43710, true),
        HARRALANDER  ( 4, "Harralander",  47702, 43693, 43711, true),
        RANARR       ( 5, "Ranarr",       47703, 43694, 43712, true),
        TOADFLAX     ( 6, "Toadflax",     47704, 43705, 43723, true),
        SPIRIT_WEED  ( 7, "Spirit Weed",  47705, 43695, 43713, true),
        IRIT         ( 8, "Irit",         47706, 43696, 43714, true),
        WERGALI      ( 9, "Wergali",      47707, 43706, 43724, true),
        AVANTOE      (10, "Avantoe",      47708, 43697, 43715, true),
        KWUARM       (11, "Kwuarm",       47709, 43707, 43725, true),
        BLOODWEED    (12, "Bloodweed",    47710, 43708, 43726, true),
        SNAPDRAGON   (13, "Snapdragon",   47711, 43698, 43716, true),
        CADANTINE    (14, "Cadantine",    47712, 43709, 43727, true),
        LANTADYME    (15, "Lantadyme",    47713, 43699, 43717, true),
        DWARF_WEED   (16, "Dwarf Weed",   47714, 43700, 43718, true),
        TORSTOL      (17, "Torstol",      47715, 43701, 43719, true),
        FELLSTALK    (18, "Fellstalk",    47716, 43702, 43720, true);

        /** Burner type (1-18) used by client scripts 3152/3153/12675. */
        public final int burnerType;
        /** Display name of this herb type. */
        public final String herbName;
        /** Item ID for the herb incense stick (47699-47716). */
        public final int itemId;
        /** Overload varbit (4-bit, 0-4 stacks, varps 8406-8408). */
        public final int overloadVarbit;
        /** Duration varbit (8-bit, 0-255, varps 8409-8413). */
        public final int durationVarbit;
        /** Whether this herb type requires members (Guam and Tarromin are F2P). */
        public final boolean members;

        HerbType(int burnerType, String herbName, int itemId, int overloadVarbit, int durationVarbit, boolean members) {
            this.burnerType = burnerType;
            this.herbName = herbName;
            this.itemId = itemId;
            this.overloadVarbit = overloadVarbit;
            this.durationVarbit = durationVarbit;
            this.members = members;
        }

        /**
         * Find a herb type by its item ID.
         *
         * @return the matching {@link HerbType}, or {@code null} if not found
         */
        public static HerbType fromItemId(int itemId) {
            for (HerbType type : values()) {
                if (type.itemId == itemId) return type;
            }
            return null;
        }

        /**
         * Find a herb type by its burner type (1-18).
         *
         * @return the matching {@link HerbType}, or {@code null} if not found
         */
        public static HerbType fromBurnerType(int burnerType) {
            for (HerbType type : values()) {
                if (type.burnerType == burnerType) return type;
            }
            return null;
        }
    }

    /**
     * Base wood incense stick types (crafting materials, no herb effect).
     */
    public enum WoodType {
        WOODEN  (47685, "Wooden incense sticks"),
        OAK     (47686, "Oak incense sticks"),
        WILLOW  (47687, "Willow incense sticks"),
        MAPLE   (47688, "Maple incense sticks"),
        ACADIA  (47689, "Acadia incense sticks"),
        YEW     (47690, "Yew incense sticks"),
        MAGIC   (47691, "Magic incense sticks");

        /** In-game item ID. */
        public final int itemId;
        /** Display name. */
        public final String name;

        WoodType(int itemId, String name) {
            this.itemId = itemId;
            this.name = name;
        }
    }

    /**
     * Prayer variant incense sticks (Impious/Accursed/Infernal, provide Prayer XP bonuses).
     */
    public enum PrayerType {
        IMPIOUS         (47692, "Impious incense sticks"),
        IMPIOUS_OAK     (47693, "Impious oak incense sticks"),
        IMPIOUS_WILLOW  (47694, "Impious willow incense sticks"),
        ACCURSED_MAPLE  (47695, "Accursed maple incense sticks"),
        ACCURSED_ACADIA (47696, "Accursed acadia incense sticks"),
        INFERNAL_YEW    (47697, "Infernal yew incense sticks"),
        INFERNAL_MAGIC  (47698, "Infernal magic incense sticks");

        /** In-game item ID. */
        public final int itemId;
        /** Display name. */
        public final String name;

        PrayerType(int itemId, String name) {
            this.itemId = itemId;
            this.name = name;
        }
    }
}
