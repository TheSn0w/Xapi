package com.botwithus.bot.api.cache;

import java.util.List;

/**
 * An offline-cached item definition with widget and ground action lists.
 * Action lists may contain null entries matching the game's slot layout.
 */
public record CachedItem(int id, String name, List<String> widgetActions, List<String> groundActions) {}
