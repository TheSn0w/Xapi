package com.xapi.debugger;

import static com.xapi.debugger.XapiData.*;

import com.botwithus.bot.api.model.LocalPlayer;
import com.botwithus.bot.api.model.PlayerStat;

import imgui.ImGui;
import imgui.flag.ImGuiCol;
import imgui.flag.ImGuiTableFlags;

import java.util.List;

final class PlayerTab {

    private final XapiScript script;

    PlayerTab(XapiScript s) {
        this.script = s;
    }

    void render() {
        LocalPlayer lp = script.localPlayerData;
        if (lp == null) {
            ImGui.text("Waiting for player data...");
            return;
        }

        // -- Player Info Section --
        ImGui.textColored(1f, 0.8f, 0.2f, 1f, "Player Info");
        ImGui.separator();

        ImGui.text("Name: " + (lp.name() != null ? lp.name() : "Unknown"));
        ImGui.sameLine(300);
        ImGui.text("Combat Level: " + lp.combatLevel());

        ImGui.text("Position: (" + lp.tileX() + ", " + lp.tileY() + ", " + lp.plane() + ")");
        ImGui.sameLine(300);
        ImGui.text("Moving: " + (lp.isMoving() ? "Yes" : "No"));

        ImGui.text("Animation: " + lp.animationId());
        ImGui.sameLine(300);
        ImGui.text("Stance: " + lp.stanceId());

        ImGui.text("Health: " + lp.health() + " / " + lp.maxHealth());
        ImGui.sameLine(300);
        if (lp.maxHealth() > 0) {
            float pct = (float) lp.health() / lp.maxHealth();
            ImGui.pushStyleColor(ImGuiCol.PlotHistogram, pct > 0.5f ? 0xFF00CC00 : pct > 0.25f ? 0xFF00CCCC : 0xFF0000CC);
            ImGui.progressBar(pct, 150, 14, lp.health() + "/" + lp.maxHealth());
            ImGui.popStyleColor();
        }

        if (lp.targetIndex() >= 0) {
            ImGui.text("Target: index " + lp.targetIndex() + " (type: " + lp.targetType() + ")");
        } else {
            ImGui.text("Target: None");
        }

        if (lp.overheadText() != null && !lp.overheadText().isEmpty()) {
            ImGui.text("Overhead: " + lp.overheadText());
        }

        ImGui.text("Member: " + (lp.isMember() ? "Yes" : "No"));
        ImGui.sameLine(300);
        ImGui.text("Server Index: " + lp.serverIndex());

        ImGui.spacing();
        ImGui.spacing();

        // -- Skills Section --
        ImGui.textColored(1f, 0.8f, 0.2f, 1f, "Skills");
        ImGui.separator();

        List<PlayerStat> stats = script.playerStats;
        if (stats == null || stats.isEmpty()) {
            ImGui.text("No skill data available.");
            return;
        }

        int tFlags = ImGuiTableFlags.Borders | ImGuiTableFlags.RowBg
                | ImGuiTableFlags.SizingStretchProp | ImGuiTableFlags.ScrollY | ImGuiTableFlags.Resizable;
        float tableHeight = ImGui.getContentRegionAvailY();
        if (ImGui.beginTable("##skills_table", 6, tFlags, 0, tableHeight)) {
            ImGui.tableSetupColumn("Skill", 0, 1.2f);
            ImGui.tableSetupColumn("Level", 0, 0.5f);
            ImGui.tableSetupColumn("Boosted", 0, 0.5f);
            ImGui.tableSetupColumn("XP", 0, 1f);
            ImGui.tableSetupColumn("XP to Next", 0, 0.8f);
            ImGui.tableSetupColumn("Max", 0, 0.4f);
            ImGui.tableSetupScrollFreeze(0, 1);
            ImGui.tableHeadersRow();

            long totalXp = 0;
            int totalLevel = 0;

            for (PlayerStat stat : stats) {
                String skillName = stat.skillId() < SKILL_NAMES.length ? SKILL_NAMES[stat.skillId()] : "Skill " + stat.skillId();
                int xpToNext = XapiData.xpToNextLevel(stat.xp(), stat.level(), stat.maxLevel());

                totalXp += stat.xp();
                totalLevel += stat.level();

                ImGui.tableNextRow();
                ImGui.tableSetColumnIndex(0); ImGui.text(skillName);
                ImGui.tableSetColumnIndex(1); ImGui.text(String.valueOf(stat.level()));
                ImGui.tableSetColumnIndex(2);
                if (stat.boostedLevel() != stat.level()) {
                    if (stat.boostedLevel() > stat.level()) {
                        ImGui.textColored(0.2f, 1f, 0.2f, 1f, String.valueOf(stat.boostedLevel()));
                    } else {
                        ImGui.textColored(1f, 0.3f, 0.3f, 1f, String.valueOf(stat.boostedLevel()));
                    }
                } else {
                    ImGui.text(String.valueOf(stat.boostedLevel()));
                }
                ImGui.tableSetColumnIndex(3); ImGui.text(String.format("%,d", stat.xp()));
                ImGui.tableSetColumnIndex(4);
                if (xpToNext > 0) {
                    ImGui.text(String.format("%,d", xpToNext));
                } else {
                    ImGui.textColored(0.4f, 0.9f, 0.4f, 1f, "MAX");
                }
                ImGui.tableSetColumnIndex(5); ImGui.text(String.valueOf(stat.maxLevel()));
            }

            // Total row
            ImGui.tableNextRow();
            ImGui.tableSetColumnIndex(0);
            ImGui.textColored(1f, 0.8f, 0.2f, 1f, "TOTAL");
            ImGui.tableSetColumnIndex(1);
            ImGui.textColored(1f, 0.8f, 0.2f, 1f, String.valueOf(totalLevel));
            ImGui.tableSetColumnIndex(3);
            ImGui.textColored(1f, 0.8f, 0.2f, 1f, String.format("%,d", totalXp));

            ImGui.endTable();
        }
    }
}
