package com.botwithus.bot.api.inventory.warsRetreat;

import com.botwithus.bot.api.GameAPI;
import com.botwithus.bot.api.log.BotLogger;
import com.botwithus.bot.api.log.LoggerFactory;

/**
 * Provides access to War's Retreat boss portal system.
 * <p>War's Retreat has 3 configurable boss portals that share the same varbit and enum
 * system as Max Guild garden portals. Destinations are stored in varbits 45676-45678
 * and map to 48 boss locations via enum 6187.</p>
 *
 * <p>Attunement requires a minimum of 1 boss kill (validated by script 13082). Free daily
 * attunements are gated by War's Blessing tier. Portals cannot share the same destination.</p>
 *
 * <pre>{@code
 * BossPortals portals = new BossPortals(ctx.getGameAPI());
 * BossPortals.Destination dest = portals.getPortal1Destination();
 * if (dest != null) {
 *     log.info("Portal 1: {}", dest.name);
 * }
 * }</pre>
 */
public final class BossPortals {

    private static final BotLogger log = LoggerFactory.getLogger(BossPortals.class);

    // ========================== Portal Varbits ==========================

    /** Portal configuration data (7-bit, varp 9136 bits 16-22). */
    public static final int VARBIT_PORTAL_CONFIG = 45674;

    /** Portal flag (1-bit, varp 9136 bit 23). */
    public static final int VARBIT_PORTAL_FLAG = 45675;

    /** Portal 1 destination — boss ID from enum 6187 (8-bit, varp 9152 bits 0-7). */
    public static final int VARBIT_PORTAL_1 = 45676;

    /** Portal 2 destination — boss ID from enum 6187 (8-bit, varp 9153 bits 0-7). */
    public static final int VARBIT_PORTAL_2 = 45677;

    /** Portal 3 destination — boss ID from enum 6187 (8-bit, varp 9153 bits 8-15). */
    public static final int VARBIT_PORTAL_3 = 45678;

    /** Portal flag (1-bit, varp 9153 bit 16). */
    public static final int VARBIT_PORTAL_FLAG_A = 45679;

    /** Portal flag (1-bit, varp 9153 bit 17). */
    public static final int VARBIT_PORTAL_FLAG_B = 45680;

    private final GameAPI api;

    public BossPortals(GameAPI api) {
        this.api = api;
    }

    // ========================== Portal Destinations ==========================

    /**
     * Get Portal 1's destination as a {@link Destination} enum value.
     *
     * @return the destination, or {@code null} if not attuned or unknown
     */
    public Destination getPortal1Destination() {
        return Destination.fromId(api.getVarbit(VARBIT_PORTAL_1));
    }

    /**
     * Get Portal 2's destination as a {@link Destination} enum value.
     *
     * @return the destination, or {@code null} if not attuned or unknown
     */
    public Destination getPortal2Destination() {
        return Destination.fromId(api.getVarbit(VARBIT_PORTAL_2));
    }

    /**
     * Get Portal 3's destination as a {@link Destination} enum value.
     *
     * @return the destination, or {@code null} if not attuned or unknown
     */
    public Destination getPortal3Destination() {
        return Destination.fromId(api.getVarbit(VARBIT_PORTAL_3));
    }

    /**
     * Get a portal's raw destination ID by slot number (1-3).
     *
     * @param slot portal slot (1, 2, or 3)
     * @return the raw boss ID from enum 6187, or 0 if not attuned
     */
    public int getPortalDestinationId(int slot) {
        return switch (slot) {
            case 1 -> api.getVarbit(VARBIT_PORTAL_1);
            case 2 -> api.getVarbit(VARBIT_PORTAL_2);
            case 3 -> api.getVarbit(VARBIT_PORTAL_3);
            default -> {
                log.warn("[BossPortals] Invalid portal slot: {} (expected 1-3)", slot);
                yield 0;
            }
        };
    }

    /**
     * Check if a specific portal slot is attuned to any destination.
     */
    public boolean isAttuned(int slot) {
        return getPortalDestinationId(slot) > 0;
    }

    // ========================== Boss Destinations (Enum 6187) ==========================

    /**
     * The 48 boss destinations available for portal attunement.
     * <p>IDs correspond to enum 6187 values. Shared by War's Retreat and Max Guild portals.</p>
     */
    public enum Destination {
        BORK                    ( 1, "Bork"),
        GLACOR_CAVERN           ( 2, "Glacor cavern"),
        TORMENTED_DEMONS        ( 3, "Tormented demons"),
        AIRUT_PENINSULA         ( 4, "Airut peninsula"),
        GIANT_MOLE              ( 5, "Giant mole"),
        BARROWS                 ( 6, "Barrows"),
        DAGANNOTH_KINGS         ( 7, "Dagannoth Kings"),
        CORPOREAL_BEAST         ( 8, "Corporeal beast"),
        KING_BLACK_DRAGON       ( 9, "King Black Dragon"),
        QUEEN_BLACK_DRAGON      (10, "Queen Black Dragon"),
        KALPHITE_QUEEN          (11, "Kalphite Queen"),
        KALPHITE_KING           (12, "Kalphite King"),
        COMMANDER_ZILYANA       (13, "Commander Zilyana"),
        GENERAL_GRAARDOR        (14, "General Graardor"),
        KREE_ARRA               (15, "Kree'arra"),
        KRIL_TSUTSAROTH         (16, "K'ril Tsutsaroth"),
        NEX                     (17, "Nex"),
        LEGIONES                (18, "Legiones"),
        ARAXYTE_HIVE            (19, "Araxyte hive"),
        VORAGO                  (20, "Vorago"),
        GREGOROVIC              (21, "Gregorovic"),
        HELWYR                  (22, "Helwyr"),
        TWIN_FURIES             (23, "The Twin Furies"),
        VINDICTA                (24, "Vindicta & Gorvek"),
        TELOS                   (25, "Telos"),
        THE_MAGISTER            (26, "The Magister"),
        SOLAK                   (27, "Solak, Guardian of the Grove"),
        TEMPLE_OF_AMINISHI      (28, "Temple of Aminishi"),
        DRAGONKIN_LABORATORY    (29, "Dragonkin Laboratory"),
        SHADOW_REEF             (30, "Shadow Reef"),
        LIBERATION_OF_MAZCAB    (31, "Liberation of Mazcab"),
        TZHAAR_FIGHT_CAVES      (32, "TzHaar Fight Caves"),
        TOKHAAR_FIGHT_KILN      (33, "TokHaar Fight Kiln"),
        RAKSHA                  (34, "Raksha"),
        REX_MATRIARCHS          (35, "Rex Matriarchs"),
        KERAPAC                 (36, "Kerapac"),
        ARCH_GLACOR             (37, "Arch-Glacor"),
        CROESUS                 (38, "Croesus"),
        TZKAL_ZUK               (39, "TzKal-Zuk"),
        ZAMORAKIAN_UNDERCITY    (40, "The Zamorakian Undercity"),
        RASIALS_CITADEL         (41, "Rasial's Citadel"),
        FORT_FORINTHRY          (42, "Fort Forinthry"),
        SANCTUM_OF_REBIRTH      (43, "Sanctum of Rebirth"),
        GATE_OF_ELIDINIS        (44, "The Gate of Elidinis"),
        AMASCUT                 (45, "Amascut"),
        FLESH_HATCHER_MHEKARNAHZ(46, "Flesh-hatcher Mhekarnahz"),
        IVAR                    (47, "Ivar"),
        SILVERQUILL             (48, "Silverquill");

        /** Boss destination ID from enum 6187. */
        public final int id;
        /** Display name. */
        public final String name;

        Destination(int id, String name) {
            this.id = id;
            this.name = name;
        }

        /**
         * Find a destination by its enum 6187 ID.
         *
         * @return the matching {@link Destination}, or {@code null} if not found
         */
        public static Destination fromId(int id) {
            if (id <= 0) return null;
            for (Destination dest : values()) {
                if (dest.id == id) return dest;
            }
            return null;
        }
    }
}
