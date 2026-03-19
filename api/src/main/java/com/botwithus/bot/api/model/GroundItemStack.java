package com.botwithus.bot.api.model;

import java.util.List;

/**
 * A stack of ground items at a specific tile location.
 *
 * @param handle the object stack handle
 * @param tileX  the X tile coordinate
 * @param tileY  the Y tile coordinate
 * @param tileZ  the plane (height level, 0-3)
 * @param items  the individual ground items in this stack
 * @see com.botwithus.bot.api.GameAPI#queryGroundItems
 * @see GroundItem
 */
public record GroundItemStack(int handle, int tileX, int tileY, int tileZ, List<GroundItem> items) {}
