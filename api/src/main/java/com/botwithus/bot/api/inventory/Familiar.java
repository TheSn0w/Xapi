package com.botwithus.bot.api.inventory;

import com.botwithus.bot.api.GameAPI;
import com.botwithus.bot.api.log.BotLogger;
import com.botwithus.bot.api.log.LoggerFactory;

/**
 * Provides access to the summoning familiar system — state, HP, spell points, scrolls, and timers.
 * <p>The familiar system is driven by a core varbit cluster (6050-6064) spread across varps
 * 1781, 1785, 1786, 5935, and 8041. HP is stored in varp 5194 (two 16-bit halves),
 * spell points in varp 1787, and familiar identity in varp 1831.</p>
 *
 * <p>Scroll tracking uses varbits 25408-25420 across varps 4516, 4763, 4778, 4823,
 * 10439, 65699, and 65700.</p>
 *
 * <pre>{@code
 * Familiar familiar = new Familiar(ctx.getGameAPI());
 * if (familiar.isActive()) {
 *     int hp = familiar.getCurrentHp();
 *     int maxHp = familiar.getMaxHp();
 *     int timeLeft = familiar.getTimeRemaining();
 *     int spellPoints = familiar.getSpellPoints();
 * }
 * }</pre>
 */
public final class Familiar {

    private static final BotLogger log = LoggerFactory.getLogger(Familiar.class);

    // ========================== Core Cluster (6050-6064) ==========================

    /** Familiar exists flag (1-bit, varp 5935 bit 13). 1 = familiar is summoned. */
    public static final int VARBIT_EXISTS = 6050;

    /** Time remaining in ticks until familiar despawns (13-bit, varp 8041 bits 0-12, max 8191). */
    public static final int VARBIT_TIME_REMAINING = 6051;

    /** Combat mode / aggression level (4-bit, varp 1785 bits 28-31). */
    public static final int VARBIT_COMBAT_MODE = 6052;

    /** Special move points / scroll count tracking (5-bit, varp 1786 bits 1-5). */
    public static final int VARBIT_SPECIAL_MOVE_POINTS = 6053;

    /** Special move flag (1-bit, varp 1786 bit 6). */
    public static final int VARBIT_SPECIAL_MOVE_FLAG = 6054;

    /** Special move cooldown timer (9-bit, varp 1786 bits 7-15, max 511). */
    public static final int VARBIT_SPECIAL_COOLDOWN = 6055;

    /** Familiar action flag (1-bit, varp 1786 bit 0). */
    public static final int VARBIT_ACTION_FLAG = 6056;

    /** Familiar option / interaction mode (4-bit, varp 1781 bits 7-10). */
    public static final int VARBIT_INTERACTION_MODE = 6057;

    /** Familiar status flag (1-bit, varp 1781 bit 11). */
    public static final int VARBIT_STATUS_FLAG = 6058;

    /** Familiar combat flag A (1-bit, varp 1781 bit 18). */
    public static final int VARBIT_COMBAT_FLAG_A = 6059;

    /** Familiar combat flag B (1-bit, varp 1781 bit 19). */
    public static final int VARBIT_COMBAT_FLAG_B = 6060;

    /** Familiar combat flag C (1-bit, varp 1781 bit 20). */
    public static final int VARBIT_COMBAT_FLAG_C = 6061;

    /** Familiar UI toggle (1-bit, varp 1781 bit 21). */
    public static final int VARBIT_UI_TOGGLE = 6062;

    /** Familiar inventory / Beast of Burden flag (1-bit, varp 1781 bit 24). */
    public static final int VARBIT_BOB_FLAG = 6063;

    /** Familiar type category (3-bit, varp 1781 bits 25-27, max 7). */
    public static final int VARBIT_TYPE_CATEGORY = 6064;

    // ========================== HP & Points ==========================

    /** Current HP (16-bit, varp 5194 bits 0-15). */
    public static final int VARBIT_CURRENT_HP = 19034;

    /** Max HP (16-bit, varp 5194 bits 16-31). */
    public static final int VARBIT_MAX_HP = 27403;

    /** Spell points (full varp 1787, max 60). */
    public static final int VARP_SPELL_POINTS = 1787;

    /** Familiar type/name identifier (full varp 1831). */
    public static final int VARP_FAMILIAR_TYPE = 1831;

    /** Display toggle: 1 = show numeric HP/SP values, 0 = labels only (1-bit, varp 9901 bit 29). */
    public static final int VARBIT_DISPLAY_TOGGLE = 29379;

    /** Maximum spell points for any familiar. */
    public static final int MAX_SPELL_POINTS = 60;

    // ========================== Scroll System (25408-25420) ==========================

    /** Summoning unlock/toggle flag (1-bit, varp 4516 bit 26). */
    public static final int VARBIT_SUMMONING_UNLOCK = 25408;

    /** Summoning configuration flag (1-bit, varp 4763 bit 1). */
    public static final int VARBIT_SUMMONING_CONFIG = 25409;

    /** Summoning feature flag (1-bit, varp 4778 bit 21). */
    public static final int VARBIT_SUMMONING_FEATURE = 25410;

    /** Summoning-related counter (5-bit, varp 10439 bits 0-4, max 31). */
    public static final int VARBIT_SUMMONING_COUNTER = 25411;

    /** Primary scroll count (9-bit, varp 4823 bits 0-8, max 511). */
    public static final int VARBIT_SCROLL_COUNT = 25412;

    /** Primary scroll type (4-bit, varp 4823 bits 9-12). */
    public static final int VARBIT_SCROLL_TYPE = 25413;

    /** Primary scroll option selector (3-bit, varp 4823 bits 13-15). */
    public static final int VARBIT_SCROLL_OPTION = 25414;

    /** Auto-fire scrolls toggle (1-bit, varp 4823 bit 16). */
    public static final int VARBIT_AUTO_FIRE_TOGGLE = 25415;

    /** Secondary scroll count (9-bit, varp 65700 bits 0-8, max 511). */
    public static final int VARBIT_SECONDARY_SCROLL_COUNT = 25416;

    /** Secondary scroll type (4-bit, varp 65700 bits 9-12). */
    public static final int VARBIT_SECONDARY_SCROLL_TYPE = 25417;

    /** Secondary scroll option (3-bit, varp 65700 bits 13-15). */
    public static final int VARBIT_SECONDARY_SCROLL_OPTION = 25418;

    /** Auto-fire rate / interval (4-bit, varp 65700 bits 16-19). */
    public static final int VARBIT_AUTO_FIRE_RATE = 25419;

    /** Scroll system toggle (1-bit, varp 65699 bit 0). */
    public static final int VARBIT_SCROLL_SYSTEM_TOGGLE = 25420;

    // ========================== UI Components ==========================

    /** Interface component for scrolls remaining in backpack. */
    public static final int COMPONENT_SCROLLS_BACKPACK = 43384911;

    /** Interface component for scrolls remaining in familiar. */
    public static final int COMPONENT_SCROLLS_FAMILIAR = 43384912;

    /** Interface component for total scrolls (backpack + familiar). */
    public static final int COMPONENT_SCROLLS_TOTAL = 43384954;

    private final GameAPI api;

    public Familiar(GameAPI api) {
        this.api = api;
    }

    // ========================== Core State ==========================

    /**
     * Check if a familiar is currently summoned.
     */
    public boolean isActive() {
        return api.getVarbit(VARBIT_EXISTS) == 1;
    }

    /**
     * Get the time remaining until the familiar despawns, in game ticks (max 8191).
     */
    public int getTimeRemaining() {
        return api.getVarbit(VARBIT_TIME_REMAINING);
    }

    /**
     * Get the time remaining in approximate minutes.
     * <p>Each game tick is ~0.6 seconds.</p>
     */
    public double getTimeRemainingMinutes() {
        return getTimeRemaining() * 0.6 / 60.0;
    }

    /**
     * Get the familiar's combat mode / aggression level (0-15).
     */
    public int getCombatMode() {
        return api.getVarbit(VARBIT_COMBAT_MODE);
    }

    /**
     * Get the familiar type/name identifier from varp 1831.
     */
    public int getFamiliarTypeId() {
        return api.getVarp(VARP_FAMILIAR_TYPE);
    }

    /**
     * Get the familiar type category (0-7).
     */
    public int getTypeCategory() {
        return api.getVarbit(VARBIT_TYPE_CATEGORY);
    }

    /**
     * Check if the familiar has a Beast of Burden inventory.
     */
    public boolean isBeastOfBurden() {
        return api.getVarbit(VARBIT_BOB_FLAG) == 1;
    }

    /**
     * Get the familiar interaction mode (0-15).
     */
    public int getInteractionMode() {
        return api.getVarbit(VARBIT_INTERACTION_MODE);
    }

    // ========================== HP ==========================

    /**
     * Get the familiar's current life points.
     */
    public int getCurrentHp() {
        return api.getVarbit(VARBIT_CURRENT_HP);
    }

    /**
     * Get the familiar's maximum life points.
     */
    public int getMaxHp() {
        return api.getVarbit(VARBIT_MAX_HP);
    }

    /**
     * Get the familiar's HP as a percentage (0.0 - 100.0).
     */
    public double getHpPercent() {
        int max = getMaxHp();
        if (max <= 0) return 0.0;
        return (getCurrentHp() * 100.0) / max;
    }

    // ========================== Spell Points ==========================

    /**
     * Get the current spell (special move) points (0-60).
     */
    public int getSpellPoints() {
        return api.getVarp(VARP_SPELL_POINTS);
    }

    /**
     * Check if spell points are at maximum (60).
     */
    public boolean isSpellPointsFull() {
        return getSpellPoints() >= MAX_SPELL_POINTS;
    }

    // ========================== Special Moves ==========================

    /**
     * Get the special move points tracked in the core cluster (0-31).
     */
    public int getSpecialMovePoints() {
        return api.getVarbit(VARBIT_SPECIAL_MOVE_POINTS);
    }

    /**
     * Check if a special move is flagged as available.
     */
    public boolean isSpecialMoveAvailable() {
        return api.getVarbit(VARBIT_SPECIAL_MOVE_FLAG) == 1;
    }

    /**
     * Get the special move cooldown timer (0-511).
     */
    public int getSpecialMoveCooldown() {
        return api.getVarbit(VARBIT_SPECIAL_COOLDOWN);
    }

    /**
     * Check if the special move is on cooldown.
     */
    public boolean isSpecialMoveOnCooldown() {
        return getSpecialMoveCooldown() > 0;
    }

    // ========================== Scrolls ==========================

    /**
     * Get the primary scroll count stored in the familiar (max 511).
     */
    public int getScrollCount() {
        return api.getVarbit(VARBIT_SCROLL_COUNT);
    }

    /**
     * Get the primary scroll type.
     */
    public int getScrollType() {
        return api.getVarbit(VARBIT_SCROLL_TYPE);
    }

    /**
     * Check if auto-fire scrolls is enabled.
     */
    public boolean isAutoFireEnabled() {
        return api.getVarbit(VARBIT_AUTO_FIRE_TOGGLE) == 1;
    }

    /**
     * Get the auto-fire rate / interval setting.
     */
    public int getAutoFireRate() {
        return api.getVarbit(VARBIT_AUTO_FIRE_RATE);
    }

    /**
     * Get the secondary scroll count (max 511).
     */
    public int getSecondaryScrollCount() {
        return api.getVarbit(VARBIT_SECONDARY_SCROLL_COUNT);
    }

    /**
     * Get the secondary scroll type.
     */
    public int getSecondaryScrollType() {
        return api.getVarbit(VARBIT_SECONDARY_SCROLL_TYPE);
    }

    // ========================== Display & Flags ==========================

    /**
     * Check if numeric HP/SP display is enabled (varbit 29379).
     * <p>When enabled, the UI shows "{current}/{max}" values instead of generic labels.</p>
     */
    public boolean isNumericDisplayEnabled() {
        return api.getVarbit(VARBIT_DISPLAY_TOGGLE) == 1;
    }

    /**
     * Check if the familiar UI panel is toggled on.
     */
    public boolean isUiVisible() {
        return api.getVarbit(VARBIT_UI_TOGGLE) == 1;
    }

    /**
     * Check if the familiar action flag is set.
     */
    public boolean isActionFlagged() {
        return api.getVarbit(VARBIT_ACTION_FLAG) == 1;
    }

    /**
     * Check if the scroll system toggle is enabled.
     */
    public boolean isScrollSystemEnabled() {
        return api.getVarbit(VARBIT_SCROLL_SYSTEM_TOGGLE) == 1;
    }
}
