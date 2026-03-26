package com.botwithus.bot.scripts.woodcutting;

import com.botwithus.bot.api.inventory.WoodBox;

/**
 * Configuration for the woodcutting script: tree type selection and location coordinates.
 */
public final class WoodcuttingConfig {

    /**
     * Tree types supported by the script, mapped to their WoodBox log type.
     */
    public enum TreeType {
        NORMAL  ("Tree",        "Chop down", WoodBox.LogType.LOGS),
        OAK     ("Oak",         "Chop down", WoodBox.LogType.OAK),
        WILLOW  ("Willow",      "Chop down", WoodBox.LogType.WILLOW),
        TEAK    ("Teak",        "Chop down", WoodBox.LogType.TEAK),
        MAPLE   ("Maple tree",  "Chop down", WoodBox.LogType.MAPLE),
        YEW     ("Yew",         "Chop down", WoodBox.LogType.YEW),
        MAGIC   ("Magic tree",  "Chop down", WoodBox.LogType.MAGIC),
        ELDER   ("Elder tree",  "Chop down", WoodBox.LogType.ELDER);

        /** The in-game object name to query for. */
        public final String objectName;
        /** The interaction option on the tree. */
        public final String interactOption;
        /** The corresponding WoodBox log type for fill/store checks. */
        public final WoodBox.LogType logType;

        TreeType(String objectName, String interactOption, WoodBox.LogType logType) {
            this.objectName = objectName;
            this.interactOption = interactOption;
            this.logType = logType;
        }

        /** Display name for the UI combo box. */
        public String displayName() {
            return objectName + " (" + logType.name + ")";
        }
    }

    // ── Selected tree type ───────────────────────────────────────
    private volatile TreeType treeType = TreeType.WILLOW;

    // ── Location coordinates (Draynor defaults) ──────────────────
    private volatile int treeAreaX = 3088;
    private volatile int treeAreaY = 3234;
    private volatile int bankAreaX = 3091;
    private volatile int bankAreaY = 3244;
    private volatile int walkRadius = 15;

    public TreeType getTreeType() { return treeType; }
    public void setTreeType(TreeType treeType) { this.treeType = treeType; }

    public int getTreeAreaX() { return treeAreaX; }
    public int getTreeAreaY() { return treeAreaY; }
    public void setTreeArea(int x, int y) { this.treeAreaX = x; this.treeAreaY = y; }

    public int getBankAreaX() { return bankAreaX; }
    public int getBankAreaY() { return bankAreaY; }
    public void setBankArea(int x, int y) { this.bankAreaX = x; this.bankAreaY = y; }

    public int getWalkRadius() { return walkRadius; }
    public void setWalkRadius(int radius) { this.walkRadius = radius; }
}
