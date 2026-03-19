package com.botwithus.bot.api.model;

import java.util.List;
import java.util.Map;

/**
 * Item definition from the game cache.
 *
 * @param id               the item ID
 * @param name             the item display name
 * @param members          whether this is a members-only item
 * @param stackable        whether this item stacks in inventory
 * @param shopPrice        the base shop price
 * @param geBuyLimit       the Grand Exchange buy limit
 * @param category         the item category ID
 * @param notedId          the noted variant ID, or {@code -1} if none
 * @param wearpos          the equipment slot this item occupies, or {@code -1} if not wearable
 * @param exchangeable     whether this item can be traded on the Grand Exchange
 * @param groundOptions    the right-click options when the item is on the ground
 * @param inventoryOptions the right-click options when the item is in inventory
 * @param params           additional key-value parameters from the item definition
 * @see com.botwithus.bot.api.GameAPI#getItemType
 */
public record ItemType(
        int id,
        String name,
        boolean members,
        boolean stackable,
        int shopPrice,
        int geBuyLimit,
        int category,
        int notedId,
        int wearpos,
        boolean exchangeable,
        List<String> groundOptions,
        List<String> inventoryOptions,
        Map<String, Object> params
) {}
