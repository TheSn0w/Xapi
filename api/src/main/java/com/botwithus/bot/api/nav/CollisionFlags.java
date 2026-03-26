package com.botwithus.bot.api.nav;

/**
 * Constants for tile collision flags, diagonal wall bits, region geometry,
 * binary format identifiers, and coordinate conversion helpers.
 * <p>
 * Each tile in a {@link NavRegion} stores one byte of cardinal flags and one
 * byte of diagonal flags. Cardinal flags encode walkability and directional
 * movement permissions. Diagonal flags encode whether diagonal movement
 * (NE, NW, SE, SW) is allowed through this tile.
 */
public final class CollisionFlags {

    private CollisionFlags() {}

    // ── Cardinal tile flags ──────────────────────────────────────

    /** Tile has a walkable floor. */
    public static final int FLAG_WALKABLE    = 1 << 0;
    /** Movement allowed northward (+Y) from this tile. */
    public static final int FLAG_MOVE_NORTH  = 1 << 1;
    /** Movement allowed eastward (+X) from this tile. */
    public static final int FLAG_MOVE_EAST   = 1 << 2;
    /** Movement allowed southward (-Y) from this tile. */
    public static final int FLAG_MOVE_SOUTH  = 1 << 3;
    /** Movement allowed westward (-X) from this tile. */
    public static final int FLAG_MOVE_WEST   = 1 << 4;
    /** Tile has been mapped (collision data is known). */
    public static final int FLAG_MAPPED      = 1 << 5;
    /** Tile is in a members-only area. */
    public static final int FLAG_MEMBER_ONLY = 1 << 6;

    /** All four cardinal direction flags combined. */
    public static final int FLAGS_ALL_DIRS = FLAG_MOVE_NORTH | FLAG_MOVE_EAST | FLAG_MOVE_SOUTH | FLAG_MOVE_WEST;
    /** Fully open tile: walkable, all directions, and mapped. */
    public static final int FLAGS_OPEN = FLAG_WALKABLE | FLAGS_ALL_DIRS | FLAG_MAPPED;

    // ── Diagonal wall flags ──────────────────────────────────────

    /** Diagonal movement allowed to the northeast. */
    public static final int DIAG_NE = 1 << 0;
    /** Diagonal movement allowed to the northwest. */
    public static final int DIAG_NW = 1 << 1;
    /** Diagonal movement allowed to the southeast. */
    public static final int DIAG_SE = 1 << 2;
    /** Diagonal movement allowed to the southwest. */
    public static final int DIAG_SW = 1 << 3;
    /** All diagonal movements open (default). */
    public static final int DIAG_ALL = DIAG_NE | DIAG_NW | DIAG_SE | DIAG_SW;

    // ── Region geometry ──────────────────────────────────────────

    /** Width and height of a region in tiles. */
    public static final int REGION_SIZE = 64;
    /** Total tiles per region (64 × 64). */
    public static final int TILES_PER_REGION = REGION_SIZE * REGION_SIZE;

    // ── Binary format ────────────────────────────────────────────

    /** Magic bytes "BNAV" at the start of every region .dat file. */
    public static final int MAGIC = 0x424E4156;
    /** Current binary format version. */
    public static final short VERSION = 3;

    // ── Coordinate helpers ───────────────────────────────────────

    /**
     * Converts world coordinates to a region ID.
     * Region ID packs regionX in the high byte, regionY in the low byte.
     */
    public static int toRegionId(int worldX, int worldY) {
        return ((worldX >> 6) << 8) | (worldY >> 6);
    }

    /** Extracts the local X coordinate (0–63) from a world X. */
    public static int toLocalX(int worldX) {
        return worldX & 63;
    }

    /** Extracts the local Y coordinate (0–63) from a world Y. */
    public static int toLocalY(int worldY) {
        return worldY & 63;
    }

    /** Computes the tile index within a region from local coordinates. */
    public static int tileIndex(int localX, int localY) {
        return localY * REGION_SIZE + localX;
    }

    /** Creates a unique region cache key from region ID and plane. */
    public static long regionKey(int regionId, int plane) {
        return ((long) regionId << 2) | plane;
    }

    /** Returns the opposite cardinal direction flag. */
    public static int oppositeDir(int dirFlag) {
        return switch (dirFlag) {
            case FLAG_MOVE_NORTH -> FLAG_MOVE_SOUTH;
            case FLAG_MOVE_SOUTH -> FLAG_MOVE_NORTH;
            case FLAG_MOVE_EAST  -> FLAG_MOVE_WEST;
            case FLAG_MOVE_WEST  -> FLAG_MOVE_EAST;
            default -> 0;
        };
    }

    /** Returns the tile offset {dx, dy} for a cardinal direction flag. */
    public static int[] dirDelta(int dirFlag) {
        return switch (dirFlag) {
            case FLAG_MOVE_NORTH -> new int[]{0, 1};
            case FLAG_MOVE_SOUTH -> new int[]{0, -1};
            case FLAG_MOVE_EAST  -> new int[]{1, 0};
            case FLAG_MOVE_WEST  -> new int[]{-1, 0};
            default -> new int[]{0, 0};
        };
    }

    /** Returns the diagonal bit for a given dx/dy direction. */
    public static int diagBit(int dx, int dy) {
        if (dx > 0) return dy > 0 ? DIAG_NE : DIAG_SE;
        return dy > 0 ? DIAG_NW : DIAG_SW;
    }

    /** Returns the reverse diagonal bit (NE↔SW, NW↔SE). */
    public static int diagReverse(int diagFlag) {
        return switch (diagFlag) {
            case DIAG_NE -> DIAG_SW;
            case DIAG_SW -> DIAG_NE;
            case DIAG_NW -> DIAG_SE;
            case DIAG_SE -> DIAG_NW;
            default -> 0;
        };
    }
}
