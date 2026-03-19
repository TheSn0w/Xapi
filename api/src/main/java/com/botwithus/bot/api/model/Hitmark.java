package com.botwithus.bot.api.model;

/**
 * A damage hitmark (splat) displayed on an entity.
 *
 * @param damage the damage amount displayed
 * @param type   the hitmark type (e.g., normal, poison, heal)
 * @param cycle  the game cycle when the hitmark was created
 * @see com.botwithus.bot.api.GameAPI#getEntityHitmarks
 */
public record Hitmark(int damage, int type, int cycle) {}
