package com.xapi.debugger;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Shared data records and constants used across all Xapi tabs.
 */
public final class XapiData {

    private XapiData() {}

    // ── Data records ─────────────────────────────────────────────────────

    public record LogEntry(int actionId, int param1, int param2, int param3,
                           long timestamp, int gameTick, boolean wasBlocked, String source,
                           String entityName, String optionName,
                           int playerX, int playerY, int playerPlane,
                           int playerAnim, boolean playerMoving) {}

    public record VarChange(String type, int varId, int oldValue, int newValue,
                            long timestamp, int gameTick) {}

    public record ChatEntry(int messageType, String text, String playerName,
                            long timestamp, int gameTick) {}

    public record InterfaceComponentSnapshot(int componentId, int subComponentId, int type,
                                             String text, List<String> options, int itemId, int spriteId) {}

    public record InterfaceEvent(String type, int interfaceId, long timestamp, int gameTick,
                                 List<InterfaceComponentSnapshot> components) {}

    public record SessionData(List<LogEntry> actions, List<VarChange> vars, List<ChatEntry> chat,
                              long exportTime, String description) {}

    public record XapiSettings(
            boolean recording, boolean blocking, boolean selectiveBlocking,
            boolean trackVars, boolean trackChat,
            boolean[] categoryFilters, boolean[] selectiveBlockCategories,
            boolean showVarbits, boolean showVarps, boolean showVarcs,
            String varFilterText, String varcWatchIds,
            Set<String> pinnedVars, Map<String, String> varAnnotations,
            boolean useNamesForGeneration, String scriptClassName,
            float replaySpeed,
            int entityDistanceFilter
    ) {}

    // ── Skill names and XP table ─────────────────────────────────────────

    public static final String[] SKILL_NAMES = {
        "Attack", "Defence", "Strength", "Constitution", "Ranged",
        "Prayer", "Magic", "Cooking", "Woodcutting", "Fletching",
        "Fishing", "Firemaking", "Crafting", "Smithing", "Mining",
        "Herblore", "Agility", "Thieving", "Slayer", "Farming",
        "Runecrafting", "Hunter", "Construction", "Summoning",
        "Dungeoneering", "Divination", "Invention", "Archaeology", "Necromancy"
    };

    public static final int[] XP_TABLE = buildXpTable();

    private static int[] buildXpTable() {
        int[] table = new int[150];
        table[0] = 0;
        for (int level = 2; level <= 150; level++) {
            double xp = 0;
            for (int l = 1; l < level; l++) {
                xp += Math.floor(l + 300 * Math.pow(2, l / 7.0)) / 4.0;
            }
            table[level - 1] = (int) Math.floor(xp);
        }
        return table;
    }

    public static int xpToNextLevel(int currentXp, int currentLevel, int maxLevel) {
        if (currentLevel >= maxLevel || currentLevel >= XP_TABLE.length) return 0;
        int nextLevelXp = XP_TABLE[currentLevel];
        return Math.max(0, nextLevelXp - currentXp);
    }
}
