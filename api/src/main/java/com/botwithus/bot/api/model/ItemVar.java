package com.botwithus.bot.api.model;

/**
 * A custom variable attached to an inventory item (e.g., charges, augmentation data).
 *
 * @param varId the variable ID
 * @param value the variable value
 * @see com.botwithus.bot.api.GameAPI#getItemVars
 */
public record ItemVar(int varId, int value) {}
