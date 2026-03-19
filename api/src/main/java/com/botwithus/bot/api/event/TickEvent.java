package com.botwithus.bot.api.event;

/**
 * Fired each game tick when the server tick counter advances.
 */
public class TickEvent extends GameEvent {
    private final int tick;

    public TickEvent(int tick) {
        super("tick");
        this.tick = tick;
    }

    /** Returns the server tick counter value. */
    public int getTick() { return tick; }
}
