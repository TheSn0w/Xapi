package com.botwithus.bot.api.model;

/**
 * Screen position and dimensions of an interface component.
 *
 * @param x      the X coordinate on screen
 * @param y      the Y coordinate on screen
 * @param width  the component width in pixels
 * @param height the component height in pixels
 * @see com.botwithus.bot.api.GameAPI#getComponentPosition
 */
public record ComponentPosition(int x, int y, int width, int height) {}
