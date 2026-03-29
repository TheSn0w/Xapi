package com.botwithus.bot.api.inventory.archaeology;

import com.botwithus.bot.api.GameAPI;


/**
 * Provides access to Archaeology collection completion state.
 * <p>Each artifact collection has a boolean varbit (1 = complete, 0 = incomplete).
 * There are 45 hand-in collections and 28 museum collections, each with a minimum
 * Archaeology level requirement.</p>
 *
 * <pre>{@code
 * Collections collections = new Collections(ctx.getGameAPI());
 * if (collections.isComplete(Collections.HandIn.ZAROSIAN_I)) {
 *     // First Zarosian collection has been handed in
 * }
 * int count = collections.getCompletedHandInCount();
 * }</pre>
 */
public final class Collections {

    private final GameAPI api;

    public Collections(GameAPI api) {
        this.api = api;
    }

    /**
     * Check if a hand-in collection is complete.
     */
    public boolean isComplete(HandIn collection) {
        return api.getVarbit(collection.varbit) == 1;
    }

    /**
     * Check if a museum collection is complete.
     */
    public boolean isComplete(Museum collection) {
        return api.getVarbit(collection.varbit) == 1;
    }

    /**
     * Count the number of completed hand-in collections.
     */
    public int getCompletedHandInCount() {
        int count = 0;
        for (HandIn c : HandIn.values()) {
            if (api.getVarbit(c.varbit) == 1) count++;
        }
        return count;
    }

    /**
     * Count the number of completed museum collections.
     */
    public int getCompletedMuseumCount() {
        int count = 0;
        for (Museum c : Museum.values()) {
            if (api.getVarbit(c.varbit) == 1) count++;
        }
        return count;
    }

    /**
     * Hand-in collections (given to collectors for rewards).
     */
    public enum HandIn {
        ZAROSIAN_I              (25,  46059),
        ZAMORAKIAN_I            (36,  46048),
        SARADOMINIST_I          (56,  46055),
        FINERY_OF_THE_INQUISITION(64, 30936),
        ENTERTAINING_THE_MASSES (67,  43668),
        RELIGIOUS_ICONOGRAPHY   (67,  30938),
        URNS_OF_THE_EMPIRE      (67,  30940),
        BLINGY_FINGS            (69,  46040),
        SARADOMINIST_II         (72,  46056),
        DRAGONKIN_V             (77,  55529),
        SMOKY_FINGS             (81,  46038),
        ZAROSIAN_II             (81,  46060),
        ZAMORAKIAN_II           (81,  46049),
        ARMADYLEAN_I            (81,  46052),
        GREEN_GOBBO_GOODIES_I   (83,  46045),
        DRAGONKIN_VI            (87,  55530),
        ANARCHIC_ABSTRACTION    (89,  46037),
        HITTY_FINGS             (89,  46041),
        WISE_AM_THE_MUSIC_MAN   (91,  46081),
        SHOWY_FINGS             (92,  46039),
        RED_RUM_RELICS_I        (94,  46042),
        GREEN_GOBBO_GOODIES_II  (97,  46046),
        ARMADYLEAN_II           (98,  46053),
        DRAGONKIN_I             (99,  48034),
        SARADOMINIST_III        (100, 46057),
        DRAGONKIN_II            (102, 48035),
        ZAMORAKIAN_III          (104, 46050),
        RADIANT_RENAISSANCE     (105, 46036),
        ZAROSIAN_III            (107, 46061),
        IMPERIAL_SORCERY        (107, 44901),
        DRAGONKIN_III           (108, 48036),
        RED_RUM_RELICS_II       (110, 46043),
        DRAGONKIN_VII           (113, 55531),
        HAT_PROBLEM             (114, 46083),
        HAT_HOARDER             (116, 46082),
        ZAMORAKIAN_IV           (116, 46051),
        SARADOMINIST_IV         (117, 46058),
        MAGIC_MAN               (118, 46084),
        ZAROSIAN_IV             (118, 46062),
        ARMADYLEAN_III          (118, 46054),
        IMPERIAL_IMPRESSIONISM  (118, 46035),
        KNOWLEDGE_IS_POWER      (119, 46085),
        GREEN_GOBBO_GOODIES_III (119, 46047),
        RED_RUM_RELICS_III      (119, 46044),
        DRAGONKIN_IV            (120, 48037);

        /** Minimum Archaeology level for this collection. */
        public final int minLevel;
        /** Varbit tracking completion (1 = complete). */
        public final int varbit;

        HandIn(int minLevel, int varbit) {
            this.minLevel = minLevel;
            this.varbit = varbit;
        }
    }

    /**
     * Museum collections (donated to the Varrock Museum).
     */
    public enum Museum {
        ZAROSIAN_I          (25,  46063),
        ZAMORAKIAN_I        (36,  46071),
        SARADOMINIST_I      (56,  46067),
        ZAROSIAN_V          (62,  49802),
        ZAROSIAN_VI         (64,  49803),
        ZAROSIAN_VII        (67,  49804),
        SARADOMINIST_II     (72,  46068),
        DRAGONKIN_V         (77,  55532),
        ZAROSIAN_II         (81,  46064),
        ZAMORAKIAN_II       (81,  46072),
        ARMADYLEAN_I        (81,  46075),
        DRAGONKIN_VI        (87,  55533),
        BANDOSIAN_I         (89,  46078),
        ARMADYLEAN_II       (98,  46076),
        DRAGONKIN_I         (99,  48030),
        BANDOSIAN_II        (100, 46079),
        SARADOMINIST_III    (100, 46069),
        DRAGONKIN_II        (102, 48031),
        ZAMORAKIAN_III      (104, 46073),
        ZAROSIAN_III        (107, 46065),
        DRAGONKIN_III       (108, 48032),
        DRAGONKIN_VII       (113, 55534),
        ZAMORAKIAN_IV       (116, 46074),
        SARADOMINIST_IV     (117, 46070),
        ARMADYLEAN_III      (118, 46077),
        ZAROSIAN_IV         (118, 46066),
        BANDOSIAN_III       (119, 46080),
        DRAGONKIN_IV        (120, 48033);

        /** Minimum Archaeology level for this collection. */
        public final int minLevel;
        /** Varbit tracking completion (1 = complete). */
        public final int varbit;

        Museum(int minLevel, int varbit) {
            this.minLevel = minLevel;
            this.varbit = varbit;
        }
    }
}
