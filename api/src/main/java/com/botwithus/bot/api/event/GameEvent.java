package com.botwithus.bot.api.event;

/**
 * Base class for all game events distributed through the {@link EventBus}.
 *
 * <p>Each event carries a type identifier and a timestamp indicating when it occurred.
 * Subclass this to create specific event types (e.g., {@link ChatMessageEvent}).</p>
 *
 * @see EventBus
 */
public class GameEvent {
    private final String type;
    private final long timestamp;

    /**
     * Creates a new game event with the current system time as timestamp.
     *
     * @param type the event type identifier
     */
    public GameEvent(String type) {
        this.type = type;
        this.timestamp = System.currentTimeMillis();
    }

    /**
     * Creates a new game event with an explicit timestamp.
     *
     * @param type      the event type identifier
     * @param timestamp the event timestamp in milliseconds since epoch
     */
    public GameEvent(String type, long timestamp) {
        this.type = type;
        this.timestamp = timestamp;
    }

    /**
     * Returns the event type identifier.
     *
     * @return the event type string
     */
    public String getType() { return type; }

    /**
     * Returns the timestamp when this event occurred.
     *
     * @return the timestamp in milliseconds since epoch
     */
    public long getTimestamp() { return timestamp; }
}
