package com.botwithus.bot.cli.blueprint;

import com.botwithus.bot.api.blueprint.BlueprintGraph;
import com.botwithus.bot.api.blueprint.BlueprintMetadata;
import com.botwithus.bot.api.blueprint.NodeInstance;

import imgui.ImGui;

/**
 * Renders the menu bar at the top of the blueprint editor.
 * Returns actions as flags that the BlueprintEditor processes.
 */
public class BlueprintMenuBar {

    /** Actions that can be triggered from the menu bar. */
    public enum Action {
        NONE,
        NEW,
        OPEN,
        SAVE,
        SAVE_AS,
        CLOSE,
        DELETE_SELECTED,
        SELECT_ALL,
        PLAY,
        STOP
    }

    private Action pendingAction = Action.NONE;

    /**
     * Renders the menu bar and returns the action triggered by the user (or NONE).
     *
     * @param state the editor state
     * @param graph the blueprint graph
     * @return the action triggered this frame
     */
    public Action render(BlueprintEditorState state, BlueprintGraph graph) {
        pendingAction = Action.NONE;

        if (ImGui.beginMenuBar()) {
            renderFileMenu(state, graph);
            renderEditMenu(state, graph);
            renderRunMenu();
            renderInfoLabel(state, graph);
            ImGui.endMenuBar();
        }

        return pendingAction;
    }

    private void renderFileMenu(BlueprintEditorState state, BlueprintGraph graph) {
        if (ImGui.beginMenu("File")) {
            if (ImGui.menuItem("New", "Ctrl+N")) {
                pendingAction = Action.NEW;
            }
            if (ImGui.menuItem("Open...", "Ctrl+O")) {
                pendingAction = Action.OPEN;
            }
            ImGui.separator();
            if (ImGui.menuItem("Save", "Ctrl+S")) {
                pendingAction = Action.SAVE;
            }
            if (ImGui.menuItem("Save As...")) {
                pendingAction = Action.SAVE_AS;
            }
            ImGui.separator();
            if (ImGui.menuItem("Close", "F2")) {
                pendingAction = Action.CLOSE;
            }
            ImGui.endMenu();
        }
    }

    private void renderEditMenu(BlueprintEditorState state, BlueprintGraph graph) {
        if (ImGui.beginMenu("Edit")) {
            if (ImGui.menuItem("Delete Selected", "Del", false, !state.getSelectedNodeIds().isEmpty())) {
                pendingAction = Action.DELETE_SELECTED;
            }
            if (ImGui.menuItem("Select All", "Ctrl+A")) {
                pendingAction = Action.SELECT_ALL;
            }
            ImGui.endMenu();
        }
    }

    private void renderRunMenu() {
        if (ImGui.beginMenu("Run")) {
            if (ImGui.menuItem("Play")) {
                pendingAction = Action.PLAY;
            }
            if (ImGui.menuItem("Stop")) {
                pendingAction = Action.STOP;
            }
            ImGui.endMenu();
        }
    }

    private void renderInfoLabel(BlueprintEditorState state, BlueprintGraph graph) {
        // Show blueprint name and dirty indicator on the right side of the menu bar
        String name = graph.getMetadata().name();
        String label = state.isDirty() ? "* " + name : name;

        // Right-align the label
        float textWidth = ImGui.calcTextSize(label).x;
        float availWidth = ImGui.getContentRegionAvailX();
        if (availWidth > textWidth + 20) {
            ImGui.setCursorPosX(ImGui.getCursorPosX() + availWidth - textWidth - 10);
        }
        ImGui.textColored(0.6f, 0.6f, 0.6f, 1.0f, label);
    }
}
