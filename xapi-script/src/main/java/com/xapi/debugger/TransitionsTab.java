package com.xapi.debugger;

import static com.xapi.debugger.XapiData.*;

import imgui.ImGui;
import imgui.flag.ImGuiCol;
import imgui.flag.ImGuiTableFlags;
import imgui.flag.ImGuiTreeNodeFlags;
import imgui.type.ImInt;
import imgui.type.ImString;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * Transitions tab — captures and logs transitions from live gameplay.
 * Render-thread only (no RPC calls).
 */
final class TransitionsTab {

    private static final DateTimeFormatter TIME_FMT =
            DateTimeFormatter.ofPattern("HH:mm:ss").withZone(ZoneId.systemDefault());

    /** All transition types available for manual classification. */
    private static final String[] TRANSITION_TYPES = {
            "DOOR", "STAIRCASE", "ENTRANCE", "PASSAGE", "WALL_PASSAGE",
            "AGILITY", "TELEPORT", "LODESTONE", "FAIRY_RING", "SPIRIT_TREE",
            "PORTAL", "TRANSPORT", "NPC_TRANSPORT"
    };

    private final XapiState state;
    private final TransitionCapture capture;
    private boolean autoScrollLog = true;
    private int lastLogSize;

    // Per-candidate selected type index (keyed by candidate list index)
    private final ImInt[] selectedType = new ImInt[200];
    private final boolean[] typeInitialized = new boolean[200];
    private final ImString objectFilter = new ImString(128);

    {
        for (int i = 0; i < selectedType.length; i++) selectedType[i] = new ImInt(0);
    }

    TransitionsTab(XapiState state, TransitionCapture capture) {
        this.state = state;
        this.capture = capture;
    }

    void render() {
        renderHeader();
        ImGui.separator();
        renderPendingBanner();
        renderCaptureLog();
        ImGui.separator();
        renderNearbyCandidates();
    }

    // ── Header: connection status + toggles ──────────────────────────────

    private void renderHeader() {
        // Connection status dot
        boolean connected = state.mapDebuggerStatus.equals("Connected");
        if (connected) {
            ImGui.textColored(0.2f, 0.9f, 0.2f, 1f, "\u25CF"); // green dot
        } else {
            ImGui.textColored(0.9f, 0.2f, 0.2f, 1f, "\u25CF"); // red dot
        }
        ImGui.sameLine();
        ImGui.text(state.mapDebuggerStatus);

        ImGui.sameLine(0, 20);

        // Auto-capture toggle
        boolean wasAutoCapture = state.transitionAutoCapture;
        if (wasAutoCapture) {
            ImGui.pushStyleColor(ImGuiCol.Button, 0.2f, 0.7f, 0.3f, 0.6f);
            ImGui.pushStyleColor(ImGuiCol.ButtonHovered, 0.2f, 0.7f, 0.3f, 0.8f);
        }
        if (ImGui.button(wasAutoCapture ? "Auto-Capture: ON" : "Auto-Capture: OFF")) {
            state.transitionAutoCapture = !state.transitionAutoCapture;
        }
        if (wasAutoCapture) ImGui.popStyleColor(2);

        ImGui.sameLine();

        // Track position toggle
        boolean wasTracking = state.transitionTrackPosition;
        if (wasTracking) {
            ImGui.pushStyleColor(ImGuiCol.Button, 0.2f, 0.4f, 0.8f, 0.6f);
            ImGui.pushStyleColor(ImGuiCol.ButtonHovered, 0.2f, 0.4f, 0.8f, 0.8f);
        }
        if (ImGui.button(wasTracking ? "Track Position: ON" : "Track Position: OFF")) {
            state.transitionTrackPosition = !state.transitionTrackPosition;
        }
        if (wasTracking) ImGui.popStyleColor(2);

        // Stats
        List<TransitionData> log = state.transitionLog;
        long sent = log.stream().filter(t -> "sent".equals(t.status())).count();
        long failed = log.stream().filter(t -> "failed".equals(t.status())).count();
        long pending = log.stream().filter(t -> "pending".equals(t.status())).count();
        long duplicate = log.stream().filter(t -> "duplicate".equals(t.status())).count();
        ImGui.sameLine(0, 20);
        ImGui.textColored(0.6f, 0.6f, 0.6f, 0.8f,
                String.format("Captured: %d | Sent: %d | Failed: %d | Dup: %d | Pending: %d",
                        log.size(), sent, failed, duplicate, pending));
    }

    // ── Pending capture banner ───────────────────────────────────────────

    private void renderPendingBanner() {
        TransitionCapture.State cs = capture.getCaptureState();
        if (cs == TransitionCapture.State.IDLE) return;

        float elapsed = capture.getCaptureElapsedMs() / 1000f;
        String stateText = cs == TransitionCapture.State.WAITING_MOVE ? "Waiting for movement..." : "Settling...";

        ImGui.pushStyleColor(ImGuiCol.ChildBg, 0.6f, 0.5f, 0.1f, 0.4f);
        if (ImGui.beginChild("##pending_banner", 0, 30)) {
            ImGui.textColored(1f, 0.9f, 0.3f, 1f,
                    String.format("  \u23F3 %s  '%s' '%s'  (%.1fs)",
                            stateText, capture.getPendingOptionName(),
                            capture.getPendingEntityName(), elapsed));
        }
        ImGui.endChild();
        ImGui.popStyleColor();
    }

    // ── Capture log table ────────────────────────────────────────────────

    private void renderCaptureLog() {
        ImGui.text("Capture Log");
        ImGui.sameLine();
        if (ImGui.button("Send All Pending")) {
            sendAllPending();
        }
        ImGui.sameLine();
        if (ImGui.button("Clear Log")) {
            state.transitionLog.clear();
        }

        List<TransitionData> log = state.transitionLog;
        int flags = ImGuiTableFlags.Borders | ImGuiTableFlags.RowBg
                | ImGuiTableFlags.SizingStretchProp | ImGuiTableFlags.ScrollY
                | ImGuiTableFlags.Resizable;

        // Use ~40% of remaining height for the log
        float logHeight = ImGui.getContentRegionAvailY() * 0.4f;
        if (logHeight < 80) logHeight = 80;

        if (ImGui.beginTable("##capture_log", 8, flags, 0, logHeight)) {
            ImGui.tableSetupColumn("#",       0, 0.3f);
            ImGui.tableSetupColumn("Type",    0, 0.7f);
            ImGui.tableSetupColumn("Name",    0, 1.0f);
            ImGui.tableSetupColumn("Option",  0, 0.7f);
            ImGui.tableSetupColumn("Source",  0, 0.8f);
            ImGui.tableSetupColumn("Dest",    0, 0.8f);
            ImGui.tableSetupColumn("Status",  0, 0.5f);
            ImGui.tableSetupColumn("Action",  0, 0.5f);
            ImGui.tableSetupScrollFreeze(0, 1);
            ImGui.tableHeadersRow();

            for (int i = 0; i < log.size(); i++) {
                TransitionData t = log.get(i);
                ImGui.tableNextRow();

                // #
                ImGui.tableNextColumn();
                ImGui.text(String.valueOf(i + 1));

                // Type
                ImGui.tableNextColumn();
                setTypeColor(t.type());
                ImGui.text(t.type());
                ImGui.popStyleColor();

                // Name
                ImGui.tableNextColumn();
                ImGui.text(t.name());

                // Option
                ImGui.tableNextColumn();
                ImGui.text(t.option());

                // Source
                ImGui.tableNextColumn();
                ImGui.text(String.format("(%d,%d,%d)", t.srcX(), t.srcY(), t.srcP()));

                // Dest
                ImGui.tableNextColumn();
                ImGui.text(String.format("(%d,%d,%d)", t.dstX(), t.dstY(), t.dstP()));

                // Status
                ImGui.tableNextColumn();
                renderStatus(t.status());

                // Action buttons
                ImGui.tableNextColumn();
                if ("failed".equals(t.status()) || "pending".equals(t.status())) {
                    if (ImGui.smallButton("Resend##" + i)) {
                        capture.resend(i);
                    }
                }
                ImGui.sameLine();
                if (ImGui.smallButton("X##del" + i)) {
                    state.transitionLog.remove(i);
                }
            }

            // Auto-scroll to bottom when new entries arrive
            if (log.size() > lastLogSize && autoScrollLog) {
                ImGui.setScrollHereY(1.0f);
            }
            lastLogSize = log.size();

            ImGui.endTable();
        }
    }

    private void renderStatus(String status) {
        switch (status) {
            case "sent" -> ImGui.textColored(0.2f, 0.9f, 0.2f, 1f, "\u2713 sent");
            case "failed" -> ImGui.textColored(0.9f, 0.2f, 0.2f, 1f, "\u2717 failed");
            case "pending" -> ImGui.textColored(0.9f, 0.7f, 0.2f, 1f, "\u23F3 pending");
            case "duplicate" -> ImGui.textColored(0.5f, 0.5f, 0.5f, 1f, "\u2261 dup");
            default -> ImGui.text(status);
        }
    }

    private void setTypeColor(String type) {
        switch (type) {
            case "DOOR"       -> ImGui.pushStyleColor(ImGuiCol.Text, 1f, 1f, 0f, 1f);       // yellow
            case "STAIRCASE"  -> ImGui.pushStyleColor(ImGuiCol.Text, 0f, 0.8f, 1f, 1f);
            case "TELEPORT"   -> ImGui.pushStyleColor(ImGuiCol.Text, 0.8f, 0.4f, 1f, 1f);
            case "ENTRANCE"   -> ImGui.pushStyleColor(ImGuiCol.Text, 1f, 0.67f, 0f, 1f);
            case "AGILITY"    -> ImGui.pushStyleColor(ImGuiCol.Text, 1f, 1f, 0f, 1f);
            case "FAIRY_RING" -> ImGui.pushStyleColor(ImGuiCol.Text, 0f, 1f, 0.53f, 1f);
            case "LODESTONE"  -> ImGui.pushStyleColor(ImGuiCol.Text, 1f, 1f, 1f, 1f);
            default           -> ImGui.pushStyleColor(ImGuiCol.Text, 1f, 0.4f, 0.27f, 1f);
        }
    }

    // ── Nearby objects (all scene objects) ────────────────────────────────

    private void renderNearbyCandidates() {
        if (!ImGui.collapsingHeader("Nearby Objects", ImGuiTreeNodeFlags.DefaultOpen)) return;

        // Filter
        ImGui.setNextItemWidth(200);
        ImGui.inputText("Filter##obj_filter", objectFilter);
        String filter = objectFilter.get().toLowerCase(java.util.Locale.ROOT).trim();

        List<TransitionCandidate> candidates = state.transitionCandidates;
        if (candidates.isEmpty()) {
            ImGui.textColored(0.5f, 0.5f, 0.5f, 1f, "  No objects nearby");
            return;
        }

        int flags = ImGuiTableFlags.Borders | ImGuiTableFlags.RowBg
                | ImGuiTableFlags.SizingStretchProp | ImGuiTableFlags.ScrollY
                | ImGuiTableFlags.Resizable;

        float height = ImGui.getContentRegionAvailY();
        if (height < 100) height = 100;

        if (ImGui.beginTable("##nearby_objects", 7, flags, 0, height)) {
            ImGui.tableSetupColumn("ID",       0, 0.5f);
            ImGui.tableSetupColumn("Name",     0, 1.2f);
            ImGui.tableSetupColumn("Position", 0, 0.8f);
            ImGui.tableSetupColumn("Options",  0, 1.2f);
            ImGui.tableSetupColumn("Type",     0, 0.8f);
            ImGui.tableSetupColumn("Send",     0, 0.4f);
            ImGui.tableSetupColumn("Track",    0, 0.4f);
            ImGui.tableSetupScrollFreeze(0, 1);
            ImGui.tableHeadersRow();

            for (int i = 0; i < candidates.size(); i++) {
                TransitionCandidate c = candidates.get(i);

                // Apply filter
                if (!filter.isEmpty()) {
                    String nameLo = c.name().toLowerCase(java.util.Locale.ROOT);
                    String optsLo = String.join(" ", c.options()).toLowerCase(java.util.Locale.ROOT);
                    if (!nameLo.contains(filter) && !optsLo.contains(filter)) continue;
                }

                ImGui.tableNextRow();

                // ID
                ImGui.tableNextColumn();
                ImGui.text(String.valueOf(c.typeId()));

                // Name
                ImGui.tableNextColumn();
                ImGui.text(c.name());

                // Position
                ImGui.tableNextColumn();
                ImGui.text(String.format("(%d,%d,%d)", c.tileX(), c.tileY(), c.plane()));

                // Options
                ImGui.tableNextColumn();
                List<String> visibleOpts = c.options().stream()
                        .filter(o -> o != null && !o.isEmpty() && !"null".equals(o))
                        .toList();
                ImGui.text(String.join(", ", visibleOpts));

                // Type dropdown — user can override the auto-classified type
                ImGui.tableNextColumn();
                if (i < selectedType.length) {
                    if (!typeInitialized[i]) {
                        typeInitialized[i] = true;
                        for (int t = 0; t < TRANSITION_TYPES.length; t++) {
                            if (TRANSITION_TYPES[t].equals(c.classifiedType())) {
                                selectedType[i].set(t);
                                break;
                            }
                        }
                    }
                    ImGui.setNextItemWidth(ImGui.getColumnWidth());
                    ImGui.combo("##type" + i, selectedType[i], TRANSITION_TYPES);
                }

                // Send button — creates transition from player pos to object and sends immediately
                ImGui.tableNextColumn();
                if (ImGui.smallButton("Send##" + i)) {
                    sendManualTransition(c, i);
                }

                // Track button — starts capture state machine, waits for player to interact
                ImGui.tableNextColumn();
                if (ImGui.smallButton("Track##" + i)) {
                    startTracking(c, i);
                }
            }

            ImGui.endTable();
        }
    }

    // ── Manual send: create transition and send immediately ─────────────

    private void sendManualTransition(TransitionCandidate c, int idx) {
        var lp = state.localPlayerData;
        if (lp == null) return;

        String type = (idx < selectedType.length) ? TRANSITION_TYPES[selectedType[idx].get()] : c.classifiedType();
        boolean bidir = switch (type) {
            case "DOOR", "WALL_PASSAGE", "STAIRCASE", "PASSAGE", "ENTRANCE", "AGILITY" -> true;
            default -> false;
        };
        int cost = ("DOOR".equals(type) || "WALL_PASSAGE".equals(type)) ? 1 : 2;
        String option = "";
        for (String opt : c.options()) {
            if (opt != null && !opt.isEmpty() && !"Examine".equals(opt) && !"null".equals(opt)) {
                option = opt;
                break;
            }
        }

        int srcX, srcY, srcP, dstX, dstY, dstP;
        srcP = c.plane();
        dstP = c.plane();

        if ("DOOR".equals(type) || "WALL_PASSAGE".equals(type)) {
            // A door sits on the wall edge between two tiles.
            // src = tile on the player's side, dst = the door's tile (other side).
            int dx = c.tileX() - lp.tileX();
            int dy = c.tileY() - lp.tileY();

            // dst is always the door's tile
            dstX = c.tileX();
            dstY = c.tileY();

            if (Math.abs(dx) >= Math.abs(dy)) {
                // Door is east/west — src is the tile adjacent to door, toward player
                srcX = c.tileX() + (dx >= 0 ? -1 : 1);
                srcY = c.tileY();
            } else {
                // Door is north/south — src is the tile adjacent to door, toward player
                srcX = c.tileX();
                srcY = c.tileY() + (dy >= 0 ? -1 : 1);
            }
        } else {
            // Non-door: src = player position, dst = object position
            srcX = lp.tileX();
            srcY = lp.tileY();
            srcP = lp.plane();
            dstX = c.tileX();
            dstY = c.tileY();
        }

        TransitionData transition = new TransitionData(
                type, srcX, srcY, srcP, dstX, dstY, dstP,
                c.name(), option, cost, bidir,
                System.currentTimeMillis(), "pending"
        );

        state.transitionLog.add(transition);
        capture.sendDirect(transition);
    }

    // ── Track: start capture state machine for this object ───────────────

    private void startTracking(TransitionCandidate c, int idx) {
        var lp = state.localPlayerData;
        if (lp == null) return;

        String option = "";
        for (String opt : c.options()) {
            if (opt != null && !opt.isEmpty() && !"Examine".equals(opt) && !"null".equals(opt)) {
                option = opt;
                break;
            }
        }

        // Override the type that will be used when capture completes
        String type = (idx < selectedType.length) ? TRANSITION_TYPES[selectedType[idx].get()] : c.classifiedType();
        capture.startManualCapture(c.name(), option, lp.tileX(), lp.tileY(), lp.plane(), type);
    }

    // ── Helpers ──────────────────────────────────────────────────────────

    private void sendAllPending() {
        List<TransitionData> log = state.transitionLog;
        for (int i = 0; i < log.size(); i++) {
            if ("pending".equals(log.get(i).status()) || "failed".equals(log.get(i).status())) {
                capture.resend(i);
            }
        }
    }
}
