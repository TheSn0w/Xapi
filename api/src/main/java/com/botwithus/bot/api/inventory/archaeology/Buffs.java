package com.botwithus.bot.api.inventory.archaeology;

import com.botwithus.bot.api.GameAPI;
import com.botwithus.bot.api.inventory.Backpack;


/**
 * Provides access to Archaeology Guild Shop consumable buff timers.
 * <p>Four consumable buffs are available from Ezreal's shop, each with an 8-bit
 * varbit tracking remaining duration. All four varbits partition varp 9368 into
 * four 8-bit slots. Base duration is 20 minutes, extended to 30 with Master
 * Archaeologist's Outfit. Stackable up to 60 minutes.</p>
 *
 * <pre>{@code
 * Buffs buffs = new Buffs(ctx.getGameAPI());
 * if (!buffs.isActive(Buffs.BuffType.TEA)) {
 *     buffs.use(Buffs.BuffType.TEA);
 * }
 * }</pre>
 */
public final class Buffs {

    private final GameAPI api;
    private final Backpack backpack;

    public Buffs(GameAPI api) {
        this.api = api;
        this.backpack = new Backpack(api);
    }

    /**
     * Get the remaining timer value for a buff (0 = expired/inactive).
     */
    public int getTimer(BuffType buff) {
        return api.getVarbit(buff.timerVarbit);
    }

    /**
     * Check if a buff is currently active (timer > 0).
     */
    public boolean isActive(BuffType buff) {
        return getTimer(buff) > 0;
    }

    /**
     * Check if any of the 4 buffs are currently active.
     */
    public boolean anyActive() {
        for (BuffType buff : BuffType.values()) {
            if (isActive(buff)) return true;
        }
        return false;
    }

    /**
     * Check if the player has the consumable item in their backpack.
     */
    public boolean has(BuffType buff) {
        return backpack.contains(buff.itemId);
    }

    /**
     * Use (activate) a consumable buff from the backpack.
     *
     * @return {@code true} if the action was queued
     */
    public boolean use(BuffType buff) {
        if (!has(buff)) return false;
        return backpack.interact(buff.name, buff.action);
    }

    /**
     * Archaeology Guild Shop consumable buff types.
     * <p>All share varp 9368, partitioned into 4× 8-bit slots.</p>
     */
    public enum BuffType {
        /** +10% chance to find materials while excavating. */
        MATERIAL_MANUAL(49946, "Material manual", "Read",  47025),
        /** +20% base mattock precision while excavating. */
        MONOCLE        (49947, "Hi-spec monocle", "Wear",  47026),
        /** +10% chance to find soil while excavating. No effect with Flow State relic. */
        TARPAULIN      (49948, "Tarpaulin sheet", "Place", 47027),
        /** +50% Archaeology XP while excavating. */
        TEA            (49949, "Archaeologist's tea", "Drink", 47028);

        /** In-game item ID. */
        public final int itemId;
        /** Display name. */
        public final String name;
        /** Widget action to activate the buff. */
        public final String action;
        /** Varbit tracking remaining duration (8-bit, varp 9368). */
        public final int timerVarbit;

        BuffType(int itemId, String name, String action, int timerVarbit) {
            this.itemId = itemId;
            this.name = name;
            this.action = action;
            this.timerVarbit = timerVarbit;
        }
    }
}
