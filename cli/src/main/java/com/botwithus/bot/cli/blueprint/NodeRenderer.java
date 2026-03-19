package com.botwithus.bot.cli.blueprint;

import com.botwithus.bot.api.blueprint.NodeDefinition;
import com.botwithus.bot.api.blueprint.NodeInstance;
import com.botwithus.bot.api.blueprint.PinDefinition;
import com.botwithus.bot.api.blueprint.PinDirection;
import com.botwithus.bot.api.blueprint.PinType;

import imgui.ImDrawList;
import imgui.ImGui;
import imgui.ImVec2;

import java.util.ArrayList;
import java.util.List;

/**
 * Renders individual nodes on the canvas using pure ImGui draw list commands.
 * No dependency on imgui-node-editor extension.
 */
public class NodeRenderer {

    // All sizing is derived from the current font size so nodes scale with DPI/font changes.
    // These multipliers are applied to ImGui.getTextLineHeight().
    private static final float TITLE_HEIGHT_MULT = 1.7f;
    private static final float PIN_SPACING_MULT = 1.55f;
    private static final float PIN_RADIUS_MULT = 0.35f;
    private static final float PIN_TEXT_OFFSET_MULT = 0.85f;
    private static final float NODE_PADDING_MULT = 0.55f;
    private static final float NODE_ROUNDING_MULT = 0.42f;
    private static final float MIN_NODE_WIDTH_MULT = 10.5f;

    float titleHeight()   { return ImGui.getTextLineHeight() * TITLE_HEIGHT_MULT; }
    float pinSpacing()    { return ImGui.getTextLineHeight() * PIN_SPACING_MULT; }
    float pinRadius()     { return ImGui.getTextLineHeight() * PIN_RADIUS_MULT; }
    float pinTextOffset() { return ImGui.getTextLineHeight() * PIN_TEXT_OFFSET_MULT; }
    float nodePadding()   { return ImGui.getTextLineHeight() * NODE_PADDING_MULT; }
    float nodeRounding()  { return ImGui.getTextLineHeight() * NODE_ROUNDING_MULT; }
    float minNodeWidth()  { return ImGui.getTextLineHeight() * MIN_NODE_WIDTH_MULT; }

    // Node body colors
    private static final int COL_NODE_BG         = packColor(45, 45, 48, 230);
    private static final int COL_NODE_BG_SELECTED = packColor(55, 55, 65, 240);
    private static final int COL_NODE_BORDER      = packColor(80, 80, 85, 255);
    private static final int COL_NODE_BORDER_SEL   = packColor(120, 160, 255, 255);
    private static final int COL_TITLE_BG         = packColor(35, 35, 38, 255);
    private static final int COL_TITLE_TEXT        = packColor(230, 230, 230, 255);
    private static final int COL_PIN_LABEL         = packColor(200, 200, 200, 255);
    private static final int COL_PIN_HOVER         = packColor(255, 255, 255, 80);

    /**
     * Calculates the width of a node based on its definition.
     */
    public float calcNodeWidth(NodeDefinition def) {
        float pad = nodePadding();
        float maxText = ImGui.calcTextSize(def.displayName()).x + pad * 2;
        List<PinDefinition> pins = def.pins();
        for (PinDefinition pin : pins) {
            float w = ImGui.calcTextSize(pin.name()).x + pinTextOffset() + pinRadius() * 2 + pad;
            maxText = Math.max(maxText, w * 2); // account for left+right columns
        }
        return Math.max(minNodeWidth(), maxText);
    }

    /**
     * Calculates the height of a node based on its pin count.
     */
    public float calcNodeHeight(NodeDefinition def) {
        int inputCount = 0, outputCount = 0;
        for (PinDefinition pin : def.pins()) {
            if (pin.direction() == PinDirection.INPUT) inputCount++;
            else outputCount++;
        }
        int rows = Math.max(inputCount, outputCount);
        float pad = nodePadding();
        return titleHeight() + pad + rows * pinSpacing() + pad;
    }

    /**
     * Renders a single node at its screen position.
     *
     * @param drawList the window draw list
     * @param node     the node instance
     * @param def      the node type definition
     * @param state    the editor state
     * @param screenX  top-left screen X of the node
     * @param screenY  top-left screen Y of the node
     * @param nodeWidth  calculated node width
     * @param nodeHeight calculated node height
     */
    public void render(ImDrawList drawList, NodeInstance node, NodeDefinition def,
                       BlueprintEditorState state,
                       float screenX, float screenY, float nodeWidth, float nodeHeight) {
        render(drawList, node, def, state, screenX, screenY, nodeWidth, nodeHeight, 1.0f);
    }

    public void render(ImDrawList drawList, NodeInstance node, NodeDefinition def,
                       BlueprintEditorState state,
                       float screenX, float screenY, float nodeWidth, float nodeHeight,
                       float zoom) {

        boolean selected = state.isNodeSelected(node.getId());
        float rounding = nodeRounding() * zoom;
        float th = titleHeight() * zoom;
        float pad = nodePadding() * zoom;

        // Node body
        int bgColor = selected ? COL_NODE_BG_SELECTED : COL_NODE_BG;
        drawList.addRectFilled(screenX, screenY, screenX + nodeWidth, screenY + nodeHeight,
                bgColor, rounding);

        // Title bar background
        drawList.addRectFilled(screenX, screenY, screenX + nodeWidth, screenY + th,
                COL_TITLE_BG, rounding);
        // Flat bottom for title bar (overdraw lower corners)
        drawList.addRectFilled(screenX, screenY + th - rounding,
                screenX + nodeWidth, screenY + th, COL_TITLE_BG);

        // Title text
        float titleX = screenX + pad;
        float titleY = screenY + (th - ImGui.getTextLineHeight()) * 0.5f;
        drawList.addText(titleX, titleY, COL_TITLE_TEXT, def.displayName());

        // Border
        int borderColor = selected ? COL_NODE_BORDER_SEL : COL_NODE_BORDER;
        drawList.addRect(screenX, screenY, screenX + nodeWidth, screenY + nodeHeight,
                borderColor, rounding, 0, selected ? 2f : 1f);

        // Pins
        List<PinDefinition> pins = def.pins();
        int inputIdx = 0, outputIdx = 0;
        for (int i = 0; i < pins.size(); i++) {
            PinDefinition pin = pins.get(i);
            if (pin.direction() == PinDirection.INPUT) {
                float[] pos = getInputPinScreenPos(screenX, screenY, nodeWidth, inputIdx, zoom);
                renderPin(drawList, pos[0], pos[1], pin, true, state, node.pinId(i), zoom);
                inputIdx++;
            } else {
                float[] pos = getOutputPinScreenPos(screenX, screenY, nodeWidth, outputIdx, zoom);
                renderPin(drawList, pos[0], pos[1], pin, false, state, node.pinId(i), zoom);
                outputIdx++;
            }
        }
    }

    private void renderPin(ImDrawList drawList, float cx, float cy,
                           PinDefinition pin, boolean isInput,
                           BlueprintEditorState state, long pinId, float zoom) {
        int color = PinColors.getColorU32(pin.type());
        float pr = pinRadius() * zoom;
        float pto = pinTextOffset() * zoom;

        // Draw pin circle
        if (pin.type() == PinType.EXEC) {
            if (isInput) {
                drawList.addTriangleFilled(cx - pr, cy - pr, cx - pr, cy + pr, cx + pr, cy, color);
            } else {
                drawList.addTriangleFilled(cx - pr, cy - pr, cx + pr, cy, cx - pr, cy + pr, color);
            }
        } else {
            drawList.addCircleFilled(cx, cy, pr, color);
        }

        // Hover highlight
        if (state.getHoveredPinId() == pinId) {
            drawList.addCircle(cx, cy, pr + 2, COL_PIN_HOVER, 12, 2f);
        }

        // Label (only draw if zoom is large enough to be readable)
        if (zoom > 0.3f) {
            float textY = cy - ImGui.getTextLineHeight() * 0.5f;
            if (isInput) {
                drawList.addText(cx + pto, textY, COL_PIN_LABEL, pin.name());
            } else {
                float textW = ImGui.calcTextSize(pin.name()).x;
                drawList.addText(cx - pto - textW, textY, COL_PIN_LABEL, pin.name());
            }
        }
    }

    /**
     * Returns screen position [x, y] for an input pin, with zoom applied.
     */
    public float[] getInputPinScreenPos(float nodeScreenX, float nodeScreenY,
                                         float nodeWidth, int inputIndex, float zoom) {
        float ps = pinSpacing() * zoom;
        float x = nodeScreenX;
        float y = nodeScreenY + titleHeight() * zoom + nodePadding() * zoom + inputIndex * ps + ps * 0.5f;
        return new float[]{x, y};
    }

    /** Convenience overload with zoom=1. */
    public float[] getInputPinScreenPos(float nodeScreenX, float nodeScreenY,
                                         float nodeWidth, int inputIndex) {
        return getInputPinScreenPos(nodeScreenX, nodeScreenY, nodeWidth, inputIndex, 1.0f);
    }

    /**
     * Returns screen position [x, y] for an output pin, with zoom applied.
     */
    public float[] getOutputPinScreenPos(float nodeScreenX, float nodeScreenY,
                                          float nodeWidth, int outputIndex, float zoom) {
        float ps = pinSpacing() * zoom;
        float x = nodeScreenX + nodeWidth;
        float y = nodeScreenY + titleHeight() * zoom + nodePadding() * zoom + outputIndex * ps + ps * 0.5f;
        return new float[]{x, y};
    }

    /** Convenience overload with zoom=1. */
    public float[] getOutputPinScreenPos(float nodeScreenX, float nodeScreenY,
                                          float nodeWidth, int outputIndex) {
        return getOutputPinScreenPos(nodeScreenX, nodeScreenY, nodeWidth, outputIndex, 1.0f);
    }

    /**
     * Tests if a screen point is within a pin circle.
     */
    public boolean isPointOnPin(float px, float py, float pinCx, float pinCy) {
        float dx = px - pinCx;
        float dy = py - pinCy;
        float hitRadius = pinRadius() + 4;
        return (dx * dx + dy * dy) <= hitRadius * hitRadius;
    }

    /**
     * Tests if a screen point is within the title bar area of a node (for dragging).
     */
    public boolean isPointOnTitleBar(float px, float py,
                                     float nodeScreenX, float nodeScreenY, float nodeWidth) {
        return isPointOnTitleBar(px, py, nodeScreenX, nodeScreenY, nodeWidth, 1.0f);
    }

    /**
     * Tests if a screen point is within the title bar area with zoom.
     */
    public boolean isPointOnTitleBar(float px, float py,
                                     float nodeScreenX, float nodeScreenY, float nodeWidth,
                                     float zoom) {
        return px >= nodeScreenX && px <= nodeScreenX + nodeWidth
                && py >= nodeScreenY && py <= nodeScreenY + titleHeight() * zoom;
    }

    /**
     * Tests if a screen point is within a node's bounding box.
     */
    public boolean isPointOnNode(float px, float py,
                                 float nodeScreenX, float nodeScreenY,
                                 float nodeWidth, float nodeHeight) {
        return px >= nodeScreenX && px <= nodeScreenX + nodeWidth
                && py >= nodeScreenY && py <= nodeScreenY + nodeHeight;
    }

    /**
     * Packs RGBA bytes into an ImGui-compatible U32 color (0xAABBGGRR).
     */
    static int packColor(int r, int g, int b, int a) {
        return (a << 24) | (b << 16) | (g << 8) | r;
    }
}
