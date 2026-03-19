package com.botwithus.bot.api.event;

/**
 * Fired when a break begins (logout triggered by fatigue/risk or manual schedule).
 */
public class BreakStartedEvent extends GameEvent {
    private final int durationSeconds;
    private final double fatigue;
    private final double risk;

    public BreakStartedEvent(int durationSeconds, double fatigue, double risk) {
        super("break_started");
        this.durationSeconds = durationSeconds;
        this.fatigue = fatigue;
        this.risk = risk;
    }

    /** Returns the scheduled break duration in seconds. */
    public int getDurationSeconds() { return durationSeconds; }

    /** Returns the fatigue level at break start [0,1]. */
    public double getFatigue() { return fatigue; }

    /** Returns the cumulative risk at break start. */
    public double getRisk() { return risk; }
}
