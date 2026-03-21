package com.xapi.debugger;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.stream.JsonReader;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Loads game cache data (locations, items, NPCs, interfaces) from offline JSON dump files.
 * Provides instant name/action/transform/component lookups without any RPC calls.
 *
 * <p>Expected sources:
 * <ul>
 *   <li>{@code D:\13-03-2026\locations.json} — 135K location definitions</li>
 *   <li>{@code D:\13-03-2026\items.json} — 60K item definitions</li>
 *   <li>{@code D:\13-03-2026\npcs.json} — 32K NPC definitions</li>
 *   <li>{@code D:\Claude\rs-tools\output\interfaces\} — 103K interface widget definitions</li>
 * </ul>
 */
public final class GameCacheData {

    private static final Logger log = LoggerFactory.getLogger(GameCacheData.class);

    // Default cache directories
    private static final Path DEFAULT_CACHE_DIR = Path.of("D:\\13-03-2026");
    private static final Path DEFAULT_INTERFACES_DIR = Path.of("D:\\Claude\\rs-tools\\output\\interfaces");

    // ── Location (Object) cache ────────────────────────────────────────

    public record CachedLocation(int id, String name, List<String> actions,
                                  int varbitId, int varpId, List<Integer> transforms) {}

    // ── Item cache ─────────────────────────────────────────────────────

    public record CachedItem(int id, String name, List<String> widgetActions, List<String> groundActions) {}

    // ── NPC cache ──────────────────────────────────────────────────────

    public record CachedNpc(int id, String name, List<String> actions,
                             int varbitId, int varpId, List<Integer> transforms) {}

    // ── Interface widget cache ──────────────────────────────────────────

    /** A cached interface widget — only stores menu_options and text (skip all visual data). */
    public record CachedWidget(List<String> menuOptions, String text) {}

    // ── Lookup maps ────────────────────────────────────────────────────

    private final Map<Integer, CachedLocation> locations = new ConcurrentHashMap<>();
    private final Map<Integer, CachedItem> items = new ConcurrentHashMap<>();
    private final Map<Integer, CachedNpc> npcs = new ConcurrentHashMap<>();
    // Nested map: ifaceId -> (compId -> CachedWidget)
    private final Map<Integer, Map<Integer, CachedWidget>> interfaces = new ConcurrentHashMap<>();
    private volatile int widgetCount;

    private volatile boolean loaded;
    private volatile String loadError;

    // ── Public API ─────────────────────────────────────────────────────

    public boolean isLoaded() { return loaded; }
    public String getLoadError() { return loadError; }
    public int locationCount() { return locations.size(); }
    public int itemCount() { return items.size(); }
    public int npcCount() { return npcs.size(); }

    public int interfaceCount() { return interfaces.size(); }
    public int widgetCount() { return widgetCount; }

    public CachedLocation getLocation(int id) { return locations.get(id); }
    public CachedItem getItem(int id) { return items.get(id); }
    public CachedNpc getNpc(int id) { return npcs.get(id); }

    /**
     * Gets the cached menu options for an interface widget.
     * @return the menu options list, or null if not cached
     */
    public List<String> getWidgetOptions(int ifaceId, int compId) {
        var comps = interfaces.get(ifaceId);
        if (comps == null) return null;
        var widget = comps.get(compId);
        return widget != null ? widget.menuOptions() : null;
    }

    /**
     * Gets the cached text label for an interface widget.
     * @return the text string, or null if not cached
     */
    public String getWidgetText(int ifaceId, int compId) {
        var comps = interfaces.get(ifaceId);
        if (comps == null) return null;
        var widget = comps.get(compId);
        return widget != null ? widget.text() : null;
    }

    /**
     * Returns a known interface name for common interfaces.
     * Falls back to "Interface {id}" for unknown interfaces.
     */
    public String getInterfaceName(int ifaceId) {
        // Common well-known interfaces
        return switch (ifaceId) {
            case 517 -> "Bank";
            case 762 -> "Bank";
            case 1473 -> "Production";
            case 1371 -> "Production";
            case 1370 -> "Production";
            case 37 -> "Smithing";
            case 300 -> "Grand Exchange";
            case 1189, 1188, 1184 -> "Dialogue";
            case 320 -> "Skills";
            case 387, 670 -> "Equipment";
            case 1430 -> "Backpack";
            case 590 -> "Toolbelt";
            case 1251 -> "Progress";
            default -> "Interface " + ifaceId;
        };
    }

    /**
     * Resolves a location's name following its transform/morph chain.
     * Uses the provided var value to pick the correct transform ID.
     *
     * @param typeId   the base location type ID
     * @param varValue the current varbit/varp value (use -1 to skip transform)
     * @return the resolved location, or null if not found
     */
    public CachedLocation resolveLocation(int typeId, int varValue) {
        CachedLocation base = locations.get(typeId);
        if (base == null) return null;

        // If base has a name, return it directly (no morph needed)
        if (base.name() != null && !base.name().isEmpty()) return base;

        // Follow transform chain
        if (base.transforms() != null && !base.transforms().isEmpty() && varValue >= 0) {
            int transformedId;
            if (varValue < base.transforms().size()) {
                transformedId = base.transforms().get(varValue);
            } else {
                transformedId = base.transforms().getLast();
            }
            if (transformedId != -1 && transformedId != typeId) {
                CachedLocation resolved = locations.get(transformedId);
                if (resolved != null && resolved.name() != null && !resolved.name().isEmpty()) {
                    return resolved;
                }
            }
        }

        return base;
    }

    /**
     * Resolves an NPC's definition following its transform/morph chain.
     *
     * @param typeId   the base NPC type ID
     * @param varValue the current varbit/varp value (use -1 to skip transform)
     * @return the resolved NPC, or null if not found
     */
    public CachedNpc resolveNpc(int typeId, int varValue) {
        CachedNpc base = npcs.get(typeId);
        if (base == null) return null;

        // If base has a name, return it directly
        if (base.name() != null && !base.name().isEmpty()) return base;

        // Follow transform chain
        if (base.transforms() != null && !base.transforms().isEmpty() && varValue >= 0) {
            int transformedId;
            if (varValue < base.transforms().size()) {
                transformedId = base.transforms().get(varValue);
            } else {
                transformedId = base.transforms().getLast();
            }
            if (transformedId != -1 && transformedId != typeId) {
                CachedNpc resolved = npcs.get(transformedId);
                if (resolved != null && resolved.name() != null && !resolved.name().isEmpty()) {
                    return resolved;
                }
            }
        }

        return base;
    }

    // ── Loading ────────────────────────────────────────────────────────

    /**
     * Loads all cache files from the default directory.
     * Safe to call from any thread. Logs progress and errors.
     */
    public void load() {
        load(DEFAULT_CACHE_DIR);
    }

    /**
     * Loads all cache files from the specified directory.
     */
    public void load(Path cacheDir) {
        long start = System.currentTimeMillis();
        log.info("Loading game cache from: {}", cacheDir);

        if (!Files.isDirectory(cacheDir)) {
            loadError = "Cache directory not found: " + cacheDir;
            log.warn(loadError);
            return;
        }

        try {
            loadLocations(cacheDir.resolve("locations.json"));
            loadItems(cacheDir.resolve("items.json"));
            loadNpcs(cacheDir.resolve("npcs.json"));
            loadInterfaces(DEFAULT_INTERFACES_DIR);

            loaded = true;
            long elapsed = System.currentTimeMillis() - start;
            log.info("Game cache loaded in {}ms: {} locations, {} items, {} NPCs, {} interfaces ({} widgets)",
                    elapsed, locations.size(), items.size(), npcs.size(),
                    interfaces.size(), widgetCount);
        } catch (Exception e) {
            loadError = "Failed to load cache: " + e.getMessage();
            log.error(loadError, e);
        }
    }

    private void loadLocations(Path file) throws IOException {
        if (!Files.exists(file)) {
            log.warn("locations.json not found at: {}", file);
            return;
        }
        log.info("Loading locations from: {}", file);
        long start = System.currentTimeMillis();

        try (JsonReader reader = new JsonReader(new BufferedReader(Files.newBufferedReader(file)))) {
            reader.beginArray();
            while (reader.hasNext()) {
                JsonObject obj = com.google.gson.JsonParser.parseReader(reader).getAsJsonObject();
                int id = obj.get("id").getAsInt();

                String name = obj.has("name") ? obj.get("name").getAsString() : null;
                List<String> actions = parseStringArray(obj.getAsJsonArray("actions"));

                // Parse morphs (transform chain)
                int varbitId = -1, varpId = -1;
                List<Integer> transforms = null;
                JsonObject morphs = obj.has("morphs_1") ? obj.getAsJsonObject("morphs_1") : null;
                if (morphs == null) morphs = obj.has("morphs_2") ? obj.getAsJsonObject("morphs_2") : null;
                if (morphs != null) {
                    if (morphs.has("varbit")) varbitId = morphs.get("varbit").getAsInt();
                    if (morphs.has("varp")) varpId = morphs.get("varp").getAsInt();
                    JsonArray ids = morphs.getAsJsonArray("ids");
                    if (ids != null) {
                        transforms = new ArrayList<>(ids.size());
                        for (JsonElement e : ids) transforms.add(e.getAsInt());
                    }
                }

                locations.put(id, new CachedLocation(id, name, actions, varbitId, varpId, transforms));
            }
            reader.endArray();
        }
        log.info("Loaded {} locations in {}ms", locations.size(), System.currentTimeMillis() - start);
    }

    private void loadItems(Path file) throws IOException {
        if (!Files.exists(file)) {
            log.warn("items.json not found at: {}", file);
            return;
        }
        log.info("Loading items from: {}", file);
        long start = System.currentTimeMillis();

        try (JsonReader reader = new JsonReader(new BufferedReader(Files.newBufferedReader(file)))) {
            reader.beginArray();
            while (reader.hasNext()) {
                JsonObject obj = com.google.gson.JsonParser.parseReader(reader).getAsJsonObject();
                int id = obj.get("id").getAsInt();

                String name = obj.has("name") ? obj.get("name").getAsString() : null;
                List<String> widgetActions = parseStringArray(obj.getAsJsonArray("widget_actions"));
                List<String> groundActions = parseStringArray(obj.getAsJsonArray("ground_actions"));

                items.put(id, new CachedItem(id, name, widgetActions, groundActions));
            }
            reader.endArray();
        }
        log.info("Loaded {} items in {}ms", items.size(), System.currentTimeMillis() - start);
    }

    private void loadNpcs(Path file) throws IOException {
        if (!Files.exists(file)) {
            log.warn("npcs.json not found at: {}", file);
            return;
        }
        log.info("Loading NPCs from: {}", file);
        long start = System.currentTimeMillis();

        try (JsonReader reader = new JsonReader(new BufferedReader(Files.newBufferedReader(file)))) {
            reader.beginArray();
            while (reader.hasNext()) {
                JsonObject obj = com.google.gson.JsonParser.parseReader(reader).getAsJsonObject();
                int id = obj.get("id").getAsInt();

                String name = obj.has("name") ? obj.get("name").getAsString() : null;
                List<String> actions = parseStringArray(obj.getAsJsonArray("actions"));

                // Parse morphs
                int varbitId = -1, varpId = -1;
                List<Integer> transforms = null;
                JsonObject morphs = obj.has("morphs_1") ? obj.getAsJsonObject("morphs_1") : null;
                if (morphs == null) morphs = obj.has("morphs_2") ? obj.getAsJsonObject("morphs_2") : null;
                if (morphs != null) {
                    if (morphs.has("varbit")) varbitId = morphs.get("varbit").getAsInt();
                    if (morphs.has("varp")) varpId = morphs.get("varp").getAsInt();
                    JsonArray ids = morphs.getAsJsonArray("ids");
                    if (ids != null) {
                        transforms = new ArrayList<>(ids.size());
                        for (JsonElement e : ids) transforms.add(e.getAsInt());
                    }
                }

                npcs.put(id, new CachedNpc(id, name, actions, varbitId, varpId, transforms));
            }
            reader.endArray();
        }
        log.info("Loaded {} NPCs in {}ms", npcs.size(), System.currentTimeMillis() - start);
    }

    private void loadInterfaces(Path interfacesDir) throws IOException {
        if (!Files.isDirectory(interfacesDir)) {
            log.warn("Interfaces directory not found at: {}", interfacesDir);
            return;
        }
        log.info("Loading interfaces from: {}", interfacesDir);
        long start = System.currentTimeMillis();
        int totalWidgets = 0;

        try (var files = Files.list(interfacesDir)) {
            for (Path file : (Iterable<Path>) files.filter(p -> p.toString().endsWith(".json"))::iterator) {
                try {
                    String fileName = file.getFileName().toString();
                    int ifaceId = Integer.parseInt(fileName.substring(0, fileName.length() - 5)); // strip .json

                    JsonObject root = com.google.gson.JsonParser.parseReader(
                            new JsonReader(new BufferedReader(Files.newBufferedReader(file)))).getAsJsonObject();
                    JsonArray widgets = root.getAsJsonArray("widgets");
                    if (widgets == null) continue;

                    Map<Integer, CachedWidget> compMap = null;
                    for (JsonElement we : widgets) {
                        if (!we.isJsonObject()) continue;
                        JsonObject w = we.getAsJsonObject();
                        if (!w.has("id")) continue;

                        int compId = w.get("id").getAsInt();

                        // Extract menu_options
                        List<String> menuOptions = null;
                        JsonArray optArr = w.getAsJsonArray("menu_options");
                        if (optArr != null && !optArr.isEmpty()) {
                            menuOptions = parseStringArray(optArr);
                        }

                        // Extract text
                        String text = null;
                        if (w.has("text") && !w.get("text").isJsonNull()) {
                            String t = w.get("text").getAsString();
                            if (!t.isEmpty()) text = t;
                        }

                        // Only store if widget has useful data
                        if (menuOptions != null || text != null) {
                            if (compMap == null) compMap = new HashMap<>();
                            compMap.put(compId, new CachedWidget(menuOptions, text));
                            totalWidgets++;
                        }
                    }
                    if (compMap != null) {
                        interfaces.put(ifaceId, compMap);
                    }
                } catch (NumberFormatException ignored) {
                    // Skip non-numeric filenames
                } catch (Exception e) {
                    log.debug("Failed to load interface file {}: {}", file.getFileName(), e.getMessage());
                }
            }
        }
        widgetCount = totalWidgets;
        log.info("Loaded {} interfaces ({} widgets) in {}ms",
                interfaces.size(), totalWidgets, System.currentTimeMillis() - start);
    }

    private static List<String> parseStringArray(JsonArray arr) {
        if (arr == null) return List.of();
        List<String> result = new ArrayList<>(arr.size());
        for (JsonElement e : arr) {
            result.add(e.isJsonNull() ? null : e.getAsString());
        }
        // Can't use List.copyOf() — it rejects null elements, but game actions have nulls
        return Collections.unmodifiableList(result);
    }
}
