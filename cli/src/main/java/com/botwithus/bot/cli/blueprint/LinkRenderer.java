package com.botwithus.bot.cli.blueprint;

import com.botwithus.bot.api.blueprint.BlueprintGraph;
import com.botwithus.bot.api.blueprint.Link;
import com.botwithus.bot.api.blueprint.NodeDefinition;
import com.botwithus.bot.api.blueprint.NodeInstance;
import com.botwithus.bot.api.blueprint.PinDefinition;
import com.botwithus.bot.api.blueprint.PinDirection;
import com.botwithus.bot.api.blueprint.PinType;
import com.botwithus.bot.core.blueprint.registry.NodeRegistry;

import imgui.ImDrawList;

import java.util.List;

/**
 * Renders bezier-curve links between connected pins using pure ImGui draw list commands.
 */
public class LinkRenderer {

    private static final float LINK_THICKNESS = 2.5f;
    private static final float BEZIER_TANGENT = 80f;

    private final NodeRenderer nodeRenderer;

    public LinkRenderer(NodeRenderer nodeRenderer) {
        this.nodeRenderer = nodeRenderer;
    }

    /**
     * Renders all links in the graph as bezier curves.
     *
     * @param drawList the window draw list
     * @param graph    the blueprint graph
     * @param registry the node registry
     * @param state    the editor state (for canvas offset)
     * @param canvasOriginX  canvas region origin X on screen
     * @param canvasOriginY  canvas region origin Y on screen
     */
    public void renderAll(ImDrawList drawList, BlueprintGraph graph, NodeRegistry registry,
                          BlueprintEditorState state,
                          float canvasOriginX, float canvasOriginY) {
        renderAll(drawList, graph, registry, state, canvasOriginX, canvasOriginY, 1.0f);
    }

    public void renderAll(ImDrawList drawList, BlueprintGraph graph, NodeRegistry registry,
                          BlueprintEditorState state,
                          float canvasOriginX, float canvasOriginY, float zoom) {
        float thickness = LINK_THICKNESS * zoom;

        for (Link link : graph.getLinks()) {
            float[] srcPos = getPinScreenPos(link.sourcePinId(), graph, registry, state,
                    canvasOriginX, canvasOriginY, zoom);
            float[] dstPos = getPinScreenPos(link.targetPinId(), graph, registry, state,
                    canvasOriginX, canvasOriginY, zoom);

            if (srcPos == null || dstPos == null) continue;

            PinType sourceType = resolvePinType(link.sourcePinId(), graph, registry);
            int color = PinColors.getColorU32(sourceType);

            drawBezierLink(drawList, srcPos[0], srcPos[1], dstPos[0], dstPos[1], color, thickness, zoom);
        }

        // Render in-progress link drag
        if (state.isDraggingLink()) {
            long pinId = state.getDragSourcePinId();
            PinType type = resolvePinType(pinId, graph, registry);
            int color = PinColors.getColorU32(type != null ? type : PinType.ANY);

            float[] srcPos = getPinScreenPos(pinId, graph, registry, state,
                    canvasOriginX, canvasOriginY, zoom);
            if (srcPos != null) {
                float endX = state.getDragEndX();
                float endY = state.getDragEndY();
                if (state.isDragSourceOutput()) {
                    drawBezierLink(drawList, srcPos[0], srcPos[1], endX, endY, color, thickness, zoom);
                } else {
                    drawBezierLink(drawList, endX, endY, srcPos[0], srcPos[1], color, thickness, zoom);
                }
            }
        }
    }

    private void drawBezierLink(ImDrawList drawList, float x1, float y1, float x2, float y2,
                                 int color, float thickness, float zoom) {
        float tangent = Math.min(BEZIER_TANGENT * zoom, Math.abs(x2 - x1) * 0.5f);
        tangent = Math.max(tangent, 30f * zoom);
        drawList.addBezierCubic(
                x1, y1,
                x1 + tangent, y1,
                x2 - tangent, y2,
                x2, y2,
                color, Math.max(thickness, 1f)
        );
    }

    /**
     * Computes the screen position of a pin with zoom applied.
     */
    private float[] getPinScreenPos(long pinId, BlueprintGraph graph, NodeRegistry registry,
                                    BlueprintEditorState state,
                                    float canvasOriginX, float canvasOriginY, float zoom) {
        long nodeId = pinId / 1000;
        int pinIndex = (int) (pinId % 1000);

        NodeInstance node = graph.findNode(nodeId);
        if (node == null) return null;

        NodeDefinition def = registry.getDefinition(node.getTypeId());
        if (def == null) return null;

        List<PinDefinition> pins = def.pins();
        if (pinIndex < 0 || pinIndex >= pins.size()) return null;

        PinDefinition pin = pins.get(pinIndex);
        float nodeWidth = nodeRenderer.calcNodeWidth(def) * zoom;

        float nodeScreenX = node.getX() * zoom + state.getCanvasOffsetX() + canvasOriginX;
        float nodeScreenY = node.getY() * zoom + state.getCanvasOffsetY() + canvasOriginY;

        int idx = 0;
        for (int i = 0; i < pinIndex; i++) {
            if (pins.get(i).direction() == pin.direction()) idx++;
        }

        if (pin.direction() == PinDirection.INPUT) {
            return nodeRenderer.getInputPinScreenPos(nodeScreenX, nodeScreenY, nodeWidth, idx, zoom);
        } else {
            return nodeRenderer.getOutputPinScreenPos(nodeScreenX, nodeScreenY, nodeWidth, idx, zoom);
        }
    }

    /**
     * Determines the PinType for a given pin ID.
     */
    private PinType resolvePinType(long pinId, BlueprintGraph graph, NodeRegistry registry) {
        long nodeId = pinId / 1000;
        int pinIndex = (int) (pinId % 1000);

        NodeInstance node = graph.findNode(nodeId);
        if (node == null) return PinType.ANY;

        NodeDefinition def = registry.getDefinition(node.getTypeId());
        if (def == null) return PinType.ANY;

        List<PinDefinition> pins = def.pins();
        if (pinIndex < 0 || pinIndex >= pins.size()) return PinType.ANY;

        return pins.get(pinIndex).type();
    }
}
