package com.botwithus.bot.api.model;

/**
 * A spot animation (graphic effect) rendered at a tile location in the game world.
 *
 * @param handle the spot animation handle
 * @param animId the animation/graphic ID
 * @param tileX  the X tile coordinate
 * @param tileY  the Y tile coordinate
 * @param tileZ  the plane (height level, 0-3)
 * @see com.botwithus.bot.api.GameAPI#querySpotAnims
 */
public record SpotAnim(int handle, int animId, int tileX, int tileY, int tileZ) {}
