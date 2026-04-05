package com.botwithus.bot.pathfinder;

import com.botwithus.bot.api.nav.CollisionMap;
import com.botwithus.bot.api.nav.NavRegion;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.Reader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

import static com.botwithus.bot.api.nav.CollisionFlags.*;

/**
 * Loads transitions from JSON and indexes them for O(1) lookup by tile.
 * <p>
 * When transitions are applied to a {@link CollisionMap}, the directional
 * flags on adjacent same-plane transitions are blocked, forcing A* to use
 * the transition edge instead of walking through walls/fences.
 */
public final class TransitionStore {

    private static final Logger log = LoggerFactory.getLogger(TransitionStore.class);

    /**
     * Primary index: regionKey → (tileIndex → list of transitions sourced FROM that tile).
     * regionKey = {@link CollisionFlags#regionKey(int, int)}.
     */
    private final Map<Long, Map<Integer, List<Transition>>> byRegionTile = new HashMap<>();

    /** All loaded transitions. */
    private final List<Transition> all = new ArrayList<>();

    /** Regions whose NavRegion flags have been patched with transition walls. */
    private final Set<Long> patchedRegions = new HashSet<>();

    // ── Loading ──────────────────────────────────────────────────

    /**
     * Loads transitions from a JSON file.
     * Expects format: {@code {"transitions": [...]}} or bare array.
     *
     * @return number of transitions loaded
     */
    public int loadJson(Path jsonFile) throws IOException {
        try (Reader reader = Files.newBufferedReader(jsonFile)) {
            JsonElement root = JsonParser.parseReader(reader);
            JsonArray arr;
            if (root.isJsonObject()) {
                arr = root.getAsJsonObject().getAsJsonArray("transitions");
            } else {
                arr = root.getAsJsonArray();
            }

            // First pass: collect all transitions, preferring cache sources over xapi_live.
            // Key = "srcX,srcY,srcP,name,option" to deduplicate.
            Map<String, JsonObject> deduped = new java.util.LinkedHashMap<>();
            int skippedLive = 0, skippedSelfRef = 0, skippedBadDist = 0;

            for (JsonElement el : arr) {
                JsonObject obj = el.getAsJsonObject();
                String source = obj.has("source") ? obj.get("source").getAsString() : "";

                int srcX = obj.has("srcX") ? obj.get("srcX").getAsInt() : 0;
                int srcY = obj.has("srcY") ? obj.get("srcY").getAsInt() : 0;
                int srcP = obj.has("srcP") ? obj.get("srcP").getAsInt() : 0;
                int dstX = obj.has("dstX") ? obj.get("dstX").getAsInt() : 0;
                int dstY = obj.has("dstY") ? obj.get("dstY").getAsInt() : 0;
                int dstP = obj.has("dstP") ? obj.get("dstP").getAsInt() : 0;

                // Skip self-referential
                if (srcX == dstX && srcY == dstY && srcP == dstP) { skippedSelfRef++; continue; }

                // Skip destination-only (teleports with src=0,0,0)
                if (srcX == 0 && srcY == 0 && srcP == 0) continue;

                // Validate xapi_live entries: reject clearly wrong captures.
                if (source.equals("xapi_live")) {
                    String typeStr = obj.has("type") ? obj.get("type").getAsString() : "";
                    int dist = Math.abs(dstX - srcX) + Math.abs(dstY - srcY);

                    // Adjacent transitions (doors, stiles, gates) should have manhattan ≤ 2
                    boolean shouldBeAdjacent = typeStr.equals("DOOR") || typeStr.equals("GATE")
                            || typeStr.equals("AGILITY") || typeStr.equals("PASSAGE")
                            || typeStr.equals("WALL_PASSAGE");
                    if (shouldBeAdjacent && dist > 2) {
                        skippedBadDist++;
                        continue;
                    }

                    // Staircase/ladder: src and dst should be the same tile (different plane).
                    // xapi_live often records the player position as src instead of the object.
                    boolean isVertical = typeStr.equals("STAIRCASE") || typeStr.equals("LADDER");
                    if (isVertical && srcP != dstP && (srcX != dstX || srcY != dstY)) {
                        skippedBadDist++;
                        continue;
                    }
                }

                String name = obj.has("name") ? obj.get("name").getAsString() : "";
                String option = obj.has("option") ? obj.get("option").getAsString() : "";
                String key = srcX + "," + srcY + "," + srcP + "," + name + "," + option;

                // If a cache source already exists for this key, skip xapi_live duplicates
                if (deduped.containsKey(key)) {
                    JsonObject existing = deduped.get(key);
                    String existingSource = existing.has("source") ? existing.get("source").getAsString() : "";
                    if (source.equals("xapi_live") && !existingSource.equals("xapi_live")) {
                        skippedLive++;
                        continue;
                    }
                    // If existing is xapi_live and new is cache, replace
                    if (!source.equals("xapi_live") && existingSource.equals("xapi_live")) {
                        deduped.put(key, obj);
                        skippedLive++;
                        continue;
                    }
                }

                deduped.put(key, obj);
            }

            // Second pass: parse and index deduplicated transitions
            int count = 0;
            for (JsonObject obj : deduped.values()) {
                Transition t = parseTransition(obj);
                if (t == null) continue;

                addTransition(t);

                if (t.bidir()) {
                    // Skip bidir for cross-plane transitions (stairs, ladders).
                    // The option is direction-dependent (Climb-up ≠ Climb-down)
                    // and can't be copied to the reverse. These already have explicit
                    // entries for each direction in the data.
                    if (t.srcP() != t.dstP()) continue;

                    Transition rev = new Transition(
                            t.type(), t.dstX(), t.dstY(), t.dstP(),
                            t.srcX(), t.srcY(), t.srcP(),
                            t.name(), t.option(), t.costTicks(), false
                    );
                    addTransition(rev);
                }

                count++;
            }

            log.info("Loaded {} transitions ({} indexed with bidir). Skipped: {} bad distance, {} live dupes, {} self-ref",
                    count, all.size(), skippedBadDist, skippedLive, skippedSelfRef);
            return count;
        }
    }

    private void addTransition(Transition t) {
        all.add(t);
        long rKey = regionKey(toRegionId(t.srcX(), t.srcY()), t.srcP());
        int tIdx = tileIndex(toLocalX(t.srcX()), toLocalY(t.srcY()));
        byRegionTile
                .computeIfAbsent(rKey, k -> new HashMap<>())
                .computeIfAbsent(tIdx, k -> new ArrayList<>())
                .add(t);
    }

    private static Transition parseTransition(JsonObject obj) {
        try {
            String typeStr = obj.has("type") ? obj.get("type").getAsString() : "OTHER";
            Transition.Type type = Transition.Type.fromString(typeStr);
            int srcX = obj.get("srcX").getAsInt();
            int srcY = obj.get("srcY").getAsInt();
            int srcP = obj.get("srcP").getAsInt();
            int dstX = obj.get("dstX").getAsInt();
            int dstY = obj.get("dstY").getAsInt();
            int dstP = obj.get("dstP").getAsInt();
            String name = obj.has("name") ? obj.get("name").getAsString() : "";
            String option = obj.has("option") ? obj.get("option").getAsString() : "";
            int cost = obj.has("cost") ? obj.get("cost").getAsInt() : 2;
            boolean bidir = obj.has("bidir") && obj.get("bidir").getAsBoolean();
            return new Transition(type, srcX, srcY, srcP, dstX, dstY, dstP, name, option, cost, bidir);
        } catch (Exception e) {
            log.debug("Skipping malformed transition: {}", e.getMessage());
            return null;
        }
    }

    // ── Queries ──────────────────────────────────────────────────

    /**
     * Returns transitions originating from the given tile, or empty list.
     * This is the hot-path lookup used during A* expansion.
     */
    public List<Transition> getAt(int regionId, int plane, int tileIdx) {
        long rKey = regionKey(regionId, plane);
        Map<Integer, List<Transition>> tileMap = byRegionTile.get(rKey);
        if (tileMap == null) return List.of();
        List<Transition> list = tileMap.get(tileIdx);
        return list != null ? list : List.of();
    }

    /** Returns transitions originating from world coordinates. */
    public List<Transition> getAtWorld(int worldX, int worldY, int plane) {
        int regionId = toRegionId(worldX, worldY);
        int tIdx = tileIndex(toLocalX(worldX), toLocalY(worldY));
        return getAt(regionId, plane, tIdx);
    }

    /** Returns all loaded transitions. */
    public List<Transition> getAll() {
        return Collections.unmodifiableList(all);
    }

    public int size() {
        return all.size();
    }

    // ── Wall blocking ────────────────────────────────────────────

    /**
     * Applies transition wall blocking to a NavRegion's flags.
     * Adjacent same-plane transitions get their directional flags cleared,
     * forcing A* to use the transition edge instead of walking through.
     * <p>
     * Should be called once per region after loading, before pathfinding.
     */
    public void applyWalls(NavRegion region) {
        long rKey = regionKey(region.getRegionId(), region.getPlane());
        if (patchedRegions.contains(rKey)) return;

        Map<Integer, List<Transition>> tileMap = byRegionTile.get(rKey);
        if (tileMap == null) return;

        int count = 0;
        for (List<Transition> transitions : tileMap.values()) {
            for (Transition t : transitions) {
                if (t.isAdjacent()) {
                    region.applyTransitionWall(t.srcX(), t.srcY(), t.srcP(),
                            t.dstX(), t.dstY(), t.dstP());
                    count++;
                }
            }
        }

        patchedRegions.add(rKey);
        if (count > 0) {
            log.debug("Applied {} transition walls to region {} plane {}",
                    count, region.getRegionId(), region.getPlane());
        }
    }

    /**
     * Applies walls to all regions currently loaded in the collision map.
     * Called after loading transitions to patch existing cached regions.
     */
    public void applyWallsToMap(CollisionMap map) {
        for (Long rKey : byRegionTile.keySet()) {
            int regionId = (int) (rKey >> 2);
            int plane = (int) (rKey & 3);
            NavRegion region = map.getOrLoad(regionId, plane);
            if (region != null) {
                applyWalls(region);
            }
        }
    }
}
