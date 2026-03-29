package com.botwithus.bot.api.inventory.porters;

import com.botwithus.bot.api.GameAPI;


/**
 * Provides access to the Max Guild skilling portal teleport system.
 * <p>The Max Guild garden has 2 skilling portals that can each be attuned to one of
 * 18 training locations. Destinations are stored in varbits 25054 (portal 1) and
 * 25055 (portal 2) on varp 4772. Portals 1 and 2 cannot share the same destination.
 * Default value 0 = "Unattuned".</p>
 *
 * <pre>{@code
 * SkillingTeleports teleports = new SkillingTeleports(ctx.getGameAPI());
 * int portal1 = teleports.getPortal1Destination();
 * String name = teleports.getDestinationName(portal1);
 * }</pre>
 */
public final class MaxGuildSkillingTeleports {

    /** Varbit for skilling portal 1 destination (5-bit, max 31). Varp 4772, bits 22–26. */
    public static final int VARBIT_PORTAL_1 = 25054;
    /** Varbit for skilling portal 2 destination (5-bit, max 31). Varp 4772, bits 27–31. */
    public static final int VARBIT_PORTAL_2 = 25055;

    private final GameAPI api;

    public MaxGuildSkillingTeleports(GameAPI api) {
        this.api = api;
    }

    /**
     * Get the destination ID for skilling portal 1.
     *
     * @return destination ID (1–18), or 0 if unattuned
     */
    public int getPortal1Destination() {
        return api.getVarbit(VARBIT_PORTAL_1);
    }

    /**
     * Get the destination ID for skilling portal 2.
     *
     * @return destination ID (1–18), or 0 if unattuned
     */
    public int getPortal2Destination() {
        return api.getVarbit(VARBIT_PORTAL_2);
    }

    /**
     * Get the display name for a skilling portal destination ID.
     *
     * @return the destination name, or "Unattuned" if 0, or "Unknown" if not found
     */
    public static String getDestinationName(int destinationId) {
        if (destinationId == 0) return "Unattuned";
        for (SkillingDestination d : SkillingDestination.values()) {
            if (d.id == destinationId) return d.name;
        }
        return "Unknown";
    }

    // ========================== Enums ==========================

    /**
     * Max Guild skilling portal destinations (enum 14610, 18 entries).
     */
    public enum SkillingDestination {
        ACTIVE_CRYSTAL_TREE    (1,  "Active crystal tree"),
        JADINKO_LAIR           (2,  "Jadinko Lair"),
        LAVA_FLOW_MINE         (3,  "Lava Flow Mine"),
        LIVING_ROCK_CAVERNS    (4,  "Living Rock Caverns"),
        BRILLIANT_WISP_COLONY  (5,  "Brilliant wisp colony"),
        RADIANT_WISP_COLONY    (6,  "Radiant wisp colony"),
        LUMINOUS_WISP_COLONY   (7,  "Luminous wisp colony"),
        INCANDESCENT_WISP_COLONY(8, "Incandescent wisp colony"),
        RUNESPAN               (9,  "Runespan"),
        TREE_GNOME_STRONGHOLD  (10, "Tree Gnome Stronghold"),
        ZANARIS_FAIRY_RING     (11, "Zanaris Fairy Ring"),
        PURO_PURO              (12, "Puro-Puro"),
        PRIFDDINAS_WATERFALL   (13, "Prifddinas Waterfall"),
        INVENTION_GUILD        (14, "Invention Guild"),
        MENAPHOS_VIP           (15, "Menaphos VIP area"),
        DEEP_SEA_FISHING       (16, "Deep sea fishing hub"),
        OVERGROWN_IDOLS        (17, "Overgrown idols"),
        ANACHRONIA_DINO_FARM   (18, "Anachronia Dinosaur Farm");

        /** Destination ID stored in portal varbits. */
        public final int id;
        /** Display name. */
        public final String name;

        SkillingDestination(int id, String name) {
            this.id = id;
            this.name = name;
        }
    }
}
