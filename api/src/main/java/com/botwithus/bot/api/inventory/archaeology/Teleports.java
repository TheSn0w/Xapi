package com.botwithus.bot.api.inventory.archaeology;

import com.botwithus.bot.api.GameAPI;
import com.botwithus.bot.api.log.BotLogger;
import com.botwithus.bot.api.log.LoggerFactory;

/**
 * Provides access to the Archaeology dig site teleport and unlock system.
 * <p>Teleport destinations at dig sites are unlocked by completing mysteries and
 * activating portal networks. Mystery completion is tracked in a bitfield across
 * varps 9302/9303 (47 total mysteries). Tool belt items on varp 7903 gate area access.</p>
 *
 * <p>Three dig sites have internal teleport networks:</p>
 * <ul>
 *   <li><b>Orthen</b> — 5-node dragonkin teleport device (varbit 48161 thresholds)</li>
 *   <li><b>Stormguard Citadel</b> — 6 portal locations, 4 activatable (varbits 47255-47258)</li>
 *   <li><b>Infernal Source / Warforge</b> — lift destinations unlocked by mysteries</li>
 * </ul>
 *
 * <pre>{@code
 * Teleports tp = new Teleports(ctx.getGameAPI());
 * if (tp.isMysteryComplete(Teleports.Mystery.LEAP_OF_FAITH)) {
 *     // Stormguard access is available
 * }
 * if (tp.isOrthenNodeActive(Teleports.OrthenNode.XOLO_CITY)) {
 *     // Can teleport to Xolo City
 * }
 * int progress = tp.getMysteryProgress(Teleports.Mystery.OUT_OF_THE_CRUCIBLE);
 * }</pre>
 */
public final class Teleports {

    private static final BotLogger log = LoggerFactory.getLogger(Teleports.class);

    // ========================== System Varbits ==========================

    /** Archaeology tutorial progress (7-bit, varp 9235 bits 0-6). Complete at value 100. */
    public static final int VARBIT_TUTORIAL = 46463;

    /** Qualification level / research team unlock tier (5-bit, varp 9235 bits 25-29). */
    public static final int VARBIT_QUALIFICATION = 46468;

    /** Breaking the Seal quest/mystery state (7-bit, varp 9032 bits 0-6). */
    public static final int VARBIT_BREAKING_SEAL_STATE = 45393;

    /** Met Naressa in Senntisten flag (1-bit, varp 10058 bit 13). Used by quest 481. */
    public static final int VARBIT_MET_NARESSA = 49743;

    /** Mystery completion bitfield — mysteries 0-31 (varp 9302, 1 bit each). */
    public static final int VARP_MYSTERIES_A = 9302;

    /** Mystery completion bitfield — mysteries 32-46 (varp 9303, 1 bit each). */
    public static final int VARP_MYSTERIES_B = 9303;

    /** Orthen teleport node progress (4-bit, varp 9579 bits 15-18). Thresholds: >=1, >=3, >=6, >=9. */
    public static final int VARBIT_ORTHEN_NODES = 48161;

    /** Auto-screener on tool belt (1-bit, varp 8845 bit 7). */
    public static final int VARBIT_AUTO_SCREENER = 45994;

    /** Master archaeologist's outfit set state (9-bit, varp 6992 bits 16-24). */
    public static final int VARBIT_OUTFIT_STATE = 36003;

    // ========================== Area Gate Varbits ==========================

    /** Daemonheim Imposing door accessible (1-bit, varp 11740 bit 3). 1 = passable. */
    public static final int VARBIT_DAEMONHEIM_DOOR = 55596;

    /** "On the Threshold of the Mind" research started (1-bit, varp 11740 bit 2). */
    public static final int VARBIT_THRESHOLD_RESEARCH_STARTED = 55595;

    /** "On the Threshold of the Mind" research completed (1-bit, varp 11741 bit 2). */
    public static final int VARBIT_THRESHOLD_RESEARCH_DONE = 55601;

    /** "Thought Process" research started (1-bit, varp 11740 bit 4). */
    public static final int VARBIT_THOUGHT_PROCESS_STARTED = 55597;

    /** "Thought Process" research completed (1-bit, varp 11741 bit 4). */
    public static final int VARBIT_THOUGHT_PROCESS_DONE = 55603;

    /** Senntisten bridge scaffolding state (3-bit, varp 10644 bits 6-8). >=1 = unlocked. */
    public static final int VARBIT_BRIDGE_SCAFFOLDING = 51894;

    /** "Crossing that Bridge" research started (1-bit, varp 9298 bit 27). */
    public static final int VARBIT_CROSSING_BRIDGE_STARTED = 49865;

    /** "Crossing that Bridge" research completed (1-bit, varp 9300 bit 27). */
    public static final int VARBIT_CROSSING_BRIDGE_DONE = 49871;

    /** Everlight door near lighthouse (3-bit, varp 9377 bits 0-2). 2 = open. */
    public static final int VARBIT_EVERLIGHT_DOOR = 47067;

    /** Vault of Shadows data (8-bit, varp 9383 bits 10-17). Secondary varbit alongside 47175 (state). */
    public static final int VARBIT_VAULT_OF_SHADOWS_DATA = 47176;

    /** Contract Claws final flag (1-bit, varp 9376 bit 29). Completion requires BITCOUNT(47091)=12 AND this=1. */
    public static final int VARBIT_CONTRACT_CLAWS_FLAG = 47151;

    // ========================== Portal Object IDs ==========================

    /** Stormguard active portal object ("Stormguard portal", Teleport action). */
    public static final int OBJECT_STORMGUARD_PORTAL_ACTIVE = 117135;

    /** Stormguard inactive portal platform ("Inactive portal platform", Activate action). */
    public static final int OBJECT_STORMGUARD_PORTAL_INACTIVE = 117136;

    /** Stormguard static portal (Teleport action). */
    public static final int OBJECT_STORMGUARD_PORTAL_STATIC = 117130;

    /** Stormguard static minimap portal object. */
    public static final int OBJECT_STORMGUARD_PORTAL_MINIMAP = 117129;

    /** Orthen active teleport device ("Orthen teleportation device", Teleport action). */
    public static final int OBJECT_ORTHEN_DEVICE_ACTIVE = 119034;

    /** Orthen inactive teleport device. */
    public static final int OBJECT_ORTHEN_DEVICE_INACTIVE = 119035;

    /** Orthen static main teleport device (Teleport action). */
    public static final int OBJECT_ORTHEN_DEVICE_STATIC = 119029;

    /** Orthen static minimap teleport device. */
    public static final int OBJECT_ORTHEN_DEVICE_MINIMAP = 119036;

    /** Everlight lighthouse object (Take action). */
    public static final int OBJECT_EVERLIGHT_LIGHTHOUSE = 116585;

    /** Everlight statue morph container (varbit 47062). */
    public static final int OBJECT_EVERLIGHT_STATUE_1 = 116586;

    /** Everlight statue morph container (varbit 47059). */
    public static final int OBJECT_EVERLIGHT_STATUE_2 = 116591;

    /** Everlight statue morph container (varbit 47060). */
    public static final int OBJECT_EVERLIGHT_STATUE_3 = 116596;

    /** Everlight statue morph container (varbit 47061). */
    public static final int OBJECT_EVERLIGHT_STATUE_4 = 116601;

    private final GameAPI api;

    public Teleports(GameAPI api) {
        this.api = api;
    }

    // ========================== Mystery Completion ==========================

    /**
     * Check if a specific mystery has been completed.
     * <p>Uses the completion bitfield on varps 9302/9303.</p>
     */
    public boolean isMysteryComplete(Mystery mystery) {
        int varp = mystery.bitIndex < 32 ? VARP_MYSTERIES_A : VARP_MYSTERIES_B;
        int bit = mystery.bitIndex < 32 ? mystery.bitIndex : mystery.bitIndex - 32;
        return (api.getVarp(varp) & (1 << bit)) != 0;
    }

    /**
     * Get the progress value of a mystery (from its individual progress varbit).
     * <p>Returns -1 if the mystery has no progress varbit (artefact-based mysteries).</p>
     *
     * @see Mystery#progressVarbit
     * @see Mystery#doneValue
     */
    public int getMysteryProgress(Mystery mystery) {
        if (mystery.progressVarbit == -1) return -1;
        return api.getVarbit(mystery.progressVarbit);
    }

    /**
     * Count how many mysteries are completed at a specific dig site.
     */
    public int getCompletedMysteryCount(DigSite site) {
        int count = 0;
        for (Mystery m : Mystery.values()) {
            if (m.digSite == site && isMysteryComplete(m)) count++;
        }
        return count;
    }

    /**
     * Check if all mysteries at a specific dig site are completed.
     */
    public boolean areAllMysteriesComplete(DigSite site) {
        for (Mystery m : Mystery.values()) {
            if (m.digSite == site && !isMysteryComplete(m)) return false;
        }
        return true;
    }

    // ========================== Tool Belt ==========================

    /**
     * Check if a tool belt item is present.
     * <p>All archaeology tool belt items use varp 7903 except the auto-screener (varp 8845).</p>
     */
    public boolean hasToolBeltItem(ToolBeltItem item) {
        return api.getVarbit(item.varbit) >= 1;
    }

    /**
     * Get the current wingsuit tier (0=none, 1=v1, 2=v2, 3=v3).
     */
    public int getWingsuitTier() {
        return api.getVarbit(ToolBeltItem.WINGSUIT.varbit);
    }

    // ========================== Orthen Network ==========================

    /**
     * Check if a specific Orthen teleport node is active.
     */
    public boolean isOrthenNodeActive(OrthenNode node) {
        return api.getVarbit(VARBIT_ORTHEN_NODES) >= node.threshold;
    }

    /**
     * Get the raw Orthen node progress value (0-10).
     */
    public int getOrthenNodeProgress() {
        return api.getVarbit(VARBIT_ORTHEN_NODES);
    }

    /**
     * Count how many Orthen teleport nodes are active (0-5).
     */
    public int getActiveOrthenNodeCount() {
        int progress = getOrthenNodeProgress();
        int count = 1; // Base Camp always available
        if (progress >= 1) count++;
        if (progress >= 3) count++;
        if (progress >= 6) count++;
        if (progress >= 9) count++;
        return count;
    }

    // ========================== Stormguard Portals ==========================

    /**
     * Check if a specific Stormguard portal is activated (powered with quintessence).
     */
    public boolean isStormguardPortalActive(StormguardPortal portal) {
        return api.getVarbit(portal.varbit) == 1;
    }

    /**
     * Count how many Stormguard portals have been activated (0-4).
     * <p>Does not count the 2 always-active portals (Camp and Keshik Memorial).</p>
     */
    public int getActiveStormguardPortalCount() {
        int count = 0;
        for (StormguardPortal p : StormguardPortal.values()) {
            if (isStormguardPortalActive(p)) count++;
        }
        return count;
    }

    /**
     * Check if all 4 Stormguard portals are active ("Any Port in a Storm" achievement).
     */
    public boolean areAllStormguardPortalsActive() {
        return getActiveStormguardPortalCount() == 4;
    }

    // ========================== Area Gate Checks ==========================

    /**
     * Check if the Daemonheim Imposing door is passable (friendship bracelet applied).
     */
    public boolean isDaemonheimDepthsAccessible() {
        return api.getVarbit(VARBIT_DAEMONHEIM_DOOR) == 1;
    }

    /**
     * Check if the Everlight door near the lighthouse is open (Hallowed Be... progression).
     */
    public boolean isEverlightDoorOpen() {
        return api.getVarbit(VARBIT_EVERLIGHT_DOOR) >= 2;
    }

    /**
     * Check if the Senntisten bridge excavation site is accessible.
     */
    public boolean isSenntistenBridgeAccessible() {
        return api.getVarbit(VARBIT_BRIDGE_SCAFFOLDING) >= 1;
    }

    /**
     * Check if all 8 Kharid-et shadow anchors are powered (for Praetorium fast-travel).
     */
    public boolean areAllShadowAnchorsPowered() {
        for (int varbit : SHADOW_ANCHOR_VARBITS) {
            if (api.getVarbit(varbit) != 1) return false;
        }
        return true;
    }

    // ========================== Qualification & Tutorial ==========================

    /**
     * Get the player's qualification level (research team unlock tier).
     */
    public int getQualificationLevel() {
        return api.getVarbit(VARBIT_QUALIFICATION);
    }

    /**
     * Check if the archaeology tutorial is complete.
     */
    public boolean isTutorialComplete() {
        return api.getVarbit(VARBIT_TUTORIAL) >= 100;
    }

    // ========================== Enums ==========================

    /**
     * The 8 archaeology dig sites.
     */
    public enum DigSite {
        KHARID_ET           (0, 2802, "Kharid-et",           5,  "Zarosian"),
        EVERLIGHT           (1, 2803, "Everlight",           42, "Saradominist"),
        INFERNAL_SOURCE     (2, 2804, "Infernal Source",     20, "Zamorakian"),
        STORMGUARD_CITADEL  (3, 2805, "Stormguard Citadel", 70, "Armadylean"),
        WARFORGE            (4, 2806, "Warforge",            76, "Bandosian"),
        ORTHEN              (5, 3703, "Orthen",              90, "Dragonkin"),
        SENNTISTEN          (6, 4408, "Senntisten",          60, "Zarosian"),
        DAEMONHEIM          (7, 13665, "Daemonheim",         73, "General");

        /** Index in enum 14057. */
        public final int index;
        /** DBRow ID in table 86. */
        public final int dbRow;
        /** Display name. */
        public final String name;
        /** Required Archaeology level. */
        public final int archLevel;
        /** Faction alignment. */
        public final String faction;

        DigSite(int index, int dbRow, String name, int archLevel, String faction) {
            this.index = index;
            this.dbRow = dbRow;
            this.name = name;
            this.archLevel = archLevel;
            this.faction = faction;
        }
    }

    /**
     * All 47 archaeology mysteries with their completion bitfield indices and progress varbits.
     * <p>Each mystery has a completion bit in varps 9302/9303 and optionally a separate progress
     * varbit that tracks intermediate state. Artefact-based mysteries have no progress varbit (-1).</p>
     */
    public enum Mystery {
        // Kharid-et (bits 0-7)
        BREAKING_THE_SEAL      ( 0, DigSite.KHARID_ET,          46627, 47184, 6, "Breaking the Seal"),
        PRISON_BREAK           ( 1, DigSite.KHARID_ET,          46628, 47181, 5, "Prison Break"),
        TIME_SERVED            ( 2, DigSite.KHARID_ET,          46629, 47185, 2, "Time Served"),
        THE_FORGOTTEN_PRISONER ( 3, DigSite.KHARID_ET,          46630, 47183, 3, "The Forgotten Prisoner"),
        THE_CULT_OF_ORCUS      ( 4, DigSite.KHARID_ET,          46631, -1,   -1, "The Cult of Orcus"),
        SHADOW_FALL            ( 5, DigSite.KHARID_ET,          46632, -1,   -1, "Shadow Fall"),
        DECIMATION             ( 6, DigSite.KHARID_ET,          46633, -1,   -1, "Decimation"),
        THE_VAULT_OF_SHADOWS   ( 7, DigSite.KHARID_ET,          46634, 47175, 3, "The Vault of Shadows"),
        // Everlight (bits 8-14)
        QUEEN_OF_THE_ICYENE    ( 8, DigSite.EVERLIGHT,          46635, -1,   -1, "Queen of the Icyene"),
        THE_DOUR_OF_BABLE      ( 9, DigSite.EVERLIGHT,          46636, -1,   -1, "The Dour of Bable"),
        FALL_AND_RISE          (10, DigSite.EVERLIGHT,          46637, -1,   -1, "Fall and Rise"),
        THE_EPIC_OF_HEBE       (11, DigSite.EVERLIGHT,          46638, -1,   -1, "The Epic of Hebe"),
        FALLEN_ANGELS          (12, DigSite.EVERLIGHT,          46639, 47035, 3, "Fallen Angels"),
        HALLOWED_BE            (13, DigSite.EVERLIGHT,          46640, 47036, 3, "Hallowed Be..."),
        THE_EVERLIGHT          (14, DigSite.EVERLIGHT,          46641, 47037, 6, "The Everlight"),
        // Infernal Source (bits 15-18)
        EYES_IN_THEIR_STARS    (15, DigSite.INFERNAL_SOURCE,    46642, 47082, 6, "Eyes in Their Stars"),
        EMBRACE_THE_CHAOS      (16, DigSite.INFERNAL_SOURCE,    46643, 47083, 3, "Embrace the Chaos"),
        CONTRACT_CLAWS         (17, DigSite.INFERNAL_SOURCE,    46644, 47091, -1, "Contract Claws"),
        DAGON_BYE              (18, DigSite.INFERNAL_SOURCE,    46645, 47104, 7, "Dagon Bye"),
        // Stormguard Citadel (bits 19-24)
        ATONEMENT              (19, DigSite.STORMGUARD_CITADEL, 46646, -1,   -1, "Atonement"),
        THE_SPY_WHO_LOVED_METAL(20, DigSite.STORMGUARD_CITADEL, 46647, -1,   -1, "The Spy Who Loved Metal"),
        A_STUDY_IN_AETHER      (21, DigSite.STORMGUARD_CITADEL, 46648, -1,   -1, "A Study in Aether"),
        LEAP_OF_FAITH          (22, DigSite.STORMGUARD_CITADEL, 46649, 47247, 5, "Leap of Faith"),
        WING_OUT               (23, DigSite.STORMGUARD_CITADEL, 46650, 47248, 5, "Wing Out"),
        HOWLS_FLOATING_WORKSHOP(24, DigSite.STORMGUARD_CITADEL, 46651, 47252, 6, "Howl's Floating Workshop"),
        // Warforge (bits 25-30)
        THE_FIRST_COMMANDER    (25, DigSite.WARFORGE,           46652, -1,   -1, "The First Commander"),
        HEART_OF_THE_FORGE     (26, DigSite.WARFORGE,           46653, -1,   -1, "Heart of the Forge"),
        FORGE_WAR              (27, DigSite.WARFORGE,           46654, -1,   -1, "Forge War!"),
        OUT_OF_THE_CRUCIBLE    (28, DigSite.WARFORGE,           46655, 47271, 7, "Out of the Crucible"),
        INTO_THE_FORGE         (29, DigSite.WARFORGE,           46656, 47272, 3, "Into the Forge"),
        YOU_HAVE_CHOSEN        (30, DigSite.WARFORGE,           46657, 47284, 7, "You Have Chosen..."),
        // General (bits 31-32)
        SECRETS_OF_THE_MONOLITH(31, null,                       46658, -1,   -1, "Secrets of the Monolith"),
        WRITINGS_ON_THE_WALLS  (32, null,                       46659, 46695, 2, "Writings on the Walls"),
        // Orthen (bits 33-40)
        CRYPT_O_ZOOLOGY        (33, DigSite.ORTHEN,             48103, -1,   -1, "Crypt o' Zoology"),
        TELEPORT_NODE_ON       (34, DigSite.ORTHEN,             48104, 48161, 10, "Teleport Node On"),
        DEATH_WATCH            (35, DigSite.ORTHEN,             48105, -1,   -1, "Death Watch"),
        KNOW_THY_MEASURE       (36, DigSite.ORTHEN,             48106, 48162, 12, "Know Thy Measure"),
        FREE_YOUR_MIND         (37, DigSite.ORTHEN,             48107, -1,   -1, "Free Your Mind"),
        FRAGMENTED_MEMORIES    (38, DigSite.ORTHEN,             48108, 48163, 9, "Fragmented Memories"),
        I_AM_BECOME_DEATH      (39, DigSite.ORTHEN,             48109, -1,   -1, "I Am Become Death"),
        MYSTERIOUS_CITY        (40, DigSite.ORTHEN,             48110, 48164, 6, "Mysterious City"),
        // Senntisten (bits 41-43)
        EMPTY_CHILDREN         (41, DigSite.SENNTISTEN,         49859, 49892, 5, "Empty Children"),
        NIGHT_THEATRE          (42, DigSite.SENNTISTEN,         49860, -1,   -1, "Night Theatre"),
        SECRETS_OF_INQUISITION (43, DigSite.SENNTISTEN,         49861, 49893, 5, "Secrets of the Inquisition"),
        // Daemonheim (bits 44-46)
        A_NEW_HOME             (44, DigSite.DAEMONHEIM,         55585, -1,   -1, "A New Home"),
        A_NEW_FORM             (45, DigSite.DAEMONHEIM,         55586, -1,   -1, "A New Form"),
        A_NEW_AGE              (46, DigSite.DAEMONHEIM,         55587, 55622, 7, "A New Age");

        /** Bit index in the completion bitfield (0-31 = varp 9302, 32+ = varp 9303). */
        public final int bitIndex;
        /** Parent dig site, or {@code null} for general mysteries. */
        public final DigSite digSite;
        /** Varbit for individual completion check (1-bit in bitfield). */
        public final int completionVarbit;
        /** Progress varbit (multi-bit counter), or -1 for artefact-based mysteries. */
        public final int progressVarbit;
        /** Value the progress varbit must reach for completion, or -1 if not applicable. */
        public final int doneValue;
        /** Display name. */
        public final String name;

        Mystery(int bitIndex, DigSite digSite, int completionVarbit, int progressVarbit, int doneValue, String name) {
            this.bitIndex = bitIndex;
            this.digSite = digSite;
            this.completionVarbit = completionVarbit;
            this.progressVarbit = progressVarbit;
            this.doneValue = doneValue;
            this.name = name;
        }
    }

    /**
     * Orthen dragonkin teleport device nodes.
     * <p>Nodes are unlocked sequentially. Varbit 48161 must be >= the threshold for the node to be active.
     * Each node has a hotkey (B/C/O/M/X) in the teleport interface.</p>
     */
    public enum OrthenNode {
        BASE_CAMP          (0, 0,  "Anachronia Base Camp",  'B', 90, -1),
        CRYPT_OF_VARANUS   (1, 1,  "Crypt of Varanus",      'C', 90, 119030),
        OBSERVATION_OUTPOST(2, 3,  "Observation Outpost",    'O', 101, 119031),
        MOKSHA_RITUAL_SITE (3, 6,  "Moksha Ritual Site",     'M', 106, 119032),
        XOLO_CITY          (4, 9,  "Xolo City",             'X', 113, 119033);

        /** Node index (0-4). */
        public final int index;
        /** Varbit 48161 must be >= this value for the node to be active. */
        public final int threshold;
        /** Display name. */
        public final String name;
        /** Hotkey in the teleport selection interface. */
        public final char hotkey;
        /** Effective Archaeology level required. */
        public final int archLevel;
        /** Morph container object ID, or -1 for Base Camp (no device). */
        public final int morphObjectId;

        OrthenNode(int index, int threshold, String name, char hotkey, int archLevel, int morphObjectId) {
            this.index = index;
            this.threshold = threshold;
            this.name = name;
            this.hotkey = hotkey;
            this.archLevel = archLevel;
            this.morphObjectId = morphObjectId;
        }
    }

    /**
     * The 4 activatable Stormguard Citadel portals.
     * <p>Each requires 20 quintessence + 70 Divination to activate. The Camp and Keshik Memorial
     * portals are always active and not tracked here.</p>
     */
    public enum StormguardPortal {
        OUTPOST_NE (47255, 117131, "Outpost (North-east)"),
        RND_NW     (47256, 117132, "Research & Development (North-west)"),
        RND_SW     (47257, 117133, "Research & Development (South-west)"),
        RND_SE     (47258, 117134, "Research & Development (South-east)");

        /** Varbit tracking activation state (1-bit, 1 = active). */
        public final int varbit;
        /** Morph container object ID. */
        public final int morphObjectId;
        /** Display name. */
        public final String name;

        StormguardPortal(int varbit, int morphObjectId, String name) {
            this.varbit = varbit;
            this.morphObjectId = morphObjectId;
            this.name = name;
        }
    }

    /**
     * Archaeology tool belt items that gate area access.
     * <p>All items use varp 7903 except the auto-screener (varp 8845). Value 1 = on tool belt
     * (wingsuit is tiered 0-3).</p>
     */
    public enum ToolBeltItem {
        CHAOS_STAR       (46000, 49807, 49808, "Chaos star",           "Infernal Source — prevents chaos portal misfiring"),
        IKOVIAN_GEREGE   (46001, 49656, 49657, "Ikovian gerege",       "Stormguard Citadel — initial entry"),
        STORMGUARD_GEREGE(46002, 49658, 49659, "Stormguard gerege",    "Stormguard — Howl's Workshop access"),
        BOOTS_OF_FLIGHT  (46003, -1,    50118, "Boots of Flight",      "Stormguard — gap/platform traversal"),
        WINGSUIT         (46004, 50119, 50122, "Wingsuit",             "Stormguard — v1: barriers, v2: outer islands, v3: gravitational core"),
        LEGATUS_PENDANT  (47423, 49879, 49880, "Legatus pendant",      "Kharid-et — Maximum Security barrier (with signet ring)"),
        PONTIFEX_SIGNET  (47424, 49901, 49902, "Pontifex signet ring", "Kharid-et — Chapel entrance + Maximum Security barrier"),
        INFERNAL_PUZZLE  (52348, -1,    -1,    "Infernal Puzzle Box",  "Infernal Source — puzzle box access"),
        FRIENDSHIP_BRACELET(55525, 56987, 56988, "Friendship bracelet", "Daemonheim — Imposing door to the Depths"),
        AUTO_SCREENER    (45994, -1,    50161, "Auto-screener",        "Automatically screens soil while excavating");

        /** Varbit tracking tool belt presence. */
        public final int varbit;
        /** Damaged item ID, or -1 if not applicable. */
        public final int damagedItemId;
        /** Restored item ID, or -1 if not applicable. */
        public final int restoredItemId;
        /** Display name. */
        public final String name;
        /** What area/feature this item unlocks. */
        public final String accessDescription;

        ToolBeltItem(int varbit, int damagedItemId, int restoredItemId, String name, String accessDescription) {
            this.varbit = varbit;
            this.damagedItemId = damagedItemId;
            this.restoredItemId = restoredItemId;
            this.name = name;
            this.accessDescription = accessDescription;
        }
    }

    /**
     * Items that provide teleport access to dig sites.
     */
    public enum TeleportItem {
        MASTER_HAT           (49941, "Master archaeologist's hat",     "Any dig site (configurable via enum 12939)"),
        ARCHAEOLOGY_JOURNAL  (49429, "Archaeology journal",            "Archaeology Campus"),
        ARCHAEOLOGY_TELEPORT (49935, "Archaeology teleport",           "Archaeology Campus"),
        PONTIFEX_SHADOW_RING (51678, "Pontifex shadow ring",           "Senntisten Cathedral"),
        ENRICHED_PONTIFEX_RING(53032, "Enriched pontifex shadow ring", "Senntisten OR Dream of Iaia"),
        RING_OF_KINSHIP      (15707, "Ring of kinship",                "Daemonheim");

        /** In-game item ID. */
        public final int itemId;
        /** Display name. */
        public final String name;
        /** Teleport destination(s). */
        public final String destination;

        TeleportItem(int itemId, String name, String destination) {
            this.itemId = itemId;
            this.name = name;
            this.destination = destination;
        }
    }

    /**
     * Master archaeologist's hat teleport destinations (enum 12939).
     * <p>Note: Senntisten uses Pontifex ring, Daemonheim uses Ring of kinship — neither is in this list.</p>
     */
    public enum HatDestination {
        VARROCK            (0, "Varrock Dig Site"),
        KHARID_ET          (1, "Kharid-et Dig Site"),
        INFERNAL_SOURCE    (2, "Infernal Source Dig Site"),
        EVERLIGHT          (3, "Everlight Dig Site"),
        STORMGUARD_CITADEL (4, "Stormguard Citadel Dig Site"),
        WARFORGE           (5, "Warforge Dig Site"),
        ORTHEN             (6, "Orthen Dig Site");

        /** Index in enum 12939. */
        public final int index;
        /** Display name. */
        public final String name;

        HatDestination(int index, String name) {
            this.index = index;
            this.name = name;
        }
    }

    /**
     * Wingsuit item IDs by tier.
     */
    public static final int ITEM_WINGSUIT_V1_DAMAGED = 50119;
    public static final int ITEM_WINGSUIT_V1 = 50120;
    public static final int ITEM_WINGSUIT_V2 = 50121;
    public static final int ITEM_WINGSUIT_V3 = 50122;

    /** Auto-screener inactive variant. */
    public static final int ITEM_AUTO_SCREENER_INACTIVE = 50245;

    /**
     * Everlight statue restoration varbits (varp 9375-9376, 2-bit each, values 0-3).
     */
    public static final int VARBIT_STATUE_1 = 47059;
    public static final int VARBIT_STATUE_2 = 47060;
    public static final int VARBIT_STATUE_3 = 47061;
    public static final int VARBIT_STATUE_4 = 47062;

    /**
     * Kharid-et shadow anchor varbits (varp 9384, bits 9-16).
     * <p>All 8 anchors must be powered (10 pylon batteries each) for Praetorium fast-travel.</p>
     */
    public static final int[] SHADOW_ANCHOR_VARBITS = {47191, 47192, 47193, 47194, 47195, 47196, 47197, 47198};

    /**
     * Master archaeologist's outfit item IDs.
     * <p>Only the master hat (49941) has teleport functionality.</p>
     */
    public static final int ITEM_MASTER_HAT = 49941;
    public static final int ITEM_MASTER_JACKET = 49942;
    public static final int ITEM_MASTER_TROUSERS = 49943;
    public static final int ITEM_MASTER_GLOVES = 49944;
    public static final int ITEM_MASTER_BOOTS = 49945;

    /** Base (non-master) archaeologist's hat — does NOT have teleport. */
    public static final int ITEM_BASE_HAT = 49936;
}
