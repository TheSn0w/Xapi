package com.botwithus.bot.api.model;

/**
 * Represents a UI component (widget) within a game interface.
 *
 * @param handle         the unique component handle
 * @param interfaceId    the parent interface ID
 * @param componentId    the component ID within the interface
 * @param subComponentId the sub-component ID (for nested components), or {@code -1}
 * @param type           the component type ID
 * @param itemId         the item ID displayed by this component, or {@code -1}
 * @param itemCount      the item quantity displayed
 * @param spriteId       the sprite ID rendered by this component, or {@code -1}
 * @see com.botwithus.bot.api.GameAPI#queryComponents
 */
public record Component(
        int handle, int interfaceId, int componentId, int subComponentId,
        int type, int itemId, int itemCount, int spriteId
) {}
