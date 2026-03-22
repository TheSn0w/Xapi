package com.xapi.debugger;

import com.botwithus.bot.api.cache.*;
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
 * Gson-backed {@link GameCache} implementation that loads game cache data
 * (locations, items, NPCs, interfaces) from offline JSON dump files.
 */
public final class GameCacheData implements GameCache {

    private static final Logger log = LoggerFactory.getLogger(GameCacheData.class);

    // Default cache directories
    private static final Path DEFAULT_CACHE_DIR = Path.of("D:\\13-03-2026");
    private static final Path DEFAULT_INTERFACES_DIR = Path.of("D:\\Claude\\rs-tools\\output\\interfaces");
    private static final Path DEFAULT_VARBITS_DIR = Path.of("D:\\Claude\\rs-tools\\output\\configs\\varbits");

    // ── Lookup maps ────────────────────────────────────────────────────

    private final Map<Integer, CachedLocation> locations = new ConcurrentHashMap<>();
    private final Map<Integer, CachedItem> items = new ConcurrentHashMap<>();
    private final Map<Integer, CachedNpc> npcs = new ConcurrentHashMap<>();
    // Nested map: ifaceId -> (compId -> CachedWidget)
    private final Map<Integer, Map<Integer, CachedWidget>> interfaces = new ConcurrentHashMap<>();
    private volatile int widgetCount;
    // Item varbit defs grouped by itemVarId (decoded from varp domain 5)
    private final Map<Integer, List<ItemVarbitDef>> itemVarbits = new ConcurrentHashMap<>();
    // Reverse index: varbitId -> ItemVarbitDef (for VarManager lookups)
    private final Map<Integer, ItemVarbitDef> itemVarbitById = new ConcurrentHashMap<>();

    private volatile boolean loaded;
    private volatile String loadError;

    // ── Public API ─────────────────────────────────────────────────────

    @Override public boolean isLoaded() { return loaded; }
    public String getLoadError() { return loadError; }
    public int locationCount() { return locations.size(); }
    public int itemCount() { return items.size(); }
    public int npcCount() { return npcs.size(); }

    public int interfaceCount() { return interfaces.size(); }
    public int widgetCount() { return widgetCount; }

    @Override public CachedLocation getLocation(int id) { return locations.get(id); }
    @Override public CachedItem getItem(int id) { return items.get(id); }
    @Override public CachedNpc getNpc(int id) { return npcs.get(id); }

    @Override
    public List<ItemVarbitDef> getItemVarbitDefs(int itemVarId) {
        return itemVarbits.getOrDefault(itemVarId, List.of());
    }

    @Override
    public ItemVarbitDef getItemVarbitDef(int varbitId) {
        return itemVarbitById.get(varbitId);
    }

    /** Returns total count of loaded item varbit definitions. */
    public int itemVarbitCount() {
        return itemVarbits.values().stream().mapToInt(List::size).sum();
    }

    @Override
    public List<String> getWidgetOptions(int ifaceId, int compId) {
        var comps = interfaces.get(ifaceId);
        if (comps == null) return null;
        var widget = comps.get(compId);
        return widget != null ? widget.menuOptions() : null;
    }

    @Override
    public String getWidgetText(int ifaceId, int compId) {
        var comps = interfaces.get(ifaceId);
        if (comps == null) return null;
        var widget = comps.get(compId);
        return widget != null ? widget.text() : null;
    }

    @Override
    public String getInterfaceName(int ifaceId) {
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

    @Override
    public CachedLocation resolveLocation(int typeId, int varValue) {
        CachedLocation base = locations.get(typeId);
        if (base == null) return null;

        if (base.name() != null && !base.name().isEmpty()) return base;

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

    @Override
    public CachedNpc resolveNpc(int typeId, int varValue) {
        CachedNpc base = npcs.get(typeId);
        if (base == null) return null;

        if (base.name() != null && !base.name().isEmpty()) return base;

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
            loadItemVarbits(DEFAULT_VARBITS_DIR);

            loaded = true;
            long elapsed = System.currentTimeMillis() - start;
            log.info("Game cache loaded in {}ms: {} locations, {} items, {} NPCs, {} interfaces ({} widgets), {} item varbits",
                    elapsed, locations.size(), items.size(), npcs.size(),
                    interfaces.size(), widgetCount, itemVarbitCount());
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

    /**
     * Loads item varbit definitions (domain 5) from the varbits config directory.
     * Only loads varbits whose varp encodes domain 5 (item vars).
     * Groups them by itemVarId derived from: (var_index - 2) / 256.
     */
    private void loadItemVarbits(Path varbitsDir) {
        if (!Files.isDirectory(varbitsDir)) {
            log.warn("Varbits directory not found: {}", varbitsDir);
            return;
        }
        long start = System.currentTimeMillis();
        Gson gson = new Gson();
        int count = 0;
        try (var stream = Files.list(varbitsDir)) {
            for (Path file : (Iterable<Path>) stream::iterator) {
                if (!file.toString().endsWith(".json")) continue;
                try (BufferedReader reader = Files.newBufferedReader(file)) {
                    JsonObject obj = gson.fromJson(reader, JsonObject.class);
                    if (obj == null) continue;
                    long varp = obj.has("varp") ? obj.get("varp").getAsLong() : 0;
                    int domain = (int) ((varp >> 24) & 0xFF);
                    if (domain != 5) continue; // Only item var domain

                    int varbitId = obj.has("id") ? obj.get("id").getAsInt() : -1;
                    int lowBit = obj.has("low_bit") ? obj.get("low_bit").getAsInt() : 0;
                    int highBit = obj.has("high_bit") ? obj.get("high_bit").getAsInt() : 0;
                    int varIndex = (int) (varp & 0xFFFFFF);
                    int itemVarId = (varIndex - 2) / 256; // Map varp var_index to getItemVars varId

                    ItemVarbitDef def = new ItemVarbitDef(varbitId, itemVarId, lowBit, highBit);
                    itemVarbits.computeIfAbsent(itemVarId, k -> new ArrayList<>()).add(def);
                    itemVarbitById.put(varbitId, def);
                    count++;
                } catch (Exception e) {
                    log.debug("Failed to load varbit file {}: {}", file.getFileName(), e.getMessage());
                }
            }
        } catch (IOException e) {
            log.warn("Failed to list varbits directory: {}", e.getMessage());
        }
        log.info("Loaded {} item varbit definitions in {}ms", count, System.currentTimeMillis() - start);
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
