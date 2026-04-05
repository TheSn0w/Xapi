package com.botwithus.bot.pathfinder;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * Persistent settings for the pathfinder script.
 * Saved to {@code pathfinder_settings.json} in the working directory.
 */
final class PathfinderSettings {

    private static final Logger log = LoggerFactory.getLogger(PathfinderSettings.class);
    private static final Path SETTINGS_FILE = Path.of("pathfinder_settings.json");
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    // ── Transition type filters ──────────────────────────────────
    Map<String, Boolean> typeFilters = new java.util.LinkedHashMap<>();

    // ── Saved checkpoints ────────────────────────────────────────
    List<Checkpoint> checkpoints = new ArrayList<>();

    // ── Last destination (restored on restart) ───────────────────
    int lastDestX, lastDestY, lastDestPlane;
    int jitterSeed;

    static class Checkpoint {
        String name;
        int x, y, plane;

        Checkpoint() {}
        Checkpoint(String name, int x, int y, int plane) {
            this.name = name; this.x = x; this.y = y; this.plane = plane;
        }
    }

    // ── Persistence ──────────────────────────────────────────────

    void save() {
        try {
            Files.writeString(SETTINGS_FILE, GSON.toJson(this));
        } catch (IOException e) {
            log.warn("Failed to save settings: {}", e.getMessage());
        }
    }

    static PathfinderSettings load() {
        if (!Files.exists(SETTINGS_FILE)) {
            PathfinderSettings defaults = new PathfinderSettings();
            defaults.initDefaults();
            return defaults;
        }
        try {
            String json = Files.readString(SETTINGS_FILE);
            PathfinderSettings s = GSON.fromJson(json, PathfinderSettings.class);
            if (s == null) s = new PathfinderSettings();
            s.ensureAllTypes();
            return s;
        } catch (Exception e) {
            log.warn("Failed to load settings, using defaults: {}", e.getMessage());
            PathfinderSettings defaults = new PathfinderSettings();
            defaults.initDefaults();
            return defaults;
        }
    }

    private void initDefaults() {
        typeFilters = new java.util.LinkedHashMap<>();
        for (Transition.Type t : Transition.Type.values()) {
            typeFilters.put(t.name(), true);
        }
    }

    /** Ensure any new types added to the enum get a default entry. */
    private void ensureAllTypes() {
        if (typeFilters == null) typeFilters = new java.util.LinkedHashMap<>();
        for (Transition.Type t : Transition.Type.values()) {
            typeFilters.putIfAbsent(t.name(), true);
        }
    }

    boolean isTypeEnabled(Transition.Type type) {
        Boolean b = typeFilters.get(type.name());
        return b == null || b;
    }

    void setTypeEnabled(Transition.Type type, boolean enabled) {
        typeFilters.put(type.name(), enabled);
    }
}
