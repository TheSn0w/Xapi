package com.botwithus.bot.api.model;

/**
 * Viewport and camera information for the game rendering context.
 *
 * @param viewportWidth    the viewport width in pixels
 * @param viewportHeight   the viewport height in pixels
 * @param projectionMatrix the 4x4 projection matrix (16 floats, column-major)
 * @param viewMatrix       the 4x4 view/camera matrix (16 floats, column-major)
 * @see com.botwithus.bot.api.GameAPI#getViewportInfo
 */
public record ViewportInfo(
        int viewportWidth, int viewportHeight,
        float[] projectionMatrix, float[] viewMatrix
) {}
