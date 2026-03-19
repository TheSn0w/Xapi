package com.botwithus.bot.api.model;

/**
 * A hint arrow displayed on the game screen pointing to a location or entity.
 *
 * @param handle      the hint arrow handle
 * @param type        the arrow type (e.g., entity-targeted, tile-targeted)
 * @param tileX       the X tile coordinate of the target
 * @param tileY       the Y tile coordinate of the target
 * @param tileZ       the plane (height level, 0-3)
 * @param targetIndex the server index of the target entity, or {@code -1} for tile arrows
 * @see com.botwithus.bot.api.GameAPI#queryHintArrows
 */
public record HintArrow(int handle, int type, int tileX, int tileY, int tileZ, int targetIndex) {}
