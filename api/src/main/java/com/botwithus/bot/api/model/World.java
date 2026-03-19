package com.botwithus.bot.api.model;

/**
 * Information about a game world (server).
 *
 * @param worldId    the world number
 * @param properties bitfield of world properties (e.g., members, PvP, legacy)
 * @param population the current player count
 * @param ping       the latency to this world in milliseconds
 * @param activity   the world's activity description (e.g., "Portables"), or {@code null} if not requested
 * @see com.botwithus.bot.api.GameAPI#queryWorlds
 * @see com.botwithus.bot.api.GameAPI#getCurrentWorld
 */
public record World(int worldId, int properties, int population, int ping, String activity) {}
