package com.botwithus.bot.api.inventory.porters;

import com.botwithus.bot.api.GameAPI;
import com.botwithus.bot.api.inventory.Backpack;
import com.botwithus.bot.api.inventory.Equipment;


/**
 * Provides access to the Sign of the Porter system.
 * <p>Signs of the Porter automatically teleport gathered resources to the bank.
 * There are 7 tiers (I–VII), each with inactive and active item variants. Porters
 * can be worn standalone in the pocket slot, or consumed to charge a Grace of the Elves.</p>
 *
 * <p>Charge reading uses INV_GETVAR on equipment inventory (94):
 * <ul>
 *   <li>GotE charges: {@code INV_GETVAR(94, 2, 30214)} — amulet slot</li>
 *   <li>Worn porter charges: {@code INV_GETVAR(94, 17, 20171)} — pocket slot</li>
 * </ul>
 * The porter toggle is controlled by varbit 35985 (0=off, 1=on).</p>
 *
 * <pre>{@code
 * Porters porters = new Porters(ctx.getGameAPI());
 * if (porters.isEnabled() && porters.getGotECharges() > 0) {
 *     // Porters are active and have charges
 * }
 * }</pre>
 */
public final class Porters {

    /** Varbit for porter toggle ON/OFF (0=disabled, 1=active). Varp 8176, bit 20. */
    public static final int VARBIT_PORTER_TOGGLE = 35985;
    /** Varbit for porter charge count (11-bit, max 2047). Varp 10709, bits 3–13. */
    public static final int VARBIT_PORTER_CHARGES = 52160;
    /** Varbit for "Consume a porter when overcharging" setting. Varp 7052, bit 11. */
    public static final int VARBIT_CONSUME_OVERCHARGE = 41471;

    /** Equipment inventory ID. */
    private static final int EQUIPMENT_INV = 94;
    /** Pocket slot index in equipment inventory. */
    private static final int SLOT_POCKET = 17;
    /** Param for worn porter charges (pocket slot). */
    public static final int PARAM_WORN_PORTER_CHARGES = 20171;

    private final GameAPI api;
    private final Backpack backpack;
    private final Equipment equipment;

    public Porters(GameAPI api) {
        this.api = api;
        this.backpack = new Backpack(api);
        this.equipment = new Equipment(api);
    }

    // ========================== Toggle ==========================

    /**
     * Check if the porter functionality is enabled (varbit 35985 = 1).
     */
    public boolean isEnabled() {
        return api.getVarbit(VARBIT_PORTER_TOGGLE) == 1;
    }

    // ========================== Charges ==========================

    /**
     * Get the porter charge count from varbit 52160 (0–500 base, 0–2000 with Dark Facet).
     */
    public int getCharges() {
        return api.getVarbit(VARBIT_PORTER_CHARGES);
    }

    /**
     * Check if any porter charges remain.
     */
    public boolean hasCharges() {
        return getCharges() > 0;
    }

    // ========================== Backpack Porters ==========================

    /**
     * Check if the player has any porter sign in their backpack.
     */
    public boolean hasPorterInBackpack() {
        for (PorterTier tier : PorterTier.values()) {
            if (backpack.contains(tier.inactiveId)) return true;
        }
        return false;
    }

    /**
     * Find the highest tier porter in the backpack.
     *
     * @return the highest tier found, or {@code null} if none
     */
    public PorterTier getHighestPorterInBackpack() {
        PorterTier[] tiers = PorterTier.values();
        for (int i = tiers.length - 1; i >= 0; i--) {
            if (backpack.contains(tiers[i].inactiveId)) return tiers[i];
        }
        return null;
    }

    // ========================== Equipment ==========================

    /**
     * Check if a Grace of the Elves (worn variant) is equipped.
     */
    public boolean isGotEEquipped() {
        return equipment.contains(GraceOfTheElves.ITEM_WORN);
    }

    /**
     * Check if a standalone porter is worn in the pocket slot.
     */
    public boolean hasWornPorter() {
        for (PorterTier tier : PorterTier.values()) {
            if (equipment.contains(tier.activeId)) return true;
        }
        return false;
    }

    // ========================== Enums ==========================

    /**
     * Sign of the Porter tiers (I–VII).
     */
    public enum PorterTier {
        I  (29275, 29276,  5,  6),
        II (29277, 29278, 10, 28),
        III(29279, 29280, 15, 48),
        IV (29281, 29282, 20, 68),
        V  (29283, 29284, 25, 78),
        VI (29285, 29286, 30, 88),
        VII(51487, 51491, 50, 99);

        /** Inactive (pocket slot, pre-activation) item ID. */
        public final int inactiveId;
        /** Active (activated) item ID. */
        public final int activeId;
        /** Number of charges this tier provides. */
        public final int charges;
        /** Divination level required to craft. */
        public final int divinationLevel;

        PorterTier(int inactiveId, int activeId, int charges, int divinationLevel) {
            this.inactiveId = inactiveId;
            this.activeId = activeId;
            this.charges = charges;
            this.divinationLevel = divinationLevel;
        }
    }
}
