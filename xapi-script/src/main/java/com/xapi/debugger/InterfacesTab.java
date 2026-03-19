package com.xapi.debugger;

import static com.xapi.debugger.XapiData.*;

import com.botwithus.bot.api.model.Component;
import com.botwithus.bot.api.model.OpenInterface;

import imgui.ImGui;
import imgui.flag.ImGuiCol;
import imgui.flag.ImGuiTableFlags;

import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

final class InterfacesTab {

    private final XapiScript script;

    InterfacesTab(XapiScript s) {
        this.script = s;
    }

    void render() {
        if (ImGui.beginTabBar("##iface_sub_tabs")) {
            if (ImGui.beginTabItem("Current")) {
                renderCurrentInterfacesSubTab();
                ImGui.endTabItem();
            }
            if (ImGui.beginTabItem("Events")) {
                renderInterfaceEventsSubTab();
                ImGui.endTabItem();
            }
            ImGui.endTabBar();
        }
    }

    private void renderCurrentInterfacesSubTab() {
        ImGui.textColored(0.6f, 0.6f, 0.6f, 1f, "Click an interface to load its components (updates every ~2s)");

        List<OpenInterface> ifaces = script.openInterfaces;
        if (ifaces.isEmpty()) {
            ImGui.text("No open interfaces detected.");
            return;
        }

        List<OpenInterface> sorted = new ArrayList<>(ifaces);
        sorted.sort(Comparator.comparingInt(OpenInterface::interfaceId));

        for (OpenInterface iface : sorted) {
            int ifaceId = iface.interfaceId();
            boolean open = ImGui.treeNode("Interface " + ifaceId + "##iface_" + ifaceId);
            if (ImGui.isItemClicked()) {
                script.inspectInterfaceId = ifaceId;
            }

            if (open) {
                List<Component> children = script.componentCache.get(ifaceId);
                if (children == null || children.isEmpty()) {
                    ImGui.textColored(0.7f, 0.7f, 0.7f, 1f, "Click to load components...");
                } else {
                    int flags = ImGuiTableFlags.Borders | ImGuiTableFlags.RowBg
                            | ImGuiTableFlags.SizingStretchProp | ImGuiTableFlags.Resizable;
                    if (ImGui.beginTable("##comps_" + ifaceId, 7, flags)) {
                        ImGui.tableSetupColumn("CompId", 0, 0.4f);
                        ImGui.tableSetupColumn("SubId", 0, 0.4f);
                        ImGui.tableSetupColumn("Type", 0, 0.3f);
                        ImGui.tableSetupColumn("ItemId", 0, 0.4f);
                        ImGui.tableSetupColumn("Sprite", 0, 0.4f);
                        ImGui.tableSetupColumn("Text/Options", 0, 1.5f);
                        ImGui.tableSetupColumn("Code", 0, 2f);
                        ImGui.tableSetupScrollFreeze(0, 1);
                        ImGui.tableHeadersRow();

                        for (int i = 0; i < children.size(); i++) {
                            Component c = children.get(i);
                            String key = c.interfaceId() + ":" + c.componentId();

                            ImGui.tableNextRow();
                            ImGui.tableSetColumnIndex(0); ImGui.text(String.valueOf(c.componentId()));
                            ImGui.tableSetColumnIndex(1); ImGui.text(String.valueOf(c.subComponentId()));
                            ImGui.tableSetColumnIndex(2); ImGui.text(String.valueOf(c.type()));
                            ImGui.tableSetColumnIndex(3);
                            if (c.itemId() > 0) ImGui.text(String.valueOf(c.itemId()));
                            ImGui.tableSetColumnIndex(4);
                            if (c.spriteId() > 0) ImGui.text(String.valueOf(c.spriteId()));

                            ImGui.tableSetColumnIndex(5);
                            String text = script.componentTextCache.getOrDefault(key, "");
                            List<String> opts = script.componentOptionsCache.getOrDefault(key, List.of());
                            if (!text.isEmpty()) ImGui.text(text);
                            if (!opts.isEmpty()) {
                                ImGui.textColored(0.7f, 0.7f, 0.4f, 1f, String.join(" | ", opts));
                            }

                            ImGui.tableSetColumnIndex(6);
                            String compCode = "api.queryComponents(ComponentFilter.builder().interfaceId("
                                    + c.interfaceId() + ").componentId(" + c.componentId() + ").build())";
                            ImGui.pushStyleColor(ImGuiCol.Text, 0.4f, 0.9f, 0.5f, 1f);
                            if (ImGui.selectable("query##comp_" + ifaceId + "_" + i)) {
                                ImGui.setClipboardText(compCode);
                            }
                            ImGui.popStyleColor();
                            if (ImGui.isItemHovered()) ImGui.setTooltip(compCode);
                        }
                        ImGui.endTable();
                    }
                }
                ImGui.treePop();
            }
        }
    }

    private void renderInterfaceEventsSubTab() {
        ImGui.textColored(0.6f, 0.6f, 0.6f, 1f, "Poll interval ~2s. Very fast open/close may be missed.");

        if (ImGui.smallButton("Clear Events")) {
            script.interfaceEventLog.clear();
        }
        ImGui.sameLine();
        if (ImGui.smallButton("Copy All")) {
            StringBuilder sb = new StringBuilder();
            for (InterfaceEvent ev : script.interfaceEventLog) {
                sb.append(String.format("[%s] Tick:%d Interface:%d %s\n",
                        XapiScript.TIME_FMT.format(Instant.ofEpochMilli(ev.timestamp()).atZone(ZoneId.systemDefault()).toLocalTime()),
                        ev.gameTick(), ev.interfaceId(), ev.type()));
                if ("OPENED".equals(ev.type())) {
                    for (InterfaceComponentSnapshot cs : ev.components()) {
                        if ((cs.text() != null && !cs.text().isEmpty()) || !cs.options().isEmpty()) {
                            sb.append(String.format("  Comp:%d Sub:%d", cs.componentId(), cs.subComponentId()));
                            if (cs.text() != null && !cs.text().isEmpty()) sb.append(" Text:\"").append(cs.text()).append("\"");
                            if (!cs.options().isEmpty()) sb.append(" Options:").append(cs.options());
                            sb.append(" Code:api.queueAction(new GameAction(57, 1, -1, ")
                              .append((ev.interfaceId() << 16) | cs.componentId()).append("))\n");
                        }
                    }
                }
            }
            ImGui.setClipboardText(sb.toString());
        }

        List<InterfaceEvent> events = script.interfaceEventLog;
        if (events.isEmpty()) {
            ImGui.text("No interface events recorded yet.");
            return;
        }

        // Render events in reverse (newest first)
        for (int idx = events.size() - 1; idx >= 0; idx--) {
            InterfaceEvent ev = events.get(idx);
            String time = XapiScript.TIME_FMT.format(Instant.ofEpochMilli(ev.timestamp()).atZone(ZoneId.systemDefault()).toLocalTime());
            boolean isOpen = "OPENED".equals(ev.type());

            // Find first non-empty text as summary
            String summary = "";
            if (isOpen) {
                for (InterfaceComponentSnapshot cs : ev.components()) {
                    if (cs.text() != null && !cs.text().isEmpty()) {
                        summary = cs.text();
                        if (summary.length() > 60) summary = summary.substring(0, 57) + "...";
                        break;
                    }
                }
            }

            String header = String.format("[%s] Tick:%d  %s  Interface %d  %s",
                    time, ev.gameTick(), ev.type(), ev.interfaceId(), summary);

            // Color: green for OPENED, red for CLOSED
            if (isOpen) {
                ImGui.pushStyleColor(ImGuiCol.Text, 0.2f, 1f, 0.2f, 1f);
            } else {
                ImGui.pushStyleColor(ImGuiCol.Text, 1f, 0.3f, 0.3f, 1f);
            }

            boolean expanded = isOpen && !ev.components().isEmpty()
                    && ImGui.treeNode(header + "##evt_" + idx);
            if (!expanded) {
                if (!isOpen || ev.components().isEmpty()) ImGui.text(header);
            }
            ImGui.popStyleColor();

            if (expanded) {
                // Component detail table
                int flags = ImGuiTableFlags.Borders | ImGuiTableFlags.RowBg
                        | ImGuiTableFlags.SizingStretchProp | ImGuiTableFlags.Resizable;
                if (ImGui.beginTable("##evt_comps_" + idx, 5, flags)) {
                    ImGui.tableSetupColumn("CompId", 0, 0.3f);
                    ImGui.tableSetupColumn("Type", 0, 0.2f);
                    ImGui.tableSetupColumn("Text", 0, 2f);
                    ImGui.tableSetupColumn("Options", 0, 1f);
                    ImGui.tableSetupColumn("Interaction Code", 0, 2f);
                    ImGui.tableSetupScrollFreeze(0, 1);
                    ImGui.tableHeadersRow();

                    for (InterfaceComponentSnapshot cs : ev.components()) {
                        // Skip empty components for readability
                        boolean hasContent = (cs.text() != null && !cs.text().isEmpty())
                                || !cs.options().isEmpty() || cs.itemId() > 0;
                        if (!hasContent) continue;

                        ImGui.tableNextRow();
                        ImGui.tableSetColumnIndex(0);
                        ImGui.text(cs.componentId() + ":" + cs.subComponentId());

                        ImGui.tableSetColumnIndex(1);
                        ImGui.text(String.valueOf(cs.type()));

                        ImGui.tableSetColumnIndex(2);
                        if (cs.text() != null && !cs.text().isEmpty()) {
                            ImGui.textWrapped(cs.text());
                        }

                        ImGui.tableSetColumnIndex(3);
                        if (!cs.options().isEmpty()) {
                            List<String> validOpts = new ArrayList<>();
                            for (String opt : cs.options()) {
                                if (opt != null && !opt.isEmpty()) validOpts.add(opt);
                            }
                            if (!validOpts.isEmpty()) {
                                ImGui.textColored(1f, 1f, 0.3f, 1f, String.join(" | ", validOpts));
                            }
                        }

                        ImGui.tableSetColumnIndex(4);
                        // Generate interaction code
                        int hash = (ev.interfaceId() << 16) | cs.componentId();
                        String interactCode;
                        List<String> validOpts = new ArrayList<>();
                        for (String opt : cs.options()) {
                            if (opt != null && !opt.isEmpty()) validOpts.add(opt);
                        }
                        if (!validOpts.isEmpty()) {
                            interactCode = "ComponentHelper.interactComponent(api, comp, \"" + validOpts.get(0) + "\")";
                        } else {
                            interactCode = "api.queueAction(new GameAction(57, 1, -1, " + hash + "))";
                        }
                        ImGui.pushStyleColor(ImGuiCol.Text, 0.4f, 0.9f, 0.5f, 1f);
                        if (ImGui.selectable(interactCode + "##evtcode_" + idx + "_" + cs.componentId())) {
                            ImGui.setClipboardText(interactCode);
                        }
                        ImGui.popStyleColor();
                    }
                    ImGui.endTable();
                }
                ImGui.treePop();
            }
        }
    }
}
