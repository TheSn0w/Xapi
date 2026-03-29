package com.botwithus.bot.scripts.woodcutting;

import com.botwithus.bot.api.inventory.WoodBox;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

public final class WoodcuttingProfiles {

    private static final List<TreeProfile> ALL = buildProfiles();

    private WoodcuttingProfiles() {
    }

    public static List<TreeProfile> all() {
        return ALL;
    }

    private static List<TreeProfile> buildProfiles() {
        return List.of(
                tree(
                        "tree",
                        "Tree",
                        "Tree",
                        "Chop down",
                        List.of("Chop down"),
                        1,
                        TreeMode.STANDARD_CLUSTER,
                        Set.of(),
                        Set.of(),
                        Set.of(),
                        "Logs",
                        1511,
                        WoodBox.LogType.LOGS,
                        "",
                        "Flexible early-game tree profile with dense and bankable defaults.",
                        List.of(
                                bankHotspot("tree_draynor", "Draynor Village", "Bankable",
                                        tile(3108, 3230, 0, "Draynor trees"),
                                        18,
                                        tile(3091, 3245, 0, "Draynor bank"),
                                        "Low-friction early-game regular trees."),
                                bankHotspot("tree_ge", "Grand Exchange Outer Ring", "Dense",
                                        tile(3151, 3498, 0, "GE trees"),
                                        18,
                                        tile(3165, 3485, 0, "GE bank"),
                                        "Useful if you want a central banker profile.")
                        ),
                        "tree_draynor"
                ),
                tree(
                        "oak",
                        "Oak",
                        "Oak",
                        "Chop down",
                        List.of("Chop down"),
                        15,
                        TreeMode.STANDARD_BANKABLE,
                        merge(
                                ids(1281, 3037, 8467, 11999, 37479, 135616, 136329, 136332),
                                range(38731, 38732),
                                range(125563, 125565),
                                range(136335, 136337),
                                range(136342, 136345),
                                range(136350, 136353),
                                range(136356, 136384),
                                range(136401, 136420)
                        ),
                        range(8462, 8466),
                        merge(
                                ids(10083, 38381, 38736, 38739, 38741, 38754, 51675),
                                range(65667, 65668)
                        ),
                        "Oak logs",
                        1521,
                        WoodBox.LogType.OAK,
                        "",
                        "Bankable oak loop with no-req and Seers style options.",
                        List.of(
                                bankHotspot("oak_draynor", "Draynor East Bank", "Bankable",
                                        tile(3117, 3240, 0, "Draynor oaks"),
                                        18,
                                        tile(3091, 3245, 0, "Draynor bank"),
                                        "Simple no-req fallback with easy banking."),
                                bankHotspot("oak_seers", "Seers' Village", "Dense",
                                        tile(2701, 3483, 0, "Seers oaks"),
                                        16,
                                        tile(2727, 3492, 0, "Seers bank"),
                                        "Good member route with short reset distance."),
                                chestHotspot("oak_may", "May's Quest Caravan", "Closest bank",
                                        tile(3213, 3338, 0, "Caravan oaks"),
                                        16,
                                        tile(3220, 3340, 0, "Quest caravan chest"),
                                        "Chest-adjacent loop inspired by the reference notes.")
                        ),
                        "oak_draynor"
                ),
                tree(
                        "willow",
                        "Willow",
                        "Willow",
                        "Chop down",
                        List.of("Chop down"),
                        30,
                        TreeMode.STANDARD_BANKABLE,
                        merge(
                                ids(139, 142, 2210, 2372, 37480, 38616, 38627, 58006, 135576),
                                range(125487, 125488),
                                range(135581, 135583)
                        ),
                        Set.of(),
                        merge(
                                ids(38717, 38718, 38725, 51682, 125489),
                                range(135587, 135588)
                        ),
                        "Willow logs",
                        1519,
                        WoodBox.LogType.WILLOW,
                        "",
                        "Clean bankable willow defaults with Draynor as the script-first option.",
                        List.of(
                                bankHotspot("willow_draynor", "Draynor Village", "Bankable",
                                        tile(3086, 3234, 0, "Draynor willows"),
                                        15,
                                        tile(3091, 3245, 0, "Draynor bank"),
                                        "Default low-friction willow profile."),
                                bankHotspot("willow_seers", "Seers' Village", "Dense",
                                        tile(2712, 3509, 0, "Seers willows"),
                                        16,
                                        tile(2727, 3492, 0, "Seers bank"),
                                        "Stronger member route if you want a busier cluster.")
                        ),
                        "willow_draynor"
                ),
                tree(
                        "teak",
                        "Teak",
                        "Teak",
                        "Chop down",
                        List.of("Chop down"),
                        35,
                        TreeMode.DROP_OR_PORTER,
                        ids(9036, 15062, 46275),
                        Set.of(),
                        Set.of(),
                        "Teak logs",
                        6333,
                        WoodBox.LogType.TEAK,
                        "Hardwood Grove and Ape Atoll access are not auto-verified.",
                        "Best handled as deposit-box, porter, or drop logic.",
                        List.of(
                                dropHotspot("teak_hardwood", "Hardwood Grove", "Dense",
                                        tile(2829, 3086, 0, "Hardwood Grove"),
                                        18,
                                        "Density-first teak profile. Porter or drop friendly."),
                                depositHotspot("teak_ape", "Ape Atoll Deposit Box", "Deposit",
                                        tile(2748, 2709, 0, "Ape Atoll teaks"),
                                        18,
                                        tile(2753, 2703, 0, "Ape Atoll deposit"),
                                        "Deposit-box loop for ape teaks when unlocked.")
                        ),
                        "teak_hardwood"
                ),
                tree(
                        "maple",
                        "Maple tree",
                        "Maple tree",
                        "Chop down",
                        List.of("Chop down"),
                        45,
                        TreeMode.STANDARD_BANKABLE,
                        ids(8444, 51843),
                        range(8435, 8443),
                        ids(8445),
                        "Maple logs",
                        1517,
                        WoodBox.LogType.MAPLE,
                        "",
                        "Split dense and bankable maples instead of one vague profile.",
                        List.of(
                                bankHotspot("maple_seers", "Seers' Village", "Bankable",
                                        tile(2725, 3500, 0, "Seers maples"),
                                        18,
                                        tile(2727, 3492, 0, "Seers bank"),
                                        "Clear bankable default from the reference."),
                                dropHotspot("maple_mcgrubor", "McGrubor's Wood", "Dense",
                                        tile(2646, 3496, 0, "McGrubor's"),
                                        18,
                                        "Density-first maples when pure uptime matters."),
                                dropHotspot("maple_sinclair", "Sinclair Mansion", "Dense",
                                        tile(2740, 3479, 0, "Sinclair maples"),
                                        16,
                                        "Alternate dense maple pocket.")
                        ),
                        "maple_seers"
                ),
                tree(
                        "acadia",
                        "Acadia tree",
                        "Acadia tree",
                        "Cut down",
                        List.of("Cut down"),
                        47,
                        TreeMode.STANDARD_BANKABLE,
                        ids(109001, 109003, 109005, 109007),
                        Set.of(),
                        ids(132069),
                        "Acadia logs",
                        40285,
                        WoodBox.LogType.ACADIA,
                        "Jack of Spades and Menaphos access are not auto-verified. VIP still needs manual unlock state.",
                        "Menaphos-specific ruleset with deposit-box friendly hotspots.",
                        List.of(
                                depositHotspot("acadia_imperial", "Imperial District", "Deposit",
                                        tile(3189, 2721, 0, "Imperial acadias"),
                                        18,
                                        tile(3196, 2718, 0, "Imperial deposit"),
                                        "Reliable public Menaphos acadia loop."),
                                depositHotspot("acadia_vip", "Menaphos VIP", "Dense",
                                        tile(3180, 2746, 0, "VIP acadias"),
                                        18,
                                        tile(3184, 2743, 0, "VIP deposit"),
                                        "Best acadia density once VIP access is unlocked.")
                        ),
                        "acadia_imperial"
                ),
                tree(
                        "arctic_pine",
                        "Arctic Pine",
                        "Arctic Pine",
                        "Chop down",
                        List.of("Chop down"),
                        54,
                        TreeMode.FIXED_REGION,
                        ids(70057),
                        Set.of(),
                        ids(70058),
                        "Arctic pine logs",
                        10810,
                        null,
                        "Start The Fremennik Isles is not auto-verified.",
                        "Fixed-region arctic pine handler, not a generic bank loop.",
                        List.of(
                                specialHotspot("arctic_pine_neit", "Neitiznot", "Special",
                                        tile(2320, 3798, 0, "Neitiznot pines"),
                                        18,
                                        "Use as a fixed-region profile around the split-log area.")
                        ),
                        "arctic_pine_neit"
                ),
                tree(
                        "eucalyptus",
                        "Eucalyptus tree",
                        "Eucalyptus tree",
                        "Chop down",
                        List.of("Chop down"),
                        58,
                        TreeMode.DROP_OR_PORTER,
                        ids(28951, 28952, 28953, 70066, 70068, 70071),
                        Set.of(),
                        ids(70069, 70072),
                        "Eucalyptus logs",
                        12581,
                        WoodBox.LogType.EUCALYPTUS,
                        "",
                        "Single-region drop or porter profile with minimal banking assumptions.",
                        List.of(
                                dropHotspot("eucalyptus_ooglog", "West of Oo'glog", "Drop",
                                        tile(2602, 2889, 0, "Oo'glog eucalyptus"),
                                        18,
                                        "Prefer drop or porter handling around Oo'glog.")
                        ),
                        "eucalyptus_ooglog"
                ),
                tree(
                        "mahogany",
                        "Mahogany",
                        "Mahogany",
                        "Chop down",
                        List.of("Chop down"),
                        60,
                        TreeMode.DROP_OR_PORTER,
                        merge(ids(46274), range(70074, 70077)),
                        Set.of(),
                        range(70078, 70082),
                        "Mahogany logs",
                        6332,
                        WoodBox.LogType.MAHOGANY,
                        "Mahogany spot access is not auto-verified.",
                        "Mahogany defaults to drop-or-porter until a richer banking layer exists.",
                        List.of(
                                dropHotspot("mahogany_hardwood", "Hardwood Grove", "Dense",
                                        tile(2834, 3082, 0, "Hardwood mahogany"),
                                        18,
                                        "Dense mahogany profile for porter or drop setups.")
                        ),
                        "mahogany_hardwood"
                ),
                tree(
                        "ivy",
                        "Ivy",
                        "Ivy",
                        "Chop",
                        List.of("Chop"),
                        68,
                        TreeMode.NO_PRODUCT_AFK,
                        merge(ids(66674, 66676, 66678, 92444), range(125461, 125463)),
                        Set.of(),
                        ids(127111),
                        "",
                        null,
                        null,
                        "Crwys still needs Plague's End. District unlocks are not auto-verified.",
                        "AFK wall-chop mode with no bank or wood-box handling.",
                        List.of(
                                noneHotspot("ivy_ge", "Grand Exchange", "AFK",
                                        tile(3216, 3498, 0, "GE ivy wall"),
                                        10,
                                        "Good default wall profile with straightforward reacquire logic."),
                                noneHotspot("ivy_falador", "Falador Walls", "AFK",
                                        tile(3049, 3399, 0, "Falador ivy"),
                                        12,
                                        "Alternative public ivy route."),
                                noneHotspot("ivy_crwys", "Crwys District", "AFK",
                                        tile(2239, 3382, 0, "Crwys ivy"),
                                        12,
                                        "Premium ivy profile when Prif is unlocked.")
                        ),
                        "ivy_ge"
                ),
                tree(
                        "yew",
                        "Yew",
                        "Yew",
                        "Chop down",
                        List.of("Chop down"),
                        70,
                        TreeMode.STANDARD_BANKABLE,
                        ids(1309, 12000, 38755, 92442, 125490),
                        Set.of(),
                        ids(38758, 38759, 46278, 51645, 95045, 125491),
                        "Yew logs",
                        1515,
                        WoodBox.LogType.YEW,
                        "",
                        "Split dense and bankable yews instead of treating every Seers spot the same.",
                        List.of(
                                bankHotspot("yew_seers", "Seers' Village Flax Field", "Bankable",
                                        tile(2715, 3462, 0, "Seers yews"),
                                        18,
                                        tile(2727, 3492, 0, "Seers bank"),
                                        "Reference-backed bankable yew profile."),
                                dropHotspot("yew_eagles_peak", "Eagles' Peak", "Dense",
                                        tile(2336, 3608, 0, "Eagles' Peak yews"),
                                        20,
                                        "Density-first yews without forcing a long bank loop.")
                        ),
                        "yew_seers"
                ),
                tree(
                        "blisterwood",
                        "Blisterwood Tree",
                        "Blisterwood Tree",
                        "Chop",
                        List.of("Chop"),
                        76,
                        TreeMode.FIXED_SINGLE,
                        ids(61325, 102309),
                        ids(61322),
                        ids(61323, 61324, 102310),
                        "Blisterwood logs",
                        21600,
                        WoodBox.LogType.BLISTERWOOD,
                        "Branches of Darkmeyer is not auto-verified.",
                        "Single-node special profile with no normal bank loop assumptions.",
                        List.of(
                                specialHotspot("blisterwood_darkmeyer", "Darkmeyer Arboretum", "Single",
                                        tile(3624, 3366, 0, "Blisterwood tree"),
                                        10,
                                        "Fixed single-tree profile. Use wood box or clear inventory nearby.")
                        ),
                        "blisterwood_darkmeyer"
                ),
                tree(
                        "magic",
                        "Magic tree",
                        "Magic tree",
                        "Chop down",
                        List.of("Chop down"),
                        75,
                        TreeMode.STANDARD_BANKABLE,
                        ids(37823, 63176, 92440),
                        ids(13417, 13424),
                        ids(51833, 63178, 92441),
                        "Magic logs",
                        1513,
                        WoodBox.LogType.MAGIC,
                        "Prif and resource dungeon access are not auto-verified. Legacy Seers fallback remains available.",
                        "Bankable or dense magic handling depending on the selected hotspot.",
                        List.of(
                                bankHotspot("magic_seers_legacy", "Seers' Village (Legacy)", "Bankable",
                                        tile(2699, 3424, 0, "Legacy Seers magic"),
                                        18,
                                        tile(2727, 3492, 0, "Seers bank"),
                                        "Legacy fallback until the dedicated banking reference lands."),
                                dropHotspot("magic_varrock_rd", "Varrock Sewers Resource Dungeon", "Dense",
                                        tile(3151, 9872, 0, "Varrock RD magics"),
                                        18,
                                        "Dense magic profile when pure uptime matters.")
                        ),
                        "magic_seers_legacy"
                ),
                tree(
                        "cursed_magic",
                        "Cursed magic tree",
                        "Cursed magic tree",
                        "Chop down",
                        List.of("Chop down"),
                        82,
                        TreeMode.SPECIAL_PROCESS,
                        ids(37821),
                        Set.of(),
                        Set.of(),
                        "Cursed magic logs",
                        13567,
                        WoodBox.LogType.CURSED_MAGIC,
                        "Spirit of Summer state is not auto-verified.",
                        "Guarded paired-state handler for the Spirit Realm tree.",
                        List.of(
                                specialHotspot("cursed_magic_spirit", "Spirit Realm", "Manual state",
                                        tile(3150, 3560, 0, "Spirit Realm tree"),
                                        16,
                                        "Will only fully behave when the cursed tree state is available.")
                        ),
                        "cursed_magic_spirit"
                ),
                tree(
                        "bloodwood",
                        "Bloodwood tree",
                        "Bloodwood tree",
                        "Chop down",
                        List.of("Chop down"),
                        85,
                        TreeMode.ROUTE_SPECIAL,
                        ids(4135, 19153),
                        Set.of(),
                        ids(18493, 24263, 95872, 98690),
                        "Bloodwood logs",
                        null,
                        null,
                        "Unlocks and Fletching pairing are not auto-verified.",
                        "Route-based bloodwood mode. Uses route anchors and guarded inventory handling.",
                        List.of(
                                routeHotspot("bloodwood_route", "Bloodwood Route", "Route",
                                        tile(3018, 3590, 0, "Route start"),
                                        14,
                                        HotspotProfile.route(
                                                tile(3018, 3590, 0, "Canifis"),
                                                tile(3340, 3155, 0, "Manor Farm"),
                                                tile(2632, 3455, 0, "Tree Gnome Stronghold"),
                                                tile(3117, 3508, 0, "Wilderness edge")
                                        ),
                                        "Rotates through known bloodwood-style anchors. Fine-tune after live validation.")
                        ),
                        "bloodwood_route"
                ),
                tree(
                        "elder",
                        "Elder tree",
                        "Elder tree",
                        "Chop down",
                        List.of("Chop down", "Chop"),
                        90,
                        TreeMode.ROUTE_ROTATION,
                        merge(ids(93317), range(87508, 87509), range(125584, 125585)),
                        merge(range(93294, 93295), ids(93297, 93299, 93301, 93303, 93305, 93307, 93309, 93311, 93313, 93315, 93366, 93369)),
                        Set.of(),
                        "Elder logs",
                        29556,
                        WoodBox.LogType.ELDER,
                        "Grove tier unlocks and some elder route states are not auto-verified.",
                        "Rotation planner profile rather than nearest-tree logic.",
                        List.of(
                                routeHotspot("elder_route", "Legends / Piscatoris / Grove", "Route",
                                        tile(2963, 3380, 0, "Legends' Guild start"),
                                        16,
                                        HotspotProfile.route(
                                                tile(2963, 3380, 0, "Legends' Guild"),
                                                tile(2332, 3652, 0, "Piscatoris"),
                                                tile(1578, 3855, 0, "Grove"),
                                                tile(3215, 6111, 0, "Prif Grove edge")
                                        ),
                                        "Elder trees are treated as a route problem. Works best with porters.")
                        ),
                        "elder_route"
                ),
                tree(
                        "crystal",
                        "Crystal tree",
                        "Crystal tree",
                        "Chop down",
                        List.of("Chop down", "Harvest", "Check", "Check Blossoms", "Look at"),
                        94,
                        TreeMode.ACTIVE_WORLD_STATE,
                        ids(92719, 92721),
                        ids(87533, 87535),
                        ids(46510),
                        "Crystal tree blossom",
                        null,
                        null,
                        "Active district state is not auto-verified.",
                        "Locator-first crystal tree profile for the current active tree.",
                        List.of(
                                noneHotspot("crystal_prif", "Prifddinas Crystal Tree", "Locator",
                                        tile(2212, 3376, 0, "Prif crystal zone"),
                                        20,
                                        "Looks for an active crystal tree, otherwise uses helper actions like Look at.")
                        ),
                        "crystal_prif"
                ),
                tree(
                        "eternal_magic",
                        "Eternal magic tree",
                        "Eternal magic tree",
                        "Chop down",
                        List.of("Chop down", "Identify"),
                        100,
                        TreeMode.FIXED_REGION,
                        ids(131907, 137010),
                        Set.of(),
                        Set.of(),
                        "Eternal magic logs",
                        58250,
                        WoodBox.LogType.ETERNAL_MAGIC,
                        "",
                        "High-level fixed-region profile with Identify support.",
                        List.of(
                                specialHotspot("eternal_magic_pisc", "Piscatoris Hunter Area", "Endgame",
                                        tile(2324, 3585, 0, "Eternal magic"),
                                        18,
                                        "Premium high-level handler. Use Identify when the tree is not ready.")
                        ),
                        "eternal_magic_pisc"
                ),
                tree(
                        "achey",
                        "Achey Tree",
                        "Achey Tree",
                        "Chop",
                        List.of("Chop"),
                        1,
                        TreeMode.QUEST_NICHE,
                        ids(2023, 69554, 69556),
                        Set.of(),
                        ids(3371, 69555, 69557),
                        "Achey tree logs",
                        2862,
                        WoodBox.LogType.ACHEY,
                        "Utility and quest use are not auto-verified.",
                        "Separate niche profile, kept out of the mainstream training defaults.",
                        List.of(
                                dropHotspot("achey_feldip", "Feldip Hills", "Utility",
                                        tile(2552, 2972, 0, "Achey trees"),
                                        18,
                                        "Quest and utility tree profile rather than mainstream training.")
                        ),
                        "achey_feldip"
                )
        );
    }

    private static TreeProfile tree(
            String id,
            String displayName,
            String objectName,
            String primaryAction,
            List<String> allActions,
            int requiredLevel,
            TreeMode mode,
            Set<Integer> activeIds,
            Set<Integer> helperIds,
            Set<Integer> ignoreIds,
            String productName,
            Integer logItemId,
            WoodBox.LogType woodBoxLogType,
            String requirementNote,
            String behaviourSummary,
            List<HotspotProfile> hotspots,
            String defaultHotspotId
    ) {
        return new TreeProfile(
                id,
                displayName,
                objectName,
                primaryAction,
                allActions,
                requiredLevel,
                mode,
                activeIds,
                helperIds,
                ignoreIds,
                productName,
                logItemId,
                woodBoxLogType,
                requirementNote,
                behaviourSummary,
                hotspots,
                defaultHotspotId
        );
    }

    private static HotspotProfile bankHotspot(
            String id,
            String label,
            String hotspotType,
            TileAnchor treeAnchor,
            int radius,
            TileAnchor bankAnchor,
            String note
    ) {
        return new HotspotProfile(
                id,
                label,
                hotspotType,
                treeAnchor,
                treeAnchor,
                radius,
                InventoryMode.BANK,
                bankAnchor,
                HotspotProfile.defaultBankNames(),
                HotspotProfile.defaultBankIds(),
                "Bank",
                null,
                List.of(),
                List.of(),
                "",
                List.of(),
                note,
                ""
        );
    }

    private static HotspotProfile chestHotspot(
            String id,
            String label,
            String hotspotType,
            TileAnchor treeAnchor,
            int radius,
            TileAnchor bankAnchor,
            String note
    ) {
        return new HotspotProfile(
                id,
                label,
                hotspotType,
                treeAnchor,
                treeAnchor,
                radius,
                InventoryMode.BANK,
                bankAnchor,
                List.of("Bank chest", "Chest"),
                HotspotProfile.defaultBankIds(),
                "Use",
                null,
                List.of(),
                List.of(),
                "",
                List.of(),
                note,
                ""
        );
    }

    private static HotspotProfile depositHotspot(
            String id,
            String label,
            String hotspotType,
            TileAnchor treeAnchor,
            int radius,
            TileAnchor depositAnchor,
            String note
    ) {
        return new HotspotProfile(
                id,
                label,
                hotspotType,
                treeAnchor,
                treeAnchor,
                radius,
                InventoryMode.DEPOSIT_BOX,
                null,
                List.of(),
                List.of(),
                "",
                depositAnchor,
                HotspotProfile.defaultDepositNames(),
                HotspotProfile.defaultDepositIds(),
                "Deposit",
                List.of(),
                note,
                ""
        );
    }

    private static HotspotProfile dropHotspot(
            String id,
            String label,
            String hotspotType,
            TileAnchor treeAnchor,
            int radius,
            String note
    ) {
        return new HotspotProfile(
                id,
                label,
                hotspotType,
                treeAnchor,
                treeAnchor,
                radius,
                InventoryMode.DROP,
                null,
                List.of(),
                List.of(),
                "",
                null,
                List.of(),
                List.of(),
                "",
                List.of(),
                note,
                ""
        );
    }

    private static HotspotProfile noneHotspot(
            String id,
            String label,
            String hotspotType,
            TileAnchor treeAnchor,
            int radius,
            String note
    ) {
        return new HotspotProfile(
                id,
                label,
                hotspotType,
                treeAnchor,
                treeAnchor,
                radius,
                InventoryMode.NONE,
                null,
                List.of(),
                List.of(),
                "",
                null,
                List.of(),
                List.of(),
                "",
                List.of(),
                note,
                ""
        );
    }

    private static HotspotProfile specialHotspot(
            String id,
            String label,
            String hotspotType,
            TileAnchor treeAnchor,
            int radius,
            String note
    ) {
        return new HotspotProfile(
                id,
                label,
                hotspotType,
                treeAnchor,
                treeAnchor,
                radius,
                InventoryMode.SPECIAL,
                null,
                List.of(),
                List.of(),
                "",
                null,
                List.of(),
                List.of(),
                "",
                List.of(),
                note,
                ""
        );
    }

    private static HotspotProfile routeHotspot(
            String id,
            String label,
            String hotspotType,
            TileAnchor travelAnchor,
            int radius,
            List<TileAnchor> route,
            String note
    ) {
        return new HotspotProfile(
                id,
                label,
                hotspotType,
                travelAnchor,
                travelAnchor,
                radius,
                InventoryMode.SPECIAL,
                null,
                List.of(),
                List.of(),
                "",
                null,
                List.of(),
                List.of(),
                "",
                route,
                note,
                ""
        );
    }

    private static TileAnchor tile(int x, int y, int plane, String label) {
        return new TileAnchor(x, y, plane, label);
    }

    private static Set<Integer> ids(int... ids) {
        Set<Integer> set = new LinkedHashSet<>();
        for (int id : ids) {
            set.add(id);
        }
        return Set.copyOf(set);
    }

    private static Set<Integer> range(int start, int end) {
        Set<Integer> set = new LinkedHashSet<>();
        for (int id = start; id <= end; id++) {
            set.add(id);
        }
        return Set.copyOf(set);
    }

    @SafeVarargs
    private static Set<Integer> merge(Set<Integer>... parts) {
        Set<Integer> merged = new LinkedHashSet<>();
        if (parts != null) {
            for (Set<Integer> part : parts) {
                if (part != null) {
                    merged.addAll(part);
                }
            }
        }
        return Set.copyOf(merged);
    }
}
