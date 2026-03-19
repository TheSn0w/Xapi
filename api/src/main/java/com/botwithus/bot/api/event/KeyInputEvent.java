package com.botwithus.bot.api.event;

/**
 * Fired when a key is pressed while the overlay has keyboard focus.
 */
public class KeyInputEvent extends GameEvent {
    private final int key;
    private final boolean alt;
    private final boolean ctrl;
    private final boolean shift;

    public KeyInputEvent(int key, boolean alt, boolean ctrl, boolean shift) {
        super("key_input");
        this.key = key;
        this.alt = alt;
        this.ctrl = ctrl;
        this.shift = shift;
    }

    /** Returns the virtual key code (VK_*). */
    public int getKey() { return key; }

    /** Returns whether ALT was held. */
    public boolean isAlt() { return alt; }

    /** Returns whether CTRL was held. */
    public boolean isCtrl() { return ctrl; }

    /** Returns whether SHIFT was held. */
    public boolean isShift() { return shift; }
}
