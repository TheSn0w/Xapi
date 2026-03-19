package com.botwithus.bot.api.event;

/**
 * Fired after each queued bot action is executed on the game thread.
 */
public class ActionExecutedEvent extends GameEvent {
    private final int actionId;
    private final int param1;
    private final int param2;
    private final int param3;

    public ActionExecutedEvent(int actionId, int param1, int param2, int param3) {
        super("action_executed");
        this.actionId = actionId;
        this.param1 = param1;
        this.param2 = param2;
        this.param3 = param3;
    }

    /** Returns the action type ID. */
    public int getActionId() { return actionId; }

    /** Returns the first action parameter. */
    public int getParam1() { return param1; }

    /** Returns the second action parameter. */
    public int getParam2() { return param2; }

    /** Returns the third action parameter. */
    public int getParam3() { return param3; }
}
