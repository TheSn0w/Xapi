package com.xapi.debugger;

import static com.xapi.debugger.XapiData.*;

import com.botwithus.bot.api.model.ItemVar;

import imgui.ImGui;
import imgui.ImGuiListClipper;
import imgui.flag.ImGuiCol;
import imgui.flag.ImGuiHoveredFlags;
import imgui.flag.ImGuiTableFlags;

import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

final class VariablesTab {

    private final XapiScript script;
    private final List<Integer> displayIndices = new ArrayList<>();

    VariablesTab(XapiScript s) {
        this.script = s;
    }

    void render() {
        // Type filter checkboxes
        boolean wSVB = script.showVarbits;
        if (ImGui.checkbox("Varbits##vf", wSVB)) { script.showVarbits = !wSVB; script.settingsDirty = true; }
        ImGui.sameLine();
        boolean wSVP = script.showVarps;
        if (ImGui.checkbox("Varps##vf", wSVP)) { script.showVarps = !wSVP; script.settingsDirty = true; }
        ImGui.sameLine();
        boolean itemVarDisabled = Boolean.FALSE.equals(script.itemVarSystemAvailable);
        if (itemVarDisabled) ImGui.beginDisabled();
        boolean wSIV = script.showItemVarbits;
        if (ImGui.checkbox("ItemVar##vf", wSIV)) {
            script.showItemVarbits = !wSIV;
            script.settingsDirty = true;
            if (!wSIV) { // toggling ON — reset error count
                script.itemVarErrorLogCount = 0;
            }
        }
        if (itemVarDisabled && ImGui.isItemHovered(ImGuiHoveredFlags.AllowWhenDisabled)) {
            ImGui.pushStyleColor(ImGuiCol.Text, 1.0f, 0.2f, 0.2f, 1.0f);
            ImGui.beginTooltip();
            ImGui.setWindowFontScale(1.2f);
            ImGui.text("This account does not qualify for item vars yet");
            ImGui.setWindowFontScale(1.0f);
            ImGui.endTooltip();
            ImGui.popStyleColor();
        }
        if (itemVarDisabled) ImGui.endDisabled();
        ImGui.sameLine();
        if (ImGui.button("Re-check##itemvar")) {
            script.resetItemVarProbe();
        }
        if (ImGui.isItemHovered()) {
            if (itemVarDisabled) {
                ImGui.pushStyleColor(ImGuiCol.Text, 1.0f, 0.2f, 0.2f, 1.0f);
                ImGui.beginTooltip();
                ImGui.setWindowFontScale(1.2f);
                ImGui.text("This account does not qualify for item vars yet");
                ImGui.setWindowFontScale(1.0f);
                ImGui.endTooltip();
                ImGui.popStyleColor();
            } else {
                ImGui.setTooltip("Re-probe item var system (use after obtaining a varbit item)");
            }
        }
        ImGui.sameLine();
        ImGui.text("|");
        ImGui.sameLine();
        if (ImGui.button("Clear Vars")) { script.varLog.clear(); script.varsByTick.clear(); script.lastVarSize = -1; }

        // Watchlist filter
        ImGui.text("Filter:");
        ImGui.sameLine();
        ImGui.pushItemWidth(200);
        ImGui.inputText("##var_filter", script.varFilterText);
        ImGui.popItemWidth();

        // -- Item Varbits Section (equipped items) --
        renderItemVarbits();

        List<VarChange> vars = script.varLog;
        int[] watchIds = parseWatchIds(script.varFilterText.get().trim());

        // -- Pinned Variables Section --
        List<String> pinnedList = new ArrayList<>(script.pinnedVars);
        if (!pinnedList.isEmpty()) {
            ImGui.textColored(0.9f, 0.8f, 0.3f, 1f, "Pinned Variables:");
            String toUnpin = null;
            for (String key : pinnedList) {
                String[] parts = key.split(":");
                if (parts.length != 2) continue;
                String type = parts[0];
                String idStr = parts[1];
                Integer currentVal = script.pinnedCurrentValues.get(key);
                String annot = script.varAnnotations.get(key);
                Integer freq = script.varChangeCount.get(key);
                String varCode = getVarCode(type, Integer.parseInt(idStr));

                ImGui.pushID("pin_" + key);
                if (ImGui.smallButton("x")) {
                    toUnpin = key;
                    script.settingsDirty = true;
                }
                ImGui.sameLine();
                // Type colored
                if ("varbit".equals(type)) ImGui.textColored(0.4f, 0.8f, 1f, 1f, type);
                else if ("varp".equals(type)) ImGui.textColored(1f, 0.7f, 0.4f, 1f, type);
                else if ("itemvar".equals(type)) ImGui.textColored(0.9f, 0.6f, 0.9f, 1f, type);
                else ImGui.textColored(0.6f, 0.6f, 0.6f, 1f, type);
                ImGui.sameLine();
                ImGui.text(idStr);
                ImGui.sameLine();
                ImGui.text("=");
                ImGui.sameLine();
                if (currentVal != null) {
                    ImGui.textColored(0.3f, 0.9f, 0.3f, 1f, String.valueOf(currentVal));
                } else {
                    ImGui.textColored(0.5f, 0.5f, 0.5f, 0.8f, "...");
                }
                if (annot != null && !annot.isEmpty()) {
                    ImGui.sameLine();
                    ImGui.textColored(0.7f, 0.9f, 0.7f, 1f, "(" + annot + ")");
                }
                if (freq != null && freq > 0) {
                    ImGui.sameLine();
                    ImGui.textColored(0.6f, 0.6f, 0.6f, 0.8f, "x" + freq);
                }
                ImGui.sameLine();
                ImGui.pushStyleColor(ImGuiCol.Text, 0.4f, 0.9f, 0.5f, 1f);
                if (ImGui.smallButton("copy")) ImGui.setClipboardText(varCode);
                ImGui.popStyleColor();
                if (ImGui.isItemHovered()) ImGui.setTooltip(varCode);
                ImGui.sameLine();
                if (ImGui.smallButton("label")) {
                    script.editingAnnotationKey = key;
                    script.annotationInput.set(annot != null ? annot : "");
                }
                ImGui.popID();
            }
            if (toUnpin != null) script.pinnedVars.remove(toUnpin);

            // Annotation editing inline
            if (script.editingAnnotationKey != null) {
                ImGui.text("Label for " + script.editingAnnotationKey + ":");
                ImGui.sameLine();
                ImGui.pushItemWidth(200);
                ImGui.inputText("##annot_edit", script.annotationInput);
                ImGui.popItemWidth();
                ImGui.sameLine();
                if (ImGui.button("Save##annot_save")) {
                    String val = script.annotationInput.get().trim();
                    if (val.isEmpty()) {
                        script.varAnnotations.remove(script.editingAnnotationKey);
                    } else {
                        script.varAnnotations.put(script.editingAnnotationKey, val);
                    }
                    script.editingAnnotationKey = null;
                    script.settingsDirty = true;
                }
                ImGui.sameLine();
                if (ImGui.button("Cancel##annot_cancel")) {
                    script.editingAnnotationKey = null;
                }
            }
            ImGui.separator();
        }

        // -- Pre-filter var log into display indices --
        displayIndices.clear();
        for (int i = 0; i < vars.size(); i++) {
            VarChange vc = vars.get(i);
            if ("varbit".equals(vc.type()) && !script.showVarbits) continue;
            if ("varp".equals(vc.type()) && !script.showVarps) continue;
            if ("varc".equals(vc.type()) || "varcstr".equals(vc.type())) continue;
            if ("itemvar".equals(vc.type()) && !script.showItemVarbits) continue;
            if (watchIds.length > 0 && !inWatchlist(vc.varId(), watchIds)) continue;
            displayIndices.add(i);
        }

        // -- Main Var Change Log --
        int flags = ImGuiTableFlags.Borders | ImGuiTableFlags.RowBg
                | ImGuiTableFlags.SizingStretchProp | ImGuiTableFlags.ScrollY | ImGuiTableFlags.Resizable;
        float tableHeight = ImGui.getContentRegionAvailY();
        if (tableHeight < 50) tableHeight = 50;
        if (ImGui.beginTable("##xapi_vars", 9, flags, 0, tableHeight)) {
            ImGui.tableSetupColumn("", 0, 0.15f);
            ImGui.tableSetupColumn("#", 0, 0.25f);
            ImGui.tableSetupColumn("Time", 0, 0.7f);
            ImGui.tableSetupColumn("Tick", 0, 0.4f);
            ImGui.tableSetupColumn("Type", 0, 0.4f);
            ImGui.tableSetupColumn("VarID", 0, 0.5f);
            ImGui.tableSetupColumn("Old", 0, 0.5f);
            ImGui.tableSetupColumn("New", 0, 0.5f);
            ImGui.tableSetupColumn("Delta", 0, 0.5f);
            ImGui.tableSetupScrollFreeze(0, 1);
            ImGui.tableHeadersRow();

            ImGuiListClipper clipper = new ImGuiListClipper();
            clipper.begin(displayIndices.size());
            while (clipper.step()) {
                for (int row = clipper.getDisplayStart(); row < clipper.getDisplayEnd(); row++) {
                    int i = displayIndices.get(row);
                    if (i >= vars.size()) continue;
                    VarChange vc = vars.get(i);

                    ImGui.tableNextRow();

                    if (vc.gameTick() == script.selectedActionTick) {
                        ImGui.tableSetBgColor(1, ImGui.colorConvertFloat4ToU32(0.4f, 0.6f, 0.2f, 0.3f));
                    } else if (script.hasActionOnTick(vc.gameTick())) {
                        ImGui.tableSetBgColor(1, ImGui.colorConvertFloat4ToU32(0.4f, 0.4f, 0.2f, 0.2f));
                    }

                    // Pin button
                    ImGui.tableSetColumnIndex(0);
                    String pinKey = vc.type() + ":" + vc.varId();
                    boolean isPinned = script.pinnedVars.contains(pinKey);
                    if (isPinned) {
                        ImGui.pushStyleColor(ImGuiCol.Text, 0.9f, 0.8f, 0.3f, 1f);
                        if (ImGui.smallButton("*##pin_" + i)) { script.pinnedVars.remove(pinKey); script.settingsDirty = true; }
                        ImGui.popStyleColor();
                    } else {
                        if (ImGui.smallButton("+##pin_" + i)) { script.pinnedVars.add(pinKey); script.settingsDirty = true; }
                    }

                    ImGui.tableSetColumnIndex(1); ImGui.text(String.valueOf(row + 1));
                    ImGui.tableSetColumnIndex(2); ImGui.text(LocalTime.ofInstant(Instant.ofEpochMilli(vc.timestamp()), ZoneId.systemDefault()).format(XapiScript.TIME_FMT));
                    ImGui.tableSetColumnIndex(3); ImGui.text(String.valueOf(vc.gameTick()));

                    ImGui.tableSetColumnIndex(4);
                    if ("varbit".equals(vc.type())) ImGui.textColored(0.4f, 0.8f, 1f, 1f, vc.type());
                    else if ("varp".equals(vc.type())) ImGui.textColored(1f, 0.7f, 0.4f, 1f, vc.type());
                    else if ("itemvar".equals(vc.type())) ImGui.textColored(0.9f, 0.6f, 0.9f, 1f, vc.type());
                    else ImGui.textColored(0.6f, 0.6f, 0.6f, 1f, vc.type());

                    ImGui.tableSetColumnIndex(5);
                    String varCode = getVarCode(vc.type(), vc.varId());
                    String varIdDisplay;
                    if ("itemvar".equals(vc.type())) {
                        int slot = vc.varId() / 100000;
                        int itemVarId = vc.varId() % 100000;
                        varIdDisplay = "S" + slot + ":" + itemVarId;
                    } else {
                        varIdDisplay = String.valueOf(vc.varId());
                    }
                    ImGui.pushStyleColor(ImGuiCol.Text, 0.4f, 0.9f, 0.5f, 1f);
                    if (ImGui.selectable(varIdDisplay + "##var_" + i)) ImGui.setClipboardText(varCode);
                    ImGui.popStyleColor();
                    if (ImGui.isItemHovered()) {
                        if ("itemvar".equals(vc.type())) {
                            int slot = vc.varId() / 100000;
                            int itemVarId = vc.varId() % 100000;
                            ImGui.setTooltip("Click to copy: " + varCode + "\nSlot: " + slot + " | Varbit: " + itemVarId);
                        } else {
                            renderVarHistoryTooltip(vc.type(), vc.varId());
                        }
                    }

                    ImGui.tableSetColumnIndex(6); ImGui.text(String.valueOf(vc.oldValue()));
                    ImGui.tableSetColumnIndex(7);
                    int delta = vc.newValue() - vc.oldValue();
                    if (delta > 0) ImGui.textColored(0.3f, 0.9f, 0.3f, 1f, String.valueOf(vc.newValue()));
                    else if (delta < 0) ImGui.textColored(0.9f, 0.3f, 0.3f, 1f, String.valueOf(vc.newValue()));
                    else ImGui.text(String.valueOf(vc.newValue()));

                    ImGui.tableSetColumnIndex(8);
                    String deltaStr = delta >= 0 ? "+" + delta : String.valueOf(delta);
                    Integer freq = script.varChangeCount.get(pinKey);
                    if (freq != null && freq > 1) {
                        ImGui.text(deltaStr + " (x" + freq + ")");
                    } else {
                        ImGui.text(deltaStr);
                    }
                }
            }
            clipper.end();

            if (script.lastVarSize == -1) {
                script.lastVarSize = vars.size();
            } else if (vars.size() > script.lastVarSize) {
                script.lastVarSize = vars.size();
                ImGui.setScrollHereY(1.0f);
            }
            ImGui.endTable();
        }
    }

    private void renderItemVarbits() {
        List<ItemVarEntry> items = script.itemVarCache;
        if (items.isEmpty()) return;

        if (ImGui.collapsingHeader("Item Varbits (" + items.size() + " items with vars)")) {
            int flags = ImGuiTableFlags.Borders | ImGuiTableFlags.RowBg
                    | ImGuiTableFlags.SizingStretchProp | ImGuiTableFlags.Resizable;
            if (ImGui.beginTable("##item_vars", 5, flags)) {
                ImGui.tableSetupColumn("Slot", 0, 0.6f);
                ImGui.tableSetupColumn("Item", 0, 1.2f);
                ImGui.tableSetupColumn("VarId", 0, 0.4f);
                ImGui.tableSetupColumn("Value", 0, 0.5f);
                ImGui.tableSetupColumn("Code", 0, 1.5f);
                ImGui.tableSetupScrollFreeze(0, 1);
                ImGui.tableHeadersRow();

                for (ItemVarEntry entry : items) {
                    for (ItemVar v : entry.vars()) {
                        ImGui.tableNextRow();
                        ImGui.tableSetColumnIndex(0);
                        ImGui.textColored(0.7f, 0.7f, 0.4f, 1f, entry.slotName());
                        ImGui.tableSetColumnIndex(1);
                        ImGui.text(entry.itemName().isEmpty()
                                ? String.valueOf(entry.itemId())
                                : entry.itemName() + " (" + entry.itemId() + ")");
                        ImGui.tableSetColumnIndex(2);
                        ImGui.text(String.valueOf(v.varId()));
                        ImGui.tableSetColumnIndex(3);
                        ImGui.textColored(0.3f, 0.9f, 0.3f, 1f, String.valueOf(v.value()));
                        ImGui.tableSetColumnIndex(4);
                        String code = "api.getItemVarValue(94, " + entry.slot() + ", " + v.varId() + ")";
                        ImGui.pushStyleColor(ImGuiCol.Text, 0.4f, 0.9f, 0.5f, 1f);
                        if (ImGui.selectable(code + "##iv_" + entry.slot() + "_" + v.varId())) {
                            ImGui.setClipboardText(code);
                        }
                        ImGui.popStyleColor();
                        if (ImGui.isItemHovered()) {
                            ImGui.setTooltip("Click to copy | Slot: " + entry.slotName()
                                    + " | Item: " + entry.itemName());
                        }
                    }
                }
                ImGui.endTable();
            }
            ImGui.separator();
        }
    }

    static String getVarCode(String type, int varId) {
        if ("varbit".equals(type)) return "api.getVarbit(" + varId + ")";
        if ("varp".equals(type)) return "api.getVarp(" + varId + ")";
        if ("itemvar".equals(type)) {
            int slot = varId / 100000;
            int itemVarId = varId % 100000;
            return "api.getItemVarValue(94, " + slot + ", " + itemVarId + ")";
        }
        return "var:" + varId;
    }


    private void renderVarHistoryTooltip(String type, int varId) {
        List<VarChange> vars = script.varLog;
        List<VarChange> history = new ArrayList<>();
        for (int j = vars.size() - 1; j >= 0 && history.size() < 10; j--) {
            VarChange h = vars.get(j);
            if (h.type().equals(type) && h.varId() == varId) {
                history.add(h);
            }
        }
        if (history.isEmpty()) {
            String code = getVarCode(type, varId);
            ImGui.setTooltip("Click to copy: " + code);
            return;
        }
        Collections.reverse(history);
        StringBuilder sb = new StringBuilder();
        sb.append(type).append(" ").append(varId).append(" - History:\n");
        String annot = script.varAnnotations.get(type + ":" + varId);
        if (annot != null && !annot.isEmpty()) {
            sb.append("Label: ").append(annot).append("\n");
        }
        for (int idx = 0; idx < history.size(); idx++) {
            VarChange h = history.get(idx);
            sb.append(String.format("  Tick %d:  %d -> %d", h.gameTick(), h.oldValue(), h.newValue()));
            if (idx == history.size() - 1) sb.append("  <- latest");
            sb.append("\n");
        }
        ImGui.setTooltip(sb.toString().trim());
    }

    static int[] parseWatchIds(String input) {
        if (input == null || input.isEmpty()) return new int[0];
        String[] parts = input.split("[,\\s]+");
        int[] ids = new int[parts.length];
        int count = 0;
        for (String part : parts) {
            try { ids[count++] = Integer.parseInt(part.trim()); } catch (NumberFormatException ignored) {}
        }
        if (count == 0) return new int[0];
        int[] result = new int[count];
        System.arraycopy(ids, 0, result, 0, count);
        return result;
    }

    static boolean inWatchlist(int varId, int[] watchIds) {
        for (int id : watchIds) { if (id == varId) return true; }
        return false;
    }
}
