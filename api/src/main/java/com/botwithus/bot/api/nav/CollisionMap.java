package com.botwithus.bot.api.nav;

import com.botwithus.bot.api.log.BotLogger;
import com.botwithus.bot.api.log.LoggerFactory;

import java.util.concurrent.ConcurrentHashMap;

import static com.botwithus.bot.api.nav.CollisionFlags.*;

/**
 * Thread-safe region cache providing world-coordinate collision queries.
 * Regions are lazily loaded from disk on first access via {@link RegionStore}.
 */
public final class CollisionMap {

    private static final BotLogger log = LoggerFactory.getLogger(CollisionMap.class);

    private final RegionStore store;
    private final ConcurrentHashMap<Long, NavRegion> cache = new ConcurrentHashMap<>();

    /** Sentinel for regions that don't exist on disk (avoids re-reading). */
    private static final NavRegion ABSENT = new NavRegion(0, 0, new byte[TILES_PER_REGION], null);

    public CollisionMap(RegionStore store) {
        this.store = store;
    }

    /**
     * Returns the region for the given ID and plane, loading from disk if needed.
     * Returns {@code null} if the region file does not exist.
     */
    public NavRegion getOrLoad(int regionId, int plane) {
        long key = regionKey(regionId, plane);
        NavRegion cached = cache.get(key);
        if (cached != null) return cached == ABSENT ? null : cached;

        NavRegion loaded = store.loadRegion(regionId, plane);
        cache.put(key, loaded != null ? loaded : ABSENT);
        return loaded;
    }

    // ── World-coordinate queries ─────────────────────────────────

    /**
     * Checks if a world tile is walkable.
     * Returns {@code false} if the region is not loaded or the tile is blocked.
     */
    public boolean isWalkable(int worldX, int worldY, int plane) {
        NavRegion r = getOrLoad(toRegionId(worldX, worldY), plane);
        if (r == null) return false;
        int lx = toLocalX(worldX);
        int ly = toLocalY(worldY);
        return r.isWalkable(lx, ly);
    }

    /**
     * Checks if movement in the given cardinal direction is allowed from a world tile.
     */
    public boolean canMove(int worldX, int worldY, int plane, int dirFlag) {
        NavRegion r = getOrLoad(toRegionId(worldX, worldY), plane);
        if (r == null) return false;
        int idx = tileIndex(toLocalX(worldX), toLocalY(worldY));
        return r.canMove(idx, dirFlag);
    }

    /**
     * Returns the diagonal flags for a world tile (bits for NE, NW, SE, SW).
     * Returns 0 (all blocked) if the region is not available.
     */
    public int getDiagFlags(int worldX, int worldY, int plane) {
        NavRegion r = getOrLoad(toRegionId(worldX, worldY), plane);
        if (r == null) return 0;
        int idx = tileIndex(toLocalX(worldX), toLocalY(worldY));
        return r.getDiagFlags(idx);
    }

    /** Returns the number of currently cached regions. */
    public int cacheSize() {
        return cache.size();
    }

    /** Clears all cached regions. */
    public void clearCache() {
        cache.clear();
    }
}
