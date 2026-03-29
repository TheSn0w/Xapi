package com.botwithus.bot.api.inventory.archaeology;

import com.botwithus.bot.api.GameAPI;


/**
 * Provides access to the Archaeology qualification tier and related state.
 * <p>Qualification level is tracked by varbit 46468 (varp 9235, bits 25–29).
 * Tiers gate access to guild shop upgrades, research teams, outfits, and more.</p>
 *
 * <pre>{@code
 * Qualification qual = new Qualification(ctx.getGameAPI());
 * if (qual.getTier() >= Qualification.Tier.ASSOCIATE) {
 *     // Can buy auto-screener blueprint
 * }
 * }</pre>
 */
public final class Qualification {

    /** Varbit for qualification level (1=Intern, 2=Assistant, 3=Associate, 4=Professor, 5=Guildmaster). */
    public static final int VARBIT_QUALIFICATION = 46468;
    /** Varbit for Archaeology tutorial progress (0–100 scale; 100 = complete). */
    public static final int VARBIT_TUTORIAL_PROGRESS = 46463;
    /** Varbit for Archaeology Journal ownership (boolean). */
    public static final int VARBIT_JOURNAL_OWNED = 49429;
    /** Varplayer for sprite focus percentage during excavation. */
    public static final int VARP_SPRITE_FOCUS = 9307;
    /** Varbit for familiar remaining time (9-bit, varp 1786, bits 7–15). */
    public static final int VARBIT_FAMILIAR_TIMER = 6055;

    private final GameAPI api;

    public Qualification(GameAPI api) {
        this.api = api;
    }

    // ========================== Qualification Tier ==========================

    /**
     * Get the raw qualification level value (0–5).
     * <p>0 = none, 1 = Intern, 2 = Assistant, 3 = Associate, 4 = Professor, 5 = Guildmaster.</p>
     */
    public int getTier() {
        return api.getVarbit(VARBIT_QUALIFICATION);
    }

    /**
     * Get the display name for the current qualification tier.
     */
    public String getTierName() {
        return switch (getTier()) {
            case Tier.INTERN -> "Intern";
            case Tier.ASSISTANT -> "Assistant";
            case Tier.ASSOCIATE -> "Associate";
            case Tier.PROFESSOR -> "Professor";
            case Tier.GUILDMASTER -> "Guildmaster";
            default -> "None";
        };
    }

    /**
     * Check if the player has reached at least the given tier.
     */
    public boolean hasReached(int tier) {
        return getTier() >= tier;
    }

    // ========================== Tutorial & State ==========================

    /**
     * Get the Archaeology tutorial progress (0–100 scale).
     * <p>Milestones: 60 = soil box, 70/75 = relic tiers, 100 = complete.</p>
     */
    public int getTutorialProgress() {
        return api.getVarbit(VARBIT_TUTORIAL_PROGRESS);
    }

    /**
     * Check if the Archaeology tutorial is fully complete.
     */
    public boolean isTutorialComplete() {
        return getTutorialProgress() >= 100;
    }

    /**
     * Check if the player owns the Archaeology Journal.
     */
    public boolean hasJournal() {
        return api.getVarbit(VARBIT_JOURNAL_OWNED) == 1;
    }

    // ========================== Excavation State ==========================

    /**
     * Get the current sprite focus percentage during excavation.
     * <p>Read from varplayer 9307. Value is 0 when not excavating.</p>
     */
    public int getSpriteFocus() {
        return api.getVarp(VARP_SPRITE_FOCUS);
    }

    /**
     * Get the remaining familiar timer value.
     * <p>Read from varbit 6055 (9-bit, max 511). Near 0 = about to expire.</p>
     */
    public int getFamiliarTimer() {
        return api.getVarbit(VARBIT_FAMILIAR_TIMER);
    }

    /**
     * Check if a familiar is currently active (timer > 0).
     */
    public boolean hasFamiliar() {
        return getFamiliarTimer() > 0;
    }

    /**
     * Qualification tier constants.
     */
    public static final class Tier {
        public static final int INTERN = 1;
        public static final int ASSISTANT = 2;
        public static final int ASSOCIATE = 3;
        public static final int PROFESSOR = 4;
        public static final int GUILDMASTER = 5;

        private Tier() {}
    }
}
