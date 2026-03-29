package com.xapi.debugger;

import static com.xapi.debugger.XapiData.*;

import imgui.ImGui;
import imgui.ImGuiListClipper;
import imgui.ImGuiTableColumnSortSpecs;
import imgui.ImGuiTableSortSpecs;
import imgui.flag.ImGuiCol;
import imgui.flag.ImGuiSortDirection;
import imgui.flag.ImGuiStyleVar;
import imgui.flag.ImGuiTableColumnFlags;
import imgui.flag.ImGuiTableFlags;

import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

final class VariablesTab {

    private final XapiState state;
    private final List<Integer> displayIndices = new ArrayList<>();
    private final Set<Integer> expandedVarbitGroups = new HashSet<>();

    VariablesTab(XapiState s) {
        this.state = s;
    }

    void render() {
        // Type filter checkboxes
        boolean wSVB = state.showVarbits;
        if (ImGui.checkbox("Varbits##vf", wSVB)) { state.showVarbits = !wSVB; state.settingsDirty = true; }
        ImGui.sameLine();
        boolean wSVP = state.showVarps;
        if (ImGui.checkbox("Varps##vf", wSVP)) { state.showVarps = !wSVP; state.settingsDirty = true; }
        ImGui.sameLine();
        ImGui.text("|");
        ImGui.sameLine();
        if (ImGui.button("Clear Vars")) { state.varLog.clear(); state.varsByTick.clear(); state.lastVarSize = -1; }

        // Watchlist filter
        ImGui.text("Filter:");
        ImGui.sameLine();
        ImGui.pushItemWidth(200);
        ImGui.inputText("##var_filter", state.varFilterText);
        ImGui.popItemWidth();

        // -- Var Lookup --
        ImGui.sameLine();
        ImGui.text("|");
        ImGui.sameLine();
        boolean isVarbit = state.varLookupIsVarbit;
        if (ImGui.button(isVarbit ? "Varbit##vl" : "Varp##vl")) { state.varLookupIsVarbit = !isVarbit; state.varLookupResult = Integer.MIN_VALUE; }
        if (ImGui.isItemHovered()) ImGui.setTooltip("Click to toggle varbit/varp");
        ImGui.sameLine();
        ImGui.pushItemWidth(80);
        ImGui.inputText("##var_lookup_id", state.varLookupId);
        ImGui.popItemWidth();
        if (ImGui.isItemHovered()) ImGui.setTooltip("Enter a " + (state.varLookupIsVarbit ? "varbit" : "varp") + " ID to see its current value");
        ImGui.sameLine();
        ImGui.text("=");
        ImGui.sameLine();
        int lookupVal = state.varLookupResult;
        if (lookupVal != Integer.MIN_VALUE) {
            ImGui.textColored(0.3f, 0.9f, 0.3f, 1f, String.valueOf(lookupVal));
            ImGui.sameLine();
            String lookupCode = state.varLookupIsVarbit ? "api.getVarbit(" + state.varLookupId.get().trim() + ")" : "api.getVarp(" + state.varLookupId.get().trim() + ")";
            ImGui.pushStyleColor(ImGuiCol.Text, 0.4f, 0.9f, 0.5f, 1f);
            if (ImGui.smallButton("copy##vl")) ImGui.setClipboardText(lookupCode);
            ImGui.popStyleColor();
            if (ImGui.isItemHovered()) ImGui.setTooltip(lookupCode);
        } else {
            ImGui.textColored(0.5f, 0.5f, 0.5f, 0.8f, "...");
        }

        // -- Inventory Varbit Search --
        renderInvVarSearch();

        List<VarChange> vars = state.varLog;
        int[] watchIds = parseWatchIds(state.varFilterText.get().trim());

        // -- Pinned Variables Section --
        List<String> pinnedList = new ArrayList<>(state.pinnedVars);
        if (!pinnedList.isEmpty()) {
            ImGui.textColored(0.9f, 0.8f, 0.3f, 1f, "Pinned Variables:");
            String toUnpin = null;
            for (String key : pinnedList) {
                String[] parts = key.split(":");
                if (parts.length != 2) continue;
                String type = parts[0];
                String idStr = parts[1];
                Integer currentVal = state.pinnedCurrentValues.get(key);
                String annot = state.varAnnotations.get(key);
                Integer freq = state.varChangeCount.get(key);
                String varCode = getVarCode(type, Integer.parseInt(idStr));

                ImGui.pushID("pin_" + key);
                if (ImGui.smallButton("x")) {
                    toUnpin = key;
                    state.settingsDirty = true;
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
                    state.editingAnnotationKey = key;
                    state.annotationInput.set(annot != null ? annot : "");
                }
                ImGui.popID();
            }
            if (toUnpin != null) state.pinnedVars.remove(toUnpin);

            // Annotation editing inline
            if (state.editingAnnotationKey != null) {
                ImGui.text("Label for " + state.editingAnnotationKey + ":");
                ImGui.sameLine();
                ImGui.pushItemWidth(200);
                ImGui.inputText("##annot_edit", state.annotationInput);
                ImGui.popItemWidth();
                ImGui.sameLine();
                if (ImGui.button("Save##annot_save")) {
                    String val = state.annotationInput.get().trim();
                    if (val.isEmpty()) {
                        state.varAnnotations.remove(state.editingAnnotationKey);
                    } else {
                        state.varAnnotations.put(state.editingAnnotationKey, val);
                    }
                    state.editingAnnotationKey = null;
                    state.settingsDirty = true;
                }
                ImGui.sameLine();
                if (ImGui.button("Cancel##annot_cancel")) {
                    state.editingAnnotationKey = null;
                }
            }
            ImGui.separator();
        }

        // -- Pre-filter var log into display indices --
        displayIndices.clear();
        for (int i = 0; i < vars.size(); i++) {
            VarChange vc = vars.get(i);
            if ("varbit".equals(vc.type()) && !state.showVarbits) continue;
            if ("varp".equals(vc.type()) && !state.showVarps) continue;
            if ("varc".equals(vc.type()) || "varcstr".equals(vc.type())) continue;
            if (watchIds.length > 0 && !inWatchlist(vc.varId(), watchIds)) continue;
            displayIndices.add(i);
        }

        // -- Main Var Change Log --
        int flags = ImGuiTableFlags.Borders | ImGuiTableFlags.RowBg
                | ImGuiTableFlags.SizingFixedFit | ImGuiTableFlags.ScrollY | ImGuiTableFlags.Resizable;
        float tableHeight = ImGui.getContentRegionAvailY();
        if (tableHeight < 50) tableHeight = 50;
        ImGui.pushStyleVar(ImGuiStyleVar.CellPadding, 14, 4);
        if (ImGui.beginTable("##xapi_vars", 10, flags, 0, tableHeight)) {
            ImGui.tableSetupColumn("");
            ImGui.tableSetupColumn("#");
            ImGui.tableSetupColumn("Time");
            ImGui.tableSetupColumn("Tick");
            ImGui.tableSetupColumn("Type");
            ImGui.tableSetupColumn("VarID");
            ImGui.tableSetupColumn("Old");
            ImGui.tableSetupColumn("New");
            ImGui.tableSetupColumn("Delta");
            ImGui.tableSetupColumn("Note", ImGuiTableColumnFlags.WidthStretch);
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

                    if (vc.gameTick() == state.selectedActionTick) {
                        ImGui.tableSetBgColor(1, ImGui.colorConvertFloat4ToU32(0.4f, 0.6f, 0.2f, 0.3f));
                    } else if (state.hasActionOnTick(vc.gameTick())) {
                        ImGui.tableSetBgColor(1, ImGui.colorConvertFloat4ToU32(0.4f, 0.4f, 0.2f, 0.2f));
                    }

                    // Pin button
                    ImGui.tableSetColumnIndex(0);
                    String pinKey = vc.type() + ":" + vc.varId();
                    boolean isPinned = state.pinnedVars.contains(pinKey);
                    if (isPinned) {
                        ImGui.pushStyleColor(ImGuiCol.Text, 0.9f, 0.8f, 0.3f, 1f);
                        if (ImGui.smallButton("*##pin_" + i)) { state.pinnedVars.remove(pinKey); state.settingsDirty = true; }
                        ImGui.popStyleColor();
                    } else {
                        if (ImGui.smallButton("+##pin_" + i)) { state.pinnedVars.add(pinKey); state.settingsDirty = true; }
                    }

                    ImGui.tableSetColumnIndex(1); ImGui.text(String.valueOf(row + 1));
                    ImGui.tableSetColumnIndex(2); ImGui.text(LocalTime.ofInstant(Instant.ofEpochMilli(vc.timestamp()), ZoneId.systemDefault()).format(XapiState.TIME_FMT));
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
                    Integer freq = state.varChangeCount.get(pinKey);
                    if (freq != null && freq > 1) {
                        ImGui.text(deltaStr + " (x" + freq + ")");
                    } else {
                        ImGui.text(deltaStr);
                    }

                    ImGui.tableSetColumnIndex(9);
                    String note = state.varAnnotations.get(pinKey);
                    if (note != null && !note.isEmpty()) {
                        ImGui.textColored(0.7f, 0.9f, 0.7f, 1f, note);
                    }
                }
            }
            clipper.end();

            if (state.lastVarSize == -1) {
                state.lastVarSize = vars.size();
            } else if (vars.size() > state.lastVarSize) {
                state.lastVarSize = vars.size();
                ImGui.setScrollHereY(1.0f);
            }
            ImGui.endTable();
        }
        ImGui.popStyleVar();
    }

    private void renderInvVarSearch() {
        if (ImGui.collapsingHeader("Inventory Varbit Search")) {
            ImGui.pushItemWidth(100);
            ImGui.inputInt("Inv ID##ivs", state.invVarSearchInvId);
            ImGui.sameLine();
            ImGui.inputInt("Slot##ivs", state.invVarSearchSlot);
            ImGui.popItemWidth();
            ImGui.sameLine();
            boolean live = state.invVarLiveEnabled;
            if (live) {
                ImGui.pushStyleColor(ImGuiCol.Button, 0.2f, 0.7f, 0.3f, 0.7f);
                ImGui.pushStyleColor(ImGuiCol.ButtonHovered, 0.2f, 0.7f, 0.3f, 0.9f);
            }
            if (ImGui.button(live ? "Live" : "Enable##ivs")) {
                state.invVarLiveEnabled = !live;
                if (!live) {
                    state.invVarChangeLog.clear();
                }
            }
            if (live) ImGui.popStyleColor(2);
            if (ImGui.isItemHovered()) {
                ImGui.setTooltip(live ? "Click to disable live polling" : "Enable live polling — updates every ~600ms");
            }
            ImGui.sameLine();
            ImGui.text("Filter:");
            ImGui.sameLine();
            ImGui.pushItemWidth(120);
            ImGui.inputText("##ivs_filter", state.invVarFilterText);
            ImGui.popItemWidth();
            if (ImGui.isItemHovered()) ImGui.setTooltip("Filter by varbit ID or value (e.g. \"42\" or \"v:100\" for value 100)");

            // Baseline snapshot controls
            boolean hasBaseline = !state.invVarBaseline.isEmpty();
            ImGui.sameLine();
            if (ImGui.button("Set Start##ivs_snap")) {
                state.invVarBaseline.clear();
                // Only snapshot varbits matching the current filter
                String snapFilter = state.invVarFilterText.get().trim();
                boolean snapByValue = snapFilter.startsWith("v:") || snapFilter.startsWith("V:");
                String snapNumStr = snapByValue ? snapFilter.substring(2).trim() : snapFilter;
                int snapFilterNum = Integer.MIN_VALUE;
                try { snapFilterNum = Integer.parseInt(snapNumStr); } catch (NumberFormatException ignored) {}

                for (var r : state.invVarLiveResults) {
                    boolean matches;
                    if (snapFilter.isEmpty() || snapFilterNum == Integer.MIN_VALUE) {
                        matches = true;
                    } else if (snapByValue) {
                        matches = r.decodedValue() == snapFilterNum;
                    } else {
                        matches = r.varbitId() == snapFilterNum;
                        if (!matches && r.allVarbitIds() != null) {
                            for (int id : r.allVarbitIds()) {
                                if (id == snapFilterNum) { matches = true; break; }
                            }
                        }
                    }
                    if (matches) {
                        state.invVarBaseline.put(r.varbitId(), r.decodedValue());
                        if (r.allVarbitIds() != null) {
                            for (int sibId : r.allVarbitIds()) {
                                state.invVarBaseline.putIfAbsent(sibId, r.decodedValue());
                            }
                        }
                    }
                }
            }
            if (ImGui.isItemHovered()) ImGui.setTooltip("Snapshot filtered varbits as baseline — \"Changed Only\" will compare against this");
            ImGui.sameLine();
            boolean changedOnly = state.invVarChangedOnly;
            if (ImGui.checkbox("Changed Only##ivs", changedOnly)) {
                state.invVarChangedOnly = !changedOnly;
            }
            if (ImGui.isItemHovered()) {
                ImGui.setTooltip(hasBaseline ? "Show only varbits changed from baseline" : "Show only varbits that changed during this session");
            }
            if (hasBaseline) {
                ImGui.sameLine();
                if (ImGui.smallButton("Clear##ivs_snap_clr")) {
                    state.invVarBaseline.clear();
                    state.invVarChangedOnly = false;
                }
                if (ImGui.isItemHovered()) ImGui.setTooltip("Clear baseline snapshot");
            }

            // Status line
            String status = state.invVarSearchStatus;
            if (!status.isEmpty()) {
                boolean isError = status.startsWith("Error") || status.startsWith("Item var system");
                if (isError) ImGui.textColored(1f, 0.3f, 0.3f, 1f, status);
                else ImGui.textColored(0.5f, 0.9f, 0.5f, 1f, status);
            }

            // Live results table
            List<XapiData.InvVarLiveEntry> results = state.invVarLiveResults;
            if (state.invVarChangedOnly) {
                if (!state.invVarBaseline.isEmpty()) {
                    // Baseline mode: show only varbits whose value differs from baseline
                    List<XapiData.InvVarLiveEntry> filtered = new ArrayList<>();
                    for (var r : results) {
                        Integer baseVal = state.invVarBaseline.get(r.varbitId());
                        if (baseVal != null && r.decodedValue() != baseVal) filtered.add(r);
                    }
                    results = filtered;
                } else {
                    // No baseline: show varbits that changed during this live session
                    List<XapiData.InvVarLiveEntry> filtered = new ArrayList<>();
                    for (var r : results) {
                        if (r.lastChangeTime() > 0) filtered.add(r);
                    }
                    results = filtered;
                }
            }
            // Promote annotated sibling varbits to their own main rows
            Set<Integer> mainVarbitIds = new HashSet<>();
            for (var r : results) mainVarbitIds.add(r.varbitId());
            List<XapiData.InvVarLiveEntry> promoted = null;
            for (var r : results) {
                if (r.allVarbitIds() == null || r.allVarbitIds().size() <= 1) continue;
                for (int sibId : r.allVarbitIds()) {
                    if (sibId == r.varbitId() || mainVarbitIds.contains(sibId)) continue;
                    if (state.varAnnotations.containsKey("invvarbit:" + sibId)) {
                        if (promoted == null) promoted = new ArrayList<>(results);
                        mainVarbitIds.add(sibId);
                        promoted.add(new XapiData.InvVarLiveEntry(
                                sibId, r.itemVarId(), r.decodedValue(), r.bits(),
                                r.previousValue(), r.lastChangeTime(), null));
                    }
                }
            }
            if (promoted != null) results = promoted;
            // Apply text filter (skip when baseline "Changed Only" is active — baseline already narrowed results)
            boolean baselineActive = state.invVarChangedOnly && !state.invVarBaseline.isEmpty();
            String filterStr = state.invVarFilterText.get().trim();
            if (!filterStr.isEmpty() && !baselineActive) {
                boolean filterByValue = filterStr.startsWith("v:") || filterStr.startsWith("V:");
                String numStr = filterByValue ? filterStr.substring(2).trim() : filterStr;
                try {
                    int filterNum = Integer.parseInt(numStr);
                    List<XapiData.InvVarLiveEntry> filtered = new ArrayList<>();
                    for (var r : results) {
                        if (filterByValue) {
                            if (r.decodedValue() == filterNum) filtered.add(r);
                        } else {
                            // Match varbit ID — check main and all siblings
                            if (r.varbitId() == filterNum) { filtered.add(r); continue; }
                            if (r.allVarbitIds() != null) {
                                for (int id : r.allVarbitIds()) {
                                    if (id == filterNum) { filtered.add(r); break; }
                                }
                            }
                        }
                    }
                    results = filtered;
                } catch (NumberFormatException ignored) {
                    // Non-numeric filter — match against labels
                    String lower = filterStr.toLowerCase();
                    List<XapiData.InvVarLiveEntry> filtered = new ArrayList<>();
                    for (var r : results) {
                        String ann = state.varAnnotations.get("invvarbit:" + r.varbitId());
                        if (ann != null && ann.toLowerCase().contains(lower)) filtered.add(r);
                    }
                    results = filtered;
                }
            }
            if (!results.isEmpty()) {
                int invId = state.invVarSearchInvId.get();
                int slot = state.invVarSearchSlot.get();
                long now = System.currentTimeMillis();
                int flags = ImGuiTableFlags.Borders | ImGuiTableFlags.RowBg
                        | ImGuiTableFlags.SizingFixedFit | ImGuiTableFlags.Resizable
                        | ImGuiTableFlags.Sortable | ImGuiTableFlags.SortTristate;

                ImGui.pushStyleVar(ImGuiStyleVar.CellPadding, 14, 4);
                if (ImGui.beginTable("##ivs_live", 5, flags)) {
                    ImGui.tableSetupColumn("VarbitID", ImGuiTableColumnFlags.DefaultSort);
                    ImGui.tableSetupColumn("Value");
                    ImGui.tableSetupColumn("Bits");
                    ImGui.tableSetupColumn("Label", ImGuiTableColumnFlags.WidthStretch | ImGuiTableColumnFlags.NoSort);
                    ImGui.tableSetupColumn("Code", ImGuiTableColumnFlags.NoSort);
                    ImGui.tableSetupScrollFreeze(0, 1);
                    ImGui.tableHeadersRow();

                    // Sort results based on column header clicks
                    ImGuiTableSortSpecs sortSpecs = ImGui.tableGetSortSpecs();
                    if (sortSpecs != null && sortSpecs.getSpecsCount() > 0) {
                        ImGuiTableColumnSortSpecs colSpec = sortSpecs.getSpecs()[0];
                        int colIdx = colSpec.getColumnIndex();
                        boolean asc = colSpec.getSortDirection() == ImGuiSortDirection.Ascending;
                        Comparator<XapiData.InvVarLiveEntry> cmp = null;
                        if (colIdx == 0) cmp = Comparator.comparingInt(XapiData.InvVarLiveEntry::varbitId);
                        else if (colIdx == 1) cmp = Comparator.comparingInt(XapiData.InvVarLiveEntry::decodedValue);
                        else if (colIdx == 2) cmp = Comparator.comparingInt(XapiData.InvVarLiveEntry::bits);
                        if (cmp != null) {
                            if (!asc) cmp = cmp.reversed();
                            results = new ArrayList<>(results);
                            results.sort(cmp);
                        }
                        sortSpecs.setSpecsDirty(false);
                    }

                    for (int i = 0; i < results.size(); i++) {
                        XapiData.InvVarLiveEntry r = results.get(i);
                        String annotKey = "invvarbit:" + r.varbitId();
                        boolean hasGroup = r.allVarbitIds() != null && r.allVarbitIds().size() > 1;
                        boolean expanded = hasGroup && expandedVarbitGroups.contains(r.varbitId());
                        ImGui.tableNextRow();

                        // Highlight row if value changed recently (fade over 15 seconds)
                        long age = r.lastChangeTime() > 0 ? now - r.lastChangeTime() : Long.MAX_VALUE;
                        if (age < 15000) {
                            float alpha = 0.4f * (1.0f - (float) age / 15000f);
                            boolean increased = r.decodedValue() > r.previousValue();
                            if (increased) {
                                ImGui.tableSetBgColor(1, ImGui.colorConvertFloat4ToU32(0.2f, 0.8f, 0.2f, alpha));
                            } else {
                                ImGui.tableSetBgColor(1, ImGui.colorConvertFloat4ToU32(0.8f, 0.2f, 0.2f, alpha));
                            }
                        }

                        // VarbitID column — clickable to expand group
                        ImGui.tableSetColumnIndex(0);
                        if (hasGroup) {
                            String arrow = expanded ? "v " : "> ";
                            ImGui.pushStyleColor(ImGuiCol.Text, 1f, 1f, 0.3f, 1f);
                            if (ImGui.selectable(arrow + r.varbitId() + " (" + r.allVarbitIds().size() + ")##vbg_" + i)) {
                                if (expanded) expandedVarbitGroups.remove(r.varbitId());
                                else expandedVarbitGroups.add(r.varbitId());
                            }
                            ImGui.popStyleColor();
                            if (ImGui.isItemHovered()) {
                                ImGui.setTooltip(expanded ? "Click to collapse" : "Click to expand " + r.allVarbitIds().size() + " varbits with value " + r.decodedValue());
                            }
                        } else {
                            ImGui.textColored(1f, 1f, 0.3f, 1f, String.valueOf(r.varbitId()));
                        }

                        ImGui.tableSetColumnIndex(1);
                        if (age < 15000) {
                            boolean increased = r.decodedValue() > r.previousValue();
                            if (increased) ImGui.textColored(0.3f, 1f, 0.3f, 1f, String.valueOf(r.decodedValue()));
                            else ImGui.textColored(1f, 0.3f, 0.3f, 1f, String.valueOf(r.decodedValue()));
                        } else {
                            ImGui.textColored(0.3f, 0.9f, 0.3f, 1f, String.valueOf(r.decodedValue()));
                        }
                        ImGui.tableSetColumnIndex(2);
                        ImGui.textColored(0.6f, 0.6f, 0.6f, 1f, String.valueOf(r.bits()));
                        ImGui.tableSetColumnIndex(3);
                        renderInvVarLabelCell(annotKey, i, "iv");
                        ImGui.tableSetColumnIndex(4);
                        String code = "VarManager.getInvVarbit(" + invId + ", " + slot + ", " + r.varbitId() + ")";
                        ImGui.pushStyleColor(ImGuiCol.Text, 0.4f, 0.9f, 0.5f, 1f);
                        if (ImGui.selectable(code + "##ivs_" + i)) {
                            ImGui.setClipboardText(code);
                        }
                        ImGui.popStyleColor();
                        if (ImGui.isItemHovered()) ImGui.setTooltip("Click to copy");

                        // Expanded sub-rows for sibling varbit IDs
                        if (expanded) {
                            for (int j = 0; j < r.allVarbitIds().size(); j++) {
                                int siblingId = r.allVarbitIds().get(j);
                                if (siblingId == r.varbitId()) continue; // skip the main one already shown
                                String subKey = "invvarbit:" + siblingId;
                                ImGui.tableNextRow();
                                ImGui.tableSetBgColor(1, ImGui.colorConvertFloat4ToU32(0.15f, 0.15f, 0.2f, 0.5f));

                                ImGui.tableSetColumnIndex(0);
                                ImGui.textColored(0.7f, 0.7f, 0.3f, 0.8f, "  " + siblingId);

                                ImGui.tableSetColumnIndex(1);
                                ImGui.textColored(0.5f, 0.7f, 0.5f, 0.8f, String.valueOf(r.decodedValue()));
                                ImGui.tableSetColumnIndex(2);
                                ImGui.textColored(0.5f, 0.5f, 0.5f, 0.8f, String.valueOf(r.bits()));
                                ImGui.tableSetColumnIndex(3);
                                String subUid = "sub_" + i + "_" + j;
                                renderInvVarLabelCell(subKey, i * 10000 + j, subUid);
                                ImGui.tableSetColumnIndex(4);
                                String subCode = "VarManager.getInvVarbit(" + invId + ", " + slot + ", " + siblingId + ")";
                                ImGui.pushStyleColor(ImGuiCol.Text, 0.4f, 0.9f, 0.5f, 0.8f);
                                if (ImGui.selectable(subCode + "##ivs_sub_" + i + "_" + j)) {
                                    ImGui.setClipboardText(subCode);
                                }
                                ImGui.popStyleColor();
                                if (ImGui.isItemHovered()) ImGui.setTooltip("Click to copy");
                            }
                        }
                    }
                    ImGui.endTable();
                }
                ImGui.popStyleVar();
            }

            // Change history (collapsible)
            List<XapiData.InvVarChangeEntry> changeLog = state.invVarChangeLog;
            if (!changeLog.isEmpty()) {
                if (ImGui.treeNode("Change History (" + changeLog.size() + ")##ivs_changes")) {
                    ImGui.sameLine();
                    if (ImGui.smallButton("Clear##ivs_hist")) {
                        state.invVarChangeLog.clear();
                    }
                    int invId = state.invVarSearchInvId.get();
                    int slot = state.invVarSearchSlot.get();
                    int flags = ImGuiTableFlags.Borders | ImGuiTableFlags.RowBg
                            | ImGuiTableFlags.SizingFixedFit | ImGuiTableFlags.Resizable;

                    ImGui.pushStyleVar(ImGuiStyleVar.CellPadding, 14, 4);
                    if (ImGui.beginTable("##ivs_changes", 6, flags)) {
                        ImGui.tableSetupColumn("VarbitID");
                        ImGui.tableSetupColumn("Old");
                        ImGui.tableSetupColumn("New");
                        ImGui.tableSetupColumn("Bits");
                        ImGui.tableSetupColumn("Label", ImGuiTableColumnFlags.WidthStretch);
                        ImGui.tableSetupColumn("Code");
                        ImGui.tableSetupScrollFreeze(0, 1);
                        ImGui.tableHeadersRow();

                        // Show most recent first
                        for (int i = changeLog.size() - 1; i >= 0; i--) {
                            XapiData.InvVarChangeEntry r = changeLog.get(i);
                            String annotKey = "invvarbit:" + r.varbitId();
                            ImGui.tableNextRow();
                            ImGui.tableSetColumnIndex(0);
                            ImGui.textColored(1f, 1f, 0.3f, 1f, String.valueOf(r.varbitId()));
                            ImGui.tableSetColumnIndex(1);
                            ImGui.textColored(0.9f, 0.3f, 0.3f, 1f, String.valueOf(r.oldDecoded()));
                            ImGui.tableSetColumnIndex(2);
                            ImGui.textColored(0.3f, 0.9f, 0.3f, 1f, String.valueOf(r.newDecoded()));
                            ImGui.tableSetColumnIndex(3);
                            ImGui.textColored(0.6f, 0.6f, 0.6f, 1f, String.valueOf(r.bits()));
                            ImGui.tableSetColumnIndex(4);
                            String annot = state.varAnnotations.get(annotKey);
                            if (annot != null && !annot.isEmpty()) {
                                ImGui.textColored(0.7f, 0.9f, 0.7f, 1f, annot);
                            }
                            ImGui.tableSetColumnIndex(5);
                            String code = "VarManager.getInvVarbit(" + invId + ", " + slot + ", " + r.varbitId() + ")";
                            ImGui.pushStyleColor(ImGuiCol.Text, 0.4f, 0.9f, 0.5f, 1f);
                            if (ImGui.selectable(code + "##ch_" + i)) {
                                ImGui.setClipboardText(code);
                            }
                            ImGui.popStyleColor();
                            if (ImGui.isItemHovered()) ImGui.setTooltip("Click to copy");
                        }
                        ImGui.endTable();
                    }
                    ImGui.popStyleVar();
                    ImGui.treePop();
                }
            }
            ImGui.separator();
        }
    }

    /** Renders the Label cell for an inv var row (main or sub-row). */
    private void renderInvVarLabelCell(String annotKey, int rowIndex, String uidPrefix) {
        String annot = state.varAnnotations.get(annotKey);
        if (annotKey.equals(state.invVarEditingKey)) {
            ImGui.pushItemWidth(ImGui.getContentRegionAvailX() - 50);
            boolean enter = ImGui.inputText("##ivedit_" + uidPrefix + "_" + rowIndex, state.invVarAnnotationInput,
                    imgui.flag.ImGuiInputTextFlags.EnterReturnsTrue);
            ImGui.popItemWidth();
            ImGui.sameLine();
            if (enter || ImGui.smallButton("ok##ivsave_" + uidPrefix + "_" + rowIndex)) {
                String val = state.invVarAnnotationInput.get().trim();
                if (val.isEmpty()) state.varAnnotations.remove(annotKey);
                else state.varAnnotations.put(annotKey, val);
                state.invVarEditingKey = null;
                state.settingsDirty = true;
            }
        } else {
            if (ImGui.smallButton("##lbl_" + uidPrefix + "_" + rowIndex)) {
                state.invVarEditingKey = annotKey;
                state.invVarAnnotationInput.set(annot != null ? annot : "");
            }
            if (ImGui.isItemHovered()) ImGui.setTooltip(annot != null ? "Edit label" : "Add label");
            if (annot != null && !annot.isEmpty()) {
                ImGui.sameLine();
                ImGui.textColored(0.7f, 0.9f, 0.7f, 1f, annot);
            }
        }
    }

    static String getVarCode(String type, int varId) {
        if ("varbit".equals(type)) return "api.getVarbit(" + varId + ")";
        if ("varp".equals(type)) return "api.getVarp(" + varId + ")";
        if ("itemvar".equals(type)) return "api.getItemVarValue(94, ?, " + varId + ")";
        return "var:" + varId;
    }


    private void renderVarHistoryTooltip(String type, int varId) {
        List<VarChange> vars = state.varLog;
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
        String annot = state.varAnnotations.get(type + ":" + varId);
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
