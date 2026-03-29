package com.botwithus.bot.api.inventory.warsRetreat;

import com.botwithus.bot.api.GameAPI;
import com.botwithus.bot.api.log.BotLogger;
import com.botwithus.bot.api.log.LoggerFactory;

/**
 * Provides access to the Soul Reaper boss task system.
 * <p>Soul Reaper tasks are boss-killing assignments that reward Reaper points.
 * Task data is packed into varp 4519 (varbits 22901-22907). Lifetime reaper points
 * are tracked separately in varbit 23260 on varp 2194.</p>
 *
 * <p>Completing all Reaper tasks grants "the Reaper" title with passive bonuses:
 * +2 Prayer, +20 Armour, +200 LP, +12% damage (all styles).</p>
 *
 * <pre>{@code
 * SoulReaper reaper = new SoulReaper(ctx.getGameAPI());
 * if (reaper.hasTask()) {
 *     int bossId = reaper.getTaskBossId();
 *     int kills = reaper.getTaskKillCount();
 *     int points = reaper.getReaperPoints();
 * }
 * }</pre>
 */
public final class SoulReaper {

    private static final BotLogger log = LoggerFactory.getLogger(SoulReaper.class);

    // ========================== Reaper Task Varbits (varp 4519) ==========================

    /** Current task boss ID (6-bit, varp 4519 bits 0-5, 0-63). */
    public static final int VARBIT_TASK_BOSS = 22901;

    /** Task kill count / progress (5-bit, varp 4519 bits 6-10). */
    public static final int VARBIT_TASK_KILLS = 22902;

    /** Task state flag (1-bit, varp 4519 bit 11). */
    public static final int VARBIT_TASK_FLAG_A = 22903;

    /** Task state flag (1-bit, varp 4519 bit 12). */
    public static final int VARBIT_TASK_FLAG_B = 22904;

    /** Reaper points (14-bit, varp 4519 bits 15-28, max 16383). */
    public static final int VARBIT_REAPER_POINTS = 22905;

    /** Reaper flag (1-bit, varp 4519 bit 29). */
    public static final int VARBIT_REAPER_FLAG_A = 22906;

    /** Reaper flag (1-bit, varp 4519 bit 30). */
    public static final int VARBIT_REAPER_FLAG_B = 22907;

    // ========================== Related Varbits ==========================

    /** Lifetime reaper points (13-bit, varp 2194 bits 8-20). */
    public static final int VARBIT_LIFETIME_POINTS = 23260;

    /** Slayer points (17-bit, varp 2092 bits 0-16). Displayed when in Slayer mode. */
    public static final int VARBIT_SLAYER_POINTS = 9071;

    private final GameAPI api;

    public SoulReaper(GameAPI api) {
        this.api = api;
    }

    // ========================== Task State ==========================

    /**
     * Check if the player currently has a reaper task assigned.
     * <p>Boss ID 0 means no task.</p>
     */
    public boolean hasTask() {
        return getTaskBossId() > 0;
    }

    /**
     * Get the current reaper task boss ID (0-63).
     * <p>0 means no task is assigned.</p>
     */
    public int getTaskBossId() {
        return api.getVarbit(VARBIT_TASK_BOSS);
    }

    /**
     * Get the current task kill count / progress.
     */
    public int getTaskKillCount() {
        return api.getVarbit(VARBIT_TASK_KILLS);
    }

    // ========================== Points ==========================

    /**
     * Get the current Reaper points (max 16383).
     */
    public int getReaperPoints() {
        return api.getVarbit(VARBIT_REAPER_POINTS);
    }

    /**
     * Get the lifetime accumulated Reaper points.
     */
    public int getLifetimeReaperPoints() {
        return api.getVarbit(VARBIT_LIFETIME_POINTS);
    }

    /**
     * Get the current Slayer points.
     */
    public int getSlayerPoints() {
        return api.getVarbit(VARBIT_SLAYER_POINTS);
    }
}
