/**
 * Port of V2 Xapi's CollisionFlags.java constants.
 * Cardinal tile flags (1 byte per tile).
 */
export const FLAG_WALKABLE    = 1 << 0;
export const FLAG_MOVE_NORTH  = 1 << 1;
export const FLAG_MOVE_EAST   = 1 << 2;
export const FLAG_MOVE_SOUTH  = 1 << 3;
export const FLAG_MOVE_WEST   = 1 << 4;
export const FLAG_MAPPED      = 1 << 5;
export const FLAG_MEMBER_ONLY = 1 << 6;

export const FLAGS_ALL_DIRS = FLAG_MOVE_NORTH | FLAG_MOVE_EAST | FLAG_MOVE_SOUTH | FLAG_MOVE_WEST;
export const FLAGS_OPEN = FLAG_WALKABLE | FLAGS_ALL_DIRS | FLAG_MAPPED;

/** Diagonal flags (1 byte per tile) */
export const DIAG_NE = 1 << 0;
export const DIAG_NW = 1 << 1;
export const DIAG_SE = 1 << 2;
export const DIAG_SW = 1 << 3;
export const DIAG_ALL = DIAG_NE | DIAG_NW | DIAG_SE | DIAG_SW;

/** Decode a cardinal flag byte into a human-readable object */
export function decodeCardinalFlags(flags: number) {
  return {
    walkable: !!(flags & FLAG_WALKABLE),
    north: !!(flags & FLAG_MOVE_NORTH),
    east: !!(flags & FLAG_MOVE_EAST),
    south: !!(flags & FLAG_MOVE_SOUTH),
    west: !!(flags & FLAG_MOVE_WEST),
    mapped: !!(flags & FLAG_MAPPED),
    memberOnly: !!(flags & FLAG_MEMBER_ONLY),
  };
}

/** Decode a diagonal flag byte */
export function decodeDiagonalFlags(flags: number) {
  return {
    ne: !!(flags & DIAG_NE),
    nw: !!(flags & DIAG_NW),
    se: !!(flags & DIAG_SE),
    sw: !!(flags & DIAG_SW),
  };
}
