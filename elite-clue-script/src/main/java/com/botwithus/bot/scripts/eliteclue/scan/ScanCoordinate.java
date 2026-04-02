package com.botwithus.bot.scripts.eliteclue.scan;

/**
 * A single scan clue candidate coordinate decoded from the game cache.
 *
 * @param x      world tile X
 * @param y      world tile Y
 * @param plane  world plane (0-3)
 * @param packed the original packed integer from the enum
 */
public record ScanCoordinate(int x, int y, int plane, int packed) {

    /**
     * Decode a packed coordinate from the cache enum.
     * Format: {@code z = (val >> 28) & 0x3, x = (val >> 14) & 0x3FFF, y = val & 0x3FFF}
     */
    public static ScanCoordinate fromPacked(int packed) {
        int z = (packed >> 28) & 0x3;
        int x = (packed >> 14) & 0x3FFF;
        int y = packed & 0x3FFF;
        return new ScanCoordinate(x, y, z, packed);
    }

    /**
     * Chebyshev distance from this coordinate to a point.
     */
    public int chebyshevDistance(int px, int py) {
        return Math.max(Math.abs(x - px), Math.abs(y - py));
    }

    @Override
    public String toString() {
        return "(" + x + ", " + y + ", " + plane + ")";
    }
}
