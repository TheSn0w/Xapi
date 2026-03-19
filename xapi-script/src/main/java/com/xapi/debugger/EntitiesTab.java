package com.xapi.debugger;

import static com.xapi.debugger.XapiData.*;

import com.botwithus.bot.api.model.Entity;
import com.botwithus.bot.api.model.EntityInfo;
import com.botwithus.bot.api.model.GroundItem;
import com.botwithus.bot.api.model.GroundItemStack;
import com.botwithus.bot.api.model.ItemType;

import imgui.ImGui;
import imgui.flag.ImGuiCol;
import imgui.flag.ImGuiTableFlags;

import java.util.List;
import java.util.Map;

final class EntitiesTab {

    private final XapiScript script;

    EntitiesTab(XapiScript s) {
        this.script = s;
    }

    void render() {
        ImGui.text("Filter:");
        ImGui.sameLine();
        ImGui.pushItemWidth(200);
        ImGui.inputText("##entity_filter", script.entityFilterText);
        ImGui.popItemWidth();
        ImGui.sameLine();
        ImGui.text("Dist:");
        ImGui.sameLine();
        if (ImGui.arrowButton("##dist_dec", 0 /* ImGuiDir_Left */)) {
            script.entityDistanceArr[0] = Math.max(5, script.entityDistanceArr[0] - 5);
            script.entityDistanceFilter = script.entityDistanceArr[0];
            script.settingsDirty = true;
        }
        ImGui.sameLine();
        ImGui.text(String.valueOf(script.entityDistanceArr[0]));
        ImGui.sameLine();
        if (ImGui.arrowButton("##dist_inc", 1 /* ImGuiDir_Right */)) {
            script.entityDistanceArr[0] = Math.min(200, script.entityDistanceArr[0] + 5);
            script.entityDistanceFilter = script.entityDistanceArr[0];
            script.settingsDirty = true;
        }
        ImGui.sameLine();
        ImGui.textColored(0.6f, 0.6f, 0.6f, 1f, "tiles");

        String filter = script.entityFilterText.get().toLowerCase();

        // Show counts in status line
        ImGui.textColored(0.6f, 0.6f, 0.6f, 0.8f,
                String.format("NPCs: %d  |  Players: %d  |  Objects: %d  |  Ground Items: %d",
                        script.nearbyNpcs.size(), script.nearbyPlayers.size(),
                        script.nearbyObjects.size(), script.nearbyGroundItems.size()));

        if (ImGui.beginTabBar("##entity_sub_tabs")) {
            if (ImGui.beginTabItem("NPCs")) {
                renderEntityTable(script.nearbyNpcs, "npc", filter);
                ImGui.endTabItem();
            }
            if (ImGui.beginTabItem("Players")) {
                renderEntityTable(script.nearbyPlayers, "player", filter);
                ImGui.endTabItem();
            }
            if (ImGui.beginTabItem("Objects")) {
                renderEntityTable(script.nearbyObjects, "location", filter);
                ImGui.endTabItem();
            }
            if (ImGui.beginTabItem("Ground Items")) {
                renderGroundItemsTable(filter);
                ImGui.endTabItem();
            }
            ImGui.endTabBar();
        }
    }

    private void renderEntityTable(List<Entity> entities, String type, String filter) {
        // Selected entity info panel (above table)
        EntityInfo selInfo = script.selectedEntityInfo;
        if (selInfo != null && script.selectedEntityHandle >= 0) {
            renderSelectedEntityPanel(selInfo);
            ImGui.spacing();
        }

        int flags = ImGuiTableFlags.Borders | ImGuiTableFlags.RowBg
                | ImGuiTableFlags.SizingStretchProp | ImGuiTableFlags.ScrollY | ImGuiTableFlags.Resizable;
        float tableHeight = ImGui.getContentRegionAvailY();
        if (ImGui.beginTable("##entities_" + type, 8, flags, 0, tableHeight)) {
            ImGui.tableSetupColumn("Name", 0, 1.5f);
            ImGui.tableSetupColumn("TypeId", 0, 0.5f);
            ImGui.tableSetupColumn("Index", 0, 0.4f);
            ImGui.tableSetupColumn("Position", 0, 0.8f);
            ImGui.tableSetupColumn("Lvl", 0, 0.3f);
            ImGui.tableSetupColumn("Anim", 0, 0.4f);
            ImGui.tableSetupColumn("Moving", 0, 0.3f);
            ImGui.tableSetupColumn("Code", 0, 1.8f);
            ImGui.tableSetupScrollFreeze(0, 1);
            ImGui.tableHeadersRow();

            Map<Integer, EntityInfo> infoSnap = script.entityInfoCache;

            for (int i = 0; i < entities.size(); i++) {
                Entity e = entities.get(i);
                String name = e.name() != null ? e.name() : "";
                if (!filter.isEmpty() && !name.toLowerCase().contains(filter)
                        && !String.valueOf(e.typeId()).contains(filter)) continue;

                EntityInfo info = infoSnap.get(e.handle());
                boolean isSelected = e.handle() == script.selectedEntityHandle;

                ImGui.tableNextRow();

                // Highlight selected row
                if (isSelected) {
                    ImGui.tableSetBgColor(1, ImGui.colorConvertFloat4ToU32(0.2f, 0.4f, 0.6f, 0.5f));
                }

                ImGui.tableSetColumnIndex(0);
                if (ImGui.selectable(name + "##sel_" + type + "_" + i, isSelected, 0)) {
                    script.selectedEntityHandle = isSelected ? -1 : e.handle(); // toggle
                }

                // Rich hover tooltip
                if (ImGui.isItemHovered() && info != null) {
                    ImGui.beginTooltip();
                    ImGui.text("--- " + name + " (ID: " + e.typeId() + ") ---");
                    ImGui.text("Handle: " + info.handle() + "  |  Index: " + info.serverIndex());
                    ImGui.text("Position: (" + info.tileX() + ", " + info.tileY() + ", " + info.tileZ() + ")");
                    if (info.maxHealth() > 0) {
                        ImGui.text("Health: " + info.health() + " / " + info.maxHealth());
                    }
                    ImGui.text("Combat Lvl: " + info.combatLevel());
                    ImGui.text("Animation: " + info.animationId() + "  |  Stance: " + info.stanceId());
                    ImGui.text("Moving: " + (info.isMoving() ? "Yes" : "No") + "  |  Hidden: " + (info.isHidden() ? "Yes" : "No"));
                    if (info.overheadText() != null && !info.overheadText().isEmpty()) {
                        ImGui.text("Overhead: " + info.overheadText());
                    }
                    if (info.followingIndex() >= 0) {
                        ImGui.text("Following: idx " + info.followingIndex());
                    }
                    ImGui.separator();
                    ImGui.textColored(0.6f, 0.6f, 0.6f, 1f, "Click to select | Right-click to copy query");
                    ImGui.endTooltip();
                }

                ImGui.tableSetColumnIndex(1); ImGui.text(String.valueOf(e.typeId()));
                ImGui.tableSetColumnIndex(2); ImGui.text(String.valueOf(e.serverIndex()));
                ImGui.tableSetColumnIndex(3); ImGui.text("(" + e.tileX() + ", " + e.tileY() + ", " + e.tileZ() + ")");
                ImGui.tableSetColumnIndex(4);
                if (info != null && info.combatLevel() > 0) ImGui.text(String.valueOf(info.combatLevel()));
                ImGui.tableSetColumnIndex(5);
                if (info != null && info.animationId() >= 0) ImGui.text(String.valueOf(info.animationId()));
                ImGui.tableSetColumnIndex(6); ImGui.text(e.isMoving() ? "Y" : "");

                ImGui.tableSetColumnIndex(7);
                String queryCode;
                if ("npc".equals(type)) {
                    queryCode = name.isEmpty()
                            ? "api.queryEntities(EntityFilter.builder().type(\"npc\").typeId(" + e.typeId() + ").maxResults(1).build())"
                            : "api.queryEntities(EntityFilter.builder().type(\"npc\").namePattern(\"" + name + "\").maxResults(1).build())";
                } else if ("player".equals(type)) {
                    queryCode = "api.queryEntities(EntityFilter.builder().type(\"player\").namePattern(\"" + name + "\").maxResults(1).build())";
                } else {
                    queryCode = name.isEmpty()
                            ? "api.queryEntities(EntityFilter.builder().type(\"location\").typeId(" + e.typeId() + ").maxResults(1).build())"
                            : "api.queryEntities(EntityFilter.builder().type(\"location\").namePattern(\"" + name + "\").maxResults(1).build())";
                }

                ImGui.pushStyleColor(ImGuiCol.Text, 0.4f, 0.9f, 0.5f, 1f);
                String codeLabel = "location".equals(type) ? "objects" : type + "s";
                String shortCode = codeLabel + ".query().name(\"" + name + "\").nearest()";
                if (ImGui.selectable(shortCode + "##code_" + type + "_" + i)) {
                    ImGui.setClipboardText(queryCode);
                }
                ImGui.popStyleColor();
                if (ImGui.isItemHovered()) ImGui.setTooltip("Click to copy full query");
            }
            ImGui.endTable();
        }
    }

    private void renderSelectedEntityPanel(EntityInfo info) {
        ImGui.pushStyleColor(ImGuiCol.ChildBg, 0.15f, 0.15f, 0.2f, 0.9f);
        ImGui.beginChild("##selected_entity", 0, 120, true);

        String name = info.name() != null ? info.name() : "Unknown";
        ImGui.textColored(1f, 0.8f, 0.2f, 1f, "Selected: " + name + " (ID: " + info.typeId() + ")");

        ImGui.text("Handle: " + info.handle() + "  |  Index: " + info.serverIndex()
                + "  |  Combat Lvl: " + info.combatLevel());
        ImGui.text("Position: (" + info.tileX() + ", " + info.tileY() + ", " + info.tileZ() + ")"
                + "  |  Moving: " + (info.isMoving() ? "Yes" : "No")
                + "  |  Hidden: " + (info.isHidden() ? "Yes" : "No"));
        ImGui.text("Animation: " + info.animationId() + "  |  Stance: " + info.stanceId());

        if (info.maxHealth() > 0) {
            float pct = (float) info.health() / info.maxHealth();
            ImGui.text("Health: " + info.health() + " / " + info.maxHealth());
            ImGui.sameLine();
            ImGui.pushStyleColor(ImGuiCol.PlotHistogram, pct > 0.5f ? 0xFF00CC00 : pct > 0.25f ? 0xFF00CCCC : 0xFF0000CC);
            ImGui.progressBar(pct, 100, 14, "");
            ImGui.popStyleColor();
        }

        if (info.overheadText() != null && !info.overheadText().isEmpty()) {
            ImGui.text("Overhead: " + info.overheadText());
        }
        if (info.followingIndex() >= 0) {
            ImGui.text("Following: idx " + info.followingIndex());
        }

        ImGui.sameLine(ImGui.getContentRegionAvailX() - 60);
        if (ImGui.smallButton("Deselect")) {
            script.selectedEntityHandle = -1;
            script.selectedEntityInfo = null;
        }

        ImGui.endChild();
        ImGui.popStyleColor();
    }

    private void renderGroundItemsTable(String filter) {
        List<GroundItemStack> stacks = script.nearbyGroundItems;
        if (stacks.isEmpty()) {
            ImGui.text("No ground items nearby.");
            return;
        }

        Map<Integer, ItemType> typeCache = script.groundItemTypeCache;

        int flags = ImGuiTableFlags.Borders | ImGuiTableFlags.RowBg
                | ImGuiTableFlags.SizingStretchProp | ImGuiTableFlags.ScrollY | ImGuiTableFlags.Resizable;
        float tableHeight = ImGui.getContentRegionAvailY();
        if (ImGui.beginTable("##ground_items", 6, flags, 0, tableHeight)) {
            ImGui.tableSetupColumn("Name", 0, 1.2f);
            ImGui.tableSetupColumn("ItemId", 0, 0.5f);
            ImGui.tableSetupColumn("Qty", 0, 0.4f);
            ImGui.tableSetupColumn("Position", 0, 0.8f);
            ImGui.tableSetupColumn("Handle", 0, 0.4f);
            ImGui.tableSetupColumn("Code", 0, 1.8f);
            ImGui.tableSetupScrollFreeze(0, 1);
            ImGui.tableHeadersRow();

            int row = 0;
            for (GroundItemStack stack : stacks) {
                if (stack.items() == null) continue;
                for (GroundItem gi : stack.items()) {
                    ItemType itemType = typeCache.get(gi.itemId());
                    String itemName = itemType != null && itemType.name() != null ? itemType.name() : "";
                    String idStr = String.valueOf(gi.itemId());

                    // Filter by name or ID
                    if (!filter.isEmpty() && !idStr.contains(filter)
                            && !itemName.toLowerCase().contains(filter)) continue;

                    ImGui.tableNextRow();
                    ImGui.tableSetColumnIndex(0);
                    ImGui.text(itemName);
                    // Hover: show ground options
                    if (ImGui.isItemHovered() && itemType != null) {
                        ImGui.beginTooltip();
                        ImGui.text("Item: " + itemName + " (ID: " + gi.itemId() + ")");
                        ImGui.text("Quantity: " + gi.quantity());
                        if (itemType.groundOptions() != null && !itemType.groundOptions().isEmpty()) {
                            ImGui.separator();
                            ImGui.textColored(0.7f, 0.7f, 0.4f, 1f, "Options:");
                            for (String opt : itemType.groundOptions()) {
                                if (opt != null && !opt.isEmpty()) {
                                    ImGui.text("  " + opt);
                                }
                            }
                        }
                        ImGui.endTooltip();
                    }
                    ImGui.tableSetColumnIndex(1); ImGui.text(idStr);
                    ImGui.tableSetColumnIndex(2); ImGui.text(String.valueOf(gi.quantity()));
                    ImGui.tableSetColumnIndex(3); ImGui.text("(" + stack.tileX() + ", " + stack.tileY() + ", " + stack.tileZ() + ")");
                    ImGui.tableSetColumnIndex(4); ImGui.text(String.valueOf(stack.handle()));
                    ImGui.tableSetColumnIndex(5);
                    String code = "api.queryGroundItems(EntityFilter.builder().sortByDistance(true).maxResults(10).build())";
                    ImGui.pushStyleColor(ImGuiCol.Text, 0.4f, 0.9f, 0.5f, 1f);
                    if (ImGui.selectable("groundItem(" + gi.itemId() + ")##gi_" + row)) {
                        ImGui.setClipboardText(code);
                    }
                    ImGui.popStyleColor();
                    if (ImGui.isItemHovered()) ImGui.setTooltip("Click to copy query");
                    row++;
                }
            }
            ImGui.endTable();
        }
    }
}
