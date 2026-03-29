package com.botwithus.bot.api.inventory.archaeology;

import com.botwithus.bot.api.GameAPI;


/**
 * Provides access to Archaeology excavation site state varbits.
 * <p>Each excavation hotspot has a varbit tracking its completion/progress state.
 * 92 sites across 7 regions, each associated with a soil type and Archaeology level.</p>
 *
 * <pre>{@code
 * ExcavationSites sites = new ExcavationSites(ctx.getGameAPI());
 * int state = sites.getState(ExcavationSites.Site.VENATOR_REMAINS);
 * }</pre>
 */
public final class ExcavationSites {

    /** Varplayer for sprite focus percentage during excavation. */
    public static final int VARP_SPRITE_FOCUS = 9307;

    private final GameAPI api;

    public ExcavationSites(GameAPI api) {
        this.api = api;
    }

    /**
     * Get the state/progress value for an excavation site.
     */
    public int getState(Site site) {
        return api.getVarbit(site.varbit);
    }

    /**
     * Get the current sprite focus percentage during excavation.
     */
    public int getSpriteFocus() {
        return api.getVarp(VARP_SPRITE_FOCUS);
    }

    /**
     * All excavation sites with their varbits, levels, soil types, and regions.
     */
    public enum Site {
        // Senntisten & Kharid-Et (Levels 1–118, Ancient gravel 49517)
        SENNTISTEN_SOIL          ( 1, 46463, 49516, Region.SENNTISTEN),
        VENATOR_REMAINS          ( 5, 46553, 49517, Region.KHARID_ET),
        LEGIONARY_REMAINS        (12, 46545, 49517, Region.KHARID_ET),
        CASTRA_DEBRIS            (17, 46552, 49517, Region.KHARID_ET),
        ADMINISTRATUM_DEBRIS     (25, 46546, 49517, Region.KHARID_ET),
        PRAESIDIO_REMAINS        (47, 46549, 49517, Region.KHARID_ET),
        CARCEREM_DEBRIS          (58, 46541, 49517, Region.KHARID_ET),
        KHARID_ET_CHAPEL_DEBRIS  (74, 46542, 49517, Region.KHARID_ET),
        PONTIFEX_REMAINS         (81, 46547, 49517, Region.KHARID_ET),
        ORCUS_ALTAR              (86, 46550, 49517, Region.KHARID_ET),
        ARMARIUM_DEBRIS          (93, 46551, 49517, Region.KHARID_ET),
        CULINARUM_DEBRIS         (100, 46544, 49517, Region.KHARID_ET),
        ANCIENT_MAGICK_MUNITIONS (107, 46543, 49517, Region.KHARID_ET),
        PRAETORIAN_REMAINS       (114, 46548, 49517, Region.KHARID_ET),
        WAR_TABLE_DEBRIS         (118, 46554, 49517, Region.KHARID_ET),

        // Everlight (Levels 42–117, Saltwater mud 49519)
        PRODROMOI_REMAINS        (42, 46525, 49519, Region.EVERLIGHT),
        MONOCEROS_REMAINS        (48, 46526, 49519, Region.EVERLIGHT),
        AMPHITHEATRE_DEBRIS      (51, 46518, 49519, Region.EVERLIGHT),
        CERAMICS_STUDIO_DEBRIS   (56, 46519, 49519, Region.EVERLIGHT),
        STADIO_DEBRIS            (61, 46515, 49519, Region.EVERLIGHT),
        DOMINION_GAMES_PODIUM    (69, 46523, 49519, Region.EVERLIGHT),
        OIKOS_STUDIO_DEBRIS      (72, 46524, 49519, Region.EVERLIGHT),
        OIKOS_FISHING_HUT        (84, 46520, 49519, Region.EVERLIGHT),
        ACROPOLIS_DEBRIS         (92, 46527, 49519, Region.EVERLIGHT),
        ICYENE_WEAPON_RACK       (100, 46516, 49519, Region.EVERLIGHT),
        STOCKPILED_ART           (105, 46517, 49519, Region.EVERLIGHT),
        BIBLIOTHEKE_DEBRIS       (109, 46521, 49519, Region.EVERLIGHT),
        OPTIMATOI_REMAINS        (117, 46522, 49519, Region.EVERLIGHT),

        // Infernal Source (Levels 20–116, Fiery brimstone 49521)
        LODGE_BAR_STORAGE        (20, 46531, 49521, Region.INFERNAL_SOURCE),
        LODGE_ART_STORAGE        (24, 46529, 49521, Region.INFERNAL_SOURCE),
        CULTIST_FOOTLOCKER       (29, 46533, 49521, Region.INFERNAL_SOURCE),
        SACRIFICIAL_ALTAR        (36, 46538, 49521, Region.INFERNAL_SOURCE),
        DIS_DUNGEON_DEBRIS       (45, 46536, 49521, Region.INFERNAL_SOURCE),
        INFERNAL_ART             (65, 46534, 49521, Region.INFERNAL_SOURCE),
        SHAKROTH_REMAINS         (68, 46539, 49521, Region.INFERNAL_SOURCE),
        ANIMAL_TROPHIES          (81, 46528, 49521, Region.INFERNAL_SOURCE),
        DIS_OVERSPILL            (89, 46535, 49521, Region.INFERNAL_SOURCE),
        BYZROTH_REMAINS          (98, 46530, 49521, Region.INFERNAL_SOURCE),
        HELLFIRE_FORGE           (104, 46537, 49521, Region.INFERNAL_SOURCE),
        CHTHONIAN_TROPHIES       (110, 46532, 49521, Region.INFERNAL_SOURCE),
        TSUTSAROTH_REMAINS       (116, 46540, 49521, Region.INFERNAL_SOURCE),

        // Stormguard Citadel (Levels 70–118, Aerated sediment 49523)
        IKOVIAN_MEMORIAL         (70, 46501, 49523, Region.STORMGUARD),
        KESHIK_GER               (76, 46496, 49523, Region.STORMGUARD),
        TAILORY_DEBRIS           (81, 46502, 49523, Region.STORMGUARD),
        WEAPONS_RESEARCH_DEBRIS  (85, 46503, 49523, Region.STORMGUARD),
        GRAVITRON_RESEARCH_DEBRIS(91, 46499, 49523, Region.STORMGUARD),
        KESHIK_TOWER_DEBRIS      (95, 46500, 49523, Region.STORMGUARD),
        DESTROYED_GOLEM          (98, 46497, 49523, Region.STORMGUARD),
        KESHIK_WEAPON_RACK       (103, 46495, 49523, Region.STORMGUARD),
        FLIGHT_RESEARCH_DEBRIS   (111, 46498, 49523, Region.STORMGUARD),
        AETHERIUM_FORGE          (112, 46493, 49523, Region.STORMGUARD),
        HOWLS_WORKSHOP_DEBRIS    (118, 46494, 49523, Region.STORMGUARD),

        // Warforge (Levels 76–119, Earthen clay 49525)
        GLADIATORIAL_GOBLIN_REMAINS(76, 46509, 49525, Region.WARFORGE),
        CRUCIBLE_STANDS_DEBRIS   (81, 46507, 49525, Region.WARFORGE),
        GOBLIN_DORM_DEBRIS       (83, 46513, 49525, Region.WARFORGE),
        BIG_HIGH_WAR_GOD_SHRINE  (89, 46508, 49525, Region.WARFORGE),
        YUBIUSK_ANIMAL_PEN       (94, 46504, 49525, Region.WARFORGE),
        GOBLIN_TRAINEE_REMAINS   (97, 46512, 49525, Region.WARFORGE),
        KYZAJ_CHAMPIONS_BOUDOIR  (100, 46506, 49525, Region.WARFORGE),
        WARFORGE_SCRAP_PILE      (104, 46511, 49525, Region.WARFORGE),
        WARFORGE_WEAPON_RACK     (110, 46514, 49525, Region.WARFORGE),
        BANDOS_SANCTUM_DEBRIS    (115, 46505, 49525, Region.WARFORGE),
        MAKESHIFT_PIE_OVEN       (119, 46510, 49525, Region.WARFORGE),

        // Orthen (Levels 90–120, Volcanic ash 50696)
        VARANUSAUR_REMAINS       (90, 48084, 50696, Region.ORTHEN),
        DRAGONKIN_RELIQUARY      (96, 48085, 50696, Region.ORTHEN),
        DRAGONKIN_COFFIN         (99, 48086, 50696, Region.ORTHEN),
        AUTOPSY_TABLE            (101, 48087, 50696, Region.ORTHEN),
        EXPERIMENT_WORKBENCH     (102, 48088, 50696, Region.ORTHEN),
        AUGHRA_REMAINS           (106, 48089, 50696, Region.ORTHEN),
        MOKSHA_DEVICE            (108, 48090, 50696, Region.ORTHEN),
        XOLO_MINE                (113, 48091, 50696, Region.ORTHEN),
        XOLO_REMAINS             (119, 48092, 50696, Region.ORTHEN),
        SAURTHEN_DEBRIS          (120, 48083, 50696, Region.ORTHEN),

        // Special & Other Sites
        OLD_SPIKE                (50, 47606,    -1, Region.ORTHEN),
        DRAGONKIN_REMAINS        (70, 47641,    -1, Region.ORTHEN),
        ORTHEN_RUBBLE            (90, 47642,    -1, Region.ORTHEN),
        CRYSTALLISED_RUBBLE      (30, 55497,    -1, Region.OTHER),
        RUNIC_DEBRIS             (86, 55371,    -1, Region.OTHER),
        STANDING_STONE_DEBRIS    (86, 55372,    -1, Region.OTHER),
        CATHEDRAL_DEBRIS         (62, 49847, 49517, Region.LOST_LANDS),
        MINISTRY_REMAINS         (60, 49848, 49517, Region.LOST_LANDS),
        MARKETPLACE_DEBRIS       (63, 49849, 49517, Region.LOST_LANDS),
        INQUISITOR_REMAINS       (64, 49850, 49517, Region.LOST_LANDS),
        CITIZEN_REMAINS          (67, 49851, 49517, Region.LOST_LANDS),
        GLADIATOR_REMAINS        (66, 49852, 49517, Region.LOST_LANDS),
        CASTLE_HALL_RUBBLE       (73, 55571, 50696, Region.OTHER),
        TUNNELLING_EQUIPMENT     (77, 55572, 50696, Region.OTHER),
        BOTANICAL_RESERVE        (78, 55573, 50696, Region.OTHER),
        COMMUNAL_SPACE           (87, 55574, 50696, Region.OTHER),
        PROJECTION_SPACE         (103, 55575, 50696, Region.OTHER),
        SECURITY_BOOTH           (107, 55576, 50696, Region.OTHER),
        TRAVELLERS_STATION       (113, 55577, 50696, Region.OTHER);

        /** Minimum Archaeology level required. */
        public final int level;
        /** Varbit tracking site completion/progress state. */
        public final int varbit;
        /** Soil item ID produced at this site, or -1 if no soil. */
        public final int soilItemId;
        /** Region this site belongs to. */
        public final Region region;

        Site(int level, int varbit, int soilItemId, Region region) {
            this.level = level;
            this.varbit = varbit;
            this.soilItemId = soilItemId;
            this.region = region;
        }
    }

    /**
     * Dig site regions.
     */
    public enum Region {
        SENNTISTEN,
        KHARID_ET,
        EVERLIGHT,
        INFERNAL_SOURCE,
        STORMGUARD,
        WARFORGE,
        ORTHEN,
        LOST_LANDS,
        OTHER
    }
}
