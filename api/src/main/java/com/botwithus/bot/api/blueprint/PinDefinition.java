package com.botwithus.bot.api.blueprint;

/**
 * Defines a pin on a node type.
 *
 * @param id           unique pin identifier within the node (e.g. "exec_in", "value")
 * @param name         display name shown in the editor
 * @param type         the data type of this pin
 * @param direction    whether this is an input or output pin
 * @param defaultValue default value for unconnected input pins, or {@code null}
 */
public record PinDefinition(
        String id,
        String name,
        PinType type,
        PinDirection direction,
        Object defaultValue
) {}
