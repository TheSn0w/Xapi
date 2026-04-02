package com.botwithus.bot.scripts.eliteclue.scan;

import java.util.List;

/**
 * A scan clue region containing all possible dig coordinates.
 *
 * @param name      human-readable region name
 * @param enumId    the cache enum ID containing packed coordinates
 * @param itemId    the clue scroll item ID that maps to this region
 * @param coords    all possible dig coordinates for this region
 */
public record ScanRegion(String name, int enumId, int itemId, List<ScanCoordinate> coords) {

    /**
     * Bounding box minimum X across all coordinates.
     */
    public int minX() {
        int min = Integer.MAX_VALUE;
        for (ScanCoordinate c : coords) min = Math.min(min, c.x());
        return min;
    }

    /**
     * Bounding box maximum X across all coordinates.
     */
    public int maxX() {
        int max = Integer.MIN_VALUE;
        for (ScanCoordinate c : coords) max = Math.max(max, c.x());
        return max;
    }

    /**
     * Bounding box minimum Y across all coordinates.
     */
    public int minY() {
        int min = Integer.MAX_VALUE;
        for (ScanCoordinate c : coords) min = Math.min(min, c.y());
        return min;
    }

    /**
     * Bounding box maximum Y across all coordinates.
     */
    public int maxY() {
        int max = Integer.MIN_VALUE;
        for (ScanCoordinate c : coords) max = Math.max(max, c.y());
        return max;
    }
}
