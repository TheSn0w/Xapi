package com.botwithus.bot.api.model;

/**
 * Health state of an entity.
 *
 * @param health    the current health points
 * @param maxHealth the maximum health points
 * @see com.botwithus.bot.api.GameAPI#getEntityHealth
 */
public record EntityHealth(int health, int maxHealth) {}
