package com.botwithus.bot.api.model;

import java.util.List;
import java.util.Map;

/**
 * NPC definition from the game cache.
 *
 * @param id          the NPC type ID
 * @param name        the NPC display name
 * @param combatLevel the NPC combat level, or {@code 0} if non-combat
 * @param visible     whether the NPC is rendered
 * @param clickable   whether the NPC can be interacted with
 * @param options     the right-click interaction options
 * @param varbitId    the varbit controlling transformation, or {@code -1} if none
 * @param varpId      the varp controlling transformation, or {@code -1} if none
 * @param transforms  the list of type IDs this NPC can transform into
 * @param params      additional key-value parameters from the NPC definition
 * @see com.botwithus.bot.api.GameAPI#getNpcType
 */
public record NpcType(
        int id,
        String name,
        int combatLevel,
        boolean visible,
        boolean clickable,
        List<String> options,
        int varbitId,
        int varpId,
        List<Integer> transforms,
        Map<String, Object> params
) {}
