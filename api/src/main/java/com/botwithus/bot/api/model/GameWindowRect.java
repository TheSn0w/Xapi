package com.botwithus.bot.api.model;

/**
 * Dimensions and position of the game window, including both the outer frame and the inner client area.
 *
 * @param x            the window X position on screen
 * @param y            the window Y position on screen
 * @param width        the total window width (including borders)
 * @param height       the total window height (including title bar and borders)
 * @param clientX      the client area X position on screen
 * @param clientY      the client area Y position on screen
 * @param clientWidth  the client area width
 * @param clientHeight the client area height
 * @see com.botwithus.bot.api.GameAPI#getGameWindowRect
 */
public record GameWindowRect(
        int x, int y, int width, int height,
        int clientX, int clientY, int clientWidth, int clientHeight
) {}
