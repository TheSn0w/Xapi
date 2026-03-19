package com.botwithus.bot.api.event;

/**
 * Fired when the client login state changes (e.g., lobby to logged in).
 *
 * <p>Common states: {@code 10} = lobby, {@code 20} = loading, {@code 30} = logged in.</p>
 */
public class LoginStateChangeEvent extends GameEvent {
    private final int oldState;
    private final int newState;

    public LoginStateChangeEvent(int oldState, int newState) {
        super("login_state_change");
        this.oldState = oldState;
        this.newState = newState;
    }

    /** Returns the previous login state value. */
    public int getOldState() { return oldState; }

    /** Returns the new login state value. */
    public int getNewState() { return newState; }
}
