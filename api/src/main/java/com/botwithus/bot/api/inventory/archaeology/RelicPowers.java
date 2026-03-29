package com.botwithus.bot.api.inventory.archaeology;

import com.botwithus.bot.api.GameAPI;


/**
 * Provides access to the Archaeology relic power system.
 * <p>32 relic powers can be unlocked at the mysterious monolith. Up to 3 can be
 * active simultaneously per loadout. Active relics are read from varbits 46702–46704
 * (varp 9455), each returning a relic power ID (0 = empty). Up to 4 loadouts are
 * available (3 base + 1 purchasable from guild shop for 80,000 chronotes).</p>
 *
 * <pre>{@code
 * RelicPowers relics = new RelicPowers(ctx.getGameAPI());
 * if (relics.isActive(RelicPowers.Relic.FLOW_STATE)) {
 *     // No soil will drop — skip soil box fill
 * }
 * if (relics.isActive(RelicPowers.Relic.PERSISTENT_RAGE)) {
 *     // Adrenaline won't drain outside combat
 * }
 * }</pre>
 */
public final class RelicPowers {

    /** Varbit for relic slot 1 — returns active relic power ID. */
    public static final int VARBIT_SLOT_1 = 46702;
    /** Varbit for relic slot 2 — returns active relic power ID. */
    public static final int VARBIT_SLOT_2 = 46703;
    /** Varbit for relic slot 3 — returns active relic power ID. */
    public static final int VARBIT_SLOT_3 = 46704;
    /** Varbit for active loadout index (0–3). */
    public static final int VARBIT_ACTIVE_LOADOUT = 57205;

    private final GameAPI api;

    public RelicPowers(GameAPI api) {
        this.api = api;
    }

    // ========================== Active Relic Queries ==========================

    /**
     * Get the relic power ID in a specific slot (1–3).
     *
     * @param slot the slot number (1, 2, or 3)
     * @return the relic power ID, or 0 if empty or invalid slot
     */
    public int getSlot(int slot) {
        return switch (slot) {
            case 1 -> api.getVarbit(VARBIT_SLOT_1);
            case 2 -> api.getVarbit(VARBIT_SLOT_2);
            case 3 -> api.getVarbit(VARBIT_SLOT_3);
            default -> 0;
        };
    }

    /**
     * Check if a specific relic power is currently active in any slot.
     */
    public boolean isActive(Relic relic) {
        int id = relic.powerId;
        return getSlot(1) == id || getSlot(2) == id || getSlot(3) == id;
    }

    /**
     * Check if a relic power is unlocked (non-zero unlock varbit value).
     */
    public boolean isUnlocked(Relic relic) {
        return api.getVarbit(relic.unlockVarbit) != 0;
    }

    /**
     * Get the active loadout index (0–3).
     */
    public int getActiveLoadout() {
        return api.getVarbit(VARBIT_ACTIVE_LOADOUT);
    }

    /**
     * Get all 3 active relic power IDs as an array.
     *
     * @return array of 3 power IDs (0 = empty slot)
     */
    public int[] getActiveRelicIds() {
        return new int[]{getSlot(1), getSlot(2), getSlot(3)};
    }

    /**
     * All 32 relic powers with their power IDs (returned by slot varbits) and unlock varbits.
     */
    public enum Relic {
        FONT_OF_LIFE          ( 1, 49588, "Increases maximum health"),
        SLAYER_INTROSPECTION  ( 2, 49589, "Choice between min and max slayer assignment amounts"),
        DIVINE_CONVERSION     ( 3, 49590, "Divine locations last longer"),
        ENDURANCE             ( 4, 49591, "Run energy drains 50% slower"),
        FURY_OF_THE_SMALL     ( 5, 49592, "Basic abilities generate +1% adrenaline"),
        POUCH_PROTECTOR       ( 6, 49593, "Runecrafting pouches never degrade"),
        CONSERVATION_OF_ENERGY( 7, 49594, "10% adrenaline refund on threshold use"),
        ABYSSAL_LINK          ( 8, 49595, "Teleport spells require no runes"),
        DEATHLESS             ( 9, 49596, "No death penalty in Dungeoneering"),
        NEXUS_MOD             (10, 49597, "Arrive at Abyss centre"),
        PHARM_ECOLOGY         (11, 49598, "Herbs from farming are noted"),
        ALWAYS_ADZE           (12, 49599, "Woodcutting has chance to burn logs for FM XP"),
        PERSISTENT_RAGE       (13, 49600, "Adrenaline doesn't drain outside combat"),
        STICKY_FINGERS        (14, 49601, "5% chance to double-pick crops"),
        BERSERKERS_FURY       (15, 49602, "Up to +5.5% damage as HP drops"),
        DEATH_WARD            (16, 49603, "5% DR below 50% HP; 10% DR below 25% HP"),
        INSPIRE_AWE           (17, 49605, "+2% XP when training combat skills"),
        INSPIRE_EFFORT        (18, 49606, "+2% XP when training gathering skills"),
        INSPIRE_GENIUS        (19, 49607, "+2% XP when training artisan skills"),
        INSPIRE_LOVE          (20, 49608, "+2% XP when training support skills"),
        RING_OF_LUCK          (21, 49609, "Permanent tier 1 luck effect"),
        RING_OF_WEALTH        (22, 49610, "Permanent tier 2 luck effect"),
        RING_OF_FORTUNE       (23, 49611, "Permanent tier 3 luck effect"),
        UNEXPECTED_DIPLOMACY  (24, 49604, "Charos's clue carrier works on all Slayer creatures"),
        HEIGHTENED_SENSES     (25, 49613, "10% maximum adrenaline increase"),
        LUCK_OF_THE_DWARVES   (26, 49612, "Permanent tier 4 luck effect"),
        BAIT_AND_SWITCH       (27, 50700, "Crystal rod fishing effect always active"),
        FLOW_STATE            (28, 50701, "+20% excavation precision, no soil received"),
        DEATH_NOTE            (29, 50702, "All guaranteed bone/ash drops are noted"),
        SHADOWS_GRACE         (30, 51879, "-50% cooldown on Surge, Escape, Bladed Dive, Barge"),
        BLESSING_OF_HET       (31, 52828, "Food and potions heal 10% more LP"),
        SPIRIT_WEAVER         (32, 56970, "Summoning special attack costs reduced");

        /** Power ID returned by the active slot varbits (46702–46704). */
        public final int powerId;
        /** Varbit that tracks unlock status (non-zero = unlocked). */
        public final int unlockVarbit;
        /** Short description of the relic's effect. */
        public final String description;

        Relic(int powerId, int unlockVarbit, String description) {
            this.powerId = powerId;
            this.unlockVarbit = unlockVarbit;
            this.description = description;
        }

        /**
         * Find a relic by its power ID.
         *
         * @return the matching relic, or {@code null}
         */
        public static Relic fromPowerId(int powerId) {
            for (Relic r : values()) {
                if (r.powerId == powerId) return r;
            }
            return null;
        }
    }
}
