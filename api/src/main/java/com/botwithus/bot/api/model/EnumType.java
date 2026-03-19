package com.botwithus.bot.api.model;

import java.util.Map;

/**
 * Enum (key-value mapping) definition from the game cache.
 * Enums map input keys to output values (e.g., skill IDs to skill names).
 *
 * @param id            the enum type ID
 * @param inputTypeId   the input key type identifier
 * @param outputTypeId  the output value type identifier
 * @param intDefault    the default integer value when a key is not found
 * @param stringDefault the default string value when a key is not found
 * @param entryCount    the total number of entries in this enum
 * @param entries       the key-value mapping entries
 * @see com.botwithus.bot.api.GameAPI#getEnumType
 */
public record EnumType(
        int id,
        int inputTypeId,
        int outputTypeId,
        int intDefault,
        String stringDefault,
        int entryCount,
        Map<String, Object> entries
) {}
