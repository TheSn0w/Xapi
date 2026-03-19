package com.botwithus.bot.api.model;

/**
 * A historical record of an executed game action.
 *
 * @param actionId  the action type ID
 * @param param1    first action parameter
 * @param param2    second action parameter
 * @param param3    third action parameter
 * @param timestamp the time the action was executed (milliseconds since epoch)
 * @param delta     the time elapsed since the previous action (milliseconds)
 * @see com.botwithus.bot.api.GameAPI#getActionHistory
 */
public record ActionEntry(int actionId, int param1, int param2, int param3, long timestamp, long delta) {}
