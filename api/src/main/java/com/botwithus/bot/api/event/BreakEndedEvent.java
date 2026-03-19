package com.botwithus.bot.api.event;

/**
 * Fired when a break countdown completes and the bot resumes.
 */
public class BreakEndedEvent extends GameEvent {

    public BreakEndedEvent() {
        super("break_ended");
    }
}
