package com.botwithus.bot.api.model;

/**
 * Tile position of an entity in the game world.
 *
 * @param tileX the X tile coordinate
 * @param tileY the Y tile coordinate
 * @param plane the plane (height level, 0-3)
 * @see com.botwithus.bot.api.GameAPI#getEntityPosition
 */
public record EntityPosition(int tileX, int tileY, int plane) {}
