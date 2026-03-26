package com.botwithus.bot.scripts.woodcutting;

/**
 * States for the woodcutting script state machine.
 */
public enum WoodcuttingState {
    IDLE("Idle"),
    CHOPPING("Chopping"),
    FILLING_WOODBOX("Filling wood box"),
    WALKING_TO_BANK("Walking to bank"),
    BANKING("Banking"),
    WALKING_TO_TREES("Walking to trees");

    private final String displayName;

    WoodcuttingState(String displayName) {
        this.displayName = displayName;
    }

    public String getDisplayName() {
        return displayName;
    }
}
