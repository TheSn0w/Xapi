package com.botwithus.bot.cli.blueprint;

import com.botwithus.bot.api.blueprint.PinType;

import java.util.EnumMap;
import java.util.Map;

/**
 * Maps {@link PinType} values to RGBA colors for rendering in the blueprint editor.
 */
public final class PinColors {

    private static final Map<PinType, float[]> COLORS = new EnumMap<>(PinType.class);

    static {
        COLORS.put(PinType.EXEC,                new float[]{1.0f, 1.0f, 1.0f, 1.0f});
        COLORS.put(PinType.BOOLEAN,             new float[]{0.9f, 0.2f, 0.2f, 1.0f});
        COLORS.put(PinType.INT,                 new float[]{0.2f, 0.9f, 0.9f, 1.0f});
        COLORS.put(PinType.FLOAT,               new float[]{0.2f, 0.9f, 0.2f, 1.0f});
        COLORS.put(PinType.STRING,              new float[]{0.9f, 0.2f, 0.9f, 1.0f});
        COLORS.put(PinType.ENTITY,              new float[]{0.9f, 0.9f, 0.2f, 1.0f});
        COLORS.put(PinType.ENTITY_LIST,         new float[]{0.9f, 0.6f, 0.2f, 1.0f});
        COLORS.put(PinType.ENTITY_INFO,         new float[]{0.9f, 0.8f, 0.3f, 1.0f});
        COLORS.put(PinType.LOCAL_PLAYER,        new float[]{1.0f, 0.84f, 0.0f, 1.0f});
        COLORS.put(PinType.INVENTORY_ITEM,      new float[]{0.4f, 0.7f, 1.0f, 1.0f});
        COLORS.put(PinType.INVENTORY_ITEM_LIST, new float[]{0.3f, 0.5f, 1.0f, 1.0f});
        COLORS.put(PinType.PLAYER_STAT,         new float[]{0.5f, 1.0f, 0.5f, 1.0f});
        COLORS.put(PinType.COMPONENT,           new float[]{0.7f, 0.4f, 1.0f, 1.0f});
        COLORS.put(PinType.COMPONENT_LIST,      new float[]{0.5f, 0.3f, 0.8f, 1.0f});
        COLORS.put(PinType.GAME_ACTION,         new float[]{1.0f, 0.4f, 0.2f, 1.0f});
        COLORS.put(PinType.ANY,                 new float[]{0.7f, 0.7f, 0.7f, 1.0f});
    }

    private static final float[] DEFAULT_COLOR = {0.7f, 0.7f, 0.7f, 1.0f};

    private PinColors() {}

    /**
     * Returns the RGBA color for a pin type as a float array {r, g, b, a}.
     */
    public static float[] getColor(PinType type) {
        float[] c = COLORS.get(type);
        return c != null ? c : DEFAULT_COLOR;
    }

    /**
     * Returns the packed U32 color for a pin type (suitable for ImGui draw list calls).
     * Format: 0xAABBGGRR.
     */
    public static int getColorU32(PinType type) {
        float[] c = getColor(type);
        int r = (int) (c[0] * 255.0f) & 0xFF;
        int g = (int) (c[1] * 255.0f) & 0xFF;
        int b = (int) (c[2] * 255.0f) & 0xFF;
        int a = (int) (c[3] * 255.0f) & 0xFF;
        return (a << 24) | (b << 16) | (g << 8) | r;
    }
}
