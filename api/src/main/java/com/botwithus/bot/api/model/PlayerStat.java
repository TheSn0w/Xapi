package com.botwithus.bot.api.model;

/**
 * Skill/stat information for the local player.
 *
 * @param skillId      the skill ID (0-based, e.g., 0 = Attack)
 * @param level        the base (real) level
 * @param boostedLevel the current boosted/drained level
 * @param maxLevel     the maximum achievable level (e.g., 99 or 120)
 * @param xp           the total experience in this skill
 * @see com.botwithus.bot.api.GameAPI#getPlayerStat
 * @see com.botwithus.bot.api.GameAPI#getPlayerStats
 */
public record PlayerStat(int skillId, int level, int boostedLevel, int maxLevel, int xp) {}
