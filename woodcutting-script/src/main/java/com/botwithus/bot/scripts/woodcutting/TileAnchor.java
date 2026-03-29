package com.botwithus.bot.scripts.woodcutting;

public final class TileAnchor {

    private final int x;
    private final int y;
    private final int plane;
    private final String label;

    public TileAnchor(int x, int y, int plane, String label) {
        this.x = x;
        this.y = y;
        this.plane = plane;
        this.label = label;
    }

    public int x() {
        return x;
    }

    public int y() {
        return y;
    }

    public int plane() {
        return plane;
    }

    public String label() {
        return label;
    }

    public String shortText() {
        return String.format("(%d, %d, %d)", x, y, plane);
    }

    public String displayText() {
        return label == null || label.isBlank()
                ? shortText()
                : label + " " + shortText();
    }
}
