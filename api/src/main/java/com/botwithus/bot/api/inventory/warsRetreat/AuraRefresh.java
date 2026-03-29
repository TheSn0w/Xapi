package com.botwithus.bot.api.inventory.warsRetreat;

import com.botwithus.bot.api.GameAPI;
import com.botwithus.bot.api.log.BotLogger;
import com.botwithus.bot.api.log.LoggerFactory;

/**
 * Provides access to the aura reset system available at War's Retreat.
 * <p>Auras can be reset using tiered refresh items purchased from War's Wares or Vis wax.
 * Each refresh tier can only reset auras of equal or lower tier. The reset interface
 * is managed by client script 13725.</p>
 *
 * <p>Aura time remaining is tracked in varbit 52651 (varp 10776, 16-bit, units of 0.6s).
 * Refresh charges for each tier are stored in varbits 40130-40133 across varps 7868-7869.
 * Generic refreshes use varbit 40396 on varp 7655.</p>
 *
 * <pre>{@code
 * AuraRefresh aura = new AuraRefresh(ctx.getGameAPI());
 * int timeLeft = aura.getTimeRemaining();
 * double minutes = aura.getTimeRemainingMinutes();
 * int t1Charges = aura.getRefreshCharges(AuraRefresh.RefreshTier.TIER_1);
 * }</pre>
 */
public final class AuraRefresh {

    private static final BotLogger log = LoggerFactory.getLogger(AuraRefresh.class);

    /** Aura time remaining (16-bit, varp 10776 bits 0-15, units of 0.6 seconds). */
    public static final int VARBIT_TIME_REMAINING = 52651;

    /** Tier 1 aura refresh charges (15-bit, varp 7868 bits 0-14). */
    public static final int VARBIT_TIER1_CHARGES = 40130;

    /** Tier 2 aura refresh charges (17-bit, varp 7868 bits 15-31). */
    public static final int VARBIT_TIER2_CHARGES = 40131;

    /** Tier 3 aura refresh charges (15-bit, varp 7869 bits 0-14). */
    public static final int VARBIT_TIER3_CHARGES = 40132;

    /** Tier 4 aura refresh charges (17-bit, varp 7869 bits 15-31). */
    public static final int VARBIT_TIER4_CHARGES = 40133;

    /** Generic aura refresh charges (8-bit, varp 7655 bits 11-18). */
    public static final int VARBIT_GENERIC_CHARGES = 40396;

    // ========================== UI Components ==========================

    /** Generic refresh slot in aura interface. */
    public static final int COMPONENT_GENERIC_REFRESH = 126418966;

    /** Tier 1 refresh slot. */
    public static final int COMPONENT_TIER1_REFRESH = 126418967;

    /** Tier 2 refresh slot. */
    public static final int COMPONENT_TIER2_REFRESH = 126418968;

    /** Tier 3 refresh slot. */
    public static final int COMPONENT_TIER3_REFRESH = 126418969;

    /** Tier 4 refresh slot. */
    public static final int COMPONENT_TIER4_REFRESH = 126418970;

    /** Vis wax refresh slot. */
    public static final int COMPONENT_VIS_WAX_REFRESH = 126418971;

    /** Close aura interface component. */
    public static final int COMPONENT_CLOSE = 126419111;

    private final GameAPI api;

    public AuraRefresh(GameAPI api) {
        this.api = api;
    }

    // ========================== Aura Time ==========================

    /**
     * Get the raw aura time remaining value (0.6s units).
     */
    public int getTimeRemaining() {
        return api.getVarbit(VARBIT_TIME_REMAINING);
    }

    /**
     * Get the aura time remaining in minutes.
     */
    public double getTimeRemainingMinutes() {
        return getTimeRemaining() * 0.6 / 60.0;
    }

    /**
     * Check if an aura is currently active (has time remaining).
     */
    public boolean isAuraActive() {
        return getTimeRemaining() > 0;
    }

    // ========================== Refresh Charges ==========================

    /**
     * Get the number of refresh charges for a specific tier.
     */
    public int getRefreshCharges(RefreshTier tier) {
        return api.getVarbit(tier.varbit);
    }

    /**
     * Check if the player has at least one refresh charge for a specific tier.
     */
    public boolean hasRefreshCharge(RefreshTier tier) {
        return getRefreshCharges(tier) > 0;
    }

    // ========================== Enums ==========================

    /**
     * Aura refresh tiers available from War's Wares.
     * <p>Each tier can reset auras of equal or lower tier. Higher tiers cost more Marks of War.</p>
     */
    public enum RefreshTier {
        TIER_1  (40130, 31847, "Aura refresh (tier 1)", 1000),
        TIER_2  (40131, 31848, "Aura refresh (tier 2)", 2000),
        TIER_3  (40132, 31849, "Aura refresh (tier 3)", 3000),
        TIER_4  (40133, 31850, "Aura refresh (tier 4)", 4000),
        GENERIC (40396, 42661, "Aura refresh (generic)", 0),
        LIFE    (-1,    42662, "Life refresh",           2500);

        /** Varbit tracking charges, or -1 if not tracked via varbit. */
        public final int varbit;
        /** Item ID for this refresh. */
        public final int itemId;
        /** Display name. */
        public final String name;
        /** Cost in Marks of War. */
        public final int marksCost;

        RefreshTier(int varbit, int itemId, String name, int marksCost) {
            this.varbit = varbit;
            this.itemId = itemId;
            this.name = name;
            this.marksCost = marksCost;
        }
    }
}
