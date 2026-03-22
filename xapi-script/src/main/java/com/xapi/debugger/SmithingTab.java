package com.xapi.debugger;

import imgui.ImGui;
import imgui.flag.ImGuiCol;
import imgui.flag.ImGuiTableFlags;
import imgui.flag.ImGuiTreeNodeFlags;

import com.botwithus.bot.api.inventory.Smithing;

import java.util.List;

/**
 * Debug tab that displays live smithing/smelting interface state (interface 37).
 * <p>Shows varps, varbits, quality tiers, material/product grids, and copyable action code snippets.</p>
 */
final class SmithingTab {

    private final XapiState state;

    SmithingTab(XapiState s) {
        this.state = s;
    }

    void render() {
        // Status header
        boolean open = state.smithOpen;
        ImGui.text("Smithing Interface: ");
        ImGui.sameLine();
        if (open) {
            ImGui.textColored(0.2f, 1f, 0.2f, 1f, "OPEN");
            ImGui.sameLine();
            ImGui.text(" — Mode: ");
            ImGui.sameLine();
            if (state.smithIsSmelting) {
                ImGui.textColored(1f, 0.6f, 0.2f, 1f, "SMELTING");
            } else {
                ImGui.textColored(0.4f, 0.7f, 1f, 1f, "SMITHING");
            }
        } else {
            ImGui.textColored(1f, 0.3f, 0.3f, 1f, "CLOSED");
        }

        // Active smithing status (independent of interface being open)
        ImGui.sameLine();
        ImGui.text("  |  Active: ");
        ImGui.sameLine();
        if (state.activelySmithing) {
            ImGui.textColored(0.2f, 0.8f, 1f, 1f, "SMITHING");
        } else {
            ImGui.textColored(0.6f, 0.6f, 0.6f, 1f, "IDLE");
        }

        ImGui.spacing();
        ImGui.separator();
        ImGui.spacing();

        // Active smithing section (always rendered when actively smithing)
        renderActiveSmithing();

        // Live state (Property/Value table) — always visible
        renderLiveState();

        if (!open) {
            ImGui.spacing();
            ImGui.textColored(0.6f, 0.6f, 0.6f, 1f,
                    "Open the smithing interface at an anvil or smelting at a furnace to see grids & action codes.");
            return;
        }

        ImGui.spacing();
        ImGui.separator();
        ImGui.spacing();

        // Quality tiers
        renderQualityTiers();

        ImGui.spacing();
        ImGui.separator();
        ImGui.spacing();

        // Material grid
        renderGrid("Material Grid", state.smithMaterialEntries, true);

        ImGui.spacing();
        ImGui.separator();
        ImGui.spacing();

        // Product grid
        renderGrid("Product Grid", state.smithProductEntries, false);

        ImGui.spacing();
        ImGui.separator();
        ImGui.spacing();

        // Action code snippets
        renderActionCodeSection();
    }

    private void renderActiveSmithing() {
        if (!state.activelySmithing) return;

        if (ImGui.collapsingHeader("Active Smithing Progress", ImGuiTreeNodeFlags.DefaultOpen)) {
            Smithing.UnfinishedItem active = state.activeSmithingItem;

            if (active != null) {
                // Item being created
                ImGui.text("Creating: ");
                ImGui.sameLine();
                String name = active.creatingName() != null ? active.creatingName() : "Unknown";
                ImGui.textColored(0.5f, 0.9f, 1f, 1f,
                        name + "  (ID: " + active.creatingItemId() + ")");

                ImGui.spacing();

                // Heat bar (orange)
                float heatPct = state.smithMaxHeat > 0
                        ? (float) active.currentHeat() / state.smithMaxHeat : 0f;
                int heatColor = heatPct >= 0.67f
                        ? ImGui.colorConvertFloat4ToU32(1f, 0.5f, 0f, 1f)   // orange
                        : heatPct >= 0.34f
                        ? ImGui.colorConvertFloat4ToU32(1f, 0.8f, 0f, 1f)   // yellow
                        : heatPct > 0f
                        ? ImGui.colorConvertFloat4ToU32(1f, 0.3f, 0.3f, 1f) // red
                        : ImGui.colorConvertFloat4ToU32(0.4f, 0.4f, 0.4f, 1f); // grey

                ImGui.text("Heat:");
                ImGui.sameLine();
                ImGui.textColored(0.7f, 0.7f, 0.7f, 1f,
                        active.currentHeat() + " / " + state.smithMaxHeat
                                + "  (" + state.smithHeatPercent + "%)");
                ImGui.sameLine();
                ImGui.textColored(0.5f, 0.9f, 1f, 1f,
                        "[" + state.smithHeatBand + " — " + state.smithProgressPerStrike + " prog/strike]");

                ImGui.pushStyleColor(ImGuiCol.PlotHistogram, heatColor);
                ImGui.progressBar(heatPct, 300, 16,
                        active.currentHeat() + " / " + state.smithMaxHeat);
                ImGui.popStyleColor();

                // Progress bar (blue)
                float progPct = active.maxProgress() > 0
                        ? (float) active.currentProgress() / active.maxProgress() : 0f;
                ImGui.text("Progress:");
                ImGui.sameLine();
                ImGui.textColored(0.7f, 0.7f, 0.7f, 1f,
                        active.currentProgress() + " / " + active.maxProgress()
                                + "  (" + active.progressPercent() + "%)");

                ImGui.pushStyleColor(ImGuiCol.PlotHistogram,
                        ImGui.colorConvertFloat4ToU32(0.2f, 0.6f, 1f, 1f));
                ImGui.progressBar(progPct, 300, 16,
                        active.currentProgress() + " / " + active.maxProgress());
                ImGui.popStyleColor();

                // XP remaining
                ImGui.text("XP Remaining: ");
                ImGui.sameLine();
                ImGui.textColored(0.5f, 0.9f, 1f, 1f, String.valueOf(active.experienceLeft()));

                // Reheat rate
                ImGui.text("Reheat Rate: ");
                ImGui.sameLine();
                ImGui.textColored(0.7f, 0.7f, 0.7f, 1f,
                        state.smithReheatRate + " heat/tick at forge");
            }

            ImGui.spacing();

            // All unfinished items table
            List<Smithing.UnfinishedItem> allItems = state.allUnfinishedItems;
            if (!allItems.isEmpty()) {
                ImGui.separator();
                ImGui.text("Unfinished Items in Backpack: " + allItems.size());
                ImGui.spacing();

                int flags = ImGuiTableFlags.Borders | ImGuiTableFlags.RowBg
                        | ImGuiTableFlags.SizingStretchProp | ImGuiTableFlags.ScrollY;
                float h = Math.min(allItems.size() * 25f + 30f, 200f);
                if (ImGui.beginTable("##smith_unfinished", 6, flags, 0, h)) {
                    ImGui.tableSetupColumn("Slot", 0, 0.3f);
                    ImGui.tableSetupColumn("Creating", 0, 1.5f);
                    ImGui.tableSetupColumn("Progress", 0, 0.8f);
                    ImGui.tableSetupColumn("Heat", 0, 0.5f);
                    ImGui.tableSetupColumn("XP Left", 0, 0.5f);
                    ImGui.tableSetupColumn("%", 0, 0.4f);
                    ImGui.tableSetupScrollFreeze(0, 1);
                    ImGui.tableHeadersRow();

                    for (Smithing.UnfinishedItem item : allItems) {
                        ImGui.tableNextRow();
                        // Highlight the active item
                        if (active != null && item.slot() == active.slot()) {
                            ImGui.tableSetBgColor(1,
                                    ImGui.colorConvertFloat4ToU32(0.2f, 0.5f, 0.2f, 0.3f));
                        }
                        ImGui.tableSetColumnIndex(0);
                        ImGui.text(String.valueOf(item.slot()));
                        ImGui.tableSetColumnIndex(1);
                        String n = item.creatingName() != null ? item.creatingName() : "ID:" + item.creatingItemId();
                        ImGui.textColored(0.5f, 0.9f, 1f, 1f, n);
                        ImGui.tableSetColumnIndex(2);
                        ImGui.text(item.currentProgress() + "/" + item.maxProgress());
                        ImGui.tableSetColumnIndex(3);
                        ImGui.text(String.valueOf(item.currentHeat()));
                        ImGui.tableSetColumnIndex(4);
                        ImGui.text(String.valueOf(item.experienceLeft()));
                        ImGui.tableSetColumnIndex(5);
                        ImGui.text(item.progressPercent() + "%");
                    }
                    ImGui.endTable();
                }
            }
        }

        ImGui.spacing();
        ImGui.separator();
        ImGui.spacing();
    }

    private void renderLiveState() {
        if (ImGui.collapsingHeader("Live State", ImGuiTreeNodeFlags.DefaultOpen)) {
            int flags = ImGuiTableFlags.Borders | ImGuiTableFlags.RowBg | ImGuiTableFlags.SizingStretchProp;
            if (ImGui.beginTable("##smith_state", 2, flags)) {
                ImGui.tableSetupColumn("Property", 0, 1.5f);
                ImGui.tableSetupColumn("Value", 0, 2f);
                ImGui.tableHeadersRow();

                stateRow("Mode", state.smithIsSmelting ? "Smelting" : "Smithing");

                int selectedId = state.smithSelectedItem;
                String selectedName = state.smithProductName;
                stateRow("Selected Item", selectedName != null && selectedId > 0
                        ? selectedName + " (ID: " + selectedId + ")" : String.valueOf(selectedId));
                stateRow("Product Name", selectedName != null ? selectedName : "—");
                stateRow("Quantity", String.valueOf(state.smithQuantity));
                stateRow("Quality Tier", state.smithQualityTier + " (" + state.smithQualityName + ")");
                stateRow("Material (dbrow)", String.valueOf(state.smithMaterialDbrow));
                stateRow("Product (dbrow)", String.valueOf(state.smithProductDbrow));
                stateRow("Location", String.valueOf(state.smithLocation));
                stateRow("Outfit Bonus 1", String.valueOf(state.smithOutfitBonus1));
                stateRow("Outfit Bonus 2", String.valueOf(state.smithOutfitBonus2));
                stateRow("Heat Efficiency", String.valueOf(state.smithHeatEfficiency));
                stateRow("Exceeds Backpack", String.valueOf(state.smithExceedsBackpack));
                stateRow("Full Blacksmith Outfit", String.valueOf(state.smithFullOutfit));
                stateRow("Varrock Armour", String.valueOf(state.smithVarrockArmour));

                List<Integer> bonuses = state.smithActiveBonuses;
                stateRow("Active Bonuses", bonuses.isEmpty() ? "None" : bonuses.toString());

                ImGui.endTable();
            }
        }
    }

    private void stateRow(String property, String value) {
        ImGui.tableNextRow();
        ImGui.tableSetColumnIndex(0);
        ImGui.text(property);
        ImGui.tableSetColumnIndex(1);
        ImGui.textColored(0.5f, 0.9f, 1f, 1f, value);
    }

    private void renderQualityTiers() {
        if (ImGui.collapsingHeader("Quality Tiers", ImGuiTreeNodeFlags.DefaultOpen)) {
            String[] tierNames = {"Base", "+1", "+2", "+3", "+4", "+5", "Burial"};
            int[] tierVarbits = {0, 1, 2, 3, 4, 5, 50};
            int[] tierComps = {149, 161, 159, 157, 155, 153, 151};
            int currentTier = state.smithQualityTier;

            int flags = ImGuiTableFlags.Borders | ImGuiTableFlags.RowBg | ImGuiTableFlags.SizingStretchProp;
            if (ImGui.beginTable("##smith_quality", 4, flags)) {
                ImGui.tableSetupColumn("Tier", 0, 0.5f);
                ImGui.tableSetupColumn("Varbit", 0, 0.4f);
                ImGui.tableSetupColumn("Component", 0, 0.5f);
                ImGui.tableSetupColumn("Code", 0, 1.5f);
                ImGui.tableHeadersRow();

                for (int i = 0; i < tierNames.length; i++) {
                    ImGui.tableNextRow();
                    if (tierVarbits[i] == currentTier) {
                        ImGui.tableSetBgColor(1, ImGui.colorConvertFloat4ToU32(0.2f, 0.5f, 0.2f, 0.3f));
                    }
                    ImGui.tableSetColumnIndex(0);
                    ImGui.text(tierNames[i]);
                    ImGui.tableSetColumnIndex(1);
                    ImGui.text(String.valueOf(tierVarbits[i]));
                    ImGui.tableSetColumnIndex(2);
                    ImGui.text("comp(37, " + tierComps[i] + ")");
                    ImGui.tableSetColumnIndex(3);
                    String code = "GameAction(57, 1, -1, " + ((37 << 16) | tierComps[i]) + ")";
                    ImGui.pushStyleColor(ImGuiCol.Button, ImGui.colorConvertFloat4ToU32(0.3f, 0.3f, 0.5f, 0.6f));
                    if (ImGui.smallButton("Copy##qt_" + i)) {
                        ImGui.setClipboardText("api.queueAction(new " + code + ");");
                    }
                    ImGui.popStyleColor();
                    if (ImGui.isItemHovered()) ImGui.setTooltip("api.queueAction(new " + code + ");");
                }
                ImGui.endTable();
            }
        }
    }

    private void renderGrid(String label, List<SmithingTabEntry> entries, boolean isMaterial) {
        if (ImGui.collapsingHeader(label)) {
            if (entries.isEmpty()) {
                ImGui.textColored(0.6f, 0.6f, 0.6f, 1f, "No entries found.");
                return;
            }

            int flags = ImGuiTableFlags.Borders | ImGuiTableFlags.RowBg
                    | ImGuiTableFlags.SizingStretchProp | ImGuiTableFlags.ScrollY | ImGuiTableFlags.Resizable;
            float tableHeight = Math.min(entries.size() * 25f + 30f, 250f);
            String tableId = isMaterial ? "##smith_mat_grid" : "##smith_prod_grid";
            if (ImGui.beginTable(tableId, 5, flags, 0, tableHeight)) {
                ImGui.tableSetupColumn("Grid", 0, 0.3f);
                ImGui.tableSetupColumn("Sub", 0, 0.3f);
                ImGui.tableSetupColumn("Item ID", 0, 0.5f);
                ImGui.tableSetupColumn("Name", 0, 1.5f);
                ImGui.tableSetupColumn("Code", 0, 1.2f);
                ImGui.tableSetupScrollFreeze(0, 1);
                ImGui.tableHeadersRow();

                int[] gridComps = isMaterial
                        ? new int[]{52, 62, 72, 82, 92}
                        : new int[]{103, 114, 125, 136, 147};

                for (SmithingTabEntry e : entries) {
                    ImGui.tableNextRow();
                    ImGui.tableSetColumnIndex(0);
                    ImGui.text(String.valueOf(e.gridIndex()));
                    ImGui.tableSetColumnIndex(1);
                    ImGui.text(String.valueOf(e.subIndex()));
                    ImGui.tableSetColumnIndex(2);
                    ImGui.text(String.valueOf(e.itemId()));
                    ImGui.tableSetColumnIndex(3);
                    ImGui.textColored(0.5f, 0.9f, 1f, 1f, e.name() != null ? e.name() : "???");
                    ImGui.tableSetColumnIndex(4);
                    int compId = (e.gridIndex() < gridComps.length) ? gridComps[e.gridIndex()] : 0;
                    String code = "GameAction(57, 1, " + e.subIndex() + ", " + ((37 << 16) | compId) + ")";
                    ImGui.pushStyleColor(ImGuiCol.Button, ImGui.colorConvertFloat4ToU32(0.3f, 0.3f, 0.5f, 0.6f));
                    if (ImGui.smallButton("Copy##g_" + e.gridIndex() + "_" + e.subIndex())) {
                        ImGui.setClipboardText("api.queueAction(new " + code + ");");
                    }
                    ImGui.popStyleColor();
                    if (ImGui.isItemHovered()) ImGui.setTooltip("api.queueAction(new " + code + ");");
                }
                ImGui.endTable();
            }
        }
    }

    private void renderActionCodeSection() {
        if (ImGui.collapsingHeader("Action Code Snippets")) {
            ImGui.textColored(0.6f, 0.6f, 0.6f, 1f, "Click any snippet to copy to clipboard.");
            ImGui.spacing();

            codeRow("Make (COMPONENT)", "api.queueAction(new GameAction(57, 1, -1, " + ((37 << 16) | 163) + "));");
            codeRow("Decrease Qty", "api.queueAction(new GameAction(57, 1, 0, " + ((37 << 16) | 34) + "));");
            codeRow("Increase Qty", "api.queueAction(new GameAction(57, 1, 7, " + ((37 << 16) | 34) + "));");
        }
    }

    private void codeRow(String label, String code) {
        ImGui.bulletText(label + ":");
        ImGui.sameLine();
        ImGui.pushStyleColor(ImGuiCol.Text, ImGui.colorConvertFloat4ToU32(0.4f, 0.8f, 0.4f, 1f));
        ImGui.text(code);
        ImGui.popStyleColor();
        if (ImGui.isItemHovered() && ImGui.isMouseClicked(0)) ImGui.setClipboardText(code);
        if (ImGui.isItemHovered()) ImGui.setTooltip("Click to copy");
    }

    // ── Data records for pre-collected grid entries ─────────────
    record SmithingTabEntry(int gridIndex, int subIndex, int itemId, String name) {}
}
