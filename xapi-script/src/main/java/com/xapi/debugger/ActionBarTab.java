package com.xapi.debugger;

import static com.xapi.debugger.XapiData.*;

import imgui.ImGui;
import imgui.flag.ImGuiCol;
import imgui.flag.ImGuiTableFlags;
import imgui.type.ImString;

import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Action Bar tab for the Xapi debugger.
 * Features: hide/expand interfaces, filter, type labels, description tooltips,
 * copy-to-clipboard, empty slot indicators, change detection log.
 */
final class ActionBarTab {

    private static final int SLOT_STRIDE = 13;
    private static final int[] ACTION_BAR_INTERFACES = {1430, 1670, 1671, 1672, 1673};
    private static final String[] IFACE_LABELS = {
            "Main Bar (1430)", "Extra Bar 1 (1670)", "Extra Bar 2 (1671)",
            "Extra Bar 3 (1672)", "Extra Bar 4 (1673)"
    };

    // UI state references (stored in XapiState for persistence)

    private final XapiState state;

    ActionBarTab(XapiState s) {
        this.state = s;
        // Initialize probe IDs from persisted state
        s.probeVarcIds = parseIds(s.abProbeVarcs.get());
        s.probeVarpIds = parseIds(s.abProbeVarps.get());
        s.probeVarbitIds = parseIds(s.abProbeVarbits.get());
    }

    void render() {
        // ── Header: status + bar switcher + progress ──
        float adrenPct = state.actionBarAdrenalineRaw / 10f;
        ImGui.text(String.format("Adrenaline: %.1f%%  |  Locked: %s",
                adrenPct, state.actionBarLocked));
        // GCD indicator — local timer estimate (3 ticks = 1.8s)
        ImGui.sameLine();
        long gcdElapsed = System.currentTimeMillis() - state.lastAbilityActivationTime;
        if (state.lastAbilityActivationTime > 0 && gcdElapsed < 1800) {
            float gcdRemaining = (1800 - gcdElapsed) / 1000f;
            ImGui.textColored(1.0f, 0.4f, 0.3f, 1.0f, String.format("  |  GCD: ~%.1fs", gcdRemaining));
        } else if (state.lastAbilityActivationTime > 0) {
            ImGui.textColored(0.3f, 0.9f, 0.3f, 1.0f, "  |  GCD: Ready");
        }
        String progress = state.spriteMapProgress;
        if (progress != null && !progress.isEmpty()) {
            ImGui.sameLine();
            ImGui.textColored(0.6f, 0.8f, 1.0f, 1.0f, "  " + progress);
        }

        // ── Filter + toggle buttons ──
        ImGui.text("Filter:");
        ImGui.sameLine();
        ImGui.pushItemWidth(150);
        ImGui.inputText("##ab_filter", state.actionBarFilter);
        ImGui.popItemWidth();
        ImGui.sameLine();
        if (ImGui.smallButton("X##clr")) { state.actionBarFilter.set(""); }
        ImGui.sameLine();
        toggleButton("Changes", state.abShowChangeLog, () -> state.abShowChangeLog = !state.abShowChangeLog);
        ImGui.sameLine();
        toggleButton("History", state.abShowHistory, () -> state.abShowHistory = !state.abShowHistory);
        ImGui.sameLine();
        toggleButton("Compare", state.abShowComparison, () -> state.abShowComparison = !state.abShowComparison);
        ImGui.sameLine();
        toggleButton("Debug", state.abShowDebug, () -> state.abShowDebug = !state.abShowDebug);
        if (state.abShowComparison) {
            ImGui.sameLine();
            if (ImGui.smallButton("Snapshot##snap")) { state.comparisonBarPreset = -2; }
            if (ImGui.isItemHovered()) ImGui.setTooltip("Take a snapshot of current bars for comparison");
        }

        // ── Interface visibility buttons ──
        ImGui.sameLine();
        ImGui.text("  |");
        for (int i = 0; i < ACTION_BAR_INTERFACES.length; i++) {
            int ifaceId = ACTION_BAR_INTERFACES[i];
            ImGui.sameLine();
            boolean isCollapsed = state.abCollapsed.getOrDefault(ifaceId, false);
            boolean isExpanded = state.abExpandedInterface == ifaceId;
            if (isExpanded) {
                ImGui.pushStyleColor(ImGuiCol.Button, 0.2f, 0.6f, 0.3f, 1f);
            } else if (isCollapsed) {
                ImGui.pushStyleColor(ImGuiCol.Button, 0.4f, 0.2f, 0.2f, 1f);
            } else {
                ImGui.pushStyleColor(ImGuiCol.Button, 0.2f, 0.2f, 0.3f, 1f);
            }
            if (ImGui.smallButton((isCollapsed ? "+" : "-") + "##vis" + ifaceId)) {
                if (isExpanded) {
                    state.abExpandedInterface = -1; // un-expand
                } else if (isCollapsed) {
                    state.abCollapsed.put(ifaceId, false); // show
                } else {
                    state.abCollapsed.put(ifaceId, true); // hide
                }
            }
            ImGui.popStyleColor();
            if (ImGui.isItemHovered()) {
                String tip = isExpanded ? "Click to un-expand " + ifaceId
                        : isCollapsed ? "Click to show " + ifaceId
                        : "Click to hide " + ifaceId + "\nRight-click to expand";
                ImGui.setTooltip(tip);
            }
            if (ImGui.isItemClicked(1) && !isCollapsed) { // right-click to expand
                state.abExpandedInterface = (state.abExpandedInterface == ifaceId) ? -1 : ifaceId;
            }
            ImGui.sameLine();
            ImGui.textColored(0.6f, 0.6f, 0.6f, 1f, String.valueOf(ifaceId));
        }

        ImGui.spacing();
        ImGui.separator();

        // ── Panels (collapsible) ──
        if (state.abShowDebug) { renderDebug(); ImGui.separator(); }
        if (state.abShowChangeLog) { renderChangeLog(); ImGui.separator(); }
        if (state.abShowHistory) { renderHistory(); ImGui.separator(); }
        if (state.abShowComparison) { renderComparison(); ImGui.separator(); }

        // ── Main content ──
        List<ActionBarEntry> allEntries = state.actionBarEntries;
        String filter = state.actionBarFilter.get().toLowerCase();

        if (allEntries.isEmpty()) {
            ImGui.text(state.actionBarOpen ? "No components with options found." : "Action bar not visible.");
            return;
        }

        int abFlags = ImGuiTableFlags.Borders | ImGuiTableFlags.RowBg
                | ImGuiTableFlags.Resizable | ImGuiTableFlags.SizingStretchProp | ImGuiTableFlags.ScrollY;

        // Count visible interfaces for height splitting
        int visibleCount = 0;
        for (int id : ACTION_BAR_INTERFACES) {
            if (state.abCollapsed.getOrDefault(id, false)) continue;
            if (state.abExpandedInterface >= 0 && state.abExpandedInterface != id) continue;
            if (allEntries.stream().anyMatch(e -> e.interfaceId() == id)) visibleCount++;
        }

        for (int idx = 0; idx < ACTION_BAR_INTERFACES.length; idx++) {
            int ifaceId = ACTION_BAR_INTERFACES[idx];
            if (state.abCollapsed.getOrDefault(ifaceId, false)) continue;
            if (state.abExpandedInterface >= 0 && state.abExpandedInterface != ifaceId) continue;

            List<ActionBarEntry> ifaceEntries = allEntries.stream()
                    .filter(e -> e.interfaceId() == ifaceId)
                    .filter(e -> matchesFilter(e, filter))
                    .toList();
            if (ifaceEntries.isEmpty() && filter.isEmpty()) continue;

            ImGui.spacing();
            ImGui.textColored(0.4f, 0.8f, 1.0f, 1.0f,
                    IFACE_LABELS[idx] + "  (" + ifaceEntries.size() + " slots)");

            float tableHeight = (ImGui.getContentRegionAvailY() - 25) / Math.max(1, visibleCount);
            visibleCount = Math.max(1, visibleCount - 1);

            if (ifaceEntries.isEmpty()) {
                ImGui.text("No matching entries.");
                continue;
            }

            if (ImGui.beginTable("##ab_" + ifaceId, 6, abFlags, 0, tableHeight)) {
                ImGui.tableSetupColumn("Slot", 0, 0.3f);
                ImGui.tableSetupColumn("Name", 0, 1.5f);
                ImGui.tableSetupColumn("Type", 0, 0.6f);
                ImGui.tableSetupColumn("Item ID", 0, 0.5f);
                ImGui.tableSetupColumn("Sprite ID", 0, 0.5f);
                ImGui.tableSetupColumn("Options", 0, 2.5f);
                ImGui.tableSetupScrollFreeze(0, 1);
                ImGui.tableHeadersRow();

                for (ActionBarEntry entry : ifaceEntries) {
                    ImGui.tableNextRow();

                    // Empty slot indicator — dim the whole row
                    boolean empty = entry.name().isEmpty() && entry.itemId() <= 0 && entry.spriteId() <= 0;
                    if (empty) {
                        ImGui.tableSetBgColor(1, ImGui.colorConvertFloat4ToU32(0.15f, 0.15f, 0.15f, 0.5f));
                    }

                    // Slot
                    ImGui.tableSetColumnIndex(0);
                    if (entry.slot() > 0) {
                        ImGui.text(String.valueOf(entry.slot()));
                    } else {
                        ImGui.textColored(0.5f, 0.5f, 0.5f, 1.0f, "-");
                    }

                    // Name + description + stats tooltip
                    ImGui.tableSetColumnIndex(1);
                    if (!entry.name().isEmpty()) {
                        ImGui.text(entry.name());
                        if (ImGui.isItemHovered()) {
                            StringBuilder tip = new StringBuilder(entry.name());
                            // Ability type (p2799)
                            String tierName = switch (entry.abilityType()) {
                                case 0 -> "Auto"; case 1 -> "Basic"; case 2 -> "Enhanced";
                                case 4 -> "Ultimate"; case 7 -> "Special"; default -> "Type " + entry.abilityType();
                            };
                            tip.append("  [").append(tierName).append("]");

                            if (!entry.description().isEmpty()) {
                                tip.append("\n\n").append(entry.description());
                            }
                            // Adrenaline
                            if (entry.adrenGain() > 0) {
                                tip.append("\nAdren gain: +").append(entry.adrenGain() / 10f).append("%");
                            }
                            if (entry.specCost() > 0) {
                                tip.append("\nSpec cost: ").append(entry.specCost() / 10f).append("%");
                            } else if (entry.adrenCost() > 0) {
                                tip.append("\nAdren cost: ").append(entry.adrenCost() / 10f).append("%");
                            }
                            // Cooldown
                            if (entry.cooldownTicks() > 0) {
                                tip.append("\nCooldown: ").append(entry.cooldownTicks() * 0.6f).append("s (").append(entry.cooldownTicks()).append(" ticks)");
                            }
                            // GCD exempt
                            if (entry.canCastGCD() >= 0) {
                                tip.append("\nCast during GCD: ").append(entry.canCastGCD() == 1 ? "Yes" : "No");
                            }
                            // Target requirement
                            if (entry.requiresTarget() == 0) {
                                tip.append("\nRequires target: No");
                            }
                            tip.append("\n\nInterface: ").append(entry.interfaceId());
                            tip.append("  |  Component: ").append(entry.componentId());
                            if (entry.subComponentId() >= 0) tip.append("  |  Sub: ").append(entry.subComponentId());
                            ImGui.setTooltip(tip.toString());
                        }
                    } else if (empty) {
                        ImGui.textColored(0.4f, 0.4f, 0.4f, 1.0f, "(empty)");
                        if (ImGui.isItemHovered()) {
                            ImGui.setTooltip(String.format("Interface: %d  |  Component: %d%s",
                                    entry.interfaceId(), entry.componentId(),
                                    entry.subComponentId() >= 0 ? "  |  Sub: " + entry.subComponentId() : ""));
                        }
                    } else {
                        ImGui.textColored(0.5f, 0.5f, 0.5f, 1.0f, "-");
                        if (ImGui.isItemHovered()) {
                            ImGui.setTooltip(String.format("Interface: %d  |  Component: %d%s",
                                    entry.interfaceId(), entry.componentId(),
                                    entry.subComponentId() >= 0 ? "  |  Sub: " + entry.subComponentId() : ""));
                        }
                    }

                    // Slot type label
                    ImGui.tableSetColumnIndex(2);
                    if (!entry.slotType().isEmpty()) {
                        float r = 0.6f, g = 0.6f, b = 0.6f;
                        switch (entry.slotType()) {
                            case "Melee", "Strength" -> { r = 0.9f; g = 0.4f; b = 0.3f; }
                            case "Ranged" -> { r = 0.3f; g = 0.8f; b = 0.3f; }
                            case "Magic" -> { r = 0.4f; g = 0.5f; b = 1.0f; }
                            case "Defence" -> { r = 0.5f; g = 0.7f; b = 0.9f; }
                            case "Necromancy" -> { r = 0.7f; g = 0.3f; b = 0.8f; }
                            case "Item" -> { r = 0.9f; g = 0.8f; b = 0.3f; }
                            case "Teleport" -> { r = 0.3f; g = 0.9f; b = 0.9f; }
                            case "Other" -> { r = 0.7f; g = 0.7f; b = 0.5f; }
                        }
                        ImGui.textColored(r, g, b, 1.0f, entry.slotType());
                    } else {
                        ImGui.textColored(0.4f, 0.4f, 0.4f, 1.0f, "-");
                    }

                    // Item ID
                    ImGui.tableSetColumnIndex(3);
                    ImGui.text(entry.itemId() > 0 ? String.valueOf(entry.itemId()) : "-");

                    // Sprite ID
                    ImGui.tableSetColumnIndex(4);
                    ImGui.text(entry.spriteId() >= 0 ? String.valueOf(entry.spriteId()) : "-");

                    // Options
                    ImGui.tableSetColumnIndex(5);
                    String optStr = String.join(", ", entry.options());
                    ImGui.text(optStr);
                    if (ImGui.isItemHovered()) {
                        StringBuilder tooltip = new StringBuilder();
                        for (int i = 0; i < entry.options().size(); i++) {
                            if (i > 0) tooltip.append("\n");
                            tooltip.append((i + 1)).append(": ").append(entry.options().get(i));
                        }
                        tooltip.append("\n\nRight-click to copy");
                        ImGui.setTooltip(tooltip.toString());
                    }

                    // Right-click context menu on Options cell
                    String popupId = "##ctx_" + ifaceId + "_" + entry.componentId();
                    if (ImGui.isItemClicked(1)) {
                        ImGui.openPopup(popupId);
                    }
                    if (ImGui.beginPopup(popupId)) {
                        if (!entry.name().isEmpty()) {
                            String firstOpt = entry.options().isEmpty() ? "Activate" : entry.options().getFirst();
                            if (ImGui.menuItem("Copy: actionBar.interact(\"" + entry.name() + "\", \"" + firstOpt + "\")")) {
                                ImGui.setClipboardText("actionBar.interact(\"" + entry.name() + "\", \"" + firstOpt + "\");");
                            }
                            for (String opt : entry.options()) {
                                if (ImGui.menuItem("Copy: actionBar.interact(\"" + entry.name() + "\", \"" + opt + "\")")) {
                                    ImGui.setClipboardText("actionBar.interact(\"" + entry.name() + "\", \"" + opt + "\");");
                                }
                            }
                            ImGui.separator();
                        }
                        if (ImGui.menuItem("Copy name: " + entry.name())) {
                            ImGui.setClipboardText(entry.name());
                        }
                        if (entry.spriteId() > 0 && ImGui.menuItem("Copy sprite ID: " + entry.spriteId())) {
                            ImGui.setClipboardText(String.valueOf(entry.spriteId()));
                        }
                        if (entry.itemId() > 0 && ImGui.menuItem("Copy item ID: " + entry.itemId())) {
                            ImGui.setClipboardText(String.valueOf(entry.itemId()));
                        }
                        if (ImGui.menuItem("Copy component: " + entry.interfaceId() + ":" + entry.componentId())) {
                            ImGui.setClipboardText(entry.interfaceId() + ":" + entry.componentId());
                        }
                        ImGui.endPopup();
                    }
                }

                ImGui.endTable();
            }
        }
    }

    // Probe change log — tracks when probed values change
    private final java.util.List<String> probeChangeLog = new java.util.ArrayList<>();
    private final Map<String, Integer> lastProbeValues = new HashMap<>();

    private void renderVariableProbe() {
        ImGui.textColored(0.9f, 0.9f, 0.5f, 1.0f, "Variable Probe (comma-separated IDs, updates live)");
        ImGui.sameLine();
        toggleButton("Fast Poll", state.probeActive, () -> state.probeActive = !state.probeActive);
        if (state.probeActive) {
            ImGui.sameLine();
            ImGui.textColored(1.0f, 0.6f, 0.2f, 1.0f, " (50ms polling)");
        }

        ImGui.text("VarCs:");
        ImGui.sameLine();
        ImGui.pushItemWidth(200);
        if (ImGui.inputText("##probe_varc", state.abProbeVarcs)) {
            state.probeVarcIds = parseIds(state.abProbeVarcs.get());
        }
        ImGui.popItemWidth();

        ImGui.sameLine();
        ImGui.text("  Varps:");
        ImGui.sameLine();
        ImGui.pushItemWidth(200);
        if (ImGui.inputText("##probe_varp", state.abProbeVarps)) {
            state.probeVarpIds = parseIds(state.abProbeVarps.get());
        }
        ImGui.popItemWidth();

        ImGui.sameLine();
        ImGui.text("  Varbits:");
        ImGui.sameLine();
        ImGui.pushItemWidth(200);
        if (ImGui.inputText("##probe_varbit", state.abProbeVarbits)) {
            state.probeVarbitIds = parseIds(state.abProbeVarbits.get());
        }
        ImGui.popItemWidth();

        // Display results + track changes
        Map<String, Integer> results = state.probeResults;
        if (!results.isEmpty()) {
            // Detect changes
            for (var entry : results.entrySet()) {
                Integer prev = lastProbeValues.put(entry.getKey(), entry.getValue());
                if (prev != null && !prev.equals(entry.getValue())) {
                    String time = LocalTime.now().format(XapiState.TIME_FMT);
                    probeChangeLog.add(String.format("[%s] %s: %d -> %d", time, entry.getKey(), prev, entry.getValue()));
                    while (probeChangeLog.size() > 100) probeChangeLog.remove(0);
                }
            }

            int flags = ImGuiTableFlags.Borders | ImGuiTableFlags.RowBg | ImGuiTableFlags.SizingStretchProp;
            if (ImGui.beginTable("##probe_results", 3, flags)) {
                ImGui.tableSetupColumn("Type", 0, 0.4f);
                ImGui.tableSetupColumn("ID", 0, 0.3f);
                ImGui.tableSetupColumn("Value", 0, 0.8f);
                ImGui.tableHeadersRow();

                for (var entry : results.entrySet()) {
                    String[] parts = entry.getKey().split(":");
                    ImGui.tableNextRow();

                    // Highlight recently changed values
                    Integer prev = lastProbeValues.get(entry.getKey());
                    ImGui.tableSetColumnIndex(0);
                    ImGui.text(parts[0]);
                    ImGui.tableSetColumnIndex(1);
                    ImGui.text(parts.length > 1 ? parts[1] : "?");
                    ImGui.tableSetColumnIndex(2);
                    int val = entry.getValue();
                    ImGui.text(String.format("%d  (0x%X)", val, val));
                }
                ImGui.endTable();
            }
        }

        // Change log (render-thread polling)
        if (!probeChangeLog.isEmpty()) {
            ImGui.spacing();
            ImGui.textColored(0.8f, 0.8f, 0.4f, 1.0f,
                    "Poll Change Log (" + probeChangeLog.size() + ")");
            ImGui.sameLine();
            if (ImGui.smallButton("Clear##probelog")) { probeChangeLog.clear(); lastProbeValues.clear(); }

            int start = Math.max(0, probeChangeLog.size() - 15);
            for (int i = probeChangeLog.size() - 1; i >= start; i--) {
                ImGui.text(probeChangeLog.get(i));
            }
        }

        // VarC tick-based change log (polled every game tick in onTick — more reliable)
        List<VarcChange> varcChanges = state.varcChangeLog;
        if (!varcChanges.isEmpty()) {
            ImGui.spacing();
            ImGui.textColored(0.5f, 1.0f, 0.8f, 1.0f,
                    "VarC Tick Change Log (" + varcChanges.size() + ")");
            ImGui.sameLine();
            if (ImGui.smallButton("Clear##varclog")) { state.varcChangeLog.clear(); state.tickProbeVarcValues.clear(); }

            int flags = ImGuiTableFlags.Borders | ImGuiTableFlags.RowBg | ImGuiTableFlags.SizingStretchProp;
            if (ImGui.beginTable("##varc_changes", 5, flags)) {
                ImGui.tableSetupColumn("Time", 0, 0.6f);
                ImGui.tableSetupColumn("Tick", 0, 0.3f);
                ImGui.tableSetupColumn("VarC", 0, 0.3f);
                ImGui.tableSetupColumn("Old", 0, 0.6f);
                ImGui.tableSetupColumn("New", 0, 0.6f);
                ImGui.tableHeadersRow();

                int vStart = Math.max(0, varcChanges.size() - 20);
                for (int i = varcChanges.size() - 1; i >= vStart; i--) {
                    VarcChange vc = varcChanges.get(i);
                    ImGui.tableNextRow();
                    ImGui.tableSetColumnIndex(0);
                    ImGui.text(LocalTime.ofInstant(Instant.ofEpochMilli(vc.timestamp()), ZoneId.systemDefault())
                            .format(XapiState.TIME_FMT));
                    ImGui.tableSetColumnIndex(1);
                    ImGui.text(String.valueOf(vc.gameTick()));
                    ImGui.tableSetColumnIndex(2);
                    ImGui.text(String.valueOf(vc.varcId()));
                    ImGui.tableSetColumnIndex(3);
                    ImGui.text(String.valueOf(vc.oldValue()));
                    ImGui.tableSetColumnIndex(4);
                    ImGui.text(String.valueOf(vc.newValue()));
                }
                ImGui.endTable();
            }
        }
    }

    private static int[] parseIds(String input) {
        if (input == null || input.isBlank()) return new int[0];
        String[] parts = input.split("[,\\s]+");
        java.util.List<Integer> ids = new java.util.ArrayList<>();
        for (String p : parts) {
            try {
                String trimmed = p.trim();
                if (!trimmed.isEmpty()) ids.add(Integer.parseInt(trimmed));
            } catch (NumberFormatException ignored) {}
        }
        return ids.stream().mapToInt(Integer::intValue).toArray();
    }

    private void renderDebug() {
        ImGui.textColored(1.0f, 1.0f, 0.4f, 1.0f, "Debug Panel — Raw Values & Variable Probe");

        ImGui.text(String.format("Last activation tick: %d", state.lastAbilityActivationTick));
        ImGui.spacing();

        // ── Variable probe ──
        renderVariableProbe();
        ImGui.spacing();

        // Raw params per ability
        List<ActionBarEntry> entries = state.actionBarEntries;
        List<ActionBarEntry> abilities = entries.stream()
                .filter(e -> e.spriteId() > 0 && !e.name().isEmpty())
                .toList();

        if (abilities.isEmpty()) {
            ImGui.text("No abilities with resolved names to show.");
            return;
        }

        int flags = ImGuiTableFlags.Borders | ImGuiTableFlags.RowBg
                | ImGuiTableFlags.SizingStretchProp | ImGuiTableFlags.Resizable;
        if (ImGui.beginTable("##ab_debug", 12, flags)) {
            ImGui.tableSetupColumn("Name", 0, 1.2f);
            ImGui.tableSetupColumn("Iface", 0, 0.3f);
            ImGui.tableSetupColumn("Slot", 0, 0.2f);
            ImGui.tableSetupColumn("Sprite", 0, 0.4f);
            ImGui.tableSetupColumn("p2796\nCD Ticks", 0, 0.4f);
            ImGui.tableSetupColumn("p2798\nAdrenCost", 0, 0.4f);
            ImGui.tableSetupColumn("p2799\nAbilType", 0, 0.4f);
            ImGui.tableSetupColumn("p2800\nAdrenGain", 0, 0.4f);
            ImGui.tableSetupColumn("p4332\nSpecCost", 0, 0.4f);
            ImGui.tableSetupColumn("p5550\nGCD Cast", 0, 0.4f);
            ImGui.tableSetupColumn("p3394\nNeedTgt", 0, 0.4f);
            ImGui.tableSetupColumn("Category", 0, 0.5f);
            ImGui.tableHeadersRow();

            for (ActionBarEntry entry : abilities) {
                ImGui.tableNextRow();
                ImGui.tableSetColumnIndex(0); ImGui.text(entry.name());
                ImGui.tableSetColumnIndex(1); ImGui.text(String.valueOf(entry.interfaceId()));
                ImGui.tableSetColumnIndex(2); ImGui.text(entry.slot() > 0 ? String.valueOf(entry.slot()) : "-");
                ImGui.tableSetColumnIndex(3); ImGui.text(String.valueOf(entry.spriteId()));
                ImGui.tableSetColumnIndex(4); ImGui.text(entry.cooldownTicks() > 0 ? String.valueOf(entry.cooldownTicks()) : "-");
                ImGui.tableSetColumnIndex(5); ImGui.text(entry.adrenCost() > 0 ? String.valueOf(entry.adrenCost()) : "-");
                ImGui.tableSetColumnIndex(6); ImGui.text(String.valueOf(entry.abilityType()));
                ImGui.tableSetColumnIndex(7); ImGui.text(entry.adrenGain() > 0 ? String.valueOf(entry.adrenGain()) : "-");
                ImGui.tableSetColumnIndex(8); ImGui.text(entry.specCost() > 0 ? String.valueOf(entry.specCost()) : "-");
                ImGui.tableSetColumnIndex(9); ImGui.text(entry.canCastGCD() >= 0 ? String.valueOf(entry.canCastGCD()) : "absent");
                ImGui.tableSetColumnIndex(10); ImGui.text(entry.requiresTarget() >= 0 ? String.valueOf(entry.requiresTarget()) : "absent");
                ImGui.tableSetColumnIndex(11); ImGui.text(entry.slotType());
            }

            ImGui.endTable();
        }
    }

    private void renderChangeLog() {
        List<ActionBarChange> changes = state.actionBarChangeLog;
        ImGui.textColored(0.9f, 0.7f, 0.3f, 1.0f, "Change Log (" + changes.size() + ")");
        ImGui.sameLine();
        if (ImGui.smallButton("Clear##changes")) { state.actionBarChangeLog.clear(); }

        if (changes.isEmpty()) {
            ImGui.text("No changes detected yet.");
            return;
        }

        int flags = ImGuiTableFlags.Borders | ImGuiTableFlags.RowBg | ImGuiTableFlags.SizingStretchProp;
        if (ImGui.beginTable("##ab_changes", 5, flags)) {
            ImGui.tableSetupColumn("Time", 0, 0.6f);
            ImGui.tableSetupColumn("Interface", 0, 0.5f);
            ImGui.tableSetupColumn("Slot", 0, 0.3f);
            ImGui.tableSetupColumn("Old", 0, 1f);
            ImGui.tableSetupColumn("New", 0, 1f);
            ImGui.tableHeadersRow();

            // Show last 20 changes, newest first
            int start = Math.max(0, changes.size() - 20);
            for (int i = changes.size() - 1; i >= start; i--) {
                ActionBarChange c = changes.get(i);
                ImGui.tableNextRow();
                ImGui.tableSetColumnIndex(0);
                ImGui.text(LocalTime.ofInstant(Instant.ofEpochMilli(c.timestamp()), ZoneId.systemDefault())
                        .format(XapiState.TIME_FMT));
                ImGui.tableSetColumnIndex(1);
                ImGui.text(String.valueOf(c.interfaceId()));
                ImGui.tableSetColumnIndex(2);
                ImGui.text(String.valueOf(c.slot()));
                ImGui.tableSetColumnIndex(3);
                ImGui.textColored(0.8f, 0.4f, 0.4f, 1f, c.oldName().isEmpty() ? "(empty)" : c.oldName());
                ImGui.tableSetColumnIndex(4);
                ImGui.textColored(0.4f, 0.8f, 0.4f, 1f, c.newName().isEmpty() ? "(empty)" : c.newName());
            }
            ImGui.endTable();
        }
    }

    private void renderHistory() {
        List<ActionBarActivation> history = state.actionBarHistory;
        ImGui.textColored(0.3f, 0.9f, 0.6f, 1.0f, "Activation History (" + history.size() + ")");
        ImGui.sameLine();
        if (ImGui.smallButton("Clear##hist")) { state.actionBarHistory.clear(); }
        ImGui.sameLine();
        if (ImGui.smallButton("Copy##hist")) {
            StringBuilder sb = new StringBuilder("Time\tTick\tName\tOption\n");
            for (ActionBarActivation a : history) {
                sb.append(LocalTime.ofInstant(Instant.ofEpochMilli(a.timestamp()), ZoneId.systemDefault())
                        .format(XapiState.TIME_FMT)).append('\t')
                        .append(a.gameTick()).append('\t')
                        .append(a.name()).append('\t')
                        .append(a.option()).append('\n');
            }
            ImGui.setClipboardText(sb.toString());
        }

        if (history.isEmpty()) {
            ImGui.text("No activations recorded yet.");
            return;
        }

        int flags = ImGuiTableFlags.Borders | ImGuiTableFlags.RowBg | ImGuiTableFlags.SizingStretchProp;
        if (ImGui.beginTable("##ab_history", 4, flags)) {
            ImGui.tableSetupColumn("Time", 0, 0.6f);
            ImGui.tableSetupColumn("Tick", 0, 0.3f);
            ImGui.tableSetupColumn("Name", 0, 1.2f);
            ImGui.tableSetupColumn("Option", 0, 0.8f);
            ImGui.tableHeadersRow();

            int start = Math.max(0, history.size() - 30);
            for (int i = history.size() - 1; i >= start; i--) {
                ActionBarActivation a = history.get(i);
                ImGui.tableNextRow();
                ImGui.tableSetColumnIndex(0);
                ImGui.text(LocalTime.ofInstant(Instant.ofEpochMilli(a.timestamp()), ZoneId.systemDefault())
                        .format(XapiState.TIME_FMT));
                ImGui.tableSetColumnIndex(1);
                ImGui.text(String.valueOf(a.gameTick()));
                ImGui.tableSetColumnIndex(2);
                ImGui.text(a.name().isEmpty() ? "-" : a.name());
                ImGui.tableSetColumnIndex(3);
                ImGui.text(a.option().isEmpty() ? "-" : a.option());
            }
            ImGui.endTable();
        }
    }

    private void renderComparison() {
        ImGui.textColored(0.9f, 0.6f, 0.9f, 1.0f, "Bar Comparison");

        List<ActionBarEntry> snapshot = state.comparisonSnapshot;
        int snapBar = state.comparisonBarPreset;
        List<ActionBarEntry> current = state.actionBarEntries;

        if (snapshot.isEmpty()) {
            ImGui.text("No snapshot taken. Click 'Snapshot' to capture current bar state, then switch bars to compare.");
            return;
        }

        ImGui.text(String.format("Snapshot: Bar %d  |  Current: Bar %d", snapBar, state.activeBarPreset));

        // Compare by interface + slot
        int flags = ImGuiTableFlags.Borders | ImGuiTableFlags.RowBg | ImGuiTableFlags.SizingStretchProp;
        for (int ifaceId : ACTION_BAR_INTERFACES) {
            List<ActionBarEntry> snapIface = snapshot.stream().filter(e -> e.interfaceId() == ifaceId && e.slot() > 0).toList();
            List<ActionBarEntry> currIface = current.stream().filter(e -> e.interfaceId() == ifaceId && e.slot() > 0).toList();
            if (snapIface.isEmpty() && currIface.isEmpty()) continue;

            ImGui.spacing();
            ImGui.textColored(0.6f, 0.6f, 0.8f, 1.0f, "Interface " + ifaceId);

            if (ImGui.beginTable("##ab_cmp_" + ifaceId, 4, flags)) {
                ImGui.tableSetupColumn("Slot", 0, 0.3f);
                ImGui.tableSetupColumn("Snapshot (Bar " + snapBar + ")", 0, 1.5f);
                ImGui.tableSetupColumn("Current (Bar " + state.activeBarPreset + ")", 0, 1.5f);
                ImGui.tableSetupColumn("Status", 0, 0.5f);
                ImGui.tableHeadersRow();

                for (int slot = 1; slot <= 14; slot++) {
                    String snapName = findSlotName(snapIface, slot);
                    String currName = findSlotName(currIface, slot);
                    boolean same = snapName.equals(currName);
                    boolean bothEmpty = snapName.isEmpty() && currName.isEmpty();
                    if (bothEmpty) continue; // skip slots empty in both

                    ImGui.tableNextRow();
                    ImGui.tableSetColumnIndex(0);
                    ImGui.text(String.valueOf(slot));

                    ImGui.tableSetColumnIndex(1);
                    if (snapName.isEmpty()) ImGui.textColored(0.4f, 0.4f, 0.4f, 1f, "(empty)");
                    else ImGui.text(snapName);

                    ImGui.tableSetColumnIndex(2);
                    if (currName.isEmpty()) ImGui.textColored(0.4f, 0.4f, 0.4f, 1f, "(empty)");
                    else ImGui.text(currName);

                    ImGui.tableSetColumnIndex(3);
                    if (same) {
                        ImGui.textColored(0.5f, 0.5f, 0.5f, 1f, "Same");
                    } else {
                        ImGui.textColored(1.0f, 0.8f, 0.2f, 1f, "Changed");
                    }
                }

                ImGui.endTable();
            }
        }
    }

    private static String findSlotName(List<ActionBarEntry> entries, int slot) {
        for (ActionBarEntry e : entries) {
            if (e.slot() == slot) return e.name();
        }
        return "";
    }

    private static void toggleButton(String label, boolean active, Runnable onClick) {
        if (active) {
            ImGui.pushStyleColor(ImGuiCol.Button, 0.2f, 0.5f, 0.3f, 1f);
        } else {
            ImGui.pushStyleColor(ImGuiCol.Button, 0.2f, 0.2f, 0.25f, 1f);
        }
        if (ImGui.smallButton(label + "##tog")) { onClick.run(); }
        ImGui.popStyleColor();
    }

    private boolean matchesFilter(ActionBarEntry entry, String filter) {
        if (filter.isEmpty()) return true;
        if (entry.name().toLowerCase().contains(filter)) return true;
        if (entry.slotType().toLowerCase().contains(filter)) return true;
        for (String opt : entry.options()) {
            if (opt.toLowerCase().contains(filter)) return true;
        }
        return false;
    }

    static int resolveSlotNumber(int interfaceId, int componentId) {
        int base = switch (interfaceId) {
            case 1430 -> 64;  case 1670 -> 21;  case 1671 -> 19;
            case 1672, 1673 -> 16;  default -> -1;
        };
        if (base < 0) return -1;
        int offset = componentId - base;
        if (offset < 0 || offset % SLOT_STRIDE != 0) return -1;
        int slot = offset / SLOT_STRIDE + 1;
        return slot <= 14 ? slot : -1;
    }
}
