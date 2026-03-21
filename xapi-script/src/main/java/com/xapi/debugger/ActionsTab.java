package com.xapi.debugger;

import static com.xapi.debugger.XapiData.*;

import com.botwithus.bot.api.inventory.ActionTranslator;
import com.botwithus.bot.api.inventory.ActionTypes;

import imgui.ImGui;
import imgui.flag.ImGuiCol;
import imgui.flag.ImGuiTableFlags;

import java.nio.file.Files;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

final class ActionsTab {

    private final XapiScript script;

    ActionsTab(XapiScript s) {
        this.script = s;
    }

    void render() {
        // Filter bar
        ImGui.text("Filter:");
        ImGui.sameLine();
        ImGui.pushItemWidth(200);
        ImGui.inputText("##action_filter", script.filterText);
        ImGui.popItemWidth();

        ImGui.sameLine(); ImGui.text(" |");
        ImGui.sameLine();
        if (ImGui.checkbox("NPC##f", script.categoryFilters[0])) { script.categoryFilters[0] = !script.categoryFilters[0]; script.settingsDirty = true; }
        ImGui.sameLine();
        if (ImGui.checkbox("Obj##f", script.categoryFilters[1])) { script.categoryFilters[1] = !script.categoryFilters[1]; script.settingsDirty = true; }
        ImGui.sameLine();
        if (ImGui.checkbox("GI##f", script.categoryFilters[2])) { script.categoryFilters[2] = !script.categoryFilters[2]; script.settingsDirty = true; }
        ImGui.sameLine();
        if (ImGui.checkbox("Player##f", script.categoryFilters[3])) { script.categoryFilters[3] = !script.categoryFilters[3]; script.settingsDirty = true; }
        ImGui.sameLine();
        if (ImGui.checkbox("Comp##f", script.categoryFilters[4])) { script.categoryFilters[4] = !script.categoryFilters[4]; script.settingsDirty = true; }
        ImGui.sameLine();
        if (ImGui.checkbox("Walk##f", script.categoryFilters[5])) { script.categoryFilters[5] = !script.categoryFilters[5]; script.settingsDirty = true; }
        ImGui.sameLine();
        if (ImGui.checkbox("Other##f", script.categoryFilters[6])) { script.categoryFilters[6] = !script.categoryFilters[6]; script.settingsDirty = true; }

        ImGui.sameLine(); ImGui.text(" |");
        ImGui.sameLine();
        if (ImGui.smallButton("Copy All")) {
            copyAllActions();
        }

        List<LogEntry> entries = script.actionLog;
        String filter = script.filterText.get().toLowerCase();
        int flags = ImGuiTableFlags.Borders | ImGuiTableFlags.RowBg
                | ImGuiTableFlags.SizingStretchProp | ImGuiTableFlags.ScrollY
                | ImGuiTableFlags.Resizable;

        float tableHeight = ImGui.getContentRegionAvailY();
        if (ImGui.beginTable("##xapi_actions", 11, flags, 0, tableHeight)) {
            ImGui.tableSetupColumn("#", 0, 0.3f);
            ImGui.tableSetupColumn("Time", 0, 0.7f);
            ImGui.tableSetupColumn("Tick", 0, 0.4f);
            ImGui.tableSetupColumn("Action", 0, 0.5f);
            ImGui.tableSetupColumn("Target", 0, 1.5f);
            ImGui.tableSetupColumn("Intent", 0, 1.2f);
            ImGui.tableSetupColumn("Vars", 0, 0.6f);
            ImGui.tableSetupColumn("P1", 0, 0.4f);
            ImGui.tableSetupColumn("P2", 0, 0.4f);
            ImGui.tableSetupColumn("P3", 0, 0.4f);
            ImGui.tableSetupColumn("Code (click to copy)", 0, 3.5f);
            ImGui.tableSetupScrollFreeze(0, 1);
            ImGui.tableHeadersRow();

            int prevTick = -1;
            for (int i = 0; i < entries.size(); i++) {
                LogEntry entry = entries.get(i);
                if (!passesCategory(entry.actionId())) continue;
                if (!filter.isEmpty()) {
                    String actionName = ActionTypes.nameOf(entry.actionId()).toLowerCase();
                    String target = script.buildTargetText(entry).toLowerCase();
                    String params = entry.param1() + " " + entry.param2() + " " + entry.param3();
                    if (!actionName.contains(filter) && !target.contains(filter) && !params.contains(filter)) continue;
                }

                ImGui.tableNextRow();
                if (entry.gameTick() == prevTick && prevTick > 0) {
                    ImGui.tableSetBgColor(1, ImGui.colorConvertFloat4ToU32(0.3f, 0.3f, 0.5f, 0.15f));
                }
                prevTick = entry.gameTick();

                if (entry.wasBlocked()) ImGui.pushStyleColor(ImGuiCol.Text, 0.9f, 0.3f, 0.3f, 0.9f);

                ImGui.tableSetColumnIndex(0);
                ImGui.text(String.valueOf(i + 1));

                ImGui.tableSetColumnIndex(1);
                ImGui.text(LocalTime.ofInstant(Instant.ofEpochMilli(entry.timestamp()), ZoneId.systemDefault()).format(XapiScript.TIME_FMT));

                ImGui.tableSetColumnIndex(2);
                if (i > 0) {
                    int delta = entry.gameTick() - entries.get(i - 1).gameTick();
                    ImGui.text(entry.gameTick() + " (+" + delta + ")");
                } else {
                    ImGui.text(String.valueOf(entry.gameTick()));
                }

                ImGui.tableSetColumnIndex(3);
                ImGui.text(ActionTypes.nameOf(entry.actionId()));

                ImGui.tableSetColumnIndex(4);
                String target = script.buildTargetText(entry);
                ImGui.text(target);
                if (ImGui.isItemHovered() && entry.playerX() > 0) {
                    ImGui.setTooltip(String.format("Player: (%d, %d, %d) anim:%d %s",
                            entry.playerX(), entry.playerY(), entry.playerPlane(),
                            entry.playerAnim(), entry.playerMoving() ? "MOVING" : "idle"));
                }

                // Intent column -- show inferred intent with color-coded confidence
                ImGui.tableSetColumnIndex(5);
                ActionSnapshot snap = i < script.snapshotLog.size() ? script.snapshotLog.get(i) : null;
                if (snap != null && snap.intent() != null) {
                    String conf = snap.intent().confidence();
                    if ("high".equals(conf)) {
                        ImGui.textColored(0.3f, 0.9f, 0.3f, 1f, snap.intent().description());
                    } else if ("medium".equals(conf)) {
                        ImGui.textColored(0.9f, 0.8f, 0.3f, 1f, snap.intent().description());
                    } else {
                        ImGui.textColored(0.6f, 0.6f, 0.6f, 1f, snap.intent().description());
                    }
                    if (ImGui.isItemHovered()) {
                        StringBuilder tip = new StringBuilder();
                        tip.append("Confidence: ").append(conf).append("\n");
                        if (snap.backpack() != null) {
                            tip.append("Backpack: ").append(28 - snap.backpack().freeSlots()).append("/28");
                            if (snap.backpack().full()) tip.append(" (FULL)");
                            tip.append("\n");
                        }
                        if (snap.triggers() != null) {
                            var t = snap.triggers();
                            if (t.inventoryChanged()) tip.append("Trigger: inventory changed\n");
                            if (t.animationEnded()) tip.append("Trigger: animation ended\n");
                            if (t.playerStopped()) tip.append("Trigger: player stopped\n");
                            if (t.varbitChanged()) tip.append("Trigger: varbit changed\n");
                            if (t.recentItemChanges() != null) {
                                for (var ic : t.recentItemChanges()) {
                                    if (ic == null) continue;
                                    int diff = ic.newQty() - ic.oldQty();
                                    tip.append(String.format("  %s %s%d\n", ic.itemName(), diff > 0 ? "+" : "", diff));
                                }
                            }
                        }
                        ImGui.setTooltip(tip.toString().trim());
                    }
                }

                // Vars column -- show var changes on this action's tick
                ImGui.tableSetColumnIndex(6);
                List<VarChange> tickVars = script.varsByTick.get(entry.gameTick());
                if (tickVars != null && !tickVars.isEmpty()) {
                    ImGui.textColored(0.9f, 0.8f, 0.3f, 1f, String.valueOf(tickVars.size()));
                    if (ImGui.isItemHovered()) {
                        StringBuilder tip = new StringBuilder("Var changes on tick " + entry.gameTick() + ":\n");
                        for (VarChange vc : tickVars) {
                            tip.append(String.format("  %s %d: %d -> %d\n", vc.type(), vc.varId(), vc.oldValue(), vc.newValue()));
                        }
                        ImGui.setTooltip(tip.toString().trim());
                    }
                }

                ImGui.tableSetColumnIndex(7); ImGui.text(String.valueOf(entry.param1()));
                ImGui.tableSetColumnIndex(8); ImGui.text(String.valueOf(entry.param2()));
                ImGui.tableSetColumnIndex(9); ImGui.text(String.valueOf(entry.param3()));

                ImGui.tableSetColumnIndex(10);
                renderCodeColumn(entry, i);

                if (entry.wasBlocked()) ImGui.popStyleColor();
            }

            if (script.lastActionSize == -1) {
                script.lastActionSize = entries.size();
            } else if (entries.size() > script.lastActionSize) {
                script.lastActionSize = entries.size();
                ImGui.setScrollHereY(1.0f);
            }
            ImGui.endTable();
        }
    }

    boolean passesCategory(int actionId) {
        if (findSlot(ActionTypes.NPC_OPTIONS, actionId) > 0) return script.categoryFilters[0];
        if (findSlot(ActionTypes.OBJECT_OPTIONS, actionId) > 0) return script.categoryFilters[1];
        if (findSlot(ActionTypes.GROUND_ITEM_OPTIONS, actionId) > 0) return script.categoryFilters[2];
        if (findSlot(ActionTypes.PLAYER_OPTIONS, actionId) > 0) return script.categoryFilters[3];
        if (actionId == ActionTypes.COMPONENT || actionId == ActionTypes.SELECT_COMPONENT_ITEM
                || actionId == ActionTypes.CONTAINER_ACTION) return script.categoryFilters[4];
        if (actionId == ActionTypes.WALK) return script.categoryFilters[5];
        return script.categoryFilters[6];
    }

    private void renderCodeColumn(LogEntry entry, int i) {
        String fullCode = ActionTranslator.toCode(
                entry.actionId(), entry.param1(), entry.param2(), entry.param3(),
                entry.entityName(), entry.optionName());
        int newline = fullCode.indexOf('\n');
        String human = script.buildTargetText(entry);
        boolean hasHuman = !human.isEmpty();

        if (newline > 0) {
            String highLevel = fullCode.substring(0, newline);
            String rawLine = fullCode.substring(newline + 1);
            String allLines = hasHuman ? "// " + human + "\n" + highLevel + "\n" + rawLine : highLevel + "\n" + rawLine;

            if (hasHuman) {
                ImGui.pushStyleColor(ImGuiCol.Text, 1f, 0.8f, 0.3f, 1f);
                if (ImGui.selectable(human + "##hum_" + i)) {
                    ImGui.setClipboardText(human + "\n// " + highLevel + "\n// " + rawLine);
                }
                ImGui.popStyleColor();
                if (ImGui.isItemHovered()) ImGui.setTooltip("Copy all — this line active, others commented");
            }

            String logLine = hasHuman
                    ? "log.info(\"" + escapeJava(human) + "\");\n"
                    : "";

            ImGui.pushStyleColor(ImGuiCol.Text, 0.4f, 0.9f, 0.5f, 1f);
            if (ImGui.selectable(highLevel + "##hi_" + i)) {
                String copied = hasHuman ? "// " + human + "\n" : "";
                ImGui.setClipboardText(copied + logLine + highLevel + "\n// " + rawLine);
            }
            ImGui.popStyleColor();
            if (ImGui.isItemHovered()) ImGui.setTooltip("Copy all — this line active, others commented");

            ImGui.pushStyleColor(ImGuiCol.Text, 0.6f, 0.6f, 0.6f, 0.9f);
            if (ImGui.selectable(rawLine + "##raw_" + i)) {
                String copied = hasHuman ? "// " + human + "\n" : "";
                ImGui.setClipboardText(copied + logLine + "// " + highLevel + "\n" + rawLine);
            }
            ImGui.popStyleColor();
            if (ImGui.isItemHovered()) ImGui.setTooltip("Copy all — this line active, others commented");

            ImGui.pushStyleColor(ImGuiCol.Text, 0.5f, 0.5f, 0.8f, 0.9f);
            if (ImGui.smallButton("Copy All##all_" + i)) ImGui.setClipboardText(allLines);
            ImGui.popStyleColor();
            if (ImGui.isItemHovered()) ImGui.setTooltip("Copy all lines uncommented");
        } else {
            if (ImGui.selectable(fullCode + "##code_" + i)) ImGui.setClipboardText(fullCode);
            if (ImGui.isItemHovered()) ImGui.setTooltip("Click to copy");
        }
    }

    void generateAndCopyScript() {
        List<ActionTranslator.ActionEntry> entries = new ArrayList<>();
        for (int i = 0; i < script.actionLog.size(); i++) {
            LogEntry e = script.actionLog.get(i);
            ActionSnapshot snap = i < script.snapshotLog.size() ? script.snapshotLog.get(i) : null;
            entries.add(new ActionTranslator.ActionEntry(
                    e.actionId(), e.param1(), e.param2(), e.param3(),
                    e.timestamp(), e.entityName(), e.optionName(),
                    snap != null && snap.intent() != null ? snap.intent().description() : null,
                    snap != null && snap.backpack() != null && snap.backpack().full(),
                    snap != null && snap.triggers() != null && snap.triggers().animationEnded(),
                    snap != null ? snap.openInterfaceId() : -1));
        }
        String code = ActionTranslator.generateScript(entries, script.scriptClassName.get(), script.useNamesForGeneration);
        ImGui.setClipboardText(code);
        script.lastExportStatus = "Script copied to clipboard (" + entries.size() + " steps)";
    }

    void scanSessionFiles() {
        try {
            if (Files.exists(XapiScript.SESSION_DIR)) {
                script.sessionFiles = Files.list(XapiScript.SESSION_DIR)
                        .filter(p -> p.toString().endsWith(".json"))
                        .map(p -> p.getFileName().toString())
                        .sorted(Comparator.reverseOrder())
                        .limit(10)
                        .toArray(String[]::new);
            }
        } catch (Exception ignored) {
            script.sessionFiles = new String[0];
        }
    }

    private void copyAllActions() {
        // Collect visible entries respecting filters
        List<LogEntry> visible = new ArrayList<>();
        String filter = script.filterText.get().toLowerCase();
        for (LogEntry e : script.actionLog) {
            if (!passesCategory(e.actionId())) continue;
            if (!filter.isEmpty()) {
                String actionName = ActionTypes.nameOf(e.actionId()).toLowerCase();
                String entity = e.entityName() != null ? e.entityName().toLowerCase() : "";
                String option = e.optionName() != null ? e.optionName().toLowerCase() : "";
                if (!actionName.contains(filter) && !entity.contains(filter) && !option.contains(filter)) continue;
            }
            visible.add(e);
        }
        if (visible.isEmpty()) return;

        // Generate switch cases with delays from recorded timestamps
        StringBuilder sb = new StringBuilder();
        sb.append("switch (step) {\n");
        for (int i = 0; i < visible.size(); i++) {
            LogEntry e = visible.get(i);

            // Delay to next action based on recorded timing
            int delayMs = 600;
            if (i + 1 < visible.size()) {
                long delta = visible.get(i + 1).timestamp() - e.timestamp();
                delayMs = (int) Math.max(300, Math.min(delta, 10000));
            }

            String human = script.buildTargetText(e);
            String fullCode = ActionTranslator.toCode(e.actionId(), e.param1(), e.param2(), e.param3(),
                    e.entityName(), e.optionName());
            // Take the first executable line (high-level if available, otherwise raw)
            int nl = fullCode.indexOf('\n');
            String code;
            if (nl > 0) {
                String highLevel = fullCode.substring(0, nl);
                code = highLevel.startsWith("//") ? fullCode.substring(nl + 1) : highLevel;
            } else {
                code = fullCode;
            }

            sb.append("    case ").append(i).append(" -> {\n");
            String logMsg = !human.isEmpty() ? human : ActionTypes.nameOf(e.actionId());
            sb.append("        log.info(\"[Step ").append(i).append("] ").append(escapeJava(logMsg)).append("\");\n");
            sb.append("        ").append(code).append("\n");
            sb.append("        step++;\n");
            sb.append("        return ").append(delayMs).append(";\n");
            sb.append("    }\n");
        }
        sb.append("    default -> { return -1; }\n");
        sb.append("}\n");
        ImGui.setClipboardText(sb.toString());
    }

    private static int findSlot(int[] options, int actionId) {
        for (int i = 1; i < options.length; i++) {
            if (options[i] == actionId) return i;
        }
        return -1;
    }

    /** Escapes quotes and backslashes for use inside a Java string literal. */
    private static String escapeJava(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
