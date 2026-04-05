package com.botwithus.bot.pathfinder;

/**
 * A navigation transition between two tiles — doors, gates, stiles, stairs, etc.
 * <p>
 * Transitions are graph edges in the A* search alongside normal walk edges.
 * When a transition exists between two tiles, the directional walk flags are
 * blocked, forcing A* to route through the transition edge instead of walking
 * through.
 */
public record Transition(
        Type type,
        int srcX, int srcY, int srcP,
        int dstX, int dstY, int dstP,
        String name,
        String option,
        int costTicks,
        boolean bidir
) {

    public enum Type {
        DOOR,
        GATE,
        STAIRCASE,
        LADDER,
        PORTAL,
        AGILITY,
        PASSAGE,
        WALL_PASSAGE,
        ENTRANCE,
        BRIDGE,
        TRANSPORT,
        NPC_TRANSPORT,
        TELEPORT,
        FAIRY_RING,
        SPIRIT_TREE,
        LODESTONE,
        ITEM_TELEPORT,
        OTHER;

        /**
         * Penalty added to A* cost (in cost units, where CARDINAL=10).
         * <p>
         * Tuned so the A* prefers walking ~15 extra tiles over using an
         * unnecessary interaction. Each interaction takes ~3-5 seconds of
         * real game time, so the penalty should reflect that.
         */
        public int penalty() {
            return switch (this) {
                case DOOR -> 200;
                case GATE -> 150;
                case AGILITY -> 150;
                case STAIRCASE, LADDER -> 100;
                case PASSAGE, WALL_PASSAGE, ENTRANCE, BRIDGE -> 80;
                case TRANSPORT, NPC_TRANSPORT -> 120;
                case TELEPORT, FAIRY_RING, SPIRIT_TREE, LODESTONE, ITEM_TELEPORT -> 100;
                case PORTAL, OTHER -> 80;
            };
        }

        public static Type fromString(String s) {
            if (s == null) return OTHER;
            return switch (s.toUpperCase()) {
                case "DOOR" -> DOOR;
                case "GATE" -> GATE;
                case "STAIRCASE" -> STAIRCASE;
                case "LADDER" -> LADDER;
                case "PORTAL" -> PORTAL;
                case "AGILITY", "AGILITY_SHORTCUT" -> AGILITY;
                case "PASSAGE" -> PASSAGE;
                case "WALL_PASSAGE" -> WALL_PASSAGE;
                case "ENTRANCE" -> ENTRANCE;
                case "BRIDGE" -> BRIDGE;
                case "TRANSPORT" -> TRANSPORT;
                case "NPC_TRANSPORT" -> NPC_TRANSPORT;
                case "TELEPORT" -> TELEPORT;
                case "FAIRY_RING" -> FAIRY_RING;
                case "SPIRIT_TREE" -> SPIRIT_TREE;
                case "LODESTONE" -> LODESTONE;
                case "ITEM_TELEPORT" -> ITEM_TELEPORT;
                default -> OTHER;
            };
        }
    }

    /** Whether this is a same-plane adjacent transition (walk-blocking). */
    public boolean isAdjacent() {
        return srcP == dstP && Math.abs(dstX - srcX) + Math.abs(dstY - srcY) == 1;
    }

    /** Whether source position is meaningful (vs teleports where src=0,0,0). */
    public boolean hasSource() {
        return srcX != 0 || srcY != 0 || srcP != 0;
    }
}
