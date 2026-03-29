package com.botwithus.bot.scripts.woodcutting;

import com.botwithus.bot.api.model.LocalPlayer;
import com.botwithus.bot.api.nav.LocalPathfinder;
import com.botwithus.bot.api.nav.WorldPathfinder;
import com.botwithus.bot.api.script.Task;
import com.botwithus.bot.api.ui.ScriptUI;
import imgui.ImGui;
import imgui.flag.ImGuiCol;
import imgui.flag.ImGuiTableFlags;
import imgui.type.ImInt;

import java.util.List;

final class WoodcuttingUI implements ScriptUI {

    private static final float[] BG = {0.08f, 0.07f, 0.05f, 1.0f};
    private static final float[] PANEL = {0.14f, 0.11f, 0.08f, 0.96f};
    private static final float[] PANEL_ALT = {0.10f, 0.16f, 0.17f, 0.90f};
    private static final float[] TEXT = {0.95f, 0.93f, 0.88f, 1.0f};
    private static final float[] MUTED = {0.67f, 0.64f, 0.58f, 1.0f};
    private static final float[] GOLD = {0.94f, 0.76f, 0.32f, 1.0f};
    private static final float[] GREEN = {0.48f, 0.87f, 0.57f, 1.0f};
    private static final float[] CYAN = {0.39f, 0.81f, 0.90f, 1.0f};
    private static final float[] ORANGE = {0.93f, 0.54f, 0.24f, 1.0f};
    private static final float[] RED = {0.93f, 0.39f, 0.33f, 1.0f};

    private final WoodcuttingScript script;
    private final ImInt selectedTree = new ImInt(0);
    private final ImInt selectedHotspot = new ImInt(0);
    private String pathfindResult = "Select a pathing or targeting probe.";

    WoodcuttingUI(WoodcuttingScript script) {
        this.script = script;
    }

    private WoodcuttingContext ctx() {
        return script.wctx;
    }

    @Override
    public void render() {
        WoodcuttingContext wctx = ctx();
        if (wctx == null || wctx.pace == null) {
            ImGui.text("Initialising...");
            return;
        }

        pushPalette();
        try {
            ImGui.textColored(GOLD[0], GOLD[1], GOLD[2], GOLD[3], "Woodcutting Atlas");
            ImGui.sameLine();
            ImGui.textColored(MUTED[0], MUTED[1], MUTED[2], MUTED[3], "profile-driven routing, pacing, and diagnostics");
            ImGui.separator();

            if (ImGui.beginTabBar("##woodcutting_tabs")) {
                if (ImGui.beginTabItem("Config")) {
                    renderConfigTab();
                    ImGui.endTabItem();
                }
                if (ImGui.beginTabItem("Diagnostics")) {
                    renderDiagnosticsTab();
                    ImGui.endTabItem();
                }
                if (ImGui.beginTabItem("Log")) {
                    renderLogTab();
                    ImGui.endTabItem();
                }
                if (ImGui.beginTabItem("Debug")) {
                    renderDebugTab();
                    ImGui.endTabItem();
                }
                ImGui.endTabBar();
            }
        } finally {
            ImGui.popStyleColor(9);
        }
    }

    private void renderConfigTab() {
        WoodcuttingContext wctx = ctx();
        TreeProfile profile = wctx.config.selectedTree();
        HotspotProfile hotspot = wctx.config.selectedHotspot();
        List<TreeProfile> profiles = wctx.config.profiles();

        String[] treeNames = profiles.stream().map(TreeProfile::displayName).toArray(String[]::new);
        selectedTree.set(indexOfProfile(profiles, profile.id()));

        pushPanel(PANEL);
        if (ImGui.beginChild("##config_controls", 0, 108, true, 0)) {
            ImGui.textColored(GOLD[0], GOLD[1], GOLD[2], GOLD[3], "Control Panel");
            ImGui.textColored(MUTED[0], MUTED[1], MUTED[2], MUTED[3], "Pick the tree, then refine the active hotspot.");
            ImGui.separator();

            ImGui.textColored(CYAN[0], CYAN[1], CYAN[2], CYAN[3], "Tree");
            ImGui.setNextItemWidth(-1);
            if (ImGui.combo("##tree_profile", selectedTree, treeNames)) {
                TreeProfile chosen = profiles.get(selectedTree.get());
                wctx.config.selectTree(chosen.id());
                wctx.syncSelectionState();
            }

            List<HotspotProfile> hotspots = wctx.config.selectedTree().hotspots();
            String[] hotspotNames = hotspots.stream().map(HotspotProfile::label).toArray(String[]::new);
            selectedHotspot.set(indexOfHotspot(hotspots, hotspot.id()));

            ImGui.textColored(CYAN[0], CYAN[1], CYAN[2], CYAN[3], "Hotspot");
            ImGui.setNextItemWidth(-1);
            if (ImGui.combo("##hotspot_profile", selectedHotspot, hotspotNames)) {
                HotspotProfile chosen = hotspots.get(selectedHotspot.get());
                wctx.config.selectHotspot(chosen.id());
                wctx.syncSelectionState();
            }
        }
        ImGui.endChild();
        popPanel();

        int flags = ImGuiTableFlags.SizingStretchProp;
        if (ImGui.beginTable("##config_cards", 2, flags)) {
            ImGui.tableNextColumn();
            renderCard("##profile_card", "Current Profile", 186, () -> {
                valueRow("Tree", profile.displayName(), TEXT);
                valueRow("Action", profile.primaryAction(), CYAN);
                valueRow("Mode", profile.mode().displayName(), TEXT);
                valueRow("Inventory", hotspot.inventoryMode().displayName(), TEXT);
                valueRow("Tracked product", profile.productName().isBlank() ? "None" : profile.productName(), MUTED);
                valueRow("Wood box", profile.supportsWoodBox() ? profile.woodBoxLogType().name : "Not used", MUTED);
            });

            ImGui.tableNextColumn();
            renderCard("##requirements_card", "Requirements", 186, () -> {
                valueRow("Required level", String.valueOf(profile.requiredLevel()), TEXT);
                valueRow("Current level", String.valueOf(wctx.woodcuttingLevel), wctx.requirementsMet ? GREEN : RED);
                ImGui.spacing();
                renderStatusPill(wctx.requirementStatus, wctx.requirementsMet, wctx.manualRequirementOnly);
                ImGui.spacing();
                ImGui.pushTextWrapPos();
                ImGui.textColored(MUTED[0], MUTED[1], MUTED[2], MUTED[3], wctx.requirementDetail);
                ImGui.popTextWrapPos();
            });
            ImGui.endTable();
        }

        renderCard("##location_card", "Location + Runtime", 170, () -> {
            valueRow("Hotspot", hotspot.label() + " (" + hotspot.hotspotType() + ")", TEXT);
            valueRow("Tree anchor", hotspot.treeAnchor().displayText(), CYAN);
            TileAnchor storage = wctx.currentBankingAnchor();
            valueRow("Storage anchor", storage == null ? "None" : storage.displayText(), storage == null ? MUTED : CYAN);
            valueRow("Search radius", hotspot.radius() + " tiles", TEXT);
            valueRow("Route step", wctx.currentRouteLabel, hotspot.hasRoute() ? ORANGE : MUTED);
            ImGui.spacing();
            ImGui.pushTextWrapPos();
            ImGui.textColored(MUTED[0], MUTED[1], MUTED[2], MUTED[3], hotspot.note());
            ImGui.popTextWrapPos();
        });

        pushPanel(PANEL_ALT);
        if (ImGui.beginChild("##behaviour_strip", 0, 78, true, 0)) {
            ImGui.textColored(GOLD[0], GOLD[1], GOLD[2], GOLD[3], "Behaviour Summary");
            ImGui.pushTextWrapPos();
            ImGui.textColored(TEXT[0], TEXT[1], TEXT[2], TEXT[3], wctx.profileSummary);
            ImGui.popTextWrapPos();
            ImGui.spacing();
            renderPill(profile.mode().displayName(), GOLD);
            ImGui.sameLine();
            renderPill(hotspot.hotspotType(), CYAN);
            ImGui.sameLine();
            renderPill(hotspot.inventoryMode().displayName(), hotspot.inventoryMode().isBankLike() ? GREEN : ORANGE);
        }
        ImGui.endChild();
        popPanel();
    }

    private void renderDiagnosticsTab() {
        WoodcuttingContext wctx = ctx();

        if (ImGui.beginTable("##diag_cards", 2, ImGuiTableFlags.SizingStretchProp)) {
            ImGui.tableNextColumn();
            renderCard("##diag_session", "Session State", 190, () -> {
                valueRow("Task", wctx.currentTaskName, TEXT);
                valueRow("Phase", wctx.pacePhase, phaseColor(wctx.pacePhase));
                valueRow("Attention", wctx.attentionState, attentionColor(wctx.attentionState));
                valueRow("Fatigue", String.format("%.1f%%", wctx.fatigue * 100.0), fatigueColor(wctx.fatigue));
                valueRow("Session", formatSession(), TEXT);
                valueRow("Delay", wctx.delayContext.isBlank() ? "None" : wctx.delayContext, CYAN);
                valueRow("Break", wctx.onBreak ? (wctx.breakLabel == null ? "Active" : wctx.breakLabel) : "None", wctx.onBreak ? ORANGE : MUTED);
            });

            ImGui.tableNextColumn();
            renderCard("##diag_resources", "Resources + Inventory", 190, () -> {
                valueRow("Collected", String.valueOf(wctx.logsChopped), GREEN);
                valueRow("Wood box fills", String.valueOf(wctx.woodboxFills), TEXT);
                valueRow("Bank trips", String.valueOf(wctx.bankTrips), TEXT);
                valueRow("Free slots", String.valueOf(wctx.freeSlots), wctx.freeSlots <= 3 ? ORANGE : TEXT);
                valueRow("Wood box", wctx.hasWoodBox ? (wctx.woodboxStored + " / " + wctx.woodboxCapacity) : "None", wctx.hasWoodBox ? GREEN : MUTED);
                valueRow("Bank open", wctx.bankOpen ? "Yes" : "No", wctx.bankOpen ? CYAN : MUTED);
                valueRow("Deposit box", wctx.depositOpen ? "Yes" : "No", wctx.depositOpen ? CYAN : MUTED);
            });
            ImGui.endTable();
        }

        renderCard("##diag_targeting", "Targeting + Profile", 180, () -> {
            valueRow("Tree", wctx.selectedTreeName, TEXT);
            valueRow("Hotspot", wctx.selectedHotspotName, CYAN);
            valueRow("Mode", wctx.modeLabel, GOLD);
            valueRow("Inventory mode", wctx.inventoryModeLabel, hotspotColor(wctx.inventoryModeLabel));
            valueRow("Target", wctx.currentTargetName, TEXT);
            valueRow("Target ID", wctx.currentTargetId < 0 ? "-" : String.valueOf(wctx.currentTargetId), MUTED);
            valueRow("Target tile", wctx.currentTargetTile, MUTED);
            valueRow("Animation", wctx.animationId == -1 ? "Idle" : String.valueOf(wctx.animationId), wctx.animationId == -1 ? MUTED : GREEN);
            valueRow("Movement", wctx.playerMoving ? "Moving" : "Stationary", wctx.playerMoving ? CYAN : MUTED);
            ImGui.spacing();
            ImGui.pushTextWrapPos();
            ImGui.textColored(MUTED[0], MUTED[1], MUTED[2], MUTED[3], wctx.hotspotNote);
            ImGui.popTextWrapPos();
        });
    }

    private void renderLogTab() {
        WoodcuttingContext wctx = ctx();

        pushPanel(PANEL);
        if (ImGui.beginChild("##log_shell", 0, 0, true, 0)) {
            ImGui.textColored(GOLD[0], GOLD[1], GOLD[2], GOLD[3], "Action Feed");
            ImGui.sameLine();
            ImGui.textColored(MUTED[0], MUTED[1], MUTED[2], MUTED[3], "(" + wctx.actionLog.size() + " entries)");
            ImGui.sameLine();
            if (ImGui.smallButton("Copy All")) {
                StringBuilder sb = new StringBuilder();
                for (String entry : wctx.actionLog) {
                    sb.append(entry).append('\n');
                }
                ImGui.setClipboardText(sb.toString());
            }
            ImGui.sameLine();
            if (ImGui.smallButton("Clear")) {
                wctx.actionLog.clear();
            }
            ImGui.separator();

            if (ImGui.beginChild("##log_entries", 0, 0, false, 0)) {
                for (String entry : wctx.actionLog) {
                    float[] color = logColor(entry);
                    ImGui.textColored(color[0], color[1], color[2], color[3], entry);
                }
            }
            ImGui.endChild();
        }
        ImGui.endChild();
        popPanel();
    }

    private void renderDebugTab() {
        WoodcuttingContext wctx = ctx();

        if (ImGui.beginTable("##debug_cards", 2, ImGuiTableFlags.SizingStretchProp)) {
            ImGui.tableNextColumn();
            renderCard("##debug_force", "Task Forcing", 150, () -> {
                for (Task task : script.getTasks()) {
                    if (ImGui.button("Run " + task.name(), -1, 0)) {
                        wctx.forceTask(task.name());
                    }
                }
            });

            ImGui.tableNextColumn();
            renderCard("##debug_pathing", "Pathing Probes", 150, () -> {
                if (ImGui.button("Distance to Trees", -1, 0)) {
                    TileAnchor anchor = wctx.currentTravelAnchor();
                    pathfindResult = testPathDistance(anchor.x(), anchor.y(), anchor.label());
                }
                if (ImGui.button("Distance to Storage", -1, 0)) {
                    TileAnchor anchor = wctx.currentBankingAnchor();
                    pathfindResult = anchor == null ? "No storage anchor configured." : testPathDistance(anchor.x(), anchor.y(), anchor.label());
                }
                if (ImGui.button("Nearest Storage Object", -1, 0)) {
                    pathfindResult = testNearestStorage();
                }
            });
            ImGui.endTable();
        }

        renderCard("##debug_result", "Probe Output", 220, () -> {
            if (ImGui.smallButton("Copy Result")) {
                ImGui.setClipboardText(pathfindResult);
            }
            ImGui.separator();
            ImGui.pushTextWrapPos();
            ImGui.textColored(TEXT[0], TEXT[1], TEXT[2], TEXT[3], pathfindResult);
            ImGui.popTextWrapPos();
        });
    }

    private String testPathDistance(int destX, int destY, String label) {
        WoodcuttingContext wctx = ctx();
        LocalPlayer player = wctx.api.getLocalPlayer();
        int px = player.tileX();
        int py = player.tileY();
        int plane = player.plane();

        int chebyshev = Math.max(Math.abs(px - destX), Math.abs(py - destY));
        WorldPathfinder wpf = WorldPathfinder.getInstance();
        String wpfInfo = "not initialised";
        if (wpf != null) {
            int dist = wpf.walkDistance(px, py, destX, destY, plane);
            wpfInfo = dist >= 0 ? "steps=" + dist : "NO PATH";
        }

        String msg = String.format("%s from (%d,%d,%d) to (%d,%d,%d)%nChebyshev: %d%nWorldPathfinder: %s",
                label, px, py, plane, destX, destY, plane, chebyshev, wpfInfo);
        wctx.logAction("DEBUG: " + msg.replace('\n', ' '));
        return msg;
    }

    private String testNearestStorage() {
        WoodcuttingContext wctx = ctx();
        HotspotProfile hotspot = wctx.hotspot();
        TileAnchor anchor = wctx.currentBankingAnchor();
        if (anchor == null) {
            return "No storage anchor configured for this hotspot.";
        }

        String[] names = hotspot.inventoryMode() == InventoryMode.DEPOSIT_BOX
                ? hotspot.depositObjectNames().toArray(String[]::new)
                : hotspot.bankObjectNames().toArray(String[]::new);

        for (String name : names) {
            var object = wctx.objects.query().named(name).within(anchor.x(), anchor.y(), 16).visible().nearest();
            if (object != null) {
                return "Nearest storage object: " + object.name() + " @ (" + object.tileX() + ", " + object.tileY() + ", " + object.plane() + ")";
            }
        }
        return "No nearby storage object found around " + anchor.displayText();
    }

    private int indexOfProfile(List<TreeProfile> profiles, String id) {
        for (int i = 0; i < profiles.size(); i++) {
            if (profiles.get(i).id().equals(id)) {
                return i;
            }
        }
        return 0;
    }

    private int indexOfHotspot(List<HotspotProfile> hotspots, String id) {
        for (int i = 0; i < hotspots.size(); i++) {
            if (hotspots.get(i).id().equals(id)) {
                return i;
            }
        }
        return 0;
    }

    private void renderCard(String id, String title, float height, Runnable body) {
        pushPanel(PANEL);
        if (ImGui.beginChild(id, 0, height, true, 0)) {
            ImGui.textColored(GOLD[0], GOLD[1], GOLD[2], GOLD[3], title);
            ImGui.separator();
            body.run();
        }
        ImGui.endChild();
        popPanel();
    }

    private void valueRow(String label, String value, float[] valueColor) {
        ImGui.textColored(MUTED[0], MUTED[1], MUTED[2], MUTED[3], label);
        ImGui.sameLine(150);
        ImGui.textColored(valueColor[0], valueColor[1], valueColor[2], valueColor[3], value);
    }

    private void renderStatusPill(String text, boolean ready, boolean manualGate) {
        if (!ready) {
            renderPill(text, RED);
            return;
        }
        renderPill(text, manualGate ? ORANGE : GREEN);
    }

    private void renderPill(String text, float[] color) {
        ImGui.pushStyleColor(ImGuiCol.Button, color[0], color[1], color[2], 0.25f);
        ImGui.pushStyleColor(ImGuiCol.ButtonHovered, color[0], color[1], color[2], 0.35f);
        ImGui.pushStyleColor(ImGuiCol.ButtonActive, color[0], color[1], color[2], 0.45f);
        ImGui.button(text);
        ImGui.popStyleColor(3);
    }

    private void pushPalette() {
        ImGui.pushStyleColor(ImGuiCol.ChildBg, PANEL[0], PANEL[1], PANEL[2], PANEL[3]);
        ImGui.pushStyleColor(ImGuiCol.FrameBg, 0.18f, 0.14f, 0.11f, 1.0f);
        ImGui.pushStyleColor(ImGuiCol.FrameBgHovered, 0.24f, 0.19f, 0.14f, 1.0f);
        ImGui.pushStyleColor(ImGuiCol.Button, 0.20f, 0.15f, 0.11f, 1.0f);
        ImGui.pushStyleColor(ImGuiCol.ButtonHovered, 0.27f, 0.20f, 0.13f, 1.0f);
        ImGui.pushStyleColor(ImGuiCol.ButtonActive, 0.33f, 0.24f, 0.16f, 1.0f);
        ImGui.pushStyleColor(ImGuiCol.Tab, 0.13f, 0.10f, 0.08f, 1.0f);
        ImGui.pushStyleColor(ImGuiCol.TabHovered, 0.23f, 0.18f, 0.12f, 1.0f);
        ImGui.pushStyleColor(ImGuiCol.TabActive, 0.28f, 0.21f, 0.14f, 1.0f);
    }

    private void pushPanel(float[] color) {
        ImGui.pushStyleColor(ImGuiCol.ChildBg, color[0], color[1], color[2], color[3]);
    }

    private void popPanel() {
        ImGui.popStyleColor();
    }

    private float[] phaseColor(String phase) {
        return switch (phase) {
            case "active" -> GREEN;
            case "warmup" -> CYAN;
            case "recovering" -> ORANGE;
            case "fatigued" -> RED;
            default -> MUTED;
        };
    }

    private float[] attentionColor(String attention) {
        return switch (attention) {
            case "Focused" -> GREEN;
            case "Drifting" -> ORANGE;
            case "Distracted" -> RED;
            default -> MUTED;
        };
    }

    private float[] fatigueColor(double fatigue) {
        if (fatigue > 1.25) {
            return RED;
        }
        if (fatigue > 1.1) {
            return ORANGE;
        }
        return GREEN;
    }

    private float[] hotspotColor(String inventoryMode) {
        return switch (inventoryMode) {
            case "Bank", "Deposit box" -> GREEN;
            case "Drop", "Special" -> ORANGE;
            default -> MUTED;
        };
    }

    private float[] logColor(String entry) {
        if (entry.contains("WARN:")) {
            return RED;
        }
        if (entry.contains("OK:")) {
            return GREEN;
        }
        if (entry.contains("MOVE:")) {
            return CYAN;
        }
        if (entry.contains("QUIRK:")) {
            return ORANGE;
        }
        if (entry.contains("TASK:")) {
            return GOLD;
        }
        return TEXT;
    }

    private String formatSession() {
        long elapsed = System.currentTimeMillis() - ctx().startTime;
        long minutes = elapsed / 60000;
        long seconds = (elapsed / 1000) % 60;
        return minutes + "m " + seconds + "s";
    }
}
