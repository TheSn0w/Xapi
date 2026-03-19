package com.botwithus.bot.cli.blueprint;

import com.botwithus.bot.api.blueprint.BlueprintGraph;
import com.botwithus.bot.api.blueprint.Link;
import com.botwithus.bot.api.blueprint.NodeDefinition;
import com.botwithus.bot.api.blueprint.NodeDefinition.PropertyDef;
import com.botwithus.bot.api.blueprint.NodeInstance;
import com.botwithus.bot.api.blueprint.PinDefinition;
import com.botwithus.bot.api.blueprint.PinDirection;
import com.botwithus.bot.api.blueprint.PinType;
import com.botwithus.bot.core.blueprint.registry.NodeRegistry;

import imgui.ImGui;
import imgui.type.ImBoolean;
import imgui.type.ImFloat;
import imgui.type.ImInt;
import imgui.type.ImString;

import java.util.Map;

/**
 * Right sidebar panel showing editable properties for the currently selected node.
 */
public class PropertyPanel {

    /**
     * Renders the property panel sidebar.
     *
     * @param graph    the blueprint graph
     * @param registry the node registry
     * @param state    the editor state (for knowing which node is selected)
     * @param width    the width allocated for this panel
     * @param height   the height allocated for this panel
     */
    public void render(BlueprintGraph graph, NodeRegistry registry, BlueprintEditorState state,
                       float width, float height) {
        ImGui.beginChild("properties", width, height, true);

        ImGui.text("Properties");
        ImGui.separator();

        long selectedId = state.getFirstSelectedNodeId();
        if (selectedId < 0) {
            ImGui.textColored(0.5f, 0.5f, 0.5f, 1.0f, "No node selected");
            ImGui.endChild();
            return;
        }

        NodeInstance node = graph.findNode(selectedId);
        if (node == null) {
            ImGui.textColored(0.5f, 0.5f, 0.5f, 1.0f, "Node not found");
            ImGui.endChild();
            return;
        }

        NodeDefinition def = registry.getDefinition(node.getTypeId());
        if (def == null) {
            ImGui.text("Unknown type: " + node.getTypeId());
            ImGui.endChild();
            return;
        }

        // Node info header
        ImGui.textColored(0.8f, 0.8f, 0.2f, 1.0f, def.displayName());
        ImGui.textColored(0.5f, 0.5f, 0.5f, 1.0f, def.typeId());
        ImGui.textColored(0.5f, 0.5f, 0.5f, 1.0f, "ID: " + node.getId());
        ImGui.spacing();
        ImGui.separator();
        ImGui.spacing();

        // Render editable properties
        Map<String, PropertyDef> properties = def.properties();
        if (properties != null && !properties.isEmpty()) {
            ImGui.text("Properties");
            ImGui.spacing();

            for (Map.Entry<String, PropertyDef> entry : properties.entrySet()) {
                String key = entry.getKey();
                PropertyDef propDef = entry.getValue();
                renderProperty(key, propDef, node, state);
            }

            ImGui.separator();
            ImGui.spacing();
        }

        // Render default values for unconnected input pins
        ImGui.text("Pin Defaults");
        ImGui.spacing();

        var pins = def.pins();
        for (int i = 0; i < pins.size(); i++) {
            PinDefinition pin = pins.get(i);
            if (pin.direction() != PinDirection.INPUT) continue;
            if (pin.type() == PinType.EXEC) continue;

            // Check if this pin has a link connected
            long pinId = node.pinId(i);
            Link link = graph.findLinkToPin(pinId);
            if (link != null) {
                // Pin is connected, show as read-only
                ImGui.textColored(0.5f, 0.5f, 0.5f, 1.0f, pin.name() + ": [connected]");
                continue;
            }

            // Render editable default value
            String propKey = "__pin_default_" + pin.id();
            Object currentValue = node.getProperty(propKey);
            if (currentValue == null) {
                currentValue = pin.defaultValue();
            }

            renderPinDefault(propKey, pin, currentValue, node, state);
        }

        ImGui.endChild();
    }

    private void renderProperty(String key, PropertyDef propDef, NodeInstance node, BlueprintEditorState state) {
        Object currentValue = node.getProperty(key);
        if (currentValue == null) {
            currentValue = propDef.defaultValue();
        }

        // If there are dropdown options, render a combo box
        if (propDef.options() != null && propDef.options().length > 0) {
            String currentStr = currentValue != null ? currentValue.toString() : propDef.options()[0];
            int currentIndex = 0;
            for (int i = 0; i < propDef.options().length; i++) {
                if (propDef.options()[i].equals(currentStr)) {
                    currentIndex = i;
                    break;
                }
            }
            ImInt selected = new ImInt(currentIndex);
            if (ImGui.combo(propDef.name(), selected, propDef.options())) {
                node.setProperty(key, propDef.options()[selected.get()]);
                state.markDirty();
            }
            return;
        }

        // Render based on type
        switch (propDef.type()) {
            case BOOLEAN -> {
                boolean val = currentValue instanceof Boolean b ? b : false;
                ImBoolean checked = new ImBoolean(val);
                if (ImGui.checkbox(propDef.name(), checked)) {
                    node.setProperty(key, checked.get());
                    state.markDirty();
                }
            }
            case INT -> {
                int val = currentValue instanceof Number n ? n.intValue() : 0;
                ImInt imVal = new ImInt(val);
                if (ImGui.inputInt(propDef.name(), imVal)) {
                    node.setProperty(key, imVal.get());
                    state.markDirty();
                }
            }
            case FLOAT -> {
                float val = currentValue instanceof Number n ? n.floatValue() : 0.0f;
                ImFloat imVal = new ImFloat(val);
                if (ImGui.inputFloat(propDef.name(), imVal)) {
                    node.setProperty(key, imVal.get());
                    state.markDirty();
                }
            }
            case STRING -> {
                String val = currentValue != null ? currentValue.toString() : "";
                ImString imVal = new ImString(val, 256);
                if (ImGui.inputText(propDef.name(), imVal)) {
                    node.setProperty(key, imVal.get());
                    state.markDirty();
                }
            }
            default -> ImGui.textColored(0.5f, 0.5f, 0.5f, 1.0f,
                    propDef.name() + " (" + propDef.type() + ")");
        }
    }

    private void renderPinDefault(String key, PinDefinition pin, Object currentValue,
                                  NodeInstance node, BlueprintEditorState state) {
        switch (pin.type()) {
            case BOOLEAN -> {
                boolean val = currentValue instanceof Boolean b ? b : false;
                ImBoolean checked = new ImBoolean(val);
                if (ImGui.checkbox(pin.name(), checked)) {
                    node.setProperty(key, checked.get());
                    state.markDirty();
                }
            }
            case INT -> {
                int val = currentValue instanceof Number n ? n.intValue() : 0;
                ImInt imVal = new ImInt(val);
                if (ImGui.inputInt(pin.name(), imVal)) {
                    node.setProperty(key, imVal.get());
                    state.markDirty();
                }
            }
            case FLOAT -> {
                float val = currentValue instanceof Number n ? n.floatValue() : 0.0f;
                ImFloat imVal = new ImFloat(val);
                if (ImGui.inputFloat(pin.name(), imVal)) {
                    node.setProperty(key, imVal.get());
                    state.markDirty();
                }
            }
            case STRING -> {
                String val = currentValue != null ? currentValue.toString() : "";
                ImString imVal = new ImString(val, 256);
                if (ImGui.inputText(pin.name(), imVal)) {
                    node.setProperty(key, imVal.get());
                    state.markDirty();
                }
            }
            default -> ImGui.textColored(0.5f, 0.5f, 0.5f, 1.0f,
                    pin.name() + " (" + pin.type() + ")");
        }
    }
}
