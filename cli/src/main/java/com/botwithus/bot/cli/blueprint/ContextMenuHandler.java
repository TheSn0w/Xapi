package com.botwithus.bot.cli.blueprint;

import com.botwithus.bot.api.blueprint.BlueprintGraph;
import com.botwithus.bot.api.blueprint.NodeDefinition;
import com.botwithus.bot.api.blueprint.NodeInstance;
import com.botwithus.bot.core.blueprint.registry.NodeRegistry;

import imgui.ImGui;

import java.util.List;
import java.util.Map;

/**
 * Handles right-click context menus within the blueprint editor canvas.
 * Uses pure ImGui popups without any node-editor extension dependency.
 */
public class ContextMenuHandler {

    private static final String CANVAS_POPUP = "canvas_context_menu";
    private static final String NODE_POPUP = "node_context_menu";

    private long contextNodeId = -1;

    // Canvas position where the right-click occurred (in graph space)
    private float contextGraphX;
    private float contextGraphY;

    /**
     * Opens the canvas context menu (right-click on empty space).
     *
     * @param graphX  graph-space X where the click occurred
     * @param graphY  graph-space Y where the click occurred
     */
    public void openCanvasMenu(float graphX, float graphY) {
        this.contextGraphX = graphX;
        this.contextGraphY = graphY;
        ImGui.openPopup(CANVAS_POPUP);
    }

    /**
     * Opens the node context menu (right-click on a node).
     *
     * @param nodeId the node that was right-clicked
     */
    public void openNodeMenu(long nodeId) {
        this.contextNodeId = nodeId;
        ImGui.openPopup(NODE_POPUP);
    }

    /**
     * Renders any open context menu popups. Call each frame.
     */
    public void render(BlueprintGraph graph, NodeRegistry registry, BlueprintEditorState state) {
        renderCanvasContextMenu(graph, registry, state);
        renderNodeContextMenu(graph, state);
    }

    private void renderCanvasContextMenu(BlueprintGraph graph, NodeRegistry registry,
                                         BlueprintEditorState state) {
        if (ImGui.beginPopup(CANVAS_POPUP)) {
            ImGui.text("Add Node");
            ImGui.separator();

            Map<String, List<NodeDefinition>> categories = registry.getCategories();
            for (Map.Entry<String, List<NodeDefinition>> entry : categories.entrySet()) {
                String category = entry.getKey();
                List<NodeDefinition> defs = entry.getValue();

                if (ImGui.beginMenu(category)) {
                    for (NodeDefinition def : defs) {
                        if (ImGui.menuItem(def.displayName())) {
                            graph.addNode(def.typeId(), contextGraphX, contextGraphY);
                            state.markDirty();
                        }
                    }
                    ImGui.endMenu();
                }
            }

            ImGui.endPopup();
        }
    }

    private void renderNodeContextMenu(BlueprintGraph graph, BlueprintEditorState state) {
        if (ImGui.beginPopup(NODE_POPUP)) {
            if (contextNodeId >= 0) {
                NodeInstance node = graph.findNode(contextNodeId);
                if (node != null) {
                    ImGui.textColored(0.7f, 0.7f, 0.7f, 1.0f, "Node: " + node.getTypeId());
                    ImGui.separator();

                    if (ImGui.menuItem("Delete Node")) {
                        graph.removeNode(contextNodeId);
                        state.deselectNode(contextNodeId);
                        state.markDirty();
                    }

                    if (ImGui.menuItem("Duplicate Node")) {
                        NodeInstance dup = graph.addNode(node.getTypeId(),
                                node.getX() + 50, node.getY() + 50);
                        for (Map.Entry<String, Object> entry : node.getPropertyValues().entrySet()) {
                            dup.setProperty(entry.getKey(), entry.getValue());
                        }
                        state.markDirty();
                    }
                }
            }
            ImGui.endPopup();
        }
    }
}
