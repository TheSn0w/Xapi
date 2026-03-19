package com.botwithus.bot.cli.blueprint;

import java.nio.file.Path;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Tracks the transient UI state of the blueprint editor (selection, hover, dialogs, dirty flag,
 * canvas pan/zoom, and interactive link dragging).
 */
public class BlueprintEditorState {

    private Path currentFilePath;
    private boolean dirty;

    private final Set<Long> selectedNodeIds = new LinkedHashSet<>();
    private long hoveredNodeId;
    private long hoveredPinId;
    private long hoveredLinkId;

    private boolean showSaveDialog;
    private boolean showOpenDialog;

    // ---- Canvas pan/scroll/zoom ----
    private float canvasOffsetX = 0f;
    private float canvasOffsetY = 0f;
    private float canvasZoom = 1.0f;

    private static final float MIN_ZOOM = 0.1f;
    private static final float MAX_ZOOM = 3.0f;

    // ---- Node dragging ----
    private long draggingNodeId = -1;
    private float dragOffsetX;
    private float dragOffsetY;

    // ---- Link dragging (creating a new link) ----
    private boolean draggingLink;
    private long dragSourcePinId = -1;
    private long dragSourceNodeId = -1;
    private boolean dragSourceIsOutput;
    private float dragEndX;
    private float dragEndY;

    // ---- File path ----

    public Path getCurrentFilePath() {
        return currentFilePath;
    }

    public void setCurrentFilePath(Path currentFilePath) {
        this.currentFilePath = currentFilePath;
    }

    // ---- Dirty flag ----

    public boolean isDirty() {
        return dirty;
    }

    public void markDirty() {
        this.dirty = true;
    }

    public void clearDirty() {
        this.dirty = false;
    }

    // ---- Selection ----

    public Set<Long> getSelectedNodeIds() {
        return Collections.unmodifiableSet(selectedNodeIds);
    }

    public void selectNode(long nodeId) {
        selectedNodeIds.add(nodeId);
    }

    public void deselectNode(long nodeId) {
        selectedNodeIds.remove(nodeId);
    }

    public void clearSelection() {
        selectedNodeIds.clear();
    }

    public void setSelection(Set<Long> ids) {
        selectedNodeIds.clear();
        selectedNodeIds.addAll(ids);
    }

    public boolean isNodeSelected(long nodeId) {
        return selectedNodeIds.contains(nodeId);
    }

    /**
     * Returns the first selected node ID, or -1 if nothing is selected.
     */
    public long getFirstSelectedNodeId() {
        return selectedNodeIds.isEmpty() ? -1 : selectedNodeIds.iterator().next();
    }

    // ---- Hover ----

    public long getHoveredNodeId() {
        return hoveredNodeId;
    }

    public void setHoveredNodeId(long hoveredNodeId) {
        this.hoveredNodeId = hoveredNodeId;
    }

    public long getHoveredPinId() {
        return hoveredPinId;
    }

    public void setHoveredPinId(long hoveredPinId) {
        this.hoveredPinId = hoveredPinId;
    }

    public long getHoveredLinkId() {
        return hoveredLinkId;
    }

    public void setHoveredLinkId(long hoveredLinkId) {
        this.hoveredLinkId = hoveredLinkId;
    }

    // ---- Dialogs ----

    public boolean isShowSaveDialog() {
        return showSaveDialog;
    }

    public void setShowSaveDialog(boolean showSaveDialog) {
        this.showSaveDialog = showSaveDialog;
    }

    public boolean isShowOpenDialog() {
        return showOpenDialog;
    }

    public void setShowOpenDialog(boolean showOpenDialog) {
        this.showOpenDialog = showOpenDialog;
    }

    // ---- Canvas offset (pan) ----

    public float getCanvasOffsetX() {
        return canvasOffsetX;
    }

    public float getCanvasOffsetY() {
        return canvasOffsetY;
    }

    public void setCanvasOffset(float x, float y) {
        this.canvasOffsetX = x;
        this.canvasOffsetY = y;
    }

    public void panCanvas(float dx, float dy) {
        this.canvasOffsetX += dx;
        this.canvasOffsetY += dy;
    }

    // ---- Zoom ----

    public float getCanvasZoom() {
        return canvasZoom;
    }

    /**
     * Zooms the canvas around a pivot point (usually the mouse position in canvas-local coords).
     * Adjusts the offset so the point under the cursor stays fixed.
     */
    public void zoomCanvas(float delta, float pivotScreenX, float pivotScreenY,
                           float canvasOriginX, float canvasOriginY) {
        float oldZoom = canvasZoom;
        canvasZoom = Math.max(MIN_ZOOM, Math.min(MAX_ZOOM, canvasZoom + delta));
        float zoomRatio = canvasZoom / oldZoom;

        // Adjust offset so the pivot point stays in the same screen position
        float pivotLocalX = pivotScreenX - canvasOriginX;
        float pivotLocalY = pivotScreenY - canvasOriginY;
        canvasOffsetX = pivotLocalX - (pivotLocalX - canvasOffsetX) * zoomRatio;
        canvasOffsetY = pivotLocalY - (pivotLocalY - canvasOffsetY) * zoomRatio;
    }

    public void setCanvasZoom(float zoom) {
        this.canvasZoom = Math.max(MIN_ZOOM, Math.min(MAX_ZOOM, zoom));
    }

    // ---- Node dragging ----

    public long getDraggingNodeId() {
        return draggingNodeId;
    }

    public void setDraggingNodeId(long id) {
        this.draggingNodeId = id;
    }

    public float getDragOffsetX() {
        return dragOffsetX;
    }

    public float getDragOffsetY() {
        return dragOffsetY;
    }

    public void setDragOffset(float x, float y) {
        this.dragOffsetX = x;
        this.dragOffsetY = y;
    }

    // ---- Link dragging ----

    public boolean isDraggingLink() {
        return draggingLink;
    }

    public void startLinkDrag(long pinId, long nodeId, boolean isOutput, float endX, float endY) {
        this.draggingLink = true;
        this.dragSourcePinId = pinId;
        this.dragSourceNodeId = nodeId;
        this.dragSourceIsOutput = isOutput;
        this.dragEndX = endX;
        this.dragEndY = endY;
    }

    public void updateLinkDragEnd(float x, float y) {
        this.dragEndX = x;
        this.dragEndY = y;
    }

    public void endLinkDrag() {
        this.draggingLink = false;
        this.dragSourcePinId = -1;
        this.dragSourceNodeId = -1;
    }

    public long getDragSourcePinId() {
        return dragSourcePinId;
    }

    public long getDragSourceNodeId() {
        return dragSourceNodeId;
    }

    public boolean isDragSourceOutput() {
        return dragSourceIsOutput;
    }

    public float getDragEndX() {
        return dragEndX;
    }

    public float getDragEndY() {
        return dragEndY;
    }
}
