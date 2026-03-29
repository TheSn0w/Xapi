package com.botwithus.bot.scripts.woodcutting;

public enum InventoryMode {
    BANK("Bank"),
    DEPOSIT_BOX("Deposit box"),
    DROP("Drop"),
    NONE("None"),
    SPECIAL("Special");

    private final String displayName;

    InventoryMode(String displayName) {
        this.displayName = displayName;
    }

    public String displayName() {
        return displayName;
    }

    public boolean isBankLike() {
        return this == BANK || this == DEPOSIT_BOX;
    }
}
