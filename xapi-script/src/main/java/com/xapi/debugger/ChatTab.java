package com.xapi.debugger;

import static com.xapi.debugger.XapiData.*;

import imgui.ImGui;
import imgui.flag.ImGuiCol;
import imgui.flag.ImGuiTableFlags;

import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.List;

final class ChatTab {

    private final XapiScript script;

    ChatTab(XapiScript s) {
        this.script = s;
    }

    void render() {
        ImGui.text("Filter:");
        ImGui.sameLine();
        ImGui.pushItemWidth(200);
        ImGui.inputText("##chat_filter", script.chatFilterText);
        ImGui.popItemWidth();
        ImGui.sameLine();
        if (ImGui.button("Clear Chat")) { script.chatLog.clear(); script.lastChatSize = -1; }

        List<ChatEntry> chats = script.chatLog;
        String filter = script.chatFilterText.get().toLowerCase();
        int flags = ImGuiTableFlags.Borders | ImGuiTableFlags.RowBg
                | ImGuiTableFlags.SizingStretchProp | ImGuiTableFlags.ScrollY | ImGuiTableFlags.Resizable;
        float tableHeight = ImGui.getContentRegionAvailY();
        if (ImGui.beginTable("##xapi_chat", 5, flags, 0, tableHeight)) {
            ImGui.tableSetupColumn("#", 0, 0.3f);
            ImGui.tableSetupColumn("Time", 0, 0.7f);
            ImGui.tableSetupColumn("Tick", 0, 0.4f);
            ImGui.tableSetupColumn("Type", 0, 0.3f);
            ImGui.tableSetupColumn("Message", 0, 4f);
            ImGui.tableSetupScrollFreeze(0, 1);
            ImGui.tableHeadersRow();

            int displayed = 0;
            for (int i = 0; i < chats.size(); i++) {
                ChatEntry ce = chats.get(i);
                if (!filter.isEmpty()) {
                    String text = (ce.text() != null ? ce.text() : "").toLowerCase();
                    String player = (ce.playerName() != null ? ce.playerName() : "").toLowerCase();
                    if (!text.contains(filter) && !player.contains(filter)) continue;
                }
                displayed++;
                ImGui.tableNextRow();
                if (script.hasActionOnTick(ce.gameTick())) ImGui.tableSetBgColor(1, ImGui.colorConvertFloat4ToU32(0.4f, 0.4f, 0.2f, 0.2f));

                ImGui.tableSetColumnIndex(0); ImGui.text(String.valueOf(displayed));
                ImGui.tableSetColumnIndex(1); ImGui.text(LocalTime.ofInstant(Instant.ofEpochMilli(ce.timestamp()), ZoneId.systemDefault()).format(XapiScript.TIME_FMT));
                ImGui.tableSetColumnIndex(2); ImGui.text(String.valueOf(ce.gameTick()));
                ImGui.tableSetColumnIndex(3); ImGui.text(String.valueOf(ce.messageType()));
                ImGui.tableSetColumnIndex(4);
                String msgText = ce.playerName() != null && !ce.playerName().isEmpty()
                        ? ce.playerName() + ": " + ce.text() : ce.text() != null ? ce.text() : "";
                ImGui.textColored(0.5f, 0.9f, 1f, 1f, msgText);
                if (ImGui.isItemHovered() && ImGui.isMouseClicked(0)) ImGui.setClipboardText(msgText);
                if (ImGui.isItemHovered()) ImGui.setTooltip("Click to copy");
            }

            if (script.lastChatSize == -1) {
                script.lastChatSize = chats.size();
            } else if (chats.size() > script.lastChatSize) {
                script.lastChatSize = chats.size();
                ImGui.setScrollHereY(1.0f);
            }
            ImGui.endTable();
        }
    }
}
