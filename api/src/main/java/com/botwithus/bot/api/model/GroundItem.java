package com.botwithus.bot.api.model;

/**
 * A single item on the ground within a {@link GroundItemStack}.
 *
 * @param itemId   the item definition ID
 * @param quantity the item quantity (stack size)
 * @see GroundItemStack
 */
public record GroundItem(int itemId, int quantity) {}
