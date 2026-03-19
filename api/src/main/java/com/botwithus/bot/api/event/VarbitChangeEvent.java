package com.botwithus.bot.api.event;

/**
 * Fired when a varbit changes value.
 */
public class VarbitChangeEvent extends GameEvent {
    private final int varId;
    private final int oldValue;
    private final int newValue;

    public VarbitChangeEvent(int varId, int oldValue, int newValue) {
        super("varbit_change");
        this.varId = varId;
        this.oldValue = oldValue;
        this.newValue = newValue;
    }

    /** Returns the varbit ID that changed. */
    public int getVarId() { return varId; }

    /** Returns the previous value. */
    public int getOldValue() { return oldValue; }

    /** Returns the new value. */
    public int getNewValue() { return newValue; }
}
