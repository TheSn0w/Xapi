package com.botwithus.bot.scripts.eliteclue.scan;

import com.botwithus.bot.api.GameAPI;
import com.botwithus.bot.api.log.BotLogger;
import com.botwithus.bot.api.log.LoggerFactory;
import com.botwithus.bot.api.model.EnumType;
import com.botwithus.bot.api.model.ItemType;

import java.util.*;

/**
 * Loads and indexes all scan clue regions from the game cache at runtime.
 * <p>
 * The loading chain is: clue item → param 235 (enum ID) → enum entries → packed coords.
 * This avoids hardcoding any coordinates — everything is derived from cache data.
 */
public final class ScanClueData {

    private static final BotLogger log = LoggerFactory.getLogger(ScanClueData.class);

    /** Param 235 on each scan clue item points to the coordinate enum. */
    private static final String PARAM_SCAN_ENUM = "235";

    /**
     * Known scan clue item IDs and their human-readable region names.
     * The item→enum mapping is read from the cache; only the item IDs are listed here
     * because there is no cache enum that enumerates "all scan clue items".
     */
    private static final int[][] SCAN_ITEMS = {
            {19043, 3103},  // Ardougne
            {19044, 3104},  // Varrock
            {19045, 3105},  // Tirannwn / Isafdar
            {19046, 3106},  // Falador
            {19047, 3107},  // Fremennik Province
            {19048, 13503}, // Menaphos (Surface)
            {19049, 3109},  // Mos Le'Harmless
            {19050, 3110},  // Deep Wilderness
            {19051, 3111},  // Kharidian Desert
            {19052, 3112},  // Feldip Hills
            {19053, 3113},  // Karamja
            {19054, 3114},  // Prifddinas
            {19055, 3115},  // Keldagrim
            {19056, 3116},  // Zanaris
            {19057, 3117},  // Lumbridge Swamp Caves
            {19058, 3118},  // Fremennik Slayer Dungeon
            {19059, 3119},  // Dorgesh-Kaan
            {19060, 3120},  // Brimhaven Dungeon
            {19061, 3121},  // Taverley Dungeon
            {19062, 3122},  // The Arc
            {19063, 3123},  // Menaphos (Underground)
            {60392, 10609}, // Fort Forinthry
    };

    private static final Map<Integer, String> REGION_NAMES = Map.ofEntries(
            Map.entry(3103, "Ardougne"),
            Map.entry(3104, "Varrock"),
            Map.entry(3105, "Tirannwn / Isafdar"),
            Map.entry(3106, "Falador"),
            Map.entry(3107, "Fremennik Province"),
            Map.entry(13503, "Menaphos (Surface)"),
            Map.entry(3109, "Mos Le'Harmless"),
            Map.entry(3110, "Deep Wilderness"),
            Map.entry(3111, "Kharidian Desert"),
            Map.entry(3112, "Feldip Hills"),
            Map.entry(3113, "Karamja"),
            Map.entry(3114, "Prifddinas"),
            Map.entry(3115, "Keldagrim"),
            Map.entry(3116, "Zanaris"),
            Map.entry(3117, "Lumbridge Swamp Caves"),
            Map.entry(3118, "Fremennik Slayer Dungeon"),
            Map.entry(3119, "Dorgesh-Kaan"),
            Map.entry(3120, "Brimhaven Dungeon"),
            Map.entry(3121, "Taverley Dungeon"),
            Map.entry(3122, "The Arc"),
            Map.entry(3123, "Menaphos (Underground)"),
            Map.entry(10609, "Fort Forinthry")
    );

    /**
     * Keyword fragments matched against the scanner interface text (1752 comp 3).
     * The text typically reads: "This scan will work within [region description]."
     * Each keyword must be unique enough to distinguish its region from all others.
     * Matching is case-insensitive. Ordered most-specific first to avoid false positives
     * (e.g., "Fremennik Slayer" before "Fremennik").
     */
    private static final Object[][] TEXT_KEYWORDS = {
            // Underground / specific-first to avoid substring collisions
            {"lumbridge swamp",         3117},  // "the caves beneath Lumbridge Swamp"
            {"fremennik slayer",        3118},  // "Fremennik Slayer Dungeon"
            {"brimhaven dungeon",       3120},  // "Brimhaven Dungeon"
            {"taverley dungeon",        3121},  // "Taverley Dungeon"
            {"dorgesh-kaan",            3119},  // "Dorgesh-Kaan"
            {"dorgesh",                 3119},  // fallback spelling
            {"keldagrim",               3115},  // "Keldagrim"
            {"zanaris",                 3116},  // "Zanaris"
            {"menaphos underground",    3123},  // "Menaphos underground" / "beneath Menaphos"
            {"beneath menaphos",        3123},  // alternate phrasing
            {"shifting tombs",          3123},  // possible reference to Menaphos UG content

            // Surface — quest-locked
            {"tirannwn",                3105},  // "Tirannwn"
            {"isafdar",                 3105},  // alternate name
            {"prifddinas",              3114},  // "Prifddinas"
            {"mos le'harmless",         3109},  // "Mos Le'Harmless"
            {"mos le",                  3109},  // without apostrophe
            {"the arc",                 3122},  // "the Arc"
            {"uncharted isles",         3122},  // possible Arc reference
            {"fort forinthry",          10609}, // "Fort Forinthry"

            // Surface — free
            {"ardougne",                3103},  // "Ardougne"
            {"varrock",                 3104},  // "Varrock"
            {"falador",                 3106},  // "Falador"
            {"fremennik",               3107},  // "Fremennik Province" (after Slayer match)
            {"karamja",                 3113},  // "Karamja"
            {"kharidian",               3111},  // "Kharidian Desert"
            {"desert",                  3111},  // fallback for desert
            {"feldip",                  3112},  // "Feldip Hills"
            {"wilderness",              3110},  // "Deep Wilderness"

            // Menaphos surface must come after underground matches
            {"menaphos",                13503}, // "Menaphos" (surface, after UG already matched)
    };

    /** itemId → ScanRegion (loaded lazily) */
    private final Map<Integer, ScanRegion> regionsByItem = new HashMap<>();

    /** enumId → ScanRegion */
    private final Map<Integer, ScanRegion> regionsByEnum = new HashMap<>();

    private final GameAPI api;
    private boolean loaded = false;

    public ScanClueData(GameAPI api) {
        this.api = api;
    }

    /**
     * Load all scan regions from the cache. Call once during script startup.
     * Reads each item's param 235 to get the enum ID, then decodes the enum entries.
     *
     * @return true if at least one region was loaded successfully
     */
    public boolean loadAll() {
        log.info("[ScanData] Loading all scan clue regions from cache...");
        int successCount = 0;

        for (int[] entry : SCAN_ITEMS) {
            int itemId = entry[0];
            int expectedEnumId = entry[1];

            ScanRegion region = loadRegionForItem(itemId, expectedEnumId);
            if (region != null) {
                regionsByItem.put(itemId, region);
                regionsByEnum.put(region.enumId(), region);
                successCount++;
                log.info("[ScanData] Loaded {} (enum {}): {} coordinates",
                        region.name(), region.enumId(), region.coords().size());
            } else {
                log.warn("[ScanData] Failed to load region for item {} (expected enum {})",
                        itemId, expectedEnumId);
            }
        }

        loaded = successCount > 0;
        log.info("[ScanData] Load complete: {}/{} regions loaded",
                successCount, SCAN_ITEMS.length);
        return loaded;
    }

    /**
     * Load a single region by reading the item's param 235 and decoding the enum.
     */
    private ScanRegion loadRegionForItem(int itemId, int expectedEnumId) {
        // Try to read param 235 from the item
        int enumId = expectedEnumId;
        ItemType itemType = api.getItemType(itemId);
        if (itemType != null && itemType.params() != null) {
            Object paramVal = itemType.params().get(PARAM_SCAN_ENUM);
            if (paramVal instanceof Number num) {
                int readEnumId = num.intValue();
                if (readEnumId > 0) {
                    if (readEnumId != expectedEnumId) {
                        log.info("[ScanData] Item {} param 235 = {} (expected {}), using cache value",
                                itemId, readEnumId, expectedEnumId);
                    }
                    enumId = readEnumId;
                }
            }
        }

        return loadRegionFromEnum(enumId, itemId);
    }

    /**
     * Decode all coordinates from a cache enum.
     */
    private ScanRegion loadRegionFromEnum(int enumId, int itemId) {
        EnumType enumType = api.getEnumType(enumId);
        if (enumType == null) {
            log.warn("[ScanData] Enum {} not found in cache", enumId);
            return null;
        }

        Map<String, Object> entries = enumType.entries();
        if (entries == null || entries.isEmpty()) {
            log.warn("[ScanData] Enum {} has no entries", enumId);
            return null;
        }

        List<ScanCoordinate> coords = new ArrayList<>();
        for (Map.Entry<String, Object> e : entries.entrySet()) {
            Object val = e.getValue();
            if (val instanceof Number num) {
                int packed = num.intValue();
                ScanCoordinate coord = ScanCoordinate.fromPacked(packed);
                coords.add(coord);
            }
        }

        if (coords.isEmpty()) {
            log.warn("[ScanData] Enum {} decoded 0 coordinates", enumId);
            return null;
        }

        String name = REGION_NAMES.getOrDefault(enumId, "Unknown (enum " + enumId + ")");
        return new ScanRegion(name, enumId, itemId, List.copyOf(coords));
    }

    /**
     * Get the scan region for a specific clue item ID.
     *
     * @return the region, or null if not found/loaded
     */
    public ScanRegion getRegionForItem(int itemId) {
        return regionsByItem.get(itemId);
    }

    /**
     * Get the scan region for a specific enum ID.
     */
    public ScanRegion getRegionForEnum(int enumId) {
        return regionsByEnum.get(enumId);
    }

    /**
     * Identify the scan region by matching keywords against the scanner interface text.
     * This is the most reliable identification method because the game tells us exactly
     * which region we're scanning.
     *
     * @param scannerText the text from interface 1752 component 3
     * @return the matched region, or null if no keyword matches
     */
    public ScanRegion identifyRegionByText(String scannerText) {
        if (scannerText == null || scannerText.isBlank()) return null;

        String lower = scannerText.toLowerCase();

        for (Object[] entry : TEXT_KEYWORDS) {
            String keyword = (String) entry[0];
            int enumId = (int) entry[1];

            if (lower.contains(keyword)) {
                ScanRegion region = regionsByEnum.get(enumId);
                if (region != null) {
                    log.info("[ScanData] Text match: '{}' matched keyword '{}' -> {} (enum {})",
                            scannerText, keyword, region.name(), enumId);
                    return region;
                } else {
                    log.warn("[ScanData] Text match keyword '{}' -> enum {} but region not loaded",
                            keyword, enumId);
                }
            }
        }

        log.warn("[ScanData] No keyword match for scanner text: '{}'", scannerText);
        return null;
    }

    /**
     * Attempt to identify which scan region the player is currently in,
     * based on proximity to any region's bounding box.
     * This is a fallback when text-based matching fails.
     */
    public ScanRegion identifyRegionByPosition(int playerX, int playerY, int playerPlane) {
        ScanRegion best = null;
        int bestDist = Integer.MAX_VALUE;

        for (ScanRegion region : regionsByItem.values()) {
            // Check if any coordinates in this region share the player's plane
            boolean planeMatch = false;
            for (ScanCoordinate c : region.coords()) {
                if (c.plane() == playerPlane) {
                    planeMatch = true;
                    break;
                }
            }
            if (!planeMatch) continue;

            // Distance to bounding box center
            int cx = (region.minX() + region.maxX()) / 2;
            int cy = (region.minY() + region.maxY()) / 2;
            int dist = Math.max(Math.abs(playerX - cx), Math.abs(playerY - cy));

            if (dist < bestDist) {
                bestDist = dist;
                best = region;
            }
        }

        return best;
    }

    /**
     * Check all known scan item IDs to see if the given item is a scan clue.
     */
    public boolean isScanClueItem(int itemId) {
        return regionsByItem.containsKey(itemId);
    }

    public boolean isLoaded() {
        return loaded;
    }

    public Collection<ScanRegion> getAllRegions() {
        return Collections.unmodifiableCollection(regionsByItem.values());
    }
}
