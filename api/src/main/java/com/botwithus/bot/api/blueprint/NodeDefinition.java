package com.botwithus.bot.api.blueprint;

import java.util.List;
import java.util.Map;

/**
 * Describes a node type available in the blueprint system.
 *
 * @param typeId      unique type identifier (e.g. "flow.branch", "gameapi.getLocalPlayer")
 * @param displayName human-readable name shown in the editor
 * @param category    category for grouping in the node palette (e.g. "Flow Control", "Game API")
 * @param pins        ordered list of pin definitions
 * @param properties  editable properties shown in the property panel
 */
public record NodeDefinition(
        String typeId,
        String displayName,
        String category,
        List<PinDefinition> pins,
        Map<String, PropertyDef> properties
) {

    /**
     * Defines an editable property on a node.
     *
     * @param name         display name
     * @param type         data type of the property
     * @param defaultValue default value
     * @param options      dropdown options (for enum-like properties), or {@code null}
     */
    public record PropertyDef(
            String name,
            PinType type,
            Object defaultValue,
            String[] options
    ) {}
}
