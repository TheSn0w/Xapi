package com.xapi.debugger;

import static com.xapi.debugger.XapiData.*;

import com.botwithus.bot.api.inventory.ActionTranslator;
import com.botwithus.bot.api.inventory.ActionTypes;

import imgui.ImGui;
import imgui.ImGuiListClipper;
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

    // Column indices (logical)
    static final int COL_NUM = 0, COL_TIME = 1, COL_TICK = 2, COL_ACTION = 3,
            COL_TARGET = 4, COL_INTENT = 5, COL_VARS = 6,
            COL_P1 = 7, COL_P2 = 8, COL_P3 = 9, COL_CODE = 10;
    static final int COL_COUNT = 11;
    static final String[] COL_NAMES = {"#", "Time", "Tick", "Action", "Target", "Intent", "Vars", "P1", "P2", "P3", "Code"};
    static final float[] COL_WEIGHTS = {0.3f, 0.7f, 0.4f, 0.5f, 1.5f, 1.2f, 0.6f, 0.4f, 0.4f, 0.4f, 3.5f};
    // Columns that can be toggled (# , Action, Target, Code are always visible)
    static final boolean[] COL_TOGGLEABLE = {false, true, true, false, false, true, true, true, true, true, false};

    // Compact mode: which columns to hide
    private static final int[] COMPACT_HIDE = {COL_TIME, COL_TICK, COL_INTENT, COL_VARS, COL_P1, COL_P2, COL_P3};
    private boolean[] preCompactState;

    private final XapiScript script;

    // Pre-filtered display indices (rebuilt each frame)
    private final List<Integer> displayIndices = new ArrayList<>();

    ActionsTab(XapiScript s) {
        this.script = s;
    }

    void render() {
        // ── Toolbar row 1: Filter + category checkboxes + Copy All ──
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

        // ── Toolbar row 2: Columns popup + Compact + Auto-scroll ──
        if (ImGui.smallButton("Columns")) {
            ImGui.openPopup("##col_popup");
        }
        if (ImGui.beginPopup("##col_popup")) {
            for (int c = 0; c < COL_COUNT; c++) {
                if (!COL_TOGGLEABLE[c]) continue;
                if (ImGui.checkbox(COL_NAMES[c] + "##cv" + c, script.columnVisible[c])) {
                    script.columnVisible[c] = !script.columnVisible[c];
                    script.settingsDirty = true;
                }
            }
            ImGui.endPopup();
        }

        ImGui.sameLine();
        if (ImGui.smallButton("Compact")) {
            toggleCompact();
        }
        if (ImGui.isItemHovered()) ImGui.setTooltip("Toggle: hide Time, Tick, Intent, Vars, P1-P3");

        ImGui.sameLine();
        if (ImGui.checkbox("Auto-scroll##as", script.autoScroll)) {
            script.autoScroll = !script.autoScroll;
            script.settingsDirty = true;
        }

        // ── Build visible column mapping ──
        int visibleCount = 0;
        int[] logicalToVisible = new int[COL_COUNT]; // logical -> visible index (-1 if hidden)
        int[] visibleToLogical = new int[COL_COUNT]; // visible -> logical index
        for (int c = 0; c < COL_COUNT; c++) {
            if (script.columnVisible[c]) {
                logicalToVisible[c] = visibleCount;
                visibleToLogical[visibleCount] = c;
                visibleCount++;
            } else {
                logicalToVisible[c] = -1;
            }
        }

        // ── Pre-filter entries into display index list ──
        List<LogEntry> entries = script.actionLog;
        String filter = script.filterText.get().toLowerCase();
        displayIndices.clear();
        for (int i = 0; i < entries.size(); i++) {
            LogEntry entry = entries.get(i);
            if (!passesCategory(entry.actionId())) continue;
            if (!filter.isEmpty()) {
                String actionName = ActionTypes.nameOf(entry.actionId()).toLowerCase();
                String target = script.buildTargetText(entry).toLowerCase();
                String params = entry.param1() + " " + entry.param2() + " " + entry.param3();
                if (!actionName.contains(filter) && !target.contains(filter) && !params.contains(filter)) continue;
            }
            displayIndices.add(i);
        }

        // ── Table with clipper ──
        int flags = ImGuiTableFlags.Borders | ImGuiTableFlags.RowBg
                | ImGuiTableFlags.SizingStretchProp | ImGuiTableFlags.ScrollY
                | ImGuiTableFlags.Resizable;

        float tableHeight = ImGui.getContentRegionAvailY();
        if (ImGui.beginTable("##xapi_actions", visibleCount, flags, 0, tableHeight)) {
            for (int v = 0; v < visibleCount; v++) {
                int c = visibleToLogical[v];
                String name = c == COL_CODE ? "Code (click to copy)" : COL_NAMES[c];
                ImGui.tableSetupColumn(name, 0, COL_WEIGHTS[c]);
            }
            ImGui.tableSetupScrollFreeze(0, 1);
            ImGui.tableHeadersRow();

            ImGuiListClipper clipper = new ImGuiListClipper();
            clipper.begin(displayIndices.size());
            int prevTick = -1;
            boolean removedRow = false;
            while (clipper.step()) {
                for (int row = clipper.getDisplayStart(); row < clipper.getDisplayEnd(); row++) {
                    if (removedRow) break;
                    int i = displayIndices.get(row);
                    if (i >= entries.size()) continue; // safety check
                    LogEntry entry = entries.get(i);

                    // Compute prevTick for same-tick highlighting
                    if (row > 0) {
                        int prevIdx = displayIndices.get(row - 1);
                        if (prevIdx < entries.size()) prevTick = entries.get(prevIdx).gameTick();
                    }

                    ImGui.tableNextRow();
                    if (entry.gameTick() == script.selectedActionTick) {
                        ImGui.tableSetBgColor(1, ImGui.colorConvertFloat4ToU32(0.4f, 0.6f, 0.2f, 0.3f));
                    } else if (entry.gameTick() == prevTick && prevTick > 0) {
                        ImGui.tableSetBgColor(1, ImGui.colorConvertFloat4ToU32(0.3f, 0.3f, 0.5f, 0.15f));
                    }

                    if (entry.wasBlocked()) ImGui.pushStyleColor(ImGuiCol.Text, 0.9f, 0.3f, 0.3f, 0.9f);

                    // # column (always visible)
                    ImGui.tableSetColumnIndex(logicalToVisible[COL_NUM]);
                    ImGui.text(String.valueOf(i + 1));
                    // Click row to select tick for cross-tab linking
                    if (ImGui.isItemClicked(0)) {
                        script.selectedActionTick = (script.selectedActionTick == entry.gameTick()) ? -1 : entry.gameTick();
                    }
                    // Rich row tooltip on hover
                    if (ImGui.isItemHovered()) {
                        renderRowTooltip(entry, i);
                    }
                    if (ImGui.beginPopupContextItem("actionCtx_" + i)) {
                        if (ImGui.menuItem("Remove action")) {
                            if (i < script.snapshotLog.size()) script.snapshotLog.remove(i);
                            script.actionLog.remove(i);
                            script.actionsDirty = true;
                            ImGui.endPopup();
                            if (entry.wasBlocked()) ImGui.popStyleColor();
                            removedRow = true;
                            break;
                        }
                        ImGui.endPopup();
                    }

                    // Time
                    if (logicalToVisible[COL_TIME] >= 0) {
                        ImGui.tableSetColumnIndex(logicalToVisible[COL_TIME]);
                        ImGui.text(LocalTime.ofInstant(Instant.ofEpochMilli(entry.timestamp()), ZoneId.systemDefault()).format(XapiScript.TIME_FMT));
                    }

                    // Tick
                    if (logicalToVisible[COL_TICK] >= 0) {
                        ImGui.tableSetColumnIndex(logicalToVisible[COL_TICK]);
                        if (i > 0) {
                            int delta = entry.gameTick() - entries.get(i - 1).gameTick();
                            ImGui.text(entry.gameTick() + " (+" + delta + ")");
                        } else {
                            ImGui.text(String.valueOf(entry.gameTick()));
                        }
                    }

                    // Action (always visible, with wrapping)
                    ImGui.tableSetColumnIndex(logicalToVisible[COL_ACTION]);
                    ImGui.pushTextWrapPos(ImGui.getCursorPosX() + ImGui.getColumnWidth());
                    ImGui.textWrapped(ActionTypes.nameOf(entry.actionId()));
                    ImGui.popTextWrapPos();

                    // Target (always visible, with wrapping)
                    ImGui.tableSetColumnIndex(logicalToVisible[COL_TARGET]);
                    String target = script.buildTargetText(entry);
                    ImGui.pushTextWrapPos(ImGui.getCursorPosX() + ImGui.getColumnWidth());
                    ImGui.textWrapped(target);
                    ImGui.popTextWrapPos();
                    if (ImGui.isItemHovered() && entry.playerX() > 0) {
                        ImGui.setTooltip(String.format("Player: (%d, %d, %d) anim:%d %s",
                                entry.playerX(), entry.playerY(), entry.playerPlane(),
                                entry.playerAnim(), entry.playerMoving() ? "MOVING" : "idle"));
                    }

                    // Intent
                    if (logicalToVisible[COL_INTENT] >= 0) {
                        ImGui.tableSetColumnIndex(logicalToVisible[COL_INTENT]);
                        renderIntentCell(entry, i);
                    }

                    // Vars
                    if (logicalToVisible[COL_VARS] >= 0) {
                        ImGui.tableSetColumnIndex(logicalToVisible[COL_VARS]);
                        renderVarsCell(entry);
                    }

                    // P1, P2, P3
                    if (logicalToVisible[COL_P1] >= 0) {
                        ImGui.tableSetColumnIndex(logicalToVisible[COL_P1]);
                        ImGui.text(String.valueOf(entry.param1()));
                    }
                    if (logicalToVisible[COL_P2] >= 0) {
                        ImGui.tableSetColumnIndex(logicalToVisible[COL_P2]);
                        ImGui.text(String.valueOf(entry.param2()));
                    }
                    if (logicalToVisible[COL_P3] >= 0) {
                        ImGui.tableSetColumnIndex(logicalToVisible[COL_P3]);
                        ImGui.text(String.valueOf(entry.param3()));
                    }

                    // Code (always visible)
                    ImGui.tableSetColumnIndex(logicalToVisible[COL_CODE]);
                    renderCodeColumn(entry, i);

                    if (entry.wasBlocked()) ImGui.popStyleColor();
                }
            }
            clipper.end();

            // Auto-scroll
            if (script.autoScroll) {
                if (script.lastActionSize == -1) {
                    script.lastActionSize = entries.size();
                } else if (entries.size() > script.lastActionSize) {
                    script.lastActionSize = entries.size();
                    ImGui.setScrollHereY(1.0f);
                }
            } else {
                script.lastActionSize = entries.size();
            }
            ImGui.endTable();
        }
    }

    private void toggleCompact() {
        // Check if currently in compact mode (all compact columns hidden)
        boolean isCompact = true;
        for (int c : COMPACT_HIDE) {
            if (script.columnVisible[c]) { isCompact = false; break; }
        }
        if (isCompact && preCompactState != null) {
            // Restore previous state
            System.arraycopy(preCompactState, 0, script.columnVisible, 0, COL_COUNT);
            preCompactState = null;
        } else {
            // Save current state and apply compact
            preCompactState = script.columnVisible.clone();
            for (int c : COMPACT_HIDE) {
                script.columnVisible[c] = false;
            }
        }
        script.settingsDirty = true;
    }

    private void renderRowTooltip(LogEntry entry, int i) {
        StringBuilder tip = new StringBuilder();
        tip.append("Right-click to remove\n\n");

        String actionName = ActionTypes.nameOf(entry.actionId());
        String target = script.buildTargetText(entry);
        tip.append("Action: ").append(actionName);
        if (!target.isEmpty()) tip.append(" | Target: ").append(target);
        tip.append("\n");

        tip.append(String.format("Params: p1=%d, p2=%d, p3=%d\n", entry.param1(), entry.param2(), entry.param3()));

        if (entry.playerX() > 0) {
            tip.append(String.format("Position: (%d, %d, %d) anim:%d %s\n",
                    entry.playerX(), entry.playerY(), entry.playerPlane(),
                    entry.playerAnim(), entry.playerMoving() ? "MOVING" : "idle"));
        }

        ActionSnapshot snap = i < script.snapshotLog.size() ? script.snapshotLog.get(i) : null;
        if (snap != null && snap.intent() != null) {
            tip.append("Intent: ").append(snap.intent().description())
                    .append(" (").append(snap.intent().confidence()).append(")\n");
        }

        List<VarChange> tickVars = script.varsByTick.get(entry.gameTick());
        if (tickVars != null && !tickVars.isEmpty()) {
            tip.append("Vars: ");
            for (VarChange vc : tickVars) {
                tip.append(String.format("%s %d: %d->%d  ", vc.type(), vc.varId(), vc.oldValue(), vc.newValue()));
            }
            tip.append("\n");
        }

        String code = ActionTranslator.toCode(entry.actionId(), entry.param1(), entry.param2(), entry.param3(),
                entry.entityName(), entry.optionName());
        tip.append("Code: ").append(code.replace('\n', ' '));

        ImGui.setTooltip(tip.toString().trim());
    }

    private void renderIntentCell(LogEntry entry, int i) {
        ActionSnapshot snap = i < script.snapshotLog.size() ? script.snapshotLog.get(i) : null;
        if (snap != null && snap.intent() != null) {
            String conf = snap.intent().confidence();
            float r, g, b;
            if ("high".equals(conf)) { r = 0.3f; g = 0.9f; b = 0.3f; }
            else if ("medium".equals(conf)) { r = 0.9f; g = 0.8f; b = 0.3f; }
            else { r = 0.6f; g = 0.6f; b = 0.6f; }

            ImGui.pushStyleColor(ImGuiCol.Text, r, g, b, 1f);
            ImGui.pushTextWrapPos(ImGui.getCursorPosX() + ImGui.getColumnWidth());
            ImGui.textWrapped(snap.intent().description());
            ImGui.popTextWrapPos();
            ImGui.popStyleColor();

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
    }

    private void renderVarsCell(LogEntry entry) {
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
    }

    boolean passesCategory(int actionId) {
        if (findSlot(ActionTypes.NPC_OPTIONS, actionId) > 0) return script.categoryFilters[0];
        if (findSlot(ActionTypes.OBJECT_OPTIONS, actionId) > 0) return script.categoryFilters[1];
        if (findSlot(ActionTypes.GROUND_ITEM_OPTIONS, actionId) > 0) return script.categoryFilters[2];
        if (findSlot(ActionTypes.PLAYER_OPTIONS, actionId) > 0) return script.categoryFilters[3];
        if (actionId == ActionTypes.COMPONENT || actionId == ActionTypes.SELECT_COMPONENT_ITEM
                || actionId == ActionTypes.CONTAINER_ACTION
                || actionId == ActionTypes.COMP_ON_PLAYER) return script.categoryFilters[4];
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

        ImGui.pushTextWrapPos(ImGui.getCursorPosX() + ImGui.getColumnWidth());

        if (newline > 0) {
            String highLevel = fullCode.substring(0, newline);
            String rawLine = fullCode.substring(newline + 1);
            String allLines = hasHuman ? "// " + human + "\n" + highLevel + "\n" + rawLine : highLevel + "\n" + rawLine;

            if (hasHuman) {
                ImGui.pushStyleColor(ImGuiCol.Text, 1f, 0.8f, 0.3f, 1f);
                ImGui.textWrapped(human);
                ImGui.popStyleColor();
                if (ImGui.isItemHovered()) ImGui.setTooltip("Click to copy all — this line active, others commented");
                if (ImGui.isItemClicked(0)) {
                    ImGui.setClipboardText(human + "\n// " + highLevel + "\n// " + rawLine);
                }
            }

            String logLine = hasHuman
                    ? "log.info(\"" + escapeJava(human) + "\");\n"
                    : "";

            ImGui.pushStyleColor(ImGuiCol.Text, 0.4f, 0.9f, 0.5f, 1f);
            ImGui.textWrapped(highLevel);
            ImGui.popStyleColor();
            if (ImGui.isItemHovered()) ImGui.setTooltip("Click to copy all — this line active, others commented");
            if (ImGui.isItemClicked(0)) {
                String copied = hasHuman ? "// " + human + "\n" : "";
                ImGui.setClipboardText(copied + logLine + highLevel + "\n// " + rawLine);
            }

            ImGui.pushStyleColor(ImGuiCol.Text, 0.6f, 0.6f, 0.6f, 0.9f);
            ImGui.textWrapped(rawLine);
            ImGui.popStyleColor();
            if (ImGui.isItemHovered()) ImGui.setTooltip("Click to copy all — this line active, others commented");
            if (ImGui.isItemClicked(0)) {
                String copied = hasHuman ? "// " + human + "\n" : "";
                ImGui.setClipboardText(copied + logLine + "// " + highLevel + "\n" + rawLine);
            }

            ImGui.pushStyleColor(ImGuiCol.Text, 0.5f, 0.5f, 0.8f, 0.9f);
            if (ImGui.smallButton("Copy All##all_" + i)) ImGui.setClipboardText(allLines);
            ImGui.popStyleColor();
            if (ImGui.isItemHovered()) ImGui.setTooltip("Copy all lines uncommented");
        } else {
            ImGui.textWrapped(fullCode);
            if (ImGui.isItemHovered()) ImGui.setTooltip("Click to copy");
            if (ImGui.isItemClicked(0)) ImGui.setClipboardText(fullCode);
        }

        ImGui.popTextWrapPos();
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
        return ActionTypes.findSlot(options, actionId);
    }

    /** Escapes quotes and backslashes for use inside a Java string literal. */
    private static String escapeJava(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
