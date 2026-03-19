package com.botwithus.bot.api.model;

/**
 * An active projectile (e.g., arrow, spell) travelling through the game world.
 *
 * @param handle       the projectile handle
 * @param projectileId the projectile type/definition ID
 * @param startX       the starting X tile coordinate
 * @param startY       the starting Y tile coordinate
 * @param endX         the destination X tile coordinate
 * @param endY         the destination Y tile coordinate
 * @param plane        the plane (height level, 0-3)
 * @param targetIndex  the server index of the target entity, or {@code -1}
 * @param sourceIndex  the server index of the source entity, or {@code -1}
 * @param startCycle   the game cycle when the projectile was created
 * @param endCycle     the game cycle when the projectile reaches its destination
 * @see com.botwithus.bot.api.GameAPI#queryProjectiles
 */
public record Projectile(
        int handle, int projectileId,
        int startX, int startY, int endX, int endY, int plane,
        int targetIndex, int sourceIndex,
        int startCycle, int endCycle
) {}
