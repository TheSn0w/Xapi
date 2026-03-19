package com.botwithus.bot.api.model;

/**
 * Summary information about a game inventory container.
 *
 * @param inventoryId the unique inventory ID (e.g., 93 for backpack)
 * @param itemCount   the number of items currently in the inventory
 * @param capacity    the maximum number of slots
 * @see com.botwithus.bot.api.GameAPI#queryInventories
 */
public record InventoryInfo(int inventoryId, int itemCount, int capacity) {}
