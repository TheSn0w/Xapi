package com.botwithus.bot.scripts.woodcutting;

import com.botwithus.bot.api.model.LocalPlayer;
import com.botwithus.bot.api.nav.LocalPathfinder;
import com.botwithus.bot.api.nav.WorldPathfinder;
import com.botwithus.bot.api.ui.ScriptUI;

import imgui.ImGui;
import imgui.flag.ImGuiCol;
import imgui.flag.ImGuiTableFlags;
import imgui.type.ImInt;

/**
 * ImGui UI for the woodcutting script.
 * Three tabs: Config, Diagnostics, Log.
 * <p>
 * Thread safety: all data is read from volatile fields populated by the script's
 * {@code collectUIState()} in onLoop. No RPC calls on the render thread.
 */
final class WoodcuttingUI implements ScriptUI {

    private final WoodcuttingScript script;
    private final ImInt selectedTree = new ImInt(2); // Default: WILLOW (index 2)

    WoodcuttingUI(WoodcuttingScript script) {
        this.script = script;
    }

    @Override
    public void render() {
        if (script.getPace() == null) {
            ImGui.text("Initialising...");
            return;
        }

        if (ImGui.beginTabBar("##wc_tabs")) {
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
    }

    // ── Config Tab ───────────────────────────────────────────────

    private void renderConfigTab() {
        ImGui.text("Tree Type");
        ImGui.separator();

        WoodcuttingConfig.TreeType[] types = WoodcuttingConfig.TreeType.values();
        String[] names = new String[types.length];
        for (int i = 0; i < types.length; i++) {
            names[i] = types[i].displayName();
        }

        // Sync selectedTree with current config
        WoodcuttingConfig.TreeType current = script.getConfig().getTreeType();
        selectedTree.set(current.ordinal());

        if (ImGui.combo("##tree_type", selectedTree, names)) {
            WoodcuttingConfig.TreeType chosen = types[selectedTree.get()];
            script.getConfig().setTreeType(chosen);
            script.logAction("Tree type changed to " + chosen.objectName);
        }

        ImGui.spacing();
        ImGui.text("Current: " + current.objectName);
        ImGui.text("Log type: " + current.logType.name);
        ImGui.text("Interaction: " + current.interactOption);

        ImGui.spacing();
        ImGui.separator();
        ImGui.text("Locations (Draynor defaults)");
        ImGui.text(String.format("Trees: (%d, %d)  Bank: (%d, %d)",
                script.getConfig().getTreeAreaX(), script.getConfig().getTreeAreaY(),
                script.getConfig().getBankAreaX(), script.getConfig().getBankAreaY()));
    }

    // ── Diagnostics Tab ──────────────────────────────────────────

    private void renderDiagnosticsTab() {
        int flags = ImGuiTableFlags.Borders | ImGuiTableFlags.RowBg | ImGuiTableFlags.SizingStretchProp;
        if (ImGui.beginTable("##wc_diag", 2, flags)) {
            ImGui.tableSetupColumn("Property");
            ImGui.tableSetupColumn("Value");
            ImGui.tableHeadersRow();

            // State
            diagRow("State", script.getState().getDisplayName());

            // Session time
            long elapsed = System.currentTimeMillis() - script.startTime;
            long minutes = elapsed / 60000;
            long seconds = (elapsed / 1000) % 60;
            diagRow("Session Time", String.format("%dm %ds", minutes, seconds));

            // Pace
            diagRow("Phase", script.pacePhase);
            diagRow("Fatigue", String.format("%.1f%%", script.fatigue * 100));

            ImGui.tableNextRow();
            ImGui.tableNextColumn();
            ImGui.separator();
            ImGui.tableNextColumn();
            ImGui.separator();

            // Counters
            diagRow("Logs Chopped", String.valueOf(script.logsChopped));
            diagRow("Woodbox Fills", String.valueOf(script.woodboxFills));
            diagRow("Bank Trips", String.valueOf(script.bankTrips));

            ImGui.tableNextRow();
            ImGui.tableNextColumn();
            ImGui.separator();
            ImGui.tableNextColumn();
            ImGui.separator();

            // Inventory
            diagRow("Free Slots", String.valueOf(script.freeSlots));
            diagRow("Has Wood Box", script.hasWoodBox ? "Yes" : "No");
            diagRow("Woodbox Stored", script.woodboxStored + " / " + script.woodboxCapacity);

            // Animation
            diagRow("Animation", script.animationId == -1 ? "Idle" : String.valueOf(script.animationId));
            diagRow("Bank Open", script.bankOpen ? "Yes" : "No");

            ImGui.endTable();
        }
    }

    // ── Debug Tab ─────────────────────────────────────────────────

    /** Cached pathfinding result (only recomputed on button click, not every frame). */
    private String pathfindResult = "Click a button to test";

    private void renderDebugTab() {
        ImGui.text("Force State");
        ImGui.separator();

        if (ImGui.button("-> BANKING")) {
            script.forceState(WoodcuttingState.WALKING_TO_BANK);
        }
        ImGui.sameLine();
        if (ImGui.button("-> CHOPPING")) {
            script.forceState(WoodcuttingState.CHOPPING);
        }
        ImGui.sameLine();
        if (ImGui.button("-> FILL WOODBOX")) {
            script.forceState(WoodcuttingState.FILLING_WOODBOX);
        }

        if (ImGui.button("-> WALK TO BANK")) {
            script.forceState(WoodcuttingState.WALKING_TO_BANK);
        }
        ImGui.sameLine();
        if (ImGui.button("-> WALK TO TREES")) {
            script.forceState(WoodcuttingState.WALKING_TO_TREES);
        }
        ImGui.sameLine();
        if (ImGui.button("-> IDLE")) {
            script.forceState(WoodcuttingState.IDLE);
        }

        ImGui.spacing();
        ImGui.spacing();
        ImGui.text("Pathfinding Test");
        ImGui.separator();

        if (ImGui.button("Test: Distance to Bank")) {
            pathfindResult = testPathDistance(
                    script.getConfig().getBankAreaX(),
                    script.getConfig().getBankAreaY(),
                    "Bank");
        }
        ImGui.sameLine();
        if (ImGui.button("Test: Distance to Trees")) {
            pathfindResult = testPathDistance(
                    script.getConfig().getTreeAreaX(),
                    script.getConfig().getTreeAreaY(),
                    "Trees");
        }

        if (ImGui.button("Test: Nearest Counter (A*)")) {
            pathfindResult = testNearestCounter();
        }

        if (ImGui.button("Test: All Counter Distances")) {
            pathfindResult = testAllCounterDistances();
        }

        if (ImGui.button("Dump Bank Wall Flags")) {
            pathfindResult = dumpBankWallFlags();
        }

        ImGui.spacing();
        if (!pathfindResult.isEmpty()) {
            if (ImGui.smallButton("Copy Result")) {
                ImGui.setClipboardText(pathfindResult);
            }
            ImGui.sameLine();
        }
        ImGui.textWrapped(pathfindResult);
    }

    /**
     * Computes both Chebyshev and WorldPathfinder walk distance from the player to a target,
     * so the user can see if the pathfinder is producing different (correct) results.
     */
    private String testPathDistance(int destX, int destY, String label) {
        if (script.getAPI() == null) return "API not ready";

        LocalPlayer player = script.getAPI().getLocalPlayer();
        int px = player.tileX(), py = player.tileY(), plane = player.plane();

        // Chebyshev
        int chebyshev = Math.max(Math.abs(px - destX), Math.abs(py - destY));

        // WorldPathfinder (transition-aware A*)
        WorldPathfinder wpf = WorldPathfinder.getInstance();
        if (wpf == null) return label + ": Chebyshev=" + chebyshev + " | WPF=N/A (not initialized)";

        int wpfDist = wpf.walkDistance(px, py, destX, destY, plane);
        String wpfInfo = wpfDist >= 0 ? "steps=" + wpfDist : "NO PATH FOUND";

        String msg = String.format("%s: from (%d,%d) to (%d,%d)\n  Chebyshev: %d tiles\n  WPF: %s",
                label, px, py, destX, destY, chebyshev, wpfInfo);
        script.logAction("PATHFIND TEST " + label + ": Chebyshev=" + chebyshev + " WPF=" + wpfInfo);
        return msg;
    }

    /**
     * Finds the nearest Counter via the query system (which now uses WorldPathfinder
     * walk distance) and reports both its tile position and the computed distance.
     */
    private String testNearestCounter() {
        if (script.getAPI() == null) return "API not ready";

        var objects = new com.botwithus.bot.api.entities.SceneObjects(script.getAPI());
        var counter = objects.query().named("Counter").nearest();
        if (counter == null) return "No Counter found nearby";

        LocalPlayer player = script.getAPI().getLocalPlayer();
        int px = player.tileX(), py = player.tileY(), plane = player.plane();
        int chebyshev = Math.max(Math.abs(px - counter.tileX()),
                Math.abs(py - counter.tileY()));

        WorldPathfinder wpf = WorldPathfinder.getInstance();
        String wpfInfo = "N/A";
        if (wpf != null) {
            int dist = wpf.walkDistance(px, py, counter.tileX(), counter.tileY(), plane);
            wpfInfo = dist >= 0 ? "steps=" + dist : "NO PATH";
        }

        String msg = String.format("Nearest Counter at (%d, %d)\n  Chebyshev: %d tiles\n  WPF: %s",
                counter.tileX(), counter.tileY(), chebyshev, wpfInfo);
        script.logAction("NEAREST COUNTER: (" + counter.tileX() + "," + counter.tileY() + ") Cheby=" + chebyshev + " WPF=" + wpfInfo);
        return msg;
    }

    /**
     * Queries all Counters and shows walk distance to each using multiple
     * pathfinding methods. Highlights which one nearest() would pick.
     */
    private String testAllCounterDistances() {
        if (script.getAPI() == null) return "API not ready";

        var objects = new com.botwithus.bot.api.entities.SceneObjects(script.getAPI());
        var counters = objects.query().named("Counter").all();
        if (counters.isEmpty()) return "No Counters found nearby";

        LocalPlayer player = script.getAPI().getLocalPlayer();
        int px = player.tileX(), py = player.tileY(), plane = player.plane();

        StringBuilder sb = new StringBuilder();
        sb.append(String.format("Player at (%d,%d) — %d Counters found:\n", px, py, counters.size()));

        WorldPathfinder wpf = WorldPathfinder.getInstance();
        int bestDist = Integer.MAX_VALUE;
        String bestTile = "";

        for (var counter : counters) {
            int cx = counter.tileX(), cy = counter.tileY();
            int chebyshev = Math.max(Math.abs(px - cx), Math.abs(py - cy));

            // Local A* (game server)
            var localPath = script.getAPI().findPath(px, py, cx, cy);
            String localStr = localPath.found() ? String.valueOf(localPath.pathLength()) : "NO PATH";

            // WorldPathfinder (ClaudeDecoder transition-aware A*)
            int wpfDist = wpf != null ? wpf.walkDistance(px, py, cx, cy, plane) : -1;
            String wpfStr = wpfDist >= 0 ? String.valueOf(wpfDist) : "NO PATH";

            String line = String.format("  (%d,%d) Cheby=%d Local=%s WPF=%s", cx, cy, chebyshev, localStr, wpfStr);
            sb.append(line).append("\n");
            script.logAction(String.format("COUNTER (%d,%d) Cheby=%d Local=%s WPF=%s", cx, cy, chebyshev, localStr, wpfStr));

            if (wpfDist >= 0 && wpfDist < bestDist) {
                bestDist = wpfDist;
                bestTile = "(" + cx + "," + cy + ")";
            }
        }

        sb.append("Nearest by WorldPathfinder: ").append(bestTile).append(" dist=").append(bestDist);
        script.logAction("NEAREST BY WPF: " + bestTile + " dist=" + bestDist);
        return sb.toString();
    }

    /**
     * Dumps the walkability and directional flags for tiles around the first counter.
     * Shows a grid to visualize where walls are in the navdata.
     */
    private String dumpBankWallFlags() {
        LocalPathfinder pf = LocalPathfinder.getInstance();
        if (pf == null) return "Pathfinder not initialized";

        var objects = new com.botwithus.bot.api.entities.SceneObjects(script.getAPI());
        var counters = objects.query().named("Counter").all();
        if (counters.isEmpty()) return "No Counters found";

        // Use the first counter as center
        int cx = counters.getFirst().tileX();
        int cy = counters.getFirst().tileY();
        var cmap = pf.getCollisionMap();
        int plane = script.getAPI().getLocalPlayer().plane();

        StringBuilder sb = new StringBuilder();
        sb.append(String.format("Flags around (%d,%d) plane=%d:\n", cx, cy, plane));
        sb.append("Format: (x,y) W=walkable N/E/S/W=can move\n\n");

        // Dump 7x7 grid around the counter
        for (int dy = 3; dy >= -3; dy--) {
            for (int dx = -3; dx <= 3; dx++) {
                int wx = cx + dx, wy = cy + dy;
                boolean walk = cmap.isWalkable(wx, wy, plane);
                boolean n = cmap.canMove(wx, wy, plane, 2);
                boolean e = cmap.canMove(wx, wy, plane, 4);
                boolean s = cmap.canMove(wx, wy, plane, 8);
                boolean w = cmap.canMove(wx, wy, plane, 16);
                String dirs = (n?"N":".")+(e?"E":".")+(s?"S":".")+(w?"W":".");
                sb.append(String.format("(%d,%d)%s%s ", wx, wy, walk?"W":"X", dirs));
            }
            sb.append("\n");
        }
        return sb.toString();
    }

    private static void diagRow(String label, String value) {
        ImGui.tableNextRow();
        ImGui.tableNextColumn();
        ImGui.text(label);
        ImGui.tableNextColumn();
        ImGui.text(value);
    }

    // ── Log Tab ──────────────────────────────────────────────────

    private void renderLogTab() {
        ImGui.text("Action Log (" + script.actionLog.size() + " entries)");
        ImGui.sameLine();
        if (ImGui.smallButton("Copy All")) {
            StringBuilder sb = new StringBuilder();
            for (String entry : script.actionLog) {
                sb.append(entry).append('\n');
            }
            ImGui.setClipboardText(sb.toString());
        }
        ImGui.sameLine();
        if (ImGui.smallButton("Clear")) {
            script.actionLog.clear();
        }
        ImGui.separator();

        float height = ImGui.getContentRegionAvailY();
        if (ImGui.beginChild("##wc_log", 0, height, false, 0)) {
            for (String entry : script.actionLog) {
                // Color-code by keyword
                if (entry.contains("FAILED") || entry.contains("Error") || entry.contains("failed") || entry.contains("No ")) {
                    ImGui.pushStyleColor(ImGuiCol.Text, 1.0f, 0.4f, 0.4f, 1.0f);
                    ImGui.textWrapped(entry);
                    ImGui.popStyleColor();
                } else if (entry.contains("OK") || entry.contains("complete") || entry.contains("Arrived") || entry.contains("Started")) {
                    ImGui.pushStyleColor(ImGuiCol.Text, 0.4f, 1.0f, 0.4f, 1.0f);
                    ImGui.textWrapped(entry);
                    ImGui.popStyleColor();
                } else if (entry.contains("Walking") || entry.contains("Opening")) {
                    ImGui.pushStyleColor(ImGuiCol.Text, 0.4f, 0.7f, 1.0f, 1.0f);
                    ImGui.textWrapped(entry);
                    ImGui.popStyleColor();
                } else {
                    ImGui.textWrapped(entry);
                }
            }
        }
        ImGui.endChild();
    }
}
