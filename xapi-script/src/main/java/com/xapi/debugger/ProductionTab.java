package com.xapi.debugger;

import com.botwithus.bot.api.model.Component;
import com.botwithus.bot.api.model.ItemType;

import imgui.ImGui;
import imgui.flag.ImGuiCol;
import imgui.flag.ImGuiTableFlags;

import java.util.List;
import java.util.Map;

/**
 * Debug tab that displays live production interface state (interfaces 1370 + 1371).
 * <p>Shows varps, product grid, category dropdown, and copyable action code snippets.</p>
 */
final class ProductionTab {

    private final XapiState state;

    ProductionTab(XapiState s) {
        this.state = s;
    }

    void render() {
        // Status header
        boolean open = state.prodOpen;
        ImGui.text("Production Interface: ");
        ImGui.sameLine();
        if (open) {
            ImGui.textColored(0.2f, 1f, 0.2f, 1f, "OPEN");
        } else {
            ImGui.textColored(1f, 0.3f, 0.3f, 1f, "CLOSED");
        }

        // Progress status (always show if producing, even if make-x interface is closed)
        ImGui.sameLine();
        ImGui.text("  |  Progress: ");
        ImGui.sameLine();
        if (state.progressOpen) {
            ImGui.textColored(0.2f, 0.8f, 1f, 1f, "PRODUCING");
        } else {
            ImGui.textColored(0.6f, 0.6f, 0.6f, 1f, "IDLE");
        }

        ImGui.spacing();
        ImGui.separator();
        ImGui.spacing();

        // Varp monitor
        renderVarps();

        ImGui.spacing();
        ImGui.separator();
        ImGui.spacing();

        // Current selection
        renderCurrentSelection();

        ImGui.spacing();
        ImGui.separator();
        ImGui.spacing();

        // Product grid
        renderProductGrid();

        ImGui.spacing();
        ImGui.separator();
        ImGui.spacing();

        // Categories
        if (state.prodHasCategories) {
            renderCategories();
            ImGui.spacing();
            ImGui.separator();
            ImGui.spacing();
        }

        // Production progress (interface 1251)
        if (state.progressOpen) {
            renderProgress();
            ImGui.spacing();
            ImGui.separator();
            ImGui.spacing();
        }

        // Copyable action code
        renderActionCodeSection();
    }

    private void renderProgress() {
        if (ImGui.collapsingHeader("Production Progress (Interface 1251)", imgui.flag.ImGuiTreeNodeFlags.DefaultOpen)) {
            // Progress bar
            int total = state.progressTotal;
            int remaining = state.progressRemaining;
            int made = total > 0 ? total - remaining : 0;
            float pct = total > 0 ? (float) made / total : 0f;

            ImGui.text("Progress: ");
            ImGui.sameLine();
            ImGui.pushStyleColor(ImGuiCol.PlotHistogram,
                    pct > 0.9f ? ImGui.colorConvertFloat4ToU32(0.2f, 0.8f, 0.2f, 1f)
                            : ImGui.colorConvertFloat4ToU32(0.2f, 0.6f, 0.8f, 1f));
            ImGui.progressBar(pct, 250, 18, made + " / " + total);
            ImGui.popStyleColor();

            // Text fields
            ImGui.text("Product: ");
            ImGui.sameLine();
            String prodName = state.progressProductName;
            ImGui.textColored(0.5f, 0.9f, 1f, 1f, prodName != null ? prodName : "???");

            String timeText = state.progressTimeText;
            if (timeText != null) {
                ImGui.text("Time Remaining: ");
                ImGui.sameLine();
                ImGui.text(timeText);
            }

            String counterText = state.progressCounterText;
            if (counterText != null) {
                ImGui.text("Counter: ");
                ImGui.sameLine();
                ImGui.text(counterText);
            }

            // Varc table
            int flags = ImGuiTableFlags.Borders | ImGuiTableFlags.RowBg | ImGuiTableFlags.SizingStretchProp;
            if (ImGui.beginTable("##prog_varcs", 3, flags)) {
                ImGui.tableSetupColumn("Variable", 0, 1.5f);
                ImGui.tableSetupColumn("ID", 0, 0.5f);
                ImGui.tableSetupColumn("Value", 0, 1f);
                ImGui.tableHeadersRow();

                progRow("Total to Make", "varc", 2228, total);
                progRow("Remaining", "varc", 2229, remaining);
                progRow("Speed Modifier", "varc", 2227, state.progressSpeedModifier);
                progRow("Product Item ID", "varp", 1175, state.progressProductId);
                progRow("Visibility", "varp", 3034, state.progressVisibility);

                ImGui.endTable();
            }

            // Stop button code
            ImGui.spacing();
            codeRow("Stop Production", "api.queueAction(new GameAction(57, 1, -1, " + ((1251 << 16) | 14) + "));");
        }
    }

    private void progRow(String name, String type, int id, int value) {
        ImGui.tableNextRow();
        ImGui.tableSetColumnIndex(0);
        ImGui.text(name);
        ImGui.tableSetColumnIndex(1);
        ImGui.textColored(0.6f, 0.6f, 0.6f, 1f, String.valueOf(id));
        ImGui.tableSetColumnIndex(2);
        ImGui.text(String.valueOf(value));
        String code = type.equals("varc") ? "api.getVarcInt(" + id + ")" : "api.getVarp(" + id + ")";
        if (ImGui.isItemHovered() && ImGui.isMouseClicked(0)) ImGui.setClipboardText(code);
        if (ImGui.isItemHovered()) ImGui.setTooltip("Click to copy: " + code);
    }

    private void renderVarps() {
        if (ImGui.collapsingHeader("Varps (Live)", imgui.flag.ImGuiTreeNodeFlags.DefaultOpen)) {
            int flags = ImGuiTableFlags.Borders | ImGuiTableFlags.RowBg | ImGuiTableFlags.SizingStretchProp;
            if (ImGui.beginTable("##prod_varps", 3, flags)) {
                ImGui.tableSetupColumn("Varp", 0, 1.5f);
                ImGui.tableSetupColumn("ID", 0, 0.5f);
                ImGui.tableSetupColumn("Value", 0, 1f);
                ImGui.tableHeadersRow();

                varpRow("Category Enum", 1168, state.prodCategoryEnum);
                varpRow("Product List Enum", 1169, state.prodProductListEnum);
                varpRow("Selected Product", 1170, state.prodSelectedItem);
                varpRow("Category Dropdown", 7881, state.prodCategoryDropdown);
                varpRow("Max Quantity", 8846, state.prodMaxQty);
                varpRow("Chosen Quantity", 8847, state.prodChosenQty);

                ImGui.endTable();
            }
        }
    }

    private void varpRow(String name, int id, int value) {
        ImGui.tableNextRow();
        ImGui.tableSetColumnIndex(0);
        ImGui.text(name);
        ImGui.tableSetColumnIndex(1);
        ImGui.textColored(0.6f, 0.6f, 0.6f, 1f, String.valueOf(id));
        ImGui.tableSetColumnIndex(2);
        String valStr = String.valueOf(value);
        ImGui.text(valStr);
        if (ImGui.isItemHovered() && ImGui.isMouseClicked(0)) {
            ImGui.setClipboardText("api.getVarp(" + id + ")");
        }
        if (ImGui.isItemHovered()) ImGui.setTooltip("Click to copy: api.getVarp(" + id + ")");
    }

    private void renderCurrentSelection() {
        ImGui.text("Selected Product: ");
        ImGui.sameLine();
        int selectedId = state.prodSelectedItem;
        if (selectedId > 0) {
            String name = state.prodSelectedName;
            ImGui.textColored(0.5f, 0.9f, 1f, 1f,
                    (name != null ? name : "???") + "  (ID: " + selectedId + ")");
            if (ImGui.isItemHovered() && ImGui.isMouseClicked(0)) {
                ImGui.setClipboardText(String.valueOf(selectedId));
            }
            if (ImGui.isItemHovered()) ImGui.setTooltip("Click to copy item ID");
        } else {
            ImGui.textColored(0.6f, 0.6f, 0.6f, 1f, "None");
        }

        ImGui.text("Quantity: ");
        ImGui.sameLine();
        int chosen = state.prodChosenQty;
        int max = state.prodMaxQty;
        if (max > 0) {
            float pct = (float) chosen / max;
            ImGui.pushStyleColor(ImGuiCol.PlotHistogram,
                    pct > 0.9f ? ImGui.colorConvertFloat4ToU32(0.2f, 0.8f, 0.2f, 1f)
                            : ImGui.colorConvertFloat4ToU32(0.8f, 0.6f, 0.2f, 1f));
            ImGui.progressBar(pct, 200, 18, chosen + " / " + max);
            ImGui.popStyleColor();
        } else {
            ImGui.text(chosen + " / " + max);
        }
    }

    private void renderProductGrid() {
        if (ImGui.collapsingHeader("Product Grid", imgui.flag.ImGuiTreeNodeFlags.DefaultOpen)) {
            List<ProductionTabEntry> products = state.prodGridEntries;
            if (products.isEmpty()) {
                ImGui.textColored(0.6f, 0.6f, 0.6f, 1f, "No products found in grid.");
                return;
            }

            int flags = ImGuiTableFlags.Borders | ImGuiTableFlags.RowBg
                    | ImGuiTableFlags.SizingStretchProp | ImGuiTableFlags.ScrollY | ImGuiTableFlags.Resizable;
            float tableHeight = Math.min(products.size() * 25f + 30f, 300f);
            if (ImGui.beginTable("##prod_grid", 4, flags, 0, tableHeight)) {
                ImGui.tableSetupColumn("Index", 0, 0.4f);
                ImGui.tableSetupColumn("Item ID", 0, 0.6f);
                ImGui.tableSetupColumn("Name", 0, 2f);
                ImGui.tableSetupColumn("Code", 0, 1.5f);
                ImGui.tableSetupScrollFreeze(0, 1);
                ImGui.tableHeadersRow();

                int selectedId = state.prodSelectedItem;
                for (ProductionTabEntry p : products) {
                    ImGui.tableNextRow();

                    // Highlight selected product
                    if (p.itemId() == selectedId) {
                        ImGui.tableSetBgColor(1, ImGui.colorConvertFloat4ToU32(0.2f, 0.5f, 0.2f, 0.3f));
                    }

                    ImGui.tableSetColumnIndex(0);
                    ImGui.text(String.valueOf(p.index()));

                    ImGui.tableSetColumnIndex(1);
                    ImGui.text(String.valueOf(p.itemId()));
                    if (ImGui.isItemHovered() && ImGui.isMouseClicked(0)) {
                        ImGui.setClipboardText(String.valueOf(p.itemId()));
                    }
                    if (ImGui.isItemHovered()) ImGui.setTooltip("Click to copy item ID");

                    ImGui.tableSetColumnIndex(2);
                    String name = p.name() != null ? p.name() : "???";
                    ImGui.textColored(0.5f, 0.9f, 1f, 1f, name);

                    ImGui.tableSetColumnIndex(3);
                    String selectCode = "GameAction(57, 1, " + (p.index() * 4 + 1) + ", " + ((1371 << 16) | 22) + ")";
                    ImGui.pushStyleColor(ImGuiCol.Button, ImGui.colorConvertFloat4ToU32(0.3f, 0.3f, 0.5f, 0.6f));
                    if (ImGui.smallButton("Copy##prod_" + p.index())) {
                        ImGui.setClipboardText("api.queueAction(new " + selectCode + ");");
                    }
                    ImGui.popStyleColor();
                    if (ImGui.isItemHovered()) ImGui.setTooltip("api.queueAction(new " + selectCode + ");");
                }
                ImGui.endTable();
            }
        }
    }

    private void renderCategories() {
        if (ImGui.collapsingHeader("Categories")) {
            List<String> categories = state.prodCategoryNames;
            if (categories.isEmpty()) {
                ImGui.textColored(0.6f, 0.6f, 0.6f, 1f, "No category data available.");
                return;
            }

            int flags = ImGuiTableFlags.Borders | ImGuiTableFlags.RowBg | ImGuiTableFlags.SizingStretchProp;
            if (ImGui.beginTable("##prod_categories", 3, flags)) {
                ImGui.tableSetupColumn("Index", 0, 0.4f);
                ImGui.tableSetupColumn("Name", 0, 2f);
                ImGui.tableSetupColumn("Code", 0, 1.5f);
                ImGui.tableHeadersRow();

                for (int i = 0; i < categories.size(); i++) {
                    ImGui.tableNextRow();
                    ImGui.tableSetColumnIndex(0);
                    ImGui.text(String.valueOf(i));
                    ImGui.tableSetColumnIndex(1);
                    ImGui.textColored(0.5f, 0.9f, 1f, 1f, categories.get(i));
                    ImGui.tableSetColumnIndex(2);
                    String code = "GameAction(57, 1, " + i + ", " + ((1477 << 16) | 896) + ")";
                    ImGui.pushStyleColor(ImGuiCol.Button, ImGui.colorConvertFloat4ToU32(0.3f, 0.3f, 0.5f, 0.6f));
                    if (ImGui.smallButton("Copy##cat_" + i)) {
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

            codeRow("Make (DIALOGUE)", "api.queueAction(new GameAction(30, 0, -1, 89784350));");
            codeRow("Decrease Qty", "api.queueAction(new GameAction(57, 1, 0, 89849875));");
            codeRow("Increase Qty", "api.queueAction(new GameAction(57, 1, 7, 89849875));");
            codeRow("Open Category Dropdown", "api.queueAction(new GameAction(57, 1, -1, 89849884));");

            int selectedId = state.prodSelectedItem;
            if (selectedId > 0) {
                int idx = -1;
                for (ProductionTabEntry p : state.prodGridEntries) {
                    if (p.itemId() == selectedId) { idx = p.index(); break; }
                }
                if (idx >= 0) {
                    codeRow("Select Current (" + (state.prodSelectedName != null ? state.prodSelectedName : selectedId) + ")",
                            "api.queueAction(new GameAction(57, 1, " + (idx * 4 + 1) + ", " + ((1371 << 16) | 22) + "));");
                }
            }
        }
    }

    private void codeRow(String label, String code) {
        ImGui.bulletText(label + ":");
        ImGui.sameLine();
        ImGui.pushStyleColor(ImGuiCol.Text, ImGui.colorConvertFloat4ToU32(0.4f, 0.8f, 0.4f, 1f));
        ImGui.text(code);
        ImGui.popStyleColor();
        if (ImGui.isItemHovered() && ImGui.isMouseClicked(0)) {
            ImGui.setClipboardText(code);
        }
        if (ImGui.isItemHovered()) ImGui.setTooltip("Click to copy");
    }

    // ── Data record for pre-collected product grid entries ─────────────
    record ProductionTabEntry(int index, int itemId, String name) {}
}
