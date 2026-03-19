package com.botwithus.bot.api.model;

/**
 * A variable bit (varbit) value from a batch query.
 *
 * @param varbitId the varbit ID
 * @param value    the varbit value
 * @see com.botwithus.bot.api.GameAPI#queryVarbits
 */
public record VarbitValue(int varbitId, int value) {}
