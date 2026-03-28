package com.xapi.debugger;

import static com.xapi.debugger.XapiData.*;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Manages settings persistence, auto-save, export/import, and shutdown hooks.
 *
 * <p>Autosave strategy: the current in-memory state is written to disk every
 * loop iteration (~600ms), unconditionally overwriting the previous file.
 * Whatever is in memory IS what's on disk — no dirty flags, no debounce,
 * no race conditions between clear and save.
 */
final class SessionManager {

    private static final Logger log = LoggerFactory.getLogger(SessionManager.class);

    private final XapiState state;

    private static final Path AUTOSAVE_FILE = XapiState.SESSION_DIR.resolve("autosave.json");
    private Thread shutdownHook;
    private volatile long lastSettingsSave;

    // Track collection sizes to skip autosave when nothing changed
    private int lastSavedActionSize;
    private int lastSavedVarSize;
    private int lastSavedChatSize;
    private int lastSavedSnapshotSize;

    SessionManager(XapiState state) {
        this.state = state;
    }

    void saveSettings() {
        try {
            XapiSettings settings = new XapiSettings(
                    state.recording, false, false,
                    true, true, true,
                    Arrays.copyOf(state.categoryFilters, state.categoryFilters.length),
                    new boolean[7],
                    state.showVarbits, state.showVarps, false, false, false,
                    state.varFilterText.get(), "",
                    new HashSet<>(state.pinnedVars), new HashMap<>(state.varAnnotations),
                    state.useNamesForGeneration, state.scriptClassName.get(),
                    state.replaySpeedArr[0],
                    state.entityDistanceFilter,
                    Arrays.copyOf(state.columnVisible, state.columnVisible.length),
                    state.autoScroll,
                    null // actionBar — populated below
            );
            // Action bar settings
            XapiData.ActionBarSettings abSettings = new XapiData.ActionBarSettings(
                    new HashMap<>(state.abCollapsed),
                    state.abExpandedInterface,
                    state.abShowChangeLog, state.abShowHistory, state.abShowComparison, state.abShowDebug,
                    state.actionBarFilter.get(),
                    state.abProbeVarcs.get(), state.abProbeVarps.get(), state.abProbeVarbits.get()
            );
            settings = new XapiData.XapiSettings(
                    settings.recording(), settings.blocking(), settings.selectiveBlocking(),
                    settings.trackVars(), settings.trackChat(), settings.trackItemVarbits(),
                    settings.categoryFilters(), settings.selectiveBlockCategories(),
                    settings.showVarbits(), settings.showVarps(), settings.showVarcs(), settings.showVarcStrs(), settings.showItemVarbits(),
                    settings.varFilterText(), settings.varcWatchIds(),
                    settings.pinnedVars(), settings.varAnnotations(),
                    settings.useNamesForGeneration(), settings.scriptClassName(),
                    settings.replaySpeed(), settings.entityDistanceFilter(),
                    settings.columnVisibility(), settings.autoScroll(),
                    abSettings
            );
            Files.writeString(XapiState.SETTINGS_FILE, XapiState.GSON.toJson(settings));
            log.debug("Settings saved to {}", XapiState.SETTINGS_FILE);
        } catch (Exception e) {
            log.warn("Failed to save settings: {}", e.getMessage());
        }
    }

    void loadSettings() {
        try {
            if (!Files.exists(XapiState.SETTINGS_FILE)) return;
            String json = Files.readString(XapiState.SETTINGS_FILE);
            XapiSettings s = XapiState.GSON.fromJson(json, XapiSettings.class);
            if (s == null) return;

            state.recording = s.recording();
            // state.blocking is NOT loaded -- always starts as false (safe default)

            if (s.categoryFilters() != null) {
                System.arraycopy(s.categoryFilters(), 0, state.categoryFilters, 0,
                        Math.min(s.categoryFilters().length, state.categoryFilters.length));
            }

            state.showVarbits = s.showVarbits();
            state.showVarps = s.showVarps();

            if (s.varFilterText() != null) state.varFilterText.set(s.varFilterText());
            if (s.pinnedVars() != null) { state.pinnedVars.clear(); state.pinnedVars.addAll(s.pinnedVars()); }
            if (s.varAnnotations() != null) { state.varAnnotations.clear(); state.varAnnotations.putAll(s.varAnnotations()); }

            state.useNamesForGeneration = s.useNamesForGeneration();
            if (s.scriptClassName() != null) state.scriptClassName.set(s.scriptClassName());
            state.replaySpeedArr[0] = s.replaySpeed();
            state.replaySpeed = s.replaySpeed();

            if (s.entityDistanceFilter() > 0) {
                state.entityDistanceFilter = s.entityDistanceFilter();
                state.entityDistanceArr[0] = state.entityDistanceFilter;
            }

            if (s.columnVisibility() != null) {
                System.arraycopy(s.columnVisibility(), 0, state.columnVisible, 0,
                        Math.min(s.columnVisibility().length, state.columnVisible.length));
            }
            state.autoScroll = s.autoScroll();

            // Action bar settings
            if (s.actionBar() != null) {
                var ab = s.actionBar();
                if (ab.collapsedInterfaces() != null) {
                    state.abCollapsed.clear();
                    state.abCollapsed.putAll(ab.collapsedInterfaces());
                }
                state.abExpandedInterface = ab.expandedInterface();
                state.abShowChangeLog = ab.showChangeLog();
                state.abShowHistory = ab.showHistory();
                state.abShowComparison = ab.showComparison();
                state.abShowDebug = ab.showDebug();
                if (ab.filterText() != null) state.actionBarFilter.set(ab.filterText());
                if (ab.probeVarcs() != null) state.abProbeVarcs.set(ab.probeVarcs());
                if (ab.probeVarps() != null) state.abProbeVarps.set(ab.probeVarps());
                if (ab.probeVarbits() != null) state.abProbeVarbits.set(ab.probeVarbits());
            }

            log.info("Settings loaded from {}", XapiState.SETTINGS_FILE);
        } catch (Exception e) {
            log.debug("Failed to load settings: {}", e.getMessage());
        }
    }

    /** Trims a list to maxSize by bulk-replacing with the tail. Returns number removed. */
    static <T> int trimLog(List<T> list, int maxSize) {
        int size = list.size();
        if (size <= maxSize) return 0;
        List<T> keep = new ArrayList<>(list.subList(size - maxSize, size));
        list.clear();
        list.addAll(keep);
        return size - maxSize;
    }

    /**
     * Writes the current in-memory state to autosave.json only if the data
     * has changed since the last save. Called every loop iteration (~600ms).
     */
    void doAutoSaveIfChanged() {
        int aSize = state.actionLog.size();
        int vSize = state.varLog.size();
        int cSize = state.chatLog.size();
        int sSize = state.snapshotLog.size();
        if (aSize == lastSavedActionSize && vSize == lastSavedVarSize
                && cSize == lastSavedChatSize && sSize == lastSavedSnapshotSize) {
            return; // Nothing changed — skip expensive serialization + disk write
        }
        doAutoSave();
    }

    /**
     * Writes the current in-memory state to autosave.json unconditionally.
     * Used by clear, stop, and shutdown hook where we must guarantee a write.
     */
    void doAutoSave() {
        try {
            SessionData session = new SessionData(
                    new ArrayList<>(state.actionLog), new ArrayList<>(state.varLog), new ArrayList<>(state.chatLog),
                    new ArrayList<>(state.snapshotLog),
                    System.currentTimeMillis(), "autosave",
                    state.lastSeenActionTimestamp
            );
            Files.writeString(AUTOSAVE_FILE, XapiState.GSON.toJson(session));
            lastSavedActionSize = state.actionLog.size();
            lastSavedVarSize = state.varLog.size();
            lastSavedChatSize = state.chatLog.size();
            lastSavedSnapshotSize = state.snapshotLog.size();
        } catch (Exception e) {
            log.warn("Auto-save failed: {}", e.getMessage());
        }
    }

    void loadAutoSave() {
        try {
            if (!Files.exists(AUTOSAVE_FILE)) return;
            String json = Files.readString(AUTOSAVE_FILE);
            SessionData session = XapiState.GSON.fromJson(json, SessionData.class);
            if (session == null) return;
            if (session.actions() != null && !session.actions().isEmpty()) {
                state.actionLog.addAll(session.actions());
            }
            if (session.vars() != null && !session.vars().isEmpty()) {
                state.varLog.addAll(session.vars());
                for (VarChange vc : session.vars()) {
                    state.varsByTick.computeIfAbsent(vc.gameTick(), k -> new CopyOnWriteArrayList<>()).add(vc);
                }
            }
            if (session.chat() != null && !session.chat().isEmpty()) {
                state.chatLog.addAll(session.chat());
            }
            if (session.snapshots() != null && !session.snapshots().isEmpty()) {
                state.snapshotLog.addAll(session.snapshots());
            }
            if (session.lastSeenActionTimestamp() > 0) {
                state.lastSeenActionTimestamp = session.lastSeenActionTimestamp();
            }
            log.info("Auto-save restored: {} actions, {} vars, {} chat",
                    state.actionLog.size(), state.varLog.size(), state.chatLog.size());
        } catch (Exception e) {
            log.debug("Failed to load auto-save: {}", e.getMessage());
        }
    }

    void doExport() {
        try {
            SessionData session = new SessionData(
                    new ArrayList<>(state.actionLog), new ArrayList<>(state.varLog), new ArrayList<>(state.chatLog),
                    new ArrayList<>(state.snapshotLog),
                    System.currentTimeMillis(), "Xapi session export",
                    state.lastSeenActionTimestamp
            );
            String filename = "session_" + new java.text.SimpleDateFormat("yyyyMMdd_HHmmss").format(new java.util.Date()) + ".json";
            Path file = XapiState.SESSION_DIR.resolve(filename);
            Files.writeString(file, XapiState.GSON.toJson(session));
            state.lastExportStatus = "Exported: " + filename;
            log.info("Session exported to {}", file);
        } catch (Exception e) {
            state.lastExportStatus = "Export failed: " + e.getMessage();
            log.error("Export failed", e);
        }
    }

    void doImport(String filename) {
        try {
            Path file = XapiState.SESSION_DIR.resolve(filename);
            String json = Files.readString(file);
            SessionData session = XapiState.GSON.fromJson(json, SessionData.class);
            if (session.actions() != null) { state.actionLog.clear(); state.actionLog.addAll(session.actions()); }
            if (session.vars() != null) {
                state.varLog.clear(); state.varLog.addAll(session.vars());
                state.varsByTick.clear();
                for (VarChange vc : state.varLog) {
                    state.varsByTick.computeIfAbsent(vc.gameTick(), k -> new CopyOnWriteArrayList<>()).add(vc);
                }
            }
            if (session.chat() != null) { state.chatLog.clear(); state.chatLog.addAll(session.chat()); }
            state.snapshotLog.clear();
            if (session.snapshots() != null) { state.snapshotLog.addAll(session.snapshots()); }
            state.lastExportStatus = "Imported: " + filename + " (" + state.actionLog.size() + " actions)";
            log.info("Session imported from {}", file);
        } catch (Exception e) {
            state.lastExportStatus = "Import failed: " + e.getMessage();
            log.error("Import failed", e);
        }
    }

    void installShutdownHook() {
        shutdownHook = new Thread(this::doAutoSave, "xapi-shutdown-save");
        Runtime.getRuntime().addShutdownHook(shutdownHook);
    }

    void removeShutdownHook() {
        try { Runtime.getRuntime().removeShutdownHook(shutdownHook); } catch (Exception ignored) {}
    }

    boolean shouldPersistSettings(long now) {
        if (state.saveSettingsRequested || (state.settingsDirty && now - lastSettingsSave > 30000)) {
            state.saveSettingsRequested = false;
            state.settingsDirty = false;
            lastSettingsSave = now;
            saveSettings();
            return true;
        }
        return false;
    }
}
