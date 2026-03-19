package com.botwithus.bot.cli.blueprint;

import com.botwithus.bot.api.blueprint.BlueprintGraph;
import com.botwithus.bot.api.blueprint.NodeDefinition;
import com.botwithus.bot.core.blueprint.registry.NodeRegistry;

import imgui.ImGui;
import imgui.flag.ImGuiTreeNodeFlags;
import imgui.type.ImString;

import java.util.List;
import java.util.Map;

/**
 * Left sidebar panel showing a categorized, searchable list of available node types.
 * Nodes can be added to the graph by double-clicking or pressing Enter.
 */
public class NodePalette {

    private final ImString searchBuffer = new ImString(128);

    /**
     * Renders the node palette sidebar.
     *
     * @param registry the node registry containing all available node definitions
     * @param graph    the blueprint graph (for adding nodes)
     * @param state    the editor state (for marking dirty)
     * @param width    the width allocated for this panel
     * @param height   the height allocated for this panel
     */
    public void render(NodeRegistry registry, BlueprintGraph graph, BlueprintEditorState state,
                       float width, float height) {
        ImGui.beginChild("palette", width, height, true);

        ImGui.text("Nodes");
        ImGui.separator();

        // Search filter
        ImGui.pushItemWidth(ImGui.getContentRegionAvailX());
        ImGui.inputText("##search", searchBuffer);
        ImGui.popItemWidth();
        ImGui.spacing();

        String filter = searchBuffer.get().toLowerCase().trim();

        Map<String, List<NodeDefinition>> categories = registry.getCategories();
        for (Map.Entry<String, List<NodeDefinition>> entry : categories.entrySet()) {
            String category = entry.getKey();
            List<NodeDefinition> defs = entry.getValue();

            // Filter definitions by search term
            List<NodeDefinition> filtered = defs;
            if (!filter.isEmpty()) {
                filtered = defs.stream()
                        .filter(d -> d.displayName().toLowerCase().contains(filter)
                                || d.typeId().toLowerCase().contains(filter))
                        .toList();
            }
            if (filtered.isEmpty()) continue;

            int flags = ImGuiTreeNodeFlags.DefaultOpen;
            if (ImGui.treeNodeEx(category, flags)) {
                for (NodeDefinition def : filtered) {
                    if (ImGui.selectable(def.displayName(), false)) {
                        // Single click selects; we use double-click to add
                    }
                    if (ImGui.isItemHovered() && ImGui.isMouseDoubleClicked(0)) {
                        addNodeToGraph(def, graph, state);
                    }
                    // Tooltip with type ID
                    if (ImGui.isItemHovered()) {
                        ImGui.beginTooltip();
                        ImGui.text(def.typeId());
                        ImGui.endTooltip();
                    }
                }
                ImGui.treePop();
            }
        }

        ImGui.endChild();
    }

    /**
     * Adds a new node of the given type to the graph at a default position.
     */
    private void addNodeToGraph(NodeDefinition def, BlueprintGraph graph, BlueprintEditorState state) {
        // Place new nodes near the center of the visible canvas area
        float x = 400.0f + (float) (Math.random() * 100 - 50);
        float y = 300.0f + (float) (Math.random() * 100 - 50);
        graph.addNode(def.typeId(), x, y);
        state.markDirty();
    }
}
