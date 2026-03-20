package com.xapi.debugger;

import static com.xapi.debugger.XapiData.*;

import imgui.ImGui;
import imgui.flag.ImGuiCol;
import imgui.flag.ImGuiTableFlags;

import java.time.Instant;
import java.time.ZoneId;
import java.util.List;

final class InventoryTab {

    private final XapiScript script;
    private int lastLogSize = -1;

    InventoryTab(XapiScript s) {
        this.script = s;
    }

    void render() {
        List<InventoryChange> log = script.inventoryLog;

        if (ImGui.smallButton("Clear##inv_clear")) {
            script.inventoryLog.clear();
            lastLogSize = -1;
        }
        ImGui.sameLine();
        ImGui.text("Changes: " + log.size());

        if (log.isEmpty()) {
            ImGui.text("No inventory changes recorded yet.");
            return;
        }

        int flags = ImGuiTableFlags.Borders | ImGuiTableFlags.RowBg
                | ImGuiTableFlags.SizingStretchProp | ImGuiTableFlags.ScrollY | ImGuiTableFlags.Resizable;
        float tableHeight = ImGui.getContentRegionAvailY();
        if (tableHeight < 50) tableHeight = 50;
        if (ImGui.beginTable("##inv_changes", 7, flags, 0, tableHeight)) {
            ImGui.tableSetupColumn("Time", 0, 0.7f);
            ImGui.tableSetupColumn("Tick", 0, 0.4f);
            ImGui.tableSetupColumn("Item", 0, 1.2f);
            ImGui.tableSetupColumn("ItemId", 0, 0.4f);
            ImGui.tableSetupColumn("Old", 0, 0.4f);
            ImGui.tableSetupColumn("New", 0, 0.4f);
            ImGui.tableSetupColumn("Delta", 0, 0.5f);
            ImGui.tableSetupScrollFreeze(0, 1);
            ImGui.tableHeadersRow();

            for (int i = 0; i < log.size(); i++) {
                InventoryChange ic = log.get(i);
                int delta = ic.newQty() - ic.oldQty();

                ImGui.tableNextRow();

                ImGui.tableSetColumnIndex(0);
                ImGui.text(Instant.ofEpochMilli(ic.timestamp())
                        .atZone(ZoneId.systemDefault()).format(XapiScript.TIME_FMT));

                ImGui.tableSetColumnIndex(1);
                ImGui.text(String.valueOf(ic.gameTick()));

                ImGui.tableSetColumnIndex(2);
                ImGui.text(ic.itemName());

                ImGui.tableSetColumnIndex(3);
                ImGui.textColored(0.6f, 0.6f, 0.6f, 1f, String.valueOf(ic.itemId()));

                ImGui.tableSetColumnIndex(4);
                ImGui.text(String.valueOf(ic.oldQty()));

                ImGui.tableSetColumnIndex(5);
                if (delta > 0) {
                    ImGui.textColored(0.3f, 0.9f, 0.3f, 1f, String.valueOf(ic.newQty()));
                } else if (delta < 0) {
                    ImGui.textColored(0.9f, 0.3f, 0.3f, 1f, String.valueOf(ic.newQty()));
                } else {
                    ImGui.text(String.valueOf(ic.newQty()));
                }

                ImGui.tableSetColumnIndex(6);
                String deltaStr = delta > 0 ? "+" + delta : String.valueOf(delta);
                if (delta > 0) {
                    ImGui.textColored(0.3f, 0.9f, 0.3f, 1f, deltaStr);
                } else if (delta < 0) {
                    ImGui.textColored(0.9f, 0.3f, 0.3f, 1f, deltaStr);
                } else {
                    ImGui.text(deltaStr);
                }
            }

            // Auto-scroll to bottom on new entries
            if (lastLogSize == -1) {
                lastLogSize = log.size();
            } else if (log.size() > lastLogSize) {
                lastLogSize = log.size();
                ImGui.setScrollHereY(1.0f);
            }
            ImGui.endTable();
        }
    }
}
