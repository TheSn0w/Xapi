package com.botwithus.bot.api.inventory.warsRetreat;

import com.botwithus.bot.api.GameAPI;
import com.botwithus.bot.api.log.BotLogger;
import com.botwithus.bot.api.log.LoggerFactory;

/**
 * Provides access to War's Blessings, Combat Score, and medallion progression.
 * <p>War's Blessings are unlocked by accumulating Combat Score (varp 12120). Each tier
 * is a wearable pocket-slot item granting permanent passive PvM benefits. Unlock state
 * is tracked in varbits 57652-57657 on varp 12136 with values: 0=locked, 1=active, 2=superseded.</p>
 *
 * <pre>{@code
 * Blessings blessings = new Blessings(ctx.getGameAPI());
 * int score = blessings.getCombatScore();
 * Blessings.Tier active = blessings.getActiveTier();
 * if (active != null && active.ordinal() >= Blessings.Tier.BLESSING_2.ordinal()) {
 *     // GWD killcount is bypassed
 * }
 * }</pre>
 */
public final class Blessings {

    private static final BotLogger log = LoggerFactory.getLogger(Blessings.class);

    /** Combat Score (full varp 12120, 32-bit). */
    public static final int VARP_COMBAT_SCORE = 12120;

    private final GameAPI api;

    public Blessings(GameAPI api) {
        this.api = api;
    }

    // ========================== Combat Score ==========================

    /**
     * Get the player's total Combat Score.
     */
    public int getCombatScore() {
        return api.getVarp(VARP_COMBAT_SCORE);
    }

    // ========================== Blessing Status ==========================

    /**
     * Get the unlock state of a specific blessing tier.
     *
     * @return 0=locked, 1=active, 2=superseded by higher tier
     */
    public int getTierState(Tier tier) {
        return api.getVarbit(tier.varbit);
    }

    /**
     * Check if a specific blessing tier is currently active.
     */
    public boolean isTierActive(Tier tier) {
        return getTierState(tier) == 1;
    }

    /**
     * Check if a specific blessing tier has been unlocked (active or superseded).
     */
    public boolean isTierUnlocked(Tier tier) {
        return getTierState(tier) >= 1;
    }

    /**
     * Get the highest currently active blessing tier.
     *
     * @return the active {@link Tier}, or {@code null} if none are active
     */
    public Tier getActiveTier() {
        // Check from highest to lowest since higher tiers supersede lower ones
        Tier[] tiers = Tier.values();
        for (int i = tiers.length - 1; i >= 0; i--) {
            if (getTierState(tiers[i]) == 1) return tiers[i];
        }
        return null;
    }

    // ========================== Enums ==========================

    /**
     * War's Blessing and Combat Score progression tiers.
     * <p>Blessings 1-4 are wearable pocket-slot items. Master and Grandmaster
     * unlock medallions and mastery auras but no additional blessing item.</p>
     */
    public enum Tier {
        BLESSING_1   (57652, 58412, 58416, -1,    3625, "Easy"),
        BLESSING_2   (57653, 58413, 58417, -1,    3626, "Medium"),
        BLESSING_3   (57654, 58414, 58418, -1,    3627, "Hard"),
        BLESSING_4   (57655, 58415, 58419, -1,    3628, "Elite"),
        MASTER       (57656, -1,    58420, 58422, 3629, "Master"),
        GRANDMASTER  (57657, -1,    58421, 58423, 3630, "Grandmaster");

        /** Varbit tracking unlock state (2-bit, varp 12136). 0=locked, 1=active, 2=superseded. */
        public final int varbit;
        /** Blessing item ID (pocket slot), or -1 if no blessing item for this tier. */
        public final int blessingItemId;
        /** Medallion item ID (neck slot). */
        public final int medallionItemId;
        /** Combat Mastery aura item ID (aura slot), or -1 if no aura for this tier. */
        public final int auraItemId;
        /** Achievement ID for this tier. */
        public final int achievementId;
        /** Difficulty label. */
        public final String difficulty;

        Tier(int varbit, int blessingItemId, int medallionItemId, int auraItemId, int achievementId, String difficulty) {
            this.varbit = varbit;
            this.blessingItemId = blessingItemId;
            this.medallionItemId = medallionItemId;
            this.auraItemId = auraItemId;
            this.achievementId = achievementId;
            this.difficulty = difficulty;
        }
    }
}
