package com.botwithus.bot.api.model;

/**
 * A position on the game screen in pixel coordinates.
 *
 * @param screenX the X coordinate on screen
 * @param screenY the Y coordinate on screen
 * @see com.botwithus.bot.api.GameAPI#getWorldToScreen
 * @see com.botwithus.bot.api.GameAPI#batchWorldToScreen
 */
public record ScreenPosition(double screenX, double screenY) {}
