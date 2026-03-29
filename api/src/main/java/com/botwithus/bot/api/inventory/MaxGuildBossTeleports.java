package com.botwithus.bot.api.inventory;

import com.botwithus.bot.api.GameAPI;


/**
 * Provides access to the Max Guild boss portal teleport system.
 * <p>Players can attune two portal destinations (A and B) via the Grace of the Elves
 * or the Max Guild portal. Destinations are stored in varbits 45677 (Portal A) and
 * 45678 (Portal B) on varp 9153. Portals A and B cannot share the same destination.</p>
 *
 * <pre>{@code
 * BossTeleports teleports = new BossTeleports(ctx.getGameAPI());
 * int portalA = teleports.getPortalADestination();
 * String name = teleports.getDestinationName(portalA);
 * }</pre>
 */
public final class MaxGuildBossTeleports {


    /** Varbit for main boss portal slot 1 (8-bit). Varp 9152, bits 0-7. */
    public static final int VARBIT_BOSS_PORTAL = 45676;

    private final GameAPI api;

    public MaxGuildBossTeleports(GameAPI api) {
        this.api = api;
    }


    /**
     * Get the destination ID for the main boss portal.
     *
     * @return destination ID, or 0 if not set
     */
    public int getBossPortalDestination() {
        return api.getVarbit(VARBIT_BOSS_PORTAL);
    }

    /**
     * Get the display name for a portal destination ID.
     *
     * @return the destination name, or "Unknown" if not found
     */
    public static String getDestinationName(int destinationId) {
        for (PortalDestination d : PortalDestination.values()) {
            if (d.id == destinationId) return d.name;
        }
        return "Unknown";
    }

    // ========================== Enums ==========================

    /**
     * Max Guild boss portal destinations (enum 6187).
     */
    public enum PortalDestination {
        BORK                (1,  "Bork"),
        GLACOR_CAVERN       (2,  "Glacor cavern"),
        TORMENTED_DEMONS    (3,  "Tormented demons"),
        AIRUT_PENINSULA     (4,  "Airut peninsula"),
        GIANT_MOLE          (5,  "Giant mole"),
        BARROWS             (6,  "Barrows"),
        DAGANNOTH_KINGS     (7,  "Dagannoth Kings"),
        CORPOREAL_BEAST     (8,  "Corporeal beast"),
        KING_BLACK_DRAGON   (9,  "King Black Dragon"),
        QUEEN_BLACK_DRAGON  (10, "Queen Black Dragon"),
        KALPHITE_QUEEN      (11, "Kalphite Queen"),
        KALPHITE_KING       (12, "Kalphite King"),
        COMMANDER_ZILYANA   (13, "Commander Zilyana"),
        GENERAL_GRAARDOR    (14, "General Graardor"),
        KREEARRA            (15, "Kree'arra"),
        KRIL_TSUTSAROTH     (16, "K'ril Tsutsaroth"),
        NEX                 (17, "Nex"),
        LEGIONES            (18, "Legiones"),
        ARAXYTE_HIVE        (19, "Araxyte hive"),
        VORAGO              (20, "Vorago"),
        GREGOROVIC          (21, "Gregorovic"),
        HELWYR              (22, "Helwyr"),
        TWIN_FURIES         (23, "The Twin Furies"),
        VINDICTA_GORVEK     (24, "Vindicta & Gorvek"),
        TELOS               (25, "Telos"),
        THE_MAGISTER        (26, "The Magister"),
        SOLAK               (27, "Solak, Guardian of the Grove"),
        TEMPLE_OF_AMINISHI  (28, "Temple of Aminishi"),
        DRAGONKIN_LABORATORY(29, "Dragonkin Laboratory"),
        SHADOW_REEF         (30, "Shadow Reef"),
        LIBERATION_OF_MAZCAB(31, "Liberation of Mazcab"),
        FIGHT_CAVES         (32, "TzHaar Fight Caves"),
        FIGHT_KILN          (33, "TokHaar Fight Kiln"),
        RAKSHA              (34, "Raksha"),
        REX_MATRIARCHS      (35, "Rex Matriarchs"),
        KERAPAC             (36, "Kerapac"),
        ARCH_GLACOR         (37, "Arch-Glacor"),
        CROESUS             (38, "Croesus"),
        TZKAL_ZUK           (39, "TzKal-Zuk"),
        ZAMORAKIAN_UNDERCITY(40, "The Zamorakian Undercity"),
        RASIALS_CITADEL     (41, "Rasial's Citadel"),
        FORT_FORINTHRY      (42, "Fort Forinthry"),
        SANCTUM_OF_REBIRTH  (43, "Sanctum of Rebirth"),
        GATE_OF_ELIDINIS    (44, "The Gate of Elidinis"),
        AMASCUT             (45, "Amascut"),
        MHEKARNAHZ          (46, "Flesh-hatcher Mhekarnahz"),
        IVAR                (47, "Ivar"),
        SILVERQUILL         (48, "Silverquill");

        /** Destination ID stored in portal varbits. */
        public final int id;
        /** Display name. */
        public final String name;

        PortalDestination(int id, String name) {
            this.id = id;
            this.name = name;
        }
    }
}
