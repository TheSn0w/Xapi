package com.botwithus.bot.api.cache;

import java.util.List;

/**
 * An offline-cached location (object) definition with transform chain data.
 * Action lists may contain null entries matching the game's slot layout.
 */
public record CachedLocation(int id, String name, List<String> actions,
                              int varbitId, int varpId, List<Integer> transforms) {}
