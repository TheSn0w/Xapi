package com.botwithus.bot.api.inventory;

import com.botwithus.bot.api.GameAPI;

/**
 * Provides access to the player's tool belt state via varbits.
 * <p>The tool belt stores skilling tools, quest items, slayer items, and tiered
 * tools (pickaxes, hatchets, mattocks). Most items are simple boolean checks
 * ({@code varbit == 1}), while tiered items use a tier index where
 * {@code varbit > tier_index} means the item or better is present.</p>
 *
 * <p>All data is derived from Script 7090 which takes an item ID and returns 1
 * if that item is on the tool belt.</p>
 *
 * <pre>{@code
 * ToolBelt toolBelt = new ToolBelt(ctx.getGameAPI());
 * if (toolBelt.has(ToolBelt.Item.KNIFE)) {
 *     // player has a knife on tool belt
 * }
 * if (toolBelt.has(ToolBelt.Pickaxe.RUNE)) {
 *     // player has rune pickaxe or better on belt
 * }
 * }</pre>
 */
public final class ToolBelt {

    private final GameAPI api;

    public ToolBelt(GameAPI api) {
        this.api = api;
    }

    // ========================== Simple Item Checks ==========================

    /**
     * Check if a simple (boolean) tool belt item is present.
     *
     * <pre>{@code
     * // Check for a single item
     * if (toolBelt.has(ToolBelt.Item.KNIFE)) {
     *     // can use knife for fletching without needing one in inventory
     * }
     *
     * // Check for fishing gear before starting a fishing script
     * if (toolBelt.has(ToolBelt.Item.FLY_FISHING_ROD) && toolBelt.has(ToolBelt.Item.HARPOON)) {
     *     log.info("All fishing tools on belt, no inventory slots needed");
     * }
     *
     * // Check for slayer items
     * if (toolBelt.has(ToolBelt.Item.ROCK_HAMMER)) {
     *     // can finish off gargoyles without carrying a rock hammer
     * }
     * }</pre>
     *
     * @param item the item to check
     * @return {@code true} if the item is on the tool belt
     */
    public boolean has(Item item) {
        return api.getVarbit(item.varbitId) == 1;
    }

    // ========================== Tiered Checks ==========================

    /**
     * Check if a pickaxe of the given tier or better is on the tool belt.
     * <p>Bronze pickaxe is always on the belt (no varbit check needed).</p>
     *
     * <pre>{@code
     * // Check if player can mine runite rocks
     * if (toolBelt.has(ToolBelt.Pickaxe.RUNE)) {
     *     log.info("Rune pickaxe or better on belt");
     * }
     *
     * // Check for best-in-slot pickaxe
     * if (toolBelt.has(ToolBelt.Pickaxe.PICKAXE_OF_EARTH)) {
     *     log.info("Earth and Song pickaxe on belt, maximum mining efficiency");
     * }
     * }</pre>
     */
    public boolean has(Pickaxe pickaxe) {
        if (pickaxe == Pickaxe.BRONZE) return true;
        return api.getVarbit(Pickaxe.VARBIT_ID) > pickaxe.tierIndex;
    }

    /**
     * Check if a hatchet of the given tier or better is on the tool belt.
     * <p>Bronze hatchet is always on the belt (no varbit check needed).</p>
     *
     * <pre>{@code
     * // Check before starting a woodcutting script
     * if (toolBelt.has(ToolBelt.Hatchet.CRYSTAL)) {
     *     log.info("Crystal hatchet or better on belt");
     * }
     * }</pre>
     */
    public boolean has(Hatchet hatchet) {
        if (hatchet == Hatchet.BRONZE) return true;
        return api.getVarbit(Hatchet.VARBIT_ID) > hatchet.tierIndex;
    }

    /**
     * Check if a mattock of the given tier or better is on the tool belt.
     *
     * <pre>{@code
     * // Check for archaeology readiness
     * if (toolBelt.has(ToolBelt.Mattock.DRAGON)) {
     *     log.info("Dragon mattock or better on belt, ready for high-level dig sites");
     * }
     * }</pre>
     */
    public boolean has(Mattock mattock) {
        return api.getVarbit(Mattock.VARBIT_ID) > mattock.tierIndex;
    }

    /**
     * Check if a wingsuit of the given tier or better is on the tool belt.
     *
     * <pre>{@code
     * if (toolBelt.has(ToolBelt.Wingsuit.V3)) {
     *     log.info("Best wingsuit on belt, can use all glide shortcuts");
     * }
     * }</pre>
     */
    public boolean has(Wingsuit wingsuit) {
        return api.getVarbit(Wingsuit.VARBIT_ID) > wingsuit.tierIndex;
    }

    /**
     * Check if a dungeoneering pickaxe of the given tier or better is on the belt.
     *
     * <pre>{@code
     * if (toolBelt.has(ToolBelt.DgPickaxe.PRIMAL)) {
     *     log.info("Primal pickaxe on belt for dungeoneering mining");
     * }
     * }</pre>
     */
    public boolean has(DgPickaxe pickaxe) {
        return api.getVarbit(DgPickaxe.VARBIT_ID) > pickaxe.tierIndex;
    }

    /**
     * Check if a dungeoneering hatchet of the given tier or better is on the belt.
     *
     * <pre>{@code
     * if (toolBelt.has(ToolBelt.DgHatchet.PROMETHIUM)) {
     *     log.info("Promethium or better DG hatchet on belt");
     * }
     * }</pre>
     */
    public boolean has(DgHatchet hatchet) {
        return api.getVarbit(DgHatchet.VARBIT_ID) > hatchet.tierIndex;
    }

    // ========================== Special Checks ==========================

    /**
     * Check if the incense burner is on the tool belt.
     * <p>Always returns {@code true} (hardcoded in Script 7090).</p>
     *
     * <pre>{@code
     * // Always true — incense burner is permanently on the tool belt
     * boolean hasBurner = toolBelt.hasIncenseBurner(); // true
     * }</pre>
     */
    public boolean hasIncenseBurner() {
        return true;
    }

    /**
     * Check if the archaeologist's tools are on the tool belt.
     *
     * <pre>{@code
     * if (toolBelt.hasArchaeologistsTools()) {
     *     log.info("Archaeologist's tools on belt, can excavate without carrying them");
     * }
     * }</pre>
     */
    public boolean hasArchaeologistsTools() {
        return api.getVarbit(46463) > 0;
    }

    /**
     * Check if the explosive shaker is on the tool belt.
     *
     * <pre>{@code
     * if (toolBelt.hasExplosiveShaker()) {
     *     log.info("Explosive shaker on belt for slayer tasks");
     * }
     * }</pre>
     */
    public boolean hasExplosiveShaker() {
        return api.getVarbit(28225) > 0;
    }

    /**
     * Check if the super explosive shaker is on the tool belt.
     *
     * <pre>{@code
     * // Super explosive shaker is the upgraded version
     * if (toolBelt.hasSuperExplosiveShaker()) {
     *     log.info("Super explosive shaker on belt, no need to carry for living rock creatures");
     * }
     * }</pre>
     */
    public boolean hasSuperExplosiveShaker() {
        return api.getVarbit(28225) == 2;
    }

    /**
     * Check if a gem machete of the given tier or better is on the tool belt.
     * <p>Requires the base machete (varbit 3002) to also be present.</p>
     *
     * <pre>{@code
     * // Gem machetes require the base machete to also be on the belt
     * if (toolBelt.has(ToolBelt.Item.MACHETE) && toolBelt.has(ToolBelt.GemMachete.RED_TOPAZ)) {
     *     log.info("Red topaz machete on belt for jungle clearing");
     * }
     * }</pre>
     *
     * @param machete the gem machete to check
     * @return {@code true} if the machete is on the belt
     */
    public boolean has(GemMachete machete) {
        return api.getVarbit(3002) == 1 && api.getVarbit(GemMachete.VARBIT_ID) > machete.tierIndex;
    }

    /**
     * Get the highest pickaxe tier index stored on the tool belt.
     *
     * <pre>{@code
     * // Get the raw tier index (0 = bronze, 10 = Pickaxe of Earth and Song)
     * int tier = toolBelt.getPickaxeTier();
     * log.info("Pickaxe tier on belt: {}", tier);
     *
     * // Compare against specific tiers
     * if (tier >= ToolBelt.Pickaxe.RUNE.tierIndex) {
     *     log.info("At least rune pickaxe on belt");
     * }
     * }</pre>
     */
    public int getPickaxeTier() {
        return api.getVarbit(Pickaxe.VARBIT_ID);
    }

    /**
     * Get the highest hatchet tier index stored on the tool belt.
     *
     * <pre>{@code
     * int tier = toolBelt.getHatchetTier();
     * log.info("Hatchet tier on belt: {}", tier);
     * }</pre>
     */
    public int getHatchetTier() {
        return api.getVarbit(Hatchet.VARBIT_ID);
    }

    /**
     * Get the highest mattock tier index stored on the tool belt.
     *
     * <pre>{@code
     * int tier = toolBelt.getMattockTier();
     * if (tier >= ToolBelt.Mattock.CRYSTAL.tierIndex) {
     *     log.info("Crystal mattock or better, can excavate high-level sites");
     * }
     * }</pre>
     */
    public int getMattockTier() {
        return api.getVarbit(Mattock.VARBIT_ID);
    }

    // ========================== Enums ==========================

    /**
     * Simple boolean tool belt items checked via {@code varbit == 1}.
     */
    public enum Item {
        // Varp 1102 - Skilling Tools
        KNIFE              (2968,  946,   "Knife"),
        SHEARS             (2969,  1735,  "Shears"),
        AMULET_MOULD       (2970,  1595,  "Amulet mould"),
        CHISEL             (2971,  1755,  "Chisel"),
        HOLY_MOULD         (2972,  1599,  "Holy mould"),
        NECKLACE_MOULD     (2973,  1597,  "Necklace mould"),
        NEEDLE             (2974,  1733,  "Needle"),
        RING_MOULD         (2975,  1592,  "Ring mould"),
        TIARA_MOULD        (2976,  5523,  "Tiara mould"),
        CRAYFISH_CAGE      (2977,  13431, "Crayfish cage"),
        FISHING_ROD        (2978,  307,   "Fishing rod"),
        FLY_FISHING_ROD    (2979,  309,   "Fly fishing rod"),
        HARPOON            (2980,  311,   "Harpoon"),
        LOBSTER_POT        (2981,  301,   "Lobster pot"),
        SMALL_FISHING_NET  (2982,  303,   "Small fishing net"),
        HAMMER             (2984,  2347,  "Hammer"),
        TINDERBOX          (2986,  590,   "Tinderbox"),

        // Varp 1103 - Quest & Misc
        SAW                (2988,  8794,  "Saw"),
        AMMO_MOULD         (2989,  4,     "Ammo mould"),
        BOLT_MOULD         (2990,  9434,  "Bolt mould"),
        BRACELET_MOULD     (2991,  11065, "Bracelet mould"),
        GLASSBLOWING_PIPE  (2992,  1785,  "Glassblowing pipe"),
        SICKLE_MOULD       (2993,  2976,  "Sickle mould"),
        UNHOLY_MOULD       (2994,  1594,  "Unholy mould"),
        SEED_DIBBER        (2995,  5343,  "Seed dibber"),
        GARDENING_TROWEL   (2996,  5325,  "Gardening trowel"),
        RAKE               (2997,  5341,  "Rake"),
        SECATEURS          (2998,  5329,  "Secateurs"),
        PESTLE_AND_MORTAR  (2999,  233,   "Pestle and mortar"),
        SPADE              (3000,  952,   "Spade"),
        BIG_FISHING_NET    (3001,  305,   "Big fishing net"),
        MACHETE            (3002,  975,   "Machete"),
        BARBARIAN_ROD      (3003,  11323, "Barbarian rod"),
        WATCH              (3004,  2575,  "Watch"),
        CHART              (3005,  2576,  "Chart"),
        CHAIN_LINK_MOULD   (3006,  13153, "Chain link mould"),
        NOOSE_WAND         (3007,  10150, "Noose wand"),
        SEXTANT            (685,   2574,  "Sextant"),
        MAGIC_SECATEURS    (27430, 7409,  "Magic secateurs"),
        MAGIC_WATERING_CAN (27431, 18682, "Magic watering can"),
        TONGS              (28410, 20565, "Tongs"),
        ROD_CLAY_MOULD     (30998, 7649,  "Rod clay mould"),
        STEEL_KEY_RING     (40074, 4446,  "Steel key ring"),
        ENHANCED_GRAPPLE   (40075, 42453, "Enhanced grappling hook"),
        MASTER_LOCKPICK    (40234, 42617, "Master thief's lockpick"),

        // Varp 7903 - Extended
        MASTER_STETHOSCOPE (40235, 42618, "Master thief's stethoscope"),
        INSECT_REPELLENT   (42126, 28,    "Insect repellent"),
        CHAOS_STAR         (46000, 49808, "Chaos star"),
        IKOVIAN_GEREGE     (46001, 49657, "Ikovian gerege"),
        STORMGUARD_GEREGE  (46002, 49659, "Stormguard gerege"),
        BOOTS_OF_FLIGHT    (46003, 50118, "Boots of Flight"),
        LEGATUS_PENDANT    (47423, 49880, "Legatus pendant"),
        PONTIFEX_SIGNET    (47424, 49902, "Pontifex signet ring"),
        INFERNAL_PUZZLE_BOX(52348, 53443, "Infernal Puzzle Box"),
        FRIENDSHIP_BRACELET(55525, 56988, "Friendship bracelet"),
        DOUBLE_AMMO_MOULD  (16445, 59903, "Double ammo mould"),

        // Varp 5699 - Slayer & PvM
        ROCK_HAMMER        (28219, 4162,  "Rock hammer"),
        SALT_SHAKER        (28220, 34960, "Salt shaker"),
        ICE_SHAKER         (28221, 34961, "Ice shaker"),
        FUNGICIDE_SHAKER   (28222, 34962, "Fungicide shaker"),
        SLAYER_BELL        (28223, 10952, "Slayer bell"),
        CRYSTAL_CHIME      (28224, 32644, "Crystal chime"),
        BONECRUSHER        (28226, 18337, "Bonecrusher"),
        SEEDICIDE          (28227, 31188, "Seedicide"),
        CHARMING_IMP       (28228, 27996, "Charming imp"),
        HERBICIDE          (28229, 19675, "Herbicide"),
        OUROBOROS_POUCH    (28230, 21451, "Ouroboros pouch"),
        GOLD_ACCUMULATOR   (38660, 41375, "Advanced gold accumulator"),

        // Varp 5987 - Invention
        INVENTORS_TOOLS    (30224, 36367, "Inventor's tools"),
        BAG_OF_MATERIALS   (30224, 36368, "Bag of materials"),
        CHARGE_PACK        (30225, 36389, "Charge pack"),

        // Dungeoneering simple tools
        DG_HAMMER          (3010,  17883, "Hammer (DG)"),
        DG_TINDERBOX       (3011,  17678, "Tinderbox (DG)"),
        DG_FLY_FISHING_ROD (3012,  17794, "Fly fishing rod (DG)"),
        DG_KNIFE           (3013,  17754, "Knife (DG)"),
        DG_NEEDLE          (3014,  17446, "Needle (DG)"),
        DG_CHISEL          (3015,  17444, "Chisel (DG)");

        /** The varbit ID to check. */
        public final int varbitId;
        /** The in-game item ID. */
        public final int itemId;
        /** Display name. */
        public final String name;

        Item(int varbitId, int itemId, String name) {
            this.varbitId = varbitId;
            this.itemId = itemId;
            this.name = name;
        }
    }

    /**
     * Pickaxe tiers for the main tool belt.
     * <p>Uses {@link #VARBIT_ID varbit 18521} (8-bit). An item is on belt if
     * {@code varbit > tierIndex}. Bronze pickaxe is always present.</p>
     */
    public enum Pickaxe {
        BRONZE          (0,  1265,  "Bronze pickaxe"),
        IRON            (1,  1267,  "Iron pickaxe"),
        STEEL           (2,  1269,  "Steel pickaxe"),
        MITHRIL         (3,  1273,  "Mithril pickaxe"),
        ADAMANT         (4,  1271,  "Adamant pickaxe"),
        RUNE            (5,  1275,  "Rune pickaxe"),
        DRAGON          (6,  15259, "Dragon pickaxe"),
        CRYSTAL         (7,  32646, "Crystal pickaxe"),
        ELDER_RUNE      (8,  45012, "Elder rune pickaxe"),
        IMCANDO         (9,  47434, "Imcando pickaxe"),
        PICKAXE_OF_EARTH(10, 47436, "Pickaxe of Earth and Song");

        /** Varbit 18521 stores the highest pickaxe tier. */
        public static final int VARBIT_ID = 18521;

        public final int tierIndex;
        public final int itemId;
        public final String name;

        Pickaxe(int tierIndex, int itemId, String name) {
            this.tierIndex = tierIndex;
            this.itemId = itemId;
            this.name = name;
        }
    }

    /**
     * Hatchet tiers for the main tool belt.
     * <p>Uses {@link #VARBIT_ID varbit 18522} (8-bit). An item is on belt if
     * {@code varbit > tierIndex}. Bronze hatchet is always present.</p>
     */
    public enum Hatchet {
        BRONZE         (0,  1351,  "Bronze hatchet"),
        IRON           (1,  1349,  "Iron hatchet"),
        STEEL          (2,  1353,  "Steel hatchet"),
        MITHRIL        (3,  1355,  "Mithril hatchet"),
        ADAMANT        (4,  1357,  "Adamant hatchet"),
        RUNE           (5,  1359,  "Rune hatchet"),
        DRAGON         (6,  6739,  "Dragon hatchet"),
        CRYSTAL        (7,  32645, "Crystal hatchet");

        /** Varbit 18522 stores the highest hatchet tier. */
        public static final int VARBIT_ID = 18522;

        public final int tierIndex;
        public final int itemId;
        public final String name;

        Hatchet(int tierIndex, int itemId, String name) {
            this.tierIndex = tierIndex;
            this.itemId = itemId;
            this.name = name;
        }
    }

    /**
     * Mattock tiers for the tool belt.
     * <p>Uses {@link #VARBIT_ID varbit 45999} (4-bit). An item is on belt if
     * {@code varbit > tierIndex}.</p>
     */
    public enum Mattock {
        BRONZE         (0,  49534, "Bronze mattock"),
        IRON           (1,  49536, "Iron mattock"),
        STEEL          (2,  49538, "Steel mattock"),
        MITHRIL        (3,  49540, "Mithril mattock"),
        ADAMANT        (4,  49542, "Adamant mattock"),
        RUNE           (5,  49544, "Rune mattock"),
        ORIKALKUM      (6,  49546, "Orikalkum mattock"),
        DRAGON         (7,  49548, "Dragon mattock"),
        CRYSTAL        (8,  49550, "Crystal mattock"),
        IMCANDO        (9,  49552, "Imcando mattock"),
        MATTOCK_OF_TIME(10, 49554, "Mattock of Time and Space");

        /** Varbit 45999 stores the highest mattock tier. */
        public static final int VARBIT_ID = 45999;

        public final int tierIndex;
        public final int itemId;
        public final String name;

        Mattock(int tierIndex, int itemId, String name) {
            this.tierIndex = tierIndex;
            this.itemId = itemId;
            this.name = name;
        }
    }

    /**
     * Wingsuit tiers.
     * <p>Uses {@link #VARBIT_ID varbit 46004} (2-bit). Tier stored directly:
     * 0 = none, 1 = v1, 2 = v2, 3 = v3.</p>
     */
    public enum Wingsuit {
        V1(0, 50120, "Wingsuit v1"),
        V2(1, 50121, "Wingsuit v2"),
        V3(2, 50122, "Wingsuit v3");

        /** Varbit 46004 stores the wingsuit tier. */
        public static final int VARBIT_ID = 46004;

        public final int tierIndex;
        public final int itemId;
        public final String name;

        Wingsuit(int tierIndex, int itemId, String name) {
            this.tierIndex = tierIndex;
            this.itemId = itemId;
            this.name = name;
        }
    }

    /**
     * Gem machete tiers (require base machete on belt).
     * <p>Uses {@link #VARBIT_ID varbit 4935}. Also requires varbit 3002 == 1.</p>
     */
    public enum GemMachete {
        OPAL      (0, 6313, "Opal machete"),
        JADE      (1, 6315, "Jade machete"),
        RED_TOPAZ (2, 6317, "Red topaz machete");

        /** Varbit 4935 stores the gem machete tier. */
        public static final int VARBIT_ID = 4935;

        public final int tierIndex;
        public final int itemId;
        public final String name;

        GemMachete(int tierIndex, int itemId, String name) {
            this.tierIndex = tierIndex;
            this.itemId = itemId;
            this.name = name;
        }
    }

    /**
     * Dungeoneering pickaxe tiers.
     * <p>Uses {@link #VARBIT_ID varbit 3008} (5-bit). An item is on belt if
     * {@code varbit > tierIndex}.</p>
     */
    public enum DgPickaxe {
        NOVITE     (0,  16295, "Novite pickaxe"),
        BATHUS     (1,  16297, "Bathus pickaxe"),
        MARMAROS   (2,  16299, "Marmaros pickaxe"),
        KRATONITE  (3,  16301, "Kratonite pickaxe"),
        FRACTITE   (4,  16303, "Fractite pickaxe"),
        ZEPHYRIUM  (5,  16305, "Zephyrium pickaxe"),
        ARGONITE   (6,  16307, "Argonite pickaxe"),
        KATAGON    (7,  16309, "Katagon pickaxe"),
        GORGONITE  (8,  16311, "Gorgonite pickaxe"),
        PROMETHIUM (9,  16313, "Promethium pickaxe"),
        PRIMAL     (10, 16315, "Primal pickaxe");

        /** Varbit 3008 stores the highest DG pickaxe tier. */
        public static final int VARBIT_ID = 3008;

        public final int tierIndex;
        public final int itemId;
        public final String name;

        DgPickaxe(int tierIndex, int itemId, String name) {
            this.tierIndex = tierIndex;
            this.itemId = itemId;
            this.name = name;
        }
    }

    /**
     * Dungeoneering hatchet tiers.
     * <p>Uses {@link #VARBIT_ID varbit 3009} (4-bit). An item is on belt if
     * {@code varbit > tierIndex}.</p>
     */
    public enum DgHatchet {
        NOVITE     (0,  16361, "Novite hatchet"),
        BATHUS     (1,  16363, "Bathus hatchet"),
        MARMAROS   (2,  16365, "Marmaros hatchet"),
        KRATONITE  (3,  16367, "Kratonite hatchet"),
        FRACTITE   (4,  16369, "Fractite hatchet"),
        ZEPHYRIUM  (5,  16371, "Zephyrium hatchet"),
        ARGONITE   (6,  16373, "Argonite hatchet"),
        KATAGON    (7,  16375, "Katagon hatchet"),
        GORGONITE  (8,  16377, "Gorgonite hatchet"),
        PROMETHIUM (9,  16379, "Promethium hatchet"),
        PRIMAL     (10, 16381, "Primal hatchet");

        /** Varbit 3009 stores the highest DG hatchet tier. */
        public static final int VARBIT_ID = 3009;

        public final int tierIndex;
        public final int itemId;
        public final String name;

        DgHatchet(int tierIndex, int itemId, String name) {
            this.tierIndex = tierIndex;
            this.itemId = itemId;
            this.name = name;
        }
    }

    /**
     * Invention device ownership items (varp 8845).
     * <p>Tracked by Script 7163 for Make-X prerequisites, not Script 7090.</p>
     */
    public enum InventionDevice {
        MONKEY_HELMET     (3574,  "Monkey mind-control helmet"),
        SMALL_GIZMO_BAG   (3575,  "Small gizmo bag"),
        MEDIUM_GIZMO_BAG  (3618,  "Medium gizmo bag"),
        LARGE_GIZMO_BAG   (16612, "Large gizmo bag"),
        AUTO_SIPHON        (16700, "Mechanised siphon"),
        DIVINE_O_MATIC     (19023, "Divine-o-matic vacuum"),
        ENERGY_BARREL      (20966, "Energy barrel"),
        AUTO_SCREENER      (45994, "Auto-screener v1.080"),
        GOLEM_FRAMEWORK    (45995, "Golem framework"),
        ANCIENT_GIZMOS     (45996, "Ancient gizmos blueprint"),
        ANCIENT_TOOLS      (45997, "Ancient tools blueprint"),
        XP_CAPACITOR       (45998, "XP Capacitor blueprint");

        /** The varbit ID to check. */
        public final int varbitId;
        /** Display name. */
        public final String name;

        InventionDevice(int varbitId, String name) {
            this.varbitId = varbitId;
            this.name = name;
        }
    }

    /**
     * Check if an invention device is owned.
     * <p>Uses varp 8845 varbits. Checked by Script 7163, not Script 7090.</p>
     *
     * <pre>{@code
     * // Check for auto-screener before starting archaeology
     * if (toolBelt.has(ToolBelt.InventionDevice.AUTO_SCREENER)) {
     *     log.info("Auto-screener on belt, soil will be screened automatically");
     * }
     *
     * // Check for divine-o-matic before divination
     * if (toolBelt.has(ToolBelt.InventionDevice.DIVINE_O_MATIC)) {
     *     log.info("Divine-o-matic on belt, can harvest divine charges");
     * }
     * }</pre>
     */
    public boolean has(InventionDevice device) {
        return api.getVarbit(device.varbitId) == 1;
    }
}
