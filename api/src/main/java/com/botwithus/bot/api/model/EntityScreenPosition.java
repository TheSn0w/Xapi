package com.botwithus.bot.api.model;

/**
 * Screen-space position of an entity, obtained by projecting its world position.
 *
 * @param handle  the entity handle
 * @param screenX the X coordinate on screen
 * @param screenY the Y coordinate on screen
 * @param valid   {@code true} if the projection is valid (entity is on screen)
 * @see com.botwithus.bot.api.GameAPI#getEntityScreenPositions
 */
public record EntityScreenPosition(
        int handle, double screenX, double screenY, boolean valid
) {}
