package com.botwithus.bot.api.model;

/**
 * An item within an inventory slot.
 *
 * @param handle   the item handle (context-dependent)
 * @param itemId   the item definition ID, or {@code -1} for an empty slot
 * @param quantity the item quantity (stack size)
 * @param slot     the slot index within the inventory
 * @see com.botwithus.bot.api.GameAPI#queryInventoryItems
 * @see com.botwithus.bot.api.GameAPI#getInventoryItem
 */
public record InventoryItem(int handle, int itemId, int quantity, int slot) {}
