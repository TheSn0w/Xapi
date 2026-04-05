package com.botwithus.bot.pathfinder;

import com.botwithus.bot.api.ui.ScriptUI;
import imgui.ImGui;
import imgui.flag.ImGuiTableFlags;
import imgui.type.ImBoolean;
import imgui.type.ImInt;
import imgui.type.ImString;

import java.util.EnumSet;
import java.util.List;
import java.util.Set;

/**
 * ImGui UI for the pathfinder test script.
 */
final class PathfinderUI implements ScriptUI {

    private static final float[] CYAN  = {0f, 0.82f, 1f, 1f};
    private static final float[] GREEN = {0.48f, 0.93f, 0.62f, 1f};
    private static final float[] RED   = {1f, 0.35f, 0.35f, 1f};
    private static final float[] GOLD  = {1f, 0.84f, 0f, 1f};
    private static final float[] DIM   = {0.6f, 0.6f, 0.6f, 1f};

    private static final float INPUT_WIDTH = 160f;
    private static final float PLANE_WIDTH = 100f;

    private final PathfinderScript script;
    private final PathfinderSettings settings;

    // Input fields
    private final ImInt destX = new ImInt(0);
    private final ImInt destY = new ImInt(0);
    private final ImInt destPlane = new ImInt(0);
    private final ImInt jitterSeed = new ImInt(0);

    // Checkpoint name input
    private final ImString checkpointName = new ImString(64);

    // Type filter checkbox states (synced from settings)
    private final boolean[] typeEnabled;

    PathfinderUI(PathfinderScript script) {
        this.script = script;
        this.settings = PathfinderSettings.load();

        // Restore last destination
        destX.set(settings.lastDestX);
        destY.set(settings.lastDestY);
        destPlane.set(settings.lastDestPlane);
        jitterSeed.set(settings.jitterSeed);

        // Init type filter states from settings
        Transition.Type[] types = Transition.Type.values();
        typeEnabled = new boolean[types.length];
        for (int i = 0; i < types.length; i++) {
            typeEnabled[i] = settings.isTypeEnabled(types[i]);
        }

        // Apply filter to pathfinder when it becomes available
        applyTypeFilter();
    }

    @Override
    public void render() {
        if (script.pathfinder == null) {
            ImGui.textColored(GOLD[0], GOLD[1], GOLD[2], GOLD[3], script.statusMessage);
            return;
        }

        // Header
        ImGui.textColored(CYAN[0], CYAN[1], CYAN[2], CYAN[3], "Pathfinder");
        ImGui.sameLine();
        ImGui.textColored(DIM[0], DIM[1], DIM[2], DIM[3], "v0.1");
        ImGui.separator();

        if (ImGui.beginTabBar("##pathfinder")) {
            if (ImGui.beginTabItem("Navigate")) {
                renderNavigateTab();
                ImGui.endTabItem();
            }
            if (ImGui.beginTabItem("Transition Filters")) {
                renderFiltersTab();
                ImGui.endTabItem();
            }
            if (ImGui.beginTabItem("Path Result")) {
                renderPathResultTab();
                ImGui.endTabItem();
            }
            if (ImGui.beginTabItem("Nearby")) {
                renderNearbyTab();
                ImGui.endTabItem();
            }
            if (ImGui.beginTabItem("Stats")) {
                renderStatsTab();
                ImGui.endTabItem();
            }
            ImGui.endTabBar();
        }
    }

    // ── Navigate Tab ─────────────────────────────────────────────

    private void renderNavigateTab() {
        // Player position
        ImGui.textColored(CYAN[0], CYAN[1], CYAN[2], CYAN[3], "Player Position");
        ImGui.text(String.format("  (%d, %d) plane %d %s",
                script.playerX, script.playerY, script.playerPlane,
                script.playerMoving ? "(moving)" : ""));
        ImGui.separator();

        // Destination input
        ImGui.textColored(CYAN[0], CYAN[1], CYAN[2], CYAN[3], "Destination");

        ImGui.setNextItemWidth(INPUT_WIDTH);
        ImGui.inputInt("X##dest", destX);
        ImGui.sameLine();
        ImGui.setNextItemWidth(INPUT_WIDTH);
        ImGui.inputInt("Y##dest", destY);
        ImGui.sameLine();
        ImGui.setNextItemWidth(PLANE_WIDTH);
        ImGui.inputInt("Plane##dest", destPlane);

        ImGui.setNextItemWidth(INPUT_WIDTH);
        ImGui.inputInt("Jitter Seed", jitterSeed);
        ImGui.sameLine();
        ImGui.textColored(DIM[0], DIM[1], DIM[2], DIM[3], "(0 = shortest path)");

        ImGui.spacing();

        // Action buttons
        if (ImGui.button("Find Path")) {
            findPath();
        }
        ImGui.sameLine();
        if (ImGui.button("Find & Execute")) {
            findAndExecute();
        }
        ImGui.sameLine();
        if (ImGui.button("Player -> Dest")) {
            destX.set(script.playerX);
            destY.set(script.playerY);
            destPlane.set(script.playerPlane);
        }

        // Executor status
        ImGui.spacing();
        PathExecutor exec = script.executor;
        String stateStr = exec.getState().name();
        float[] stateColor = switch (exec.getState()) {
            case ARRIVED -> GREEN;
            case FAILED -> RED;
            case IDLE -> DIM;
            default -> GOLD;
        };
        ImGui.textColored(stateColor[0], stateColor[1], stateColor[2], stateColor[3],
                "Executor: " + stateStr);

        if (exec.isActive()) {
            ImGui.sameLine();
            ImGui.text(String.format("(%d / %d)",
                    exec.getCurrentSegmentIndex() + 1, exec.getTotalSegments()));
            ImGui.sameLine();
            if (ImGui.button("Cancel")) {
                exec.cancel();
                script.statusMessage = "Cancelled";
            }
        }

        ImGui.text(script.statusMessage);

        // ── Checkpoints ──────────────────────────────────────────
        ImGui.spacing();
        ImGui.separator();
        ImGui.textColored(CYAN[0], CYAN[1], CYAN[2], CYAN[3],
                "Checkpoints (" + settings.checkpoints.size() + ")");

        // Add checkpoint row
        ImGui.setNextItemWidth(INPUT_WIDTH);
        ImGui.inputText("##cpName", checkpointName);
        ImGui.sameLine();
        if (ImGui.button("Save##cp")) {
            String name = checkpointName.get().trim();
            if (name.isEmpty()) {
                name = String.format("(%d, %d, %d)", destX.get(), destY.get(), destPlane.get());
            }
            settings.checkpoints.add(new PathfinderSettings.Checkpoint(
                    name, destX.get(), destY.get(), destPlane.get()));
            saveSettings();
            checkpointName.set("");
        }
        ImGui.sameLine();
        if (ImGui.button("Save Player Pos")) {
            String name = String.format("(%d, %d, %d)", script.playerX, script.playerY, script.playerPlane);
            settings.checkpoints.add(new PathfinderSettings.Checkpoint(
                    name, script.playerX, script.playerY, script.playerPlane));
            saveSettings();
        }

        // Checkpoint table
        if (!settings.checkpoints.isEmpty()) {
            int removeIdx = -1;

            if (ImGui.beginTable("##checkpoints", 6,
                    ImGuiTableFlags.Borders | ImGuiTableFlags.RowBg | ImGuiTableFlags.SizingStretchProp)) {
                ImGui.tableSetupColumn("Name", 0, 3f);
                ImGui.tableSetupColumn("X", 0, 1f);
                ImGui.tableSetupColumn("Y", 0, 1f);
                ImGui.tableSetupColumn("P", 0, 0.5f);
                ImGui.tableSetupColumn("##nav", 0, 1.8f);
                ImGui.tableSetupColumn("##del", 0, 0.5f);
                ImGui.tableHeadersRow();

                for (int i = 0; i < settings.checkpoints.size(); i++) {
                    PathfinderSettings.Checkpoint cp = settings.checkpoints.get(i);
                    ImGui.tableNextRow();
                    ImGui.tableNextColumn(); ImGui.text(cp.name);
                    ImGui.tableNextColumn(); ImGui.text(String.valueOf(cp.x));
                    ImGui.tableNextColumn(); ImGui.text(String.valueOf(cp.y));
                    ImGui.tableNextColumn(); ImGui.text(String.valueOf(cp.plane));
                    ImGui.tableNextColumn();
                    if (ImGui.smallButton("Set##cp" + i)) {
                        destX.set(cp.x);
                        destY.set(cp.y);
                        destPlane.set(cp.plane);
                    }
                    ImGui.sameLine();
                    if (ImGui.smallButton("Go##cp" + i)) {
                        destX.set(cp.x);
                        destY.set(cp.y);
                        destPlane.set(cp.plane);
                        findAndExecute();
                    }
                    ImGui.tableNextColumn();
                    if (ImGui.smallButton("X##del" + i)) {
                        removeIdx = i;
                    }
                }
                ImGui.endTable();
            }

            if (removeIdx >= 0) {
                settings.checkpoints.remove(removeIdx);
                saveSettings();
            }
        }

        // ── Execution Log ────────────────────────────────────────
        ImGui.spacing();
        ImGui.separator();
        ImGui.textColored(CYAN[0], CYAN[1], CYAN[2], CYAN[3], "Execution Log");
        ImGui.sameLine();
        if (ImGui.smallButton("Copy")) {
            List<String> lines = script.executor.getLogBuffer();
            if (!lines.isEmpty()) {
                ImGui.setClipboardText(String.join("\n", lines));
            }
        }
        ImGui.sameLine();
        if (ImGui.smallButton("Clear")) {
            script.executor.clearLog();
        }

        List<String> logLines = script.executor.getLogBuffer();
        float logHeight = Math.min(200, Math.max(80, logLines.size() * 14f));
        if (ImGui.beginChild("##execLog", 0, logHeight, true)) {
            for (String line : logLines) {
                if (line.contains("WARN")) {
                    ImGui.textColored(RED[0], RED[1], RED[2], RED[3], line);
                } else if (line.contains("INTERACT") || line.contains("WAIT")) {
                    ImGui.textColored(GOLD[0], GOLD[1], GOLD[2], GOLD[3], line);
                } else if (line.contains("ARRIVED")) {
                    ImGui.textColored(GREEN[0], GREEN[1], GREEN[2], GREEN[3], line);
                } else {
                    ImGui.text(line);
                }
            }
            // Auto-scroll to bottom
            if (ImGui.getScrollY() >= ImGui.getScrollMaxY() - 20) {
                ImGui.setScrollHereY(1.0f);
            }
        }
        ImGui.endChild();
    }

    // ── Transition Filters Tab ───────────────────────────────────

    private void renderFiltersTab() {
        ImGui.textColored(CYAN[0], CYAN[1], CYAN[2], CYAN[3], "Transition Type Filters");
        ImGui.textColored(DIM[0], DIM[1], DIM[2], DIM[3],
                "Unchecked types will be ignored during pathfinding.");
        ImGui.separator();

        // Select all / none buttons
        if (ImGui.button("Enable All")) {
            for (int i = 0; i < typeEnabled.length; i++) typeEnabled[i] = true;
            syncFiltersToSettings();
        }
        ImGui.sameLine();
        if (ImGui.button("Disable All")) {
            for (int i = 0; i < typeEnabled.length; i++) typeEnabled[i] = false;
            syncFiltersToSettings();
        }
        ImGui.sameLine();
        if (ImGui.button("Walking Only")) {
            for (int i = 0; i < typeEnabled.length; i++) typeEnabled[i] = false;
            syncFiltersToSettings();
        }

        ImGui.spacing();

        Transition.Type[] types = Transition.Type.values();

        // Render in two columns for compactness
        if (ImGui.beginTable("##typeFilters", 2, ImGuiTableFlags.SizingStretchSame)) {
            int half = (types.length + 1) / 2;
            for (int i = 0; i < half; i++) {
                ImGui.tableNextRow();

                // Left column
                ImGui.tableNextColumn();
                if (renderTypeCheckbox(types[i], i)) {
                    syncFiltersToSettings();
                }

                // Right column
                int ri = i + half;
                ImGui.tableNextColumn();
                if (ri < types.length) {
                    if (renderTypeCheckbox(types[ri], ri)) {
                        syncFiltersToSettings();
                    }
                }
            }
            ImGui.endTable();
        }

        ImGui.spacing();
        ImGui.separator();

        // Summary
        int enabled = 0;
        for (boolean b : typeEnabled) if (b) enabled++;
        ImGui.text(String.format("%d / %d types enabled", enabled, types.length));
    }

    // Reusable ImBoolean to avoid allocation per frame
    private final ImBoolean cbRef = new ImBoolean();

    private boolean renderTypeCheckbox(Transition.Type type, int index) {
        cbRef.set(typeEnabled[index]);
        String label = type.name() + " (+" + type.penalty() + ")";
        if (ImGui.checkbox(label + "##tf" + index, cbRef)) {
            typeEnabled[index] = cbRef.get();
            return true;
        }
        return false;
    }

    private void syncFiltersToSettings() {
        Transition.Type[] types = Transition.Type.values();
        for (int i = 0; i < types.length; i++) {
            settings.setTypeEnabled(types[i], typeEnabled[i]);
        }
        applyTypeFilter();
        saveSettings();
    }

    private void applyTypeFilter() {
        if (script.pathfinder == null) return;

        Transition.Type[] types = Transition.Type.values();
        boolean allEnabled = true;
        for (boolean b : typeEnabled) {
            if (!b) { allEnabled = false; break; }
        }

        if (allEnabled) {
            script.pathfinder.setEnabledTypes(null);
        } else {
            Set<Transition.Type> enabled = EnumSet.noneOf(Transition.Type.class);
            for (int i = 0; i < types.length; i++) {
                if (typeEnabled[i]) enabled.add(types[i]);
            }
            script.pathfinder.setEnabledTypes(enabled);
        }
    }

    // ── Path finding helpers ─────────────────────────────────────

    private void findPath() {
        if (script.pathfinder == null) return;

        // Apply current type filter
        applyTypeFilter();

        // Save destination to settings
        settings.lastDestX = destX.get();
        settings.lastDestY = destY.get();
        settings.lastDestPlane = destPlane.get();
        settings.jitterSeed = jitterSeed.get();
        saveSettings();

        long start = System.nanoTime();

        PathResult result;
        int dp = destPlane.get();
        if (dp != script.playerPlane) {
            result = script.pathfinder.findPathCrossPlane(
                    script.playerX, script.playerY, script.playerPlane,
                    destX.get(), destY.get(), dp, jitterSeed.get());
        } else {
            result = script.pathfinder.findPath(
                    script.playerX, script.playerY,
                    destX.get(), destY.get(), dp, jitterSeed.get());
        }

        long elapsed = (System.nanoTime() - start) / 1000;
        script.lastResult = result;

        if (result.found()) {
            script.statusMessage = String.format("Path found: %d steps, %d interactions, cost %d (%dus)",
                    result.walkStepCount(), result.interactionCount(), result.totalCost(), elapsed);
        } else {
            script.statusMessage = String.format("No path found (%dus)", elapsed);
        }
    }

    private void findAndExecute() {
        findPath();
        PathResult result = script.lastResult;
        if (result != null && result.found() && script.api != null) {
            script.executor.start(result, script.api);
            script.statusMessage = "Executing — " + result.walkStepCount() + " steps, "
                    + result.interactionCount() + " interactions";
        }
    }

    private void saveSettings() {
        settings.save();
    }

    // ── Path Result Tab ──────────────────────────────────────────

    private void renderPathResultTab() {
        PathResult result = script.lastResult;
        if (result == null) {
            ImGui.text("No path computed yet.");
            return;
        }

        if (!result.found()) {
            ImGui.textColored(RED[0], RED[1], RED[2], RED[3], "No path found");
            return;
        }

        ImGui.textColored(GREEN[0], GREEN[1], GREEN[2], GREEN[3],
                String.format("Path: %d steps, %d interactions, %d teleports — cost %d",
                        result.walkStepCount(), result.interactionCount(),
                        result.teleportCount(), result.totalCost()));
        ImGui.separator();

        // Walk segments
        List<WalkSegment> segments = WalkPlan.plan(result);
        ImGui.textColored(CYAN[0], CYAN[1], CYAN[2], CYAN[3],
                "Walk Segments (" + segments.size() + ")");

        if (ImGui.beginTable("##segments", 4, ImGuiTableFlags.Borders | ImGuiTableFlags.RowBg)) {
            ImGui.tableSetupColumn("#");
            ImGui.tableSetupColumn("Target");
            ImGui.tableSetupColumn("Interaction");
            ImGui.tableSetupColumn("Object");
            ImGui.tableHeadersRow();

            for (int i = 0; i < segments.size(); i++) {
                WalkSegment seg = segments.get(i);
                ImGui.tableNextRow();
                ImGui.tableNextColumn(); ImGui.text(String.valueOf(i + 1));
                ImGui.tableNextColumn(); ImGui.text(String.format("(%d, %d)", seg.targetX(), seg.targetY()));
                ImGui.tableNextColumn();
                if (seg.hasInteraction()) {
                    ImGui.textColored(GOLD[0], GOLD[1], GOLD[2], GOLD[3],
                            seg.interactBefore().option());
                } else {
                    ImGui.textColored(DIM[0], DIM[1], DIM[2], DIM[3], "walk");
                }
                ImGui.tableNextColumn();
                if (seg.hasInteraction()) {
                    ImGui.text(seg.interactBefore().objectName());
                }
            }
            ImGui.endTable();
        }

        // Detailed steps (collapsible)
        if (ImGui.treeNode("Detailed Steps (" + result.steps().size() + ")")) {
            for (int i = 0; i < result.steps().size(); i++) {
                PathStep step = result.steps().get(i);
                switch (step) {
                    case PathStep.WalkTo w ->
                            ImGui.text(String.format("%3d  WALK  (%d, %d)", i, w.x(), w.y()));
                    case PathStep.Interact it -> {
                        ImGui.textColored(GOLD[0], GOLD[1], GOLD[2], GOLD[3],
                                String.format("%3d  INTERACT '%s' '%s' at (%d,%d) [%s]",
                                        i, it.objectName(), it.option(), it.x(), it.y(), it.type()));
                    }
                    case PathStep.Teleport tp ->
                            ImGui.textColored(CYAN[0], CYAN[1], CYAN[2], CYAN[3],
                                    String.format("%3d  TELEPORT '%s' to (%d,%d)", i, tp.name(), tp.x(), tp.y()));
                }
            }
            ImGui.treePop();
        }
    }

    // ── Nearby Transitions Tab ───────────────────────────────────

    private void renderNearbyTab() {
        TransitionStore store = script.transitions;
        if (store == null) {
            ImGui.text("Transitions not loaded.");
            return;
        }

        int px = script.playerX, py = script.playerY, pp = script.playerPlane;
        ImGui.text(String.format("Transitions within 15 tiles of (%d, %d, %d)", px, py, pp));
        ImGui.separator();

        if (ImGui.beginTable("##nearby", 7,
                ImGuiTableFlags.Borders | ImGuiTableFlags.RowBg | ImGuiTableFlags.ScrollY)) {
            ImGui.tableSetupColumn("Type");
            ImGui.tableSetupColumn("Name");
            ImGui.tableSetupColumn("Option");
            ImGui.tableSetupColumn("Src");
            ImGui.tableSetupColumn("Dst");
            ImGui.tableSetupColumn("Bidir");
            ImGui.tableSetupColumn("Action");
            ImGui.tableHeadersRow();

            int count = 0;
            for (Transition t : store.getAll()) {
                int dist = Math.abs(t.srcX() - px) + Math.abs(t.srcY() - py);
                if (dist > 15 || t.srcP() != pp) continue;
                if (count++ > 50) break;

                ImGui.tableNextRow();
                ImGui.tableNextColumn(); ImGui.text(t.type().name());
                ImGui.tableNextColumn(); ImGui.text(t.name());
                ImGui.tableNextColumn(); ImGui.text(t.option());
                ImGui.tableNextColumn(); ImGui.text(String.format("(%d,%d,%d)", t.srcX(), t.srcY(), t.srcP()));
                ImGui.tableNextColumn(); ImGui.text(String.format("(%d,%d,%d)", t.dstX(), t.dstY(), t.dstP()));
                ImGui.tableNextColumn(); ImGui.text(t.bidir() ? "Y" : "");
                ImGui.tableNextColumn();
                if (ImGui.smallButton("Set##" + count)) {
                    destX.set(t.dstX());
                    destY.set(t.dstY());
                    destPlane.set(t.dstP());
                }
            }
            ImGui.endTable();
        }
    }

    // ── Stats Tab ────────────────────────────────────────────────

    private void renderStatsTab() {
        TransitionStore store = script.transitions;
        AStarPathfinder pf = script.pathfinder;

        ImGui.textColored(CYAN[0], CYAN[1], CYAN[2], CYAN[3], "Pathfinder Stats");
        ImGui.separator();

        if (store != null) {
            ImGui.text("  Transitions loaded: " + store.size());
        }
        if (pf != null) {
            ImGui.text("  Regions cached: " + pf.getCollisionMap().cacheSize());
        }

        ImGui.spacing();
        ImGui.textColored(CYAN[0], CYAN[1], CYAN[2], CYAN[3], "Cost Reference");
        ImGui.text("  Cardinal step:  10");
        ImGui.text("  Diagonal step:  14");
        ImGui.text("  Door penalty:   200");
        ImGui.text("  Gate penalty:   60");
        ImGui.text("  Agility penalty: 60");
        ImGui.text("  Stairs penalty: 25");
        ImGui.text("  Passage penalty: 25");
    }
}
