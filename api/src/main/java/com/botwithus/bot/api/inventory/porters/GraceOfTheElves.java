package com.botwithus.bot.api.inventory.porters;

import com.botwithus.bot.api.GameAPI;
import com.botwithus.bot.api.inventory.Backpack;
import com.botwithus.bot.api.inventory.ComponentHelper;
import com.botwithus.bot.api.inventory.Equipment;
import com.botwithus.bot.api.model.Component;


/**
 * Provides access to the Grace of the Elves necklace system.
 * <p>Grace of the Elves (GotE) is an enchanted necklace that stores porter charges
 * (500 base, 2000 with Dark Facet of Grace upgrade), spawns Seren spirits during
 * gathering, and provides Max Garden portal teleports.</p>
 *
 * <p>Three item forms exist:
 * <ul>
 *   <li><b>44548</b> — Tradeable (before wearing)</li>
 *   <li><b>44549</b> — Noted form</li>
 *   <li><b>44550</b> — Worn/charged (untradeable once charged)</li>
 * </ul>
 *
 * <p>The Dark Facet of Grace (53921) imbues the necklace, doubling porter capacity
 * from 500 to 2000 and eliminating skill prayer drain. Tracked by varbit 52157.</p>
 *
 * <pre>{@code
 * GraceOfTheElves gote = new GraceOfTheElves(ctx.getGameAPI());
 * if (gote.isEquipped()) {
 *     int charges = gote.getPorterCharges();
 *     boolean upgraded = gote.isUpgraded();
 *     int maxCap = gote.getMaxCapacity(); // 500 or 2000
 * }
 * }</pre>
 */
public final class GraceOfTheElves {

    /** Tradeable item ID (pre-worn). */
    public static final int ITEM_TRADEABLE = 44548;
    /** Worn/charged item ID (untradeable). */
    public static final int ITEM_WORN = 44550;
    /** Dark Facet of Grace upgrade item ID. */
    public static final int ITEM_DARK_FACET = 53921;

    /** Varbit for Dark Facet of Grace imbued (0=no, 1=yes). Varp 10709, bit 0. */
    public static final int VARBIT_DARK_FACET_IMBUED = 52157;
    /** Varbit for Dark Facet of Luck imbued (LotD). Varp 10709, bit 1. */
    public static final int VARBIT_DARK_FACET_LUCK = 52158;
    /** Varbit for porter charge count (11-bit, max 2047). Varp 10709, bits 3–13. */
    public static final int VARBIT_PORTER_CHARGES = 52160;
    /** Varbit for porter toggle ON/OFF. Varp 8176, bit 20. */
    public static final int VARBIT_PORTER_TOGGLE = 35985;

    // Worn equipment interface for GotE teleports
    private static final int WORN_INTERFACE_ID = 1464;
    private static final int WORN_COMPONENT = 15;
    private static final int WORN_SUB_COMPONENT = 2; // neck slot
    private static final int OPTION_SKILLING_PORTAL_1 = 2;
    private static final int OPTION_SKILLING_PORTAL_2 = 3;

    /** Base porter charge capacity (without Dark Facet). */
    public static final int BASE_CAPACITY = 500;
    /** Upgraded porter charge capacity (with Dark Facet). */
    public static final int UPGRADED_CAPACITY = 2000;

    private final GameAPI api;
    private final Backpack backpack;
    private final Equipment equipment;

    public GraceOfTheElves(GameAPI api) {
        this.api = api;
        this.backpack = new Backpack(api);
        this.equipment = new Equipment(api);
    }

    // ========================== Detection ==========================

    /**
     * Check if the worn GotE (44550) is equipped in the amulet slot.
     */
    public boolean isEquipped() {
        return equipment.contains(ITEM_WORN);
    }

    /**
     * Check if the player has any GotE form in their backpack (tradeable or worn).
     */
    public boolean hasInBackpack() {
        return backpack.contains(ITEM_TRADEABLE) || backpack.contains(ITEM_WORN);
    }

    // ========================== Dark Facet ==========================

    /**
     * Check if the Dark Facet of Grace has been imbued into the necklace.
     * <p>When imbued, porter capacity increases from 500 to 2000 and
     * skill prayer drain is reduced by 100%.</p>
     */
    public boolean isUpgraded() {
        return api.getVarbit(VARBIT_DARK_FACET_IMBUED) == 1;
    }

    /**
     * Get the maximum porter charge capacity (500 base, 2000 with Dark Facet).
     */
    public int getMaxCapacity() {
        return isUpgraded() ? UPGRADED_CAPACITY : BASE_CAPACITY;
    }

    // ========================== Charges ==========================

    /**
     * Get the current porter charge count stored in the GotE.
     */
    public int getPorterCharges() {
        return api.getVarbit(VARBIT_PORTER_CHARGES);
    }

    /**
     * Check if the GotE has any porter charges remaining.
     */
    public boolean hasPorterCharges() {
        return getPorterCharges() > 0;
    }

    /**
     * Check if the GotE is at full porter capacity.
     */
    public boolean isFullyCharged() {
        return getPorterCharges() >= getMaxCapacity();
    }

    /**
     * Check if porter charges are below a given threshold.
     *
     * @param threshold the minimum acceptable charge count
     * @return {@code true} if charges are below the threshold
     */
    public boolean needsRecharge(int threshold) {
        return getPorterCharges() < threshold;
    }

    // ========================== Porter Toggle ==========================

    /**
     * Check if the porter functionality is toggled on.
     */
    public boolean isPorterEnabled() {
        return api.getVarbit(VARBIT_PORTER_TOGGLE) == 1;
    }

    /**
     * Toggle the porter on/off via the equipped GotE right-click menu.
     *
     * @return {@code true} if the action was queued
     */
    public boolean togglePorter() {
        if (!isEquipped()) return false;
        return equipment.interact(Equipment.Slot.NECK, "Toggle porter");
    }

    // ========================== Charging ==========================

    /**
     * Charge the GotE by consuming one porter from the backpack.
     * <p>Uses the "Charge porter" equipped right-click option.</p>
     *
     * @return {@code true} if the action was queued
     */
    public boolean chargePorter() {
        if (!isEquipped()) return false;
        return equipment.interact(Equipment.Slot.NECK, "Charge porter");
    }

    /**
     * Charge the GotE by consuming all porters from the backpack.
     * <p>Uses the "Charge all porters" equipped right-click option.</p>
     *
     * @return {@code true} if the action was queued
     */
    public boolean chargeAllPorters() {
        if (!isEquipped()) return false;
        return equipment.interact(Equipment.Slot.NECK, "Charge all porters");
    }

    // ========================== Teleports ==========================

    /**
     * Teleport to skilling portal 1 destination via the worn GotE.
     * <p>Uses interface 1464, component 15, sub 2, option 2.</p>
     *
     * @return {@code true} if the action was queued
     */
    public boolean teleportSkillingPortal1() {
        return teleportWornOption(OPTION_SKILLING_PORTAL_1);
    }

    /**
     * Teleport to skilling portal 2 destination via the worn GotE.
     * <p>Uses interface 1464, component 15, sub 2, option 3.</p>
     *
     * @return {@code true} if the action was queued
     */
    public boolean teleportSkillingPortal2() {
        return teleportWornOption(OPTION_SKILLING_PORTAL_2);
    }

    private boolean teleportWornOption(int option) {
        if (!isEquipped()) return false;
        Component comp = api.getComponentChildren(WORN_INTERFACE_ID, WORN_COMPONENT).stream()
                .filter(c -> c.subComponentId() == WORN_SUB_COMPONENT)
                .findFirst().orElse(null);
        if (comp == null) return false;
        ComponentHelper.queueComponentAction(api, comp, option);
        return true;
    }

}
