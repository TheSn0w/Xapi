package com.botwithus.bot.api.inventory.warsRetreat;

import com.botwithus.bot.api.GameAPI;
import com.botwithus.bot.api.log.BotLogger;
import com.botwithus.bot.api.log.LoggerFactory;

/**
 * Provides access to War's Retreat hub features: campfire, elder overload cauldron,
 * adrenaline crystal, bank chest, altar, and feature unlock flags.
 * <p>War's Retreat is a PvM hub area (region 13214). The typical lobby sequence is:
 * Teleport, Campfire, Pray, Cauldron, Bank, Incense, Adrenaline Crystal, Enter portal.</p>
 *
 * <p>Feature unlocks are purchased from War's Wares shop (NPC 26773) using Marks of War
 * (item 49236) and/or boss kill count requirements. All unlock flags are packed into
 * varp 4775 as individual bits (varbits 25089-25104).</p>
 *
 * <pre>{@code
 * HubFeatures hub = new HubFeatures(ctx.getGameAPI());
 * if (!hub.isCampfireActive()) { // warm hands at campfire }
 * if (hub.isCauldronAvailable()) { // drink from cauldron }
 * }</pre>
 */
public final class HubFeatures {

    private static final BotLogger log = LoggerFactory.getLogger(HubFeatures.class);

    // ========================== Object IDs ==========================

    /** Elder Overload Cauldron scene object. */
    public static final int OBJECT_CAULDRON = 127472;

    /** Adrenaline Crystal scene object at (3298, 10148, 0). */
    public static final int OBJECT_ADRENALINE_CRYSTAL = 114749;

    /** Bank Chest scene object at (3299, 10131, 0). */
    public static final int OBJECT_BANK_CHEST = 114750;

    /** Portal object IDs. */
    public static final int OBJECT_PORTAL_A = 92113;
    public static final int OBJECT_PORTAL_B = 127392;

    // ========================== NPC IDs ==========================

    /** War NPC (shop, talk, check cooldown, combat mastery overview). Quest 518 required. */
    public static final int NPC_WAR = 26773;

    /** War NPC (primary in-game instance). */
    public static final int NPC_WAR_PRIMARY = 25095;

    /** Tiny War pet NPC. */
    public static final int NPC_TINY_WAR = 25951;

    // ========================== Item IDs ==========================

    /** Marks of War currency (stackable, untradeable). */
    public static final int ITEM_MARKS_OF_WAR = 49236;

    /** Tiny War pet item. */
    public static final int ITEM_TINY_WAR = 44239;

    /** Portable adrenaline crystal. */
    public static final int ITEM_ADRENALINE_CRYSTAL = 38920;

    /** Depleted adrenaline crystal. */
    public static final int ITEM_INERT_ADRENALINE_CRYSTAL = 48351;

    // ========================== Hub Feature Varbits ==========================

    /** Campfire buff active — warm hands (1-bit, varp 4877 bit 21). 0=inactive, 1=active. */
    public static final int VARBIT_CAMPFIRE_BUFF = 28800;

    /** Campfire upgrade flag (1-bit, varp 4877 bit 22). */
    public static final int VARBIT_CAMPFIRE_UPGRADE_1 = 28801;

    /** Campfire upgrade flag (1-bit, varp 4877 bit 23). */
    public static final int VARBIT_CAMPFIRE_UPGRADE_2 = 28802;

    /** Campfire upgrade flag (1-bit, varp 4877 bit 24). */
    public static final int VARBIT_CAMPFIRE_UPGRADE_3 = 28803;

    /** Elder Overload Cauldron state (3-bit, varp 4909 bits 29-31). 0=available to drink. */
    public static final int VARBIT_CAULDRON = 26037;

    /** Tiny War pet unlock state. */
    public static final int VARBIT_TINY_WAR_UNLOCK = 40161;

    // ========================== Feature Unlock Varbits (varp 4775) ==========================

    /** Hub Teleport unlocked (1-bit, varp 4775 bit 10). */
    public static final int VARBIT_UNLOCK_TELEPORT = 25089;

    /** All unlock flags combined (16-bit read, varp 4775 bits 10-25). */
    public static final int VARBIT_ALL_UNLOCKS = 25105;

    // Individual unlock flags (varp 4775 bits 11-25)
    public static final int VARBIT_UNLOCK_25090 = 25090;
    public static final int VARBIT_UNLOCK_25091 = 25091;
    public static final int VARBIT_UNLOCK_25092 = 25092;
    public static final int VARBIT_UNLOCK_25093 = 25093;
    public static final int VARBIT_UNLOCK_25094 = 25094;
    public static final int VARBIT_UNLOCK_25095 = 25095;
    public static final int VARBIT_UNLOCK_25096 = 25096;
    public static final int VARBIT_UNLOCK_25097 = 25097;
    public static final int VARBIT_UNLOCK_25098 = 25098;
    public static final int VARBIT_UNLOCK_25099 = 25099;
    public static final int VARBIT_UNLOCK_25100 = 25100;
    public static final int VARBIT_UNLOCK_25101 = 25101;
    public static final int VARBIT_UNLOCK_25102 = 25102;
    public static final int VARBIT_UNLOCK_25103 = 25103;
    public static final int VARBIT_UNLOCK_25104 = 25104;

    /** Additional unlock flags on varp 4775 (bits 26-29). */
    public static final int VARBIT_UNLOCK_25106 = 25106;
    public static final int VARBIT_UNLOCK_25108 = 25108;
    public static final int VARBIT_UNLOCK_25109 = 25109;
    public static final int VARBIT_UNLOCK_25110 = 25110;

    private final GameAPI api;

    public HubFeatures(GameAPI api) {
        this.api = api;
    }

    // ========================== Campfire ==========================

    /**
     * Check if the campfire "Warm hands" buff is currently active.
     */
    public boolean isCampfireActive() {
        return api.getVarbit(VARBIT_CAMPFIRE_BUFF) == 1;
    }

    // ========================== Elder Overload Cauldron ==========================

    /**
     * Check if the Elder Overload Cauldron is available to drink from.
     * <p>Value 0 = available. Non-zero indicates cooldown or already consumed.</p>
     */
    public boolean isCauldronAvailable() {
        return api.getVarbit(VARBIT_CAULDRON) == 0;
    }

    /**
     * Get the raw cauldron state value (3-bit, 0-7).
     */
    public int getCauldronState() {
        return api.getVarbit(VARBIT_CAULDRON);
    }

    // ========================== Unlock Checks ==========================

    /**
     * Check if the War's Retreat Teleport spell is unlocked (10 boss kills).
     */
    public boolean isTeleportUnlocked() {
        return api.getVarbit(VARBIT_UNLOCK_TELEPORT) == 1;
    }

    /**
     * Get the combined unlock flags as a 16-bit value.
     */
    public int getAllUnlockFlags() {
        return api.getVarbit(VARBIT_ALL_UNLOCKS);
    }

    /**
     * Check if a specific feature unlock varbit is set.
     */
    public boolean isUnlocked(int varbit) {
        return api.getVarbit(varbit) == 1;
    }

    // ========================== Campfire Tiers ==========================

    /**
     * Campfire upgrade tiers purchasable from War's Wares.
     * <p>Each tier requires the previous upgrade, 1000 logs, a Firemaking level, and 1000 Marks of War.</p>
     */
    public enum CampfireTier {
        OAK     (11626, "Oak campfire",    1521, 15),
        WILLOW  (11627, "Willow campfire", 1519, 30),
        MAPLE   (11628, "Maple campfire",  1517, 45),
        YEW     (11629, "Yew campfire",    1515, 60),
        MAGIC   (11630, "Magic campfire",  1513, 75),
        ELDER   (11631, "Elder campfire",  29556, 90);

        /** Struct ID for this campfire tier in War's Wares shop. */
        public final int structId;
        /** Display name. */
        public final String name;
        /** Required log item ID (1000 logs needed). */
        public final int logItemId;
        /** Required Firemaking level. */
        public final int fmLevel;

        CampfireTier(int structId, String name, int logItemId, int fmLevel) {
            this.structId = structId;
            this.name = name;
            this.logItemId = logItemId;
            this.fmLevel = fmLevel;
        }
    }
}
