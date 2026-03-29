package com.botwithus.bot.api.inventory.archaeology;

import com.botwithus.bot.api.GameAPI;


/**
 * Provides access to the Archaeology research team system.
 * <p>Unlocked at Assistant qualification (varbit 46468 >= 2). Players send teams on
 * 1–24 hour expeditions for passive XP, materials, and artefacts. Research state is
 * tracked on varp 9235 and time remaining on varp 9535.</p>
 *
 * <pre>{@code
 * Research research = new Research(ctx.getGameAPI());
 * if (research.isUnlocked() && !research.isActive()) {
 *     // Can start a new research expedition
 * }
 * }</pre>
 */
public final class Research {

    /** Varbit for research team state (0 = not started, 5/10 = tutorial steps, 50 = completed). */
    public static final int VARBIT_STATE = 46466;
    /** Varbit for research time remaining / progress (17-bit, max 131,071). */
    public static final int VARBIT_TIME_REMAINING = 47802;
    /** Varbit for expedition status flag. */
    public static final int VARBIT_EXPEDITION_STATUS = 47803;
    /** Varbit for expedition/team data (6-bit). */
    public static final int VARBIT_EXPEDITION_DATA = 47804;

    /** Varbit for qualification level (gates research at >= 2). */
    public static final int VARBIT_QUALIFICATION = 46468;

    // Research data buffers (varp 9236–9237)
    /** Varbit for research data buffer 1 (16-bit). */
    public static final int VARBIT_DATA_1 = 46471;
    /** Varbit for research data buffer 2 (16-bit). */
    public static final int VARBIT_DATA_2 = 46472;
    /** Varbit for research data buffer 3 (16-bit). */
    public static final int VARBIT_DATA_3 = 46473;
    /** Varbit for research data buffer 4 (16-bit). */
    public static final int VARBIT_DATA_4 = 46474;

    private final GameAPI api;

    public Research(GameAPI api) {
        this.api = api;
    }

    // ========================== State ==========================

    /**
     * Check if the research team system is unlocked (requires Assistant qualification).
     */
    public boolean isUnlocked() {
        return api.getVarbit(VARBIT_QUALIFICATION) >= 2;
    }

    /**
     * Get the raw research state value.
     * <p>0 = not started, 5/10 = tutorial steps, 50 = completed research.</p>
     */
    public int getState() {
        return api.getVarbit(VARBIT_STATE);
    }

    /**
     * Check if a research expedition is currently in progress.
     */
    public boolean isActive() {
        int state = getState();
        return state > 0 && state < 50;
    }

    /**
     * Check if the last research expedition has completed.
     */
    public boolean isComplete() {
        return getState() >= 50;
    }

    // ========================== Time ==========================

    /**
     * Get the remaining time/progress value for the current research.
     * <p>17-bit value (max 131,071), likely tick-based. 0 when no research active.</p>
     */
    public int getTimeRemaining() {
        return api.getVarbit(VARBIT_TIME_REMAINING);
    }

    /**
     * Get the expedition status flag.
     */
    public int getExpeditionStatus() {
        return api.getVarbit(VARBIT_EXPEDITION_STATUS);
    }

    /**
     * Get the expedition/team data value (6-bit, max 63).
     */
    public int getExpeditionData() {
        return api.getVarbit(VARBIT_EXPEDITION_DATA);
    }

    /**
     * Research categories (from script 14841).
     */
    public enum Category {
        SPECIAL     (0, "Special research"),
        ARMADYLEAN  (1, "Armadylean research"),
        BANDOSIAN   (2, "Bandosian research"),
        DRAGONKIN   (3, "Dragonkin research"),
        SARADOMINIST(4, "Saradominist research"),
        ZAMORAKIAN  (5, "Zamorakian research"),
        ZAROSIAN    (6, "Zarosian research");

        /** Category ID used in scripts. */
        public final int id;
        /** Display name. */
        public final String name;

        Category(int id, String name) {
            this.id = id;
            this.name = name;
        }
    }

    /**
     * Researcher perks (from script 14682).
     */
    public enum ResearcherPerk {
        MORE_MATERIALS      (1, "Increases the amount of materials found"),
        MUCH_MORE_MATERIALS (2, "Greatly increases the amount of materials found"),
        GUARANTEED_ARTEFACT (3, "Guarantees the discovery of an artefact (2h+ research only)"),
        MORE_ARTEFACTS      (4, "Increases the chance of discovering artefacts"),
        FASTER_RESEARCH     (5, "Significantly increases the research speed"),
        BONUS_XP            (6, "10% increase to Archaeology XP"),
        FREE_OF_CHARGE      (7, "Happily works free of charge"),
        TETRACOMPASS_PIECE   (8, "Chance to obtain a tetracompass piece (research time / 24)"),
        MOONRISE_DOUBLE_SPEED(9, "Double speed when assigned to the Moonrise Temple digsite");

        /** Perk ID used in scripts. */
        public final int id;
        /** Description of the perk effect. */
        public final String description;

        ResearcherPerk(int id, String description) {
            this.id = id;
            this.description = description;
        }
    }
}
