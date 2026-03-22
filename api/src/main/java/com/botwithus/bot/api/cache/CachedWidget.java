package com.botwithus.bot.api.cache;

import java.util.List;

/**
 * An offline-cached interface widget with menu options and text label.
 */
public record CachedWidget(List<String> menuOptions, String text) {}
