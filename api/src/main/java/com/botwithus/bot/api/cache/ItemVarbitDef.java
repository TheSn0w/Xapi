package com.botwithus.bot.api.cache;

/**
 * An item varbit definition from the game cache (domain 5 varbits).
 * Decodes individual sub-values from packed item var integers.
 */
public record ItemVarbitDef(int varbitId, int itemVarId, int lowBit, int highBit) {

    /**
     * Extracts this varbit's value from a packed integer.
     */
    public int decode(int packed) {
        int mask = (1 << (highBit - lowBit + 1)) - 1;
        return (packed >>> lowBit) & mask;
    }
}
