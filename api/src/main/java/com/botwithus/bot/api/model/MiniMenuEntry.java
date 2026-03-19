package com.botwithus.bot.api.model;

/**
 * An entry in the right-click context (mini) menu.
 *
 * @param optionText the display text of the menu option
 * @param actionId   the action type ID
 * @param typeId     the entity or component type ID
 * @param itemId     the item ID (if applicable), or {@code -1}
 * @param param1     first action parameter
 * @param param2     second action parameter
 * @param param3     third action parameter
 * @see com.botwithus.bot.api.GameAPI#getMiniMenu
 */
public record MiniMenuEntry(
        String optionText, int actionId, int typeId,
        int itemId, int param1, int param2, int param3
) {}
