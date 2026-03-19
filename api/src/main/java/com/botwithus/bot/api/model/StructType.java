package com.botwithus.bot.api.model;

import java.util.Map;

/**
 * Struct definition from the game cache.
 * Structs are parameter bags containing key-value pairs of int or string values.
 *
 * @param id     the struct type ID
 * @param params the key-value parameter pairs
 * @see com.botwithus.bot.api.GameAPI#getStructType
 */
public record StructType(
        int id,
        Map<String, Object> params
) {}
