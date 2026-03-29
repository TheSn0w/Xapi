package com.botwithus.bot.scripts.woodcutting;

public enum TreeMode {
    STANDARD_CLUSTER("Standard cluster"),
    STANDARD_BANKABLE("Standard bankable"),
    DROP_OR_PORTER("Drop or porter"),
    FIXED_REGION("Fixed region"),
    FIXED_SINGLE("Fixed single"),
    NO_PRODUCT_AFK("No-product AFK"),
    SPECIAL_PROCESS("Special process"),
    ROUTE_SPECIAL("Route special"),
    ROUTE_ROTATION("Route rotation"),
    ACTIVE_WORLD_STATE("Active world state"),
    QUEST_NICHE("Quest niche");

    private final String displayName;

    TreeMode(String displayName) {
        this.displayName = displayName;
    }

    public String displayName() {
        return displayName;
    }
}
