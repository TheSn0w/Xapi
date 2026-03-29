package com.botwithus.bot.api.inventory.archaeology;

import com.botwithus.bot.api.GameAPI;


/**
 * Provides access to Archaeology Guild Shop permanent upgrade levels.
 * <p>Upgrades are purchased from Ezreal's shop with Chronotes and gated by
 * qualification tier. All upgrade varbits are packed into varp 9367.</p>
 *
 * <pre>{@code
 * GuildUpgrades upgrades = new GuildUpgrades(ctx.getGameAPI());
 * int precisionBonus = upgrades.getPrecisionBonus(); // 0, 2, 4, 6, or 8
 * int materialCap = upgrades.getMaterialStorageCapacity(); // 25, 30, 35, or 40
 * }</pre>
 */
public final class GuildUpgrades {

    // Varp 9367 cluster
    /** Varbit for soil box upgrade tier (0–3). See SoilBox API. */
    public static final int VARBIT_SOIL_BOX = 47021;
    /** Varbit for material storage upgrade tier (0–3). */
    public static final int VARBIT_MATERIAL_STORAGE = 47022;
    /** Varbit for mattock precision upgrade tier (0–4). */
    public static final int VARBIT_MATTOCK_PRECISION = 47023;

    // Varp 9399 cluster
    /** Varbit for auto-screener blueprint purchased (boolean). */
    public static final int VARBIT_AUTO_SCREENER = 47350;

    private final GameAPI api;

    public GuildUpgrades(GameAPI api) {
        this.api = api;
    }

    // ========================== Mattock Precision ==========================

    /**
     * Get the mattock precision upgrade tier (0–4).
     * <p>Each tier adds +2 precision. Total: 263,000 chronotes for +8.</p>
     */
    public int getPrecisionTier() {
        return api.getVarbit(VARBIT_MATTOCK_PRECISION);
    }

    /**
     * Get the total mattock precision bonus from guild upgrades.
     *
     * @return 0, 2, 4, 6, or 8
     */
    public int getPrecisionBonus() {
        return getPrecisionTier() * 2;
    }

    // ========================== Material Storage ==========================

    /**
     * Get the material storage upgrade tier (0–3).
     * <p>Base capacity is 25 per material. Each tier adds +5.
     * Total: 92,000 chronotes.</p>
     */
    public int getMaterialStorageTier() {
        return api.getVarbit(VARBIT_MATERIAL_STORAGE);
    }

    /**
     * Get the material storage capacity per material type.
     *
     * @return 25, 30, 35, or 40
     */
    public int getMaterialStorageCapacity() {
        return 25 + (getMaterialStorageTier() * 5);
    }

    // ========================== Auto-screener ==========================

    /**
     * Check if the auto-screener v1.080 blueprint has been purchased.
     */
    public boolean hasAutoScreenerBlueprint() {
        return api.getVarbit(VARBIT_AUTO_SCREENER) == 1;
    }
}
