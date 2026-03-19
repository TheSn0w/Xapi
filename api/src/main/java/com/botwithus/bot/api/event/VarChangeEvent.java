package com.botwithus.bot.api.event;

/**
 * Fired when a player variable (varp) changes value.
 */
public class VarChangeEvent extends GameEvent {
    private final int varId;
    private final int oldValue;
    private final int newValue;

    public VarChangeEvent(int varId, int oldValue, int newValue) {
        super("var_change");
        this.varId = varId;
        this.oldValue = oldValue;
        this.newValue = newValue;
    }

    /** Returns the varp ID that changed. */
    public int getVarId() { return varId; }

    /** Returns the previous value. */
    public int getOldValue() { return oldValue; }

    /** Returns the new value. */
    public int getNewValue() { return newValue; }
}
