package com.botwithus.bot.api.nav;

import java.util.Arrays;

import static com.botwithus.bot.api.nav.CollisionFlags.*;

/**
 * A 64×64 tile region with per-tile directional movement flags and diagonal wall flags.
 * <p>
 * Each tile stores one byte of cardinal flags:
 * <pre>
 * bit 0 = walkable       bit 4 = can move WEST
 * bit 1 = can move NORTH bit 5 = mapped
 * bit 2 = can move EAST  bit 6 = member-only
 * bit 3 = can move SOUTH bit 7 = reserved
 * </pre>
 * Index: {@code flags[localY * 64 + localX]}
 * <p>
 * And one byte of diagonal flags (bits 0–3 for NE, NW, SE, SW).
 */
public final class NavRegion {

    private final int regionId;
    private final int plane;
    private final byte[] flags;
    private final byte[] diagFlags;

    /**
     * Creates a region with pre-loaded flag data.
     *
     * @param regionId  the region identifier
     * @param plane     the plane (0–3)
     * @param flagData  4096 bytes of cardinal flags
     * @param diagFlagData 4096 bytes of diagonal flags, or null for all-open defaults
     */
    public NavRegion(int regionId, int plane, byte[] flagData, byte[] diagFlagData) {
        if (flagData.length != TILES_PER_REGION) {
            throw new IllegalArgumentException("Flag data must be " + TILES_PER_REGION + " bytes");
        }
        this.regionId = regionId;
        this.plane = plane;
        this.flags = flagData;
        if (diagFlagData != null) {
            if (diagFlagData.length != TILES_PER_REGION) {
                throw new IllegalArgumentException("DiagFlag data must be " + TILES_PER_REGION + " bytes");
            }
            this.diagFlags = diagFlagData;
        } else {
            this.diagFlags = new byte[TILES_PER_REGION];
            Arrays.fill(this.diagFlags, (byte) DIAG_ALL);
        }
    }

    // ── Flag queries ─────────────────────────────────────────────

    public boolean isWalkable(int lx, int ly) {
        return (flags[tileIndex(lx, ly)] & FLAG_WALKABLE) != 0;
    }

    public boolean canMove(int tileIdx, int dirFlag) {
        return (flags[tileIdx] & dirFlag) != 0;
    }

    public int getFlags(int tileIdx) {
        return flags[tileIdx] & 0xFF;
    }

    public int getDiagFlags(int tileIdx) {
        return diagFlags[tileIdx] & 0xFF;
    }

    public byte[] getFlagsArray() {
        return flags;
    }

    public byte[] getDiagFlagsArray() {
        return diagFlags;
    }

    // ── Wall reciprocity ─────────────────────────────────────────

    /**
     * Enforces wall reciprocity: if tile (x,y) blocks NORTH, then (x,y+1) must block SOUTH.
     * The RS3 cache stores walls one-directionally. Call after loading raw data.
     *
     * @return number of flags corrected
     */
    public int enforceWallReciprocity() {
        int fixed = 0;
        for (int ly = 0; ly < REGION_SIZE; ly++) {
            for (int lx = 0; lx < REGION_SIZE; lx++) {
                int idx = tileIndex(lx, ly);
                int f = flags[idx] & 0xFF;
                if ((f & FLAG_WALKABLE) == 0) continue;

                if ((f & FLAG_MOVE_NORTH) == 0 && ly + 1 < REGION_SIZE) {
                    int nIdx = tileIndex(lx, ly + 1);
                    if ((flags[nIdx] & FLAG_WALKABLE) != 0 && (flags[nIdx] & FLAG_MOVE_SOUTH) != 0) {
                        flags[nIdx] &= (byte) ~FLAG_MOVE_SOUTH;
                        fixed++;
                    }
                }
                if ((f & FLAG_MOVE_SOUTH) == 0 && ly - 1 >= 0) {
                    int nIdx = tileIndex(lx, ly - 1);
                    if ((flags[nIdx] & FLAG_WALKABLE) != 0 && (flags[nIdx] & FLAG_MOVE_NORTH) != 0) {
                        flags[nIdx] &= (byte) ~FLAG_MOVE_NORTH;
                        fixed++;
                    }
                }
                if ((f & FLAG_MOVE_EAST) == 0 && lx + 1 < REGION_SIZE) {
                    int nIdx = tileIndex(lx + 1, ly);
                    if ((flags[nIdx] & FLAG_WALKABLE) != 0 && (flags[nIdx] & FLAG_MOVE_WEST) != 0) {
                        flags[nIdx] &= (byte) ~FLAG_MOVE_WEST;
                        fixed++;
                    }
                }
                if ((f & FLAG_MOVE_WEST) == 0 && lx - 1 >= 0) {
                    int nIdx = tileIndex(lx - 1, ly);
                    if ((flags[nIdx] & FLAG_WALKABLE) != 0 && (flags[nIdx] & FLAG_MOVE_EAST) != 0) {
                        flags[nIdx] &= (byte) ~FLAG_MOVE_EAST;
                        fixed++;
                    }
                }
            }
        }
        return fixed;
    }

    // ── Transition wall blocking ─────────────────────────────────

    /**
     * Blocks directional movement at a transition tile (door, gate, fence).
     * Forces the A* to not walk through — it must use the real path around.
     * <p>
     * Only applies to same-plane, same-region, adjacent-tile transitions.
     *
     * @param srcX source world X
     * @param srcY source world Y
     * @param srcPlane source plane
     * @param dstX dest world X
     * @param dstY dest world Y
     * @param dstPlane dest plane
     */
    public void applyTransitionWall(int srcX, int srcY, int srcPlane,
                                     int dstX, int dstY, int dstPlane) {
        if (srcPlane != dstPlane) return;
        int srcRegion = toRegionId(srcX, srcY);
        int dstRegion = toRegionId(dstX, dstY);
        if (srcRegion != regionId || dstRegion != regionId) return;

        int srcLX = toLocalX(srcX), srcLY = toLocalY(srcY);
        int dstLX = toLocalX(dstX), dstLY = toLocalY(dstY);

        int dx = dstLX - srcLX, dy = dstLY - srcLY;
        if (Math.abs(dx) + Math.abs(dy) != 1) return;

        int dirFlag;
        if (dy == 1) dirFlag = FLAG_MOVE_NORTH;
        else if (dx == 1) dirFlag = FLAG_MOVE_EAST;
        else if (dy == -1) dirFlag = FLAG_MOVE_SOUTH;
        else dirFlag = FLAG_MOVE_WEST;

        // Block source → dest direction
        int srcIdx = tileIndex(srcLX, srcLY);
        flags[srcIdx] &= (byte) ~dirFlag;

        // Block dest → source (reverse direction)
        int[] delta = CollisionFlags.dirDelta(dirFlag);
        int nx = srcLX + delta[0], ny = srcLY + delta[1];
        if (nx >= 0 && nx < REGION_SIZE && ny >= 0 && ny < REGION_SIZE) {
            int nIdx = tileIndex(nx, ny);
            flags[nIdx] &= (byte) ~oppositeDir(dirFlag);
        }
    }

    // ── Accessors ────────────────────────────────────────────────

    public int getRegionId() { return regionId; }
    public int getPlane() { return plane; }
}
