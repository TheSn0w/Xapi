package com.botwithus.bot.cli.gui;

import com.botwithus.bot.api.inventory.ActionTypes;
import com.botwithus.bot.cli.CliContext;
import com.botwithus.bot.core.impl.ActionDebugger;
import com.botwithus.bot.core.impl.ActionDebugger.LoggedAction;

import imgui.ImGui;
import imgui.flag.ImGuiCol;
import imgui.flag.ImGuiTableFlags;

import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * Debug panel that displays a live log of all queued actions.
 * Provides toggles to record actions and optionally block their execution.
 */
public class ActionDebugPanel implements GuiPanel {

    private static final DateTimeFormatter TIME_FMT = DateTimeFormatter.ofPattern("HH:mm:ss.SSS");

    private int lastSize;

    @Override
    public String title() {
        return "Action Debug";
    }

    @Override
    public void render(CliContext ctx) {
        var activeConn = ctx.getActiveConnection();
        ActionDebugger debugger = activeConn != null
                ? ActionDebugger.forConnection(activeConn.getName())
                : ActionDebugger.global();

        ImGui.spacing();

        // --- Toolbar ---
        boolean recording = debugger.isRecording();
        boolean blocking = debugger.isBlocking();

        // Record toggle
        if (recording) {
            ImGui.pushStyleColor(ImGuiCol.Button,
                    ImGuiTheme.GREEN_R, ImGuiTheme.GREEN_G, ImGuiTheme.GREEN_B, 0.6f);
            ImGui.pushStyleColor(ImGuiCol.ButtonHovered,
                    ImGuiTheme.GREEN_R, ImGuiTheme.GREEN_G, ImGuiTheme.GREEN_B, 0.8f);
        }
        if (ImGui.button(Icons.CIRCLE_DOT + " Record")) {
            debugger.setRecording(!recording);
        }
        if (recording) {
            ImGui.popStyleColor(2);
        }
        if (ImGui.isItemHovered()) {
            ImGui.setTooltip(recording ? "Recording — click to stop" : "Click to start recording actions");
        }

        ImGui.sameLine();

        // Block toggle
        if (blocking) {
            ImGui.pushStyleColor(ImGuiCol.Button,
                    ImGuiTheme.RED_R, ImGuiTheme.RED_G, ImGuiTheme.RED_B, 0.6f);
            ImGui.pushStyleColor(ImGuiCol.ButtonHovered,
                    ImGuiTheme.RED_R, ImGuiTheme.RED_G, ImGuiTheme.RED_B, 0.8f);
        }
        if (ImGui.button(Icons.SHIELD + " Block Actions")) {
            boolean newBlocking = !blocking;
            debugger.setBlocking(newBlocking);
            // Auto-enable recording when blocking
            if (newBlocking && !recording) {
                debugger.setRecording(true);
            }
        }
        if (blocking) {
            ImGui.popStyleColor(2);
        }
        if (ImGui.isItemHovered()) {
            ImGui.setTooltip(blocking
                    ? "Blocking — actions are logged but NOT sent to game. Click to allow."
                    : "Click to block actions (they will still be logged)");
        }

        ImGui.sameLine();

        // Clear
        if (ImGui.button(Icons.TRASH + " Clear")) {
            debugger.clear();
            lastSize = 0;
        }

        ImGui.sameLine();
        ImGui.textColored(ImGuiTheme.TEXT_SEC_R, ImGuiTheme.TEXT_SEC_G, ImGuiTheme.TEXT_SEC_B, 1f,
                "  " + debugger.getLog().size() + " entries");

        if (blocking) {
            ImGui.sameLine();
            ImGui.textColored(ImGuiTheme.RED_R, ImGuiTheme.RED_G, ImGuiTheme.RED_B, 1f,
                    "  BLOCKING");
        }

        ImGui.spacing();
        GuiHelpers.subtleSeparator();
        ImGui.spacing();

        // --- Action Log Table ---
        List<LoggedAction> entries = debugger.getLog();

        int flags = ImGuiTableFlags.Borders | ImGuiTableFlags.RowBg
                | ImGuiTableFlags.SizingStretchProp | ImGuiTableFlags.ScrollY
                | ImGuiTableFlags.Resizable;

        if (ImGui.beginTable("##action_log", 7, flags, 0, ImGui.getContentRegionAvailY())) {
            ImGui.tableSetupColumn("#", 0, 0.4f);
            ImGui.tableSetupColumn("Time", 0, 1.0f);
            ImGui.tableSetupColumn("Action", 0, 1.2f);
            ImGui.tableSetupColumn("Name", 0, 1.5f);
            ImGui.tableSetupColumn("P1", 0, 0.8f);
            ImGui.tableSetupColumn("P2", 0, 0.8f);
            ImGui.tableSetupColumn("P3", 0, 0.8f);
            ImGui.tableSetupScrollFreeze(0, 1);
            ImGui.tableHeadersRow();

            for (int i = 0; i < entries.size(); i++) {
                LoggedAction entry = entries.get(i);
                ImGui.tableNextRow();

                // Color blocked rows red
                if (entry.wasBlocked()) {
                    ImGui.pushStyleColor(ImGuiCol.Text,
                            ImGuiTheme.RED_R, ImGuiTheme.RED_G, ImGuiTheme.RED_B, 0.9f);
                }

                ImGui.tableSetColumnIndex(0);
                ImGui.text(String.valueOf(i + 1));

                ImGui.tableSetColumnIndex(1);
                String time = LocalTime.ofInstant(
                        Instant.ofEpochMilli(entry.timestamp()), ZoneId.systemDefault()
                ).format(TIME_FMT);
                ImGui.text(time);

                ImGui.tableSetColumnIndex(2);
                ImGui.text(String.valueOf(entry.action().actionId()));

                ImGui.tableSetColumnIndex(3);
                String name = ActionTypes.nameOf(entry.action().actionId());
                if (entry.wasBlocked()) {
                    name += " [BLOCKED]";
                }
                ImGui.text(name);

                ImGui.tableSetColumnIndex(4);
                ImGui.text(String.valueOf(entry.action().param1()));

                ImGui.tableSetColumnIndex(5);
                ImGui.text(String.valueOf(entry.action().param2()));

                ImGui.tableSetColumnIndex(6);
                ImGui.text(String.valueOf(entry.action().param3()));

                if (entry.wasBlocked()) {
                    ImGui.popStyleColor();
                }
            }

            // Auto-scroll when new entries appear
            if (entries.size() > lastSize) {
                ImGui.setScrollHereY(1.0f);
                lastSize = entries.size();
            }

            ImGui.endTable();
        }
    }
}
