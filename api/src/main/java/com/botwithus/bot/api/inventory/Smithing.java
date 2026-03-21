package com.botwithus.bot.api.inventory;

import com.botwithus.bot.api.GameAPI;
import com.botwithus.bot.api.antiban.Delays;
import com.botwithus.bot.api.log.BotLogger;
import com.botwithus.bot.api.log.LoggerFactory;
import com.botwithus.bot.api.model.Component;
import com.botwithus.bot.api.model.EnumType;
import com.botwithus.bot.api.model.GameAction;
import com.botwithus.bot.api.model.InventoryItem;
import com.botwithus.bot.api.model.ItemType;
import com.botwithus.bot.api.model.ItemVar;
import com.botwithus.bot.api.model.PlayerStat;
import com.botwithus.bot.api.query.InventoryFilter;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

/**
 * Provides access to the RS3 smithing and smelting interface (interface 37).
 * <p>This interface opens at an anvil (smithing) or furnace (smelting) and allows
 * the player to select materials, products, and quality tiers before crafting.</p>
 *
 * <h3>Interface Structure</h3>
 * <ul>
 *   <li>Material category grids (5 rows): comp(37, 52/62/72/82/92)</li>
 *   <li>Product category grids (5 rows): comp(37, 103/114/125/136/147)</li>
 *   <li>Quality tier buttons (7 tiers): Base, +1, +2, +3, +4, +5, Burial</li>
 *   <li>Make button: comp(37, 163) — action type COMPONENT (57)</li>
 *   <li>Quantity slider: comp(37, 34) — sub:0=decrease, sub:7=increase</li>
 * </ul>
 *
 * <h3>Smithing vs Smelting</h3>
 * <p>Both use interface 37. Smelting mode hides comp(37, 5) (the smithing header).
 * The mode is set by CS2 script 2600.</p>
 *
 * <h3>Quantity &gt; 28</h3>
 * <p>With certain bonus equipment (Blacksmith's outfit, Smelting gauntlets, Varrock armour)
 * or completed quests, the server may allow producing more than 28 items in one run
 * because produced items are sent directly to the bank. The API trusts the server's
 * reported max quantity and never artificially caps at 28.</p>
 *
 * <h3>Typical usage:</h3>
 * <pre>{@code
 * Smithing smith = new Smithing(ctx.getGameAPI());
 *
 * // Wait for interface
 * smith.awaitOpen(3000);
 *
 * // Select quality and make
 * smith.selectQuality(Smithing.Quality.PLUS_5);
 * Thread.sleep(300);
 * smith.make();
 * }</pre>
 *
 * @see Production
 * @see Bank
 */
public final class Smithing {

    private static final BotLogger log = LoggerFactory.getLogger(Smithing.class);

    // ========================== Interface ==========================

    /** Smithing/smelting interface ID. */
    public static final int INTERFACE_ID = 37;

    // ========================== Components ==========================

    /** Make button component. */
    public static final int COMP_MAKE_BUTTON = 163;
    /** Quantity slider component (sub:0=decrease, sub:7=increase). */
    public static final int COMP_QUANTITY_SLIDER = 34;
    /** Quantity display panel component. */
    public static final int COMP_QUANTITY_DISPLAY = 35;
    /** Product name text component (includes quantity). */
    public static final int COMP_PRODUCT_NAME = 40;
    /** Smithing header component (hidden when smelting). */
    public static final int COMP_SMITHING_HEADER = 5;

    /** Material category grid components (5 rows). */
    public static final int[] COMP_MATERIAL_GRIDS = {52, 62, 72, 82, 92};
    /** Product category grid components (5 rows). */
    public static final int[] COMP_PRODUCT_GRIDS = {103, 114, 125, 136, 147};

    // ========================== Component Hashes ==========================

    /** Hash for the Make button: {@code (37 << 16) | 163}. */
    public static final int HASH_MAKE_BUTTON = (INTERFACE_ID << 16) | COMP_MAKE_BUTTON;
    /** Hash for the quantity slider: {@code (37 << 16) | 34}. */
    public static final int HASH_QUANTITY_SLIDER = (INTERFACE_ID << 16) | COMP_QUANTITY_SLIDER;

    // ========================== Varps ==========================

    /** Varp: material dbrow (category/tier). */
    public static final int VARP_MATERIAL = 8331;
    /** Varp: product dbrow (what type of items). */
    public static final int VARP_PRODUCT = 8332;
    /** Varp: selected base item ID. */
    public static final int VARP_SELECTED_ITEM = 8333;
    /** Varp: current anvil/furnace location. */
    public static final int VARP_LOCATION = 8334;
    /** Varp: player coordinates. */
    public static final int VARP_PLAYER_COORDS = 8335;
    /** Varp: selected quantity / XP per action. */
    public static final int VARP_QUANTITY = 8336;

    // ========================== Varbits ==========================

    /** Varbit: selected quality tier (0=Base, 1=+1, 2=+2, 3=+3, 4=+4, 5=+5, 50=Burial). */
    public static final int VARBIT_QUALITY = 43239;
    /** Varbit: smithing outfit bonus state (varid 9517, bits 0-6). */
    public static final int VARBIT_OUTFIT_BONUS_1 = 47760;
    /** Varbit: smithing outfit bonus state (varid 9517, bits 7-13). */
    public static final int VARBIT_OUTFIT_BONUS_2 = 47761;
    /** Varbit: heat efficiency modifier. */
    public static final int VARBIT_HEAT_EFFICIENCY = 20138;

    // ========================== Enums ==========================

    /** Enum: smithing item → dbrow mapping. */
    public static final int ENUM_SMITHING = 2531;
    /** Enum: smelting item → dbrow mapping. */
    public static final int ENUM_SMELTING = 2530;
    /** Enum: material index → item ID (0=Bronze 2349, 1=Iron 2351, ... 10=Dark animica 57435). */
    public static final int ENUM_MATERIALS = 2532;

    // ========================== Bonus Equipment ==========================

    /** Smelting gauntlets — doubles gold bar smelting (Family Crest quest). */
    public static final int SMELTING_GAUNTLETS = 775;
    /** Blacksmith's helmet. */
    public static final int BLACKSMITH_HELMET = 25120;
    /** Blacksmith's top. */
    public static final int BLACKSMITH_TOP = 25121;
    /** Blacksmith's apron. */
    public static final int BLACKSMITH_APRON = 25122;
    /** Blacksmith's boots. */
    public static final int BLACKSMITH_BOOTS = 25123;
    /** Blacksmith's gloves. */
    public static final int BLACKSMITH_GLOVES = 25124;
    /** Modified blacksmith's helmet (enhanced version with teleport). */
    public static final int MODIFIED_BLACKSMITH_HELMET = 32194;
    /** Varrock armour 1 (basic achievement diary). */
    public static final int VARROCK_ARMOUR_1 = 11750;
    /** Varrock armour 2 (medium achievement diary). */
    public static final int VARROCK_ARMOUR_2 = 11751;
    /** Varrock armour 3 (hard achievement diary). */
    public static final int VARROCK_ARMOUR_3 = 11752;
    /** Varrock armour 4 (elite achievement diary). */
    public static final int VARROCK_ARMOUR_4 = 19682;

    /** All known smithing/smelting bonus equipment item IDs. */
    public static final int[] ALL_BONUS_ITEMS = {
            SMELTING_GAUNTLETS, BLACKSMITH_HELMET, BLACKSMITH_TOP, BLACKSMITH_APRON,
            BLACKSMITH_BOOTS, BLACKSMITH_GLOVES, MODIFIED_BLACKSMITH_HELMET,
            VARROCK_ARMOUR_1, VARROCK_ARMOUR_2, VARROCK_ARMOUR_3, VARROCK_ARMOUR_4
    };

    /** Blacksmith's outfit piece IDs (5 pieces for full set). */
    private static final int[] BLACKSMITH_OUTFIT = {
            BLACKSMITH_HELMET, BLACKSMITH_TOP, BLACKSMITH_APRON, BLACKSMITH_BOOTS, BLACKSMITH_GLOVES
    };

    /** Equipment inventory ID for checking worn items. */
    private static final int EQUIPMENT_INVENTORY_ID = 94;

    // ========================== Active Smithing — Varclients ==========================

    /** Varclient: inventory ID containing the active unfinished item. */
    public static final int VARC_ACTIVE_INVENTORY = 5121;
    /** Varclient: slot index of the active unfinished item. */
    public static final int VARC_ACTIVE_SLOT = 5122;

    // ========================== Active Smithing — Item Var IDs ==========================

    /** Item var: key into enum 15095 for the item being created. */
    public static final int ITEMVAR_CREATING = 43222;
    /** Item var: current progress (0 → max). */
    public static final int ITEMVAR_PROGRESS = 43223;
    /** Item var: experience remaining × 10 (divide by 10 for actual XP). */
    public static final int ITEMVAR_XP_LEFT = 43224;
    /** Item var: current heat level. */
    public static final int ITEMVAR_HEAT = 43225;

    // ========================== Active Smithing — Enums & Params ==========================

    /** Enum: maps item var 43222 key → item ID being created. */
    public static final int ENUM_CREATING_ITEM = 15095;
    /** Item param: max progress required to complete the item. */
    public static final int PARAM_MAX_PROGRESS = 7801;
    /** Item param: true if this is a smithing item. */
    public static final int PARAM_IS_SMITHING_ITEM = 7802;
    /** Item param: max upgrade count (0–5). */
    public static final int PARAM_MAX_UPGRADES = 7805;
    /** Item param: base (root) item reference. */
    public static final int PARAM_BASE_ITEM = 7806;
    /** Item param: next upgrade item in the chain. */
    public static final int PARAM_NEXT_UPGRADE = 7807;
    /** Item param: true if this is a burial item. */
    public static final int PARAM_IS_BURIAL = 7808;
    /** Item param: craft category object (47066 = Metal bank). */
    public static final int PARAM_CRAFT_CATEGORY = 2655;
    /** Item param: metal category ID (18–44) for heat tier thresholds. */
    public static final int PARAM_METAL_CATEGORY = 5456;
    /** Object ID identifying the "Metal bank" craft category. */
    public static final int METAL_BANK_OBJECT = 47066;

    // ========================== Active Smithing — Constants ==========================

    /** Item ID for "Unfinished smithing item" in the backpack. */
    public static final int UNFINISHED_SMITHING_ITEM = 47068;

    /**
     * Sequential item var indices returned by {@code getItemVars(inv, slot)}.
     * <p>The CS2 scripts use var IDs 43222–43225 via {@code INV_GETVAR}, but the RPC
     * returns raw packed values at sequential indices 0–3. Values at indices 1 and 3
     * are packed — each stores two fields in upper/lower 16 bits:</p>
     * <ul>
     *   <li>0 → reserved (always 0 for regular smithing)</li>
     *   <li>1 → packed: lower 16 = creating item enum key (CS2 var 43222),
     *           upper 16 = current progress (CS2 var 43223)</li>
     *   <li>2 → XP remaining × 10 (CS2 var 43224, direct value)</li>
     *   <li>3 → packed: lower 16 = current heat (CS2 var 43225),
     *           upper 16 = metadata</li>
     * </ul>
     */
    public static final int SEQVAR_RESERVED = 0;
    /** Sequential var index for packed creating key (lower 16) + progress (upper 16). */
    public static final int SEQVAR_CREATING_PROGRESS = 1;
    /** Sequential var index for XP remaining (value × 10, direct). */
    public static final int SEQVAR_XP_LEFT = 2;
    /** Sequential var index for packed heat (lower 16) + metadata (upper 16). */
    public static final int SEQVAR_HEAT_PACKED = 3;

    /** Backpack inventory ID. */
    public static final int BACKPACK_INVENTORY = 93;
    /** Heat reduced per hammer strike. */
    public static final int HEAT_PER_STRIKE = 10;
    /** Base progress per strike at 0% heat. */
    public static final int BASE_PROGRESS_PER_STRIKE = 10;
    /** Smithing skill ID. */
    public static final int SKILL_SMITHING = 13;
    /** Firemaking skill ID. */
    public static final int SKILL_FIREMAKING = 11;

    /**
     * Heat tier thresholds from CS2 script 2546.
     * <p>Each row: {metalCategory, firstBonusLevel, secondBonusLevel, mediumSpeedLevel, fullSpeedLevel}.
     * Material bonus: +50 if smithingLevel ≥ firstBonusLevel, +50 more if ≥ secondBonusLevel.</p>
     */
    private static final int[][] HEAT_THRESHOLDS = {
        // category, +50 at, +100 at, ×4 rate at, ×2 rate at
        {18,   4,  -1,   3,   7},  // Bronze (second bonus N/A — only 1 threshold)
        {19,  11,  15,  12,  18},  // Iron
        {20,  24,  29,  22,  26},  // Steel
        {22,  32,  33,  35,  39},  // Mithril
        {23,  43,  45,  42,  44},  // Adamant
        {25,  52,  55,  54,  58},  // Rune
        {26,  64,  66,  67,  69},  // Orikalkum
        {27,  71,  79,  73,  78},  // Necronium
        {28,  84,  88,  82,  85},  // Bane
        {29,  95,  97,  93,  96},  // Elder rune
        {44, 102, 109, 104, 107},  // Primal/Animica
    };

    // ========================== Instance ==========================

    private final GameAPI api;

    /**
     * Creates a new smithing/smelting interface wrapper.
     *
     * @param api the game API instance
     */
    public Smithing(GameAPI api) {
        this.api = api;
    }

    // ========================== State Queries ==========================

    /**
     * Check whether the smithing/smelting interface is currently open.
     *
     * @return {@code true} if interface 37 is open
     */
    public boolean isOpen() {
        return api.isInterfaceOpen(INTERFACE_ID);
    }

    /**
     * Check whether the interface is in smelting mode.
     * <p>Script 2600 positions comp(37, 30) at Y=39 for smelting, Y=69 for smithing.</p>
     *
     * @return {@code true} if the interface is in smelting mode
     */
    public boolean isSmelting() {
        if (!isOpen()) return false;
        // Script 2600 sets comp(37, 30) Y position: 39 for smelting, 69 for smithing
        var pos = api.getComponentPosition(INTERFACE_ID, 30);
        return pos != null && pos.y() == 39;
    }

    /**
     * Check whether the interface is in smithing mode (not smelting).
     *
     * @return {@code true} if the interface is in smithing mode
     */
    public boolean isSmithing() {
        return isOpen() && !isSmelting();
    }

    /**
     * Get the selected base item ID.
     *
     * @return the selected item ID from varp 8333, or -1 if not open
     */
    public int getSelectedItem() {
        if (!isOpen()) return -1;
        return api.getVarp(VARP_SELECTED_ITEM);
    }

    /**
     * Get the selected item name.
     *
     * @return the item name, or {@code null} if not open or unresolvable
     */
    public String getSelectedItemName() {
        int itemId = getSelectedItem();
        if (itemId <= 0) return null;
        ItemType type = api.getItemType(itemId);
        return type != null ? type.name() : null;
    }

    /**
     * Get the material dbrow value.
     *
     * @return the material dbrow from varp 8331, or -1 if not open
     */
    public int getMaterial() {
        if (!isOpen()) return -1;
        return api.getVarp(VARP_MATERIAL);
    }

    /**
     * Get the product dbrow value.
     *
     * @return the product dbrow from varp 8332, or -1 if not open
     */
    public int getProduct() {
        if (!isOpen()) return -1;
        return api.getVarp(VARP_PRODUCT);
    }

    /**
     * Get the current quantity / XP per action value.
     *
     * @return the quantity from varp 8336, or -1 if not open
     */
    public int getQuantity() {
        if (!isOpen()) return -1;
        return api.getVarp(VARP_QUANTITY);
    }

    /**
     * Get the product name text from the interface.
     *
     * @return the product name with quantity, or {@code null} if not open
     */
    public String getProductName() {
        if (!isOpen()) return null;
        return api.getComponentText(INTERFACE_ID, COMP_PRODUCT_NAME);
    }

    /**
     * Get the currently selected quality tier.
     *
     * @return the quality tier, or {@code null} if not open or unknown tier
     */
    public Quality getQuality() {
        if (!isOpen()) return null;
        int varbitValue = api.getVarbit(VARBIT_QUALITY);
        return Quality.fromVarbit(varbitValue);
    }

    /**
     * Get the raw quality tier varbit value.
     *
     * @return the varbit 43239 value, or -1 if not open
     */
    public int getQualityTier() {
        if (!isOpen()) return -1;
        return api.getVarbit(VARBIT_QUALITY);
    }

    /**
     * Get the location varp (anvil/furnace).
     *
     * @return the location from varp 8334, or -1 if not open
     */
    public int getLocation() {
        if (!isOpen()) return -1;
        return api.getVarp(VARP_LOCATION);
    }

    // ========================== Actions ==========================

    /**
     * Click the Make button to start smithing/smelting.
     * <p>Uses action type COMPONENT (57), not DIALOGUE.</p>
     * <p>Queues: {@code GameAction(COMPONENT, 1, -1, (37<<16)|163)}</p>
     *
     * @return {@code true} if the action was queued
     */
    public boolean make() {
        if (!isOpen()) { log.warn("[Smithing] Cannot make: interface is not open"); return false; }
        api.queueAction(new GameAction(ActionTypes.COMPONENT, 1, -1, HASH_MAKE_BUTTON));
        return true;
    }

    /**
     * Click the slider decrease button (quantity -1).
     * <p>Queues: {@code GameAction(COMPONENT, 1, 0, (37<<16)|34)}</p>
     *
     * @return {@code true} if the action was queued
     */
    public boolean decreaseQuantity() {
        if (!isOpen()) { log.warn("[Smithing] Cannot decrease: interface is not open"); return false; }
        api.queueAction(new GameAction(ActionTypes.COMPONENT, 1, 0, HASH_QUANTITY_SLIDER));
        return true;
    }

    /**
     * Click the slider increase button (quantity +1).
     * <p>Queues: {@code GameAction(COMPONENT, 1, 7, (37<<16)|34)}</p>
     *
     * @return {@code true} if the action was queued
     */
    public boolean increaseQuantity() {
        if (!isOpen()) { log.warn("[Smithing] Cannot increase: interface is not open"); return false; }
        api.queueAction(new GameAction(ActionTypes.COMPONENT, 1, 7, HASH_QUANTITY_SLIDER));
        return true;
    }

    /**
     * Select a quality tier.
     * <p>Queues: {@code GameAction(COMPONENT, 1, -1, (37<<16)|quality.componentId)}</p>
     *
     * @param quality the quality tier to select
     * @return {@code true} if the action was queued
     */
    public boolean selectQuality(Quality quality) {
        if (!isOpen()) { log.warn("[Smithing] Cannot select quality: interface is not open"); return false; }
        if (quality == null) { log.warn("[Smithing] Cannot select quality: null quality"); return false; }
        int hash = (INTERFACE_ID << 16) | quality.getComponentId();
        api.queueAction(new GameAction(ActionTypes.COMPONENT, 1, -1, hash));
        return true;
    }

    /**
     * Select a material from one of the 5 material grid rows.
     * <p>Grid components: comp(37, 52/62/72/82/92).</p>
     * <p>Queues: {@code GameAction(COMPONENT, 1, subIndex, (37<<16)|gridCompId)}</p>
     *
     * @param gridIndex the 0-based grid row index (0–4)
     * @param subIndex  the sub-component index within the grid
     * @return {@code true} if the action was queued
     */
    public boolean selectMaterial(int gridIndex, int subIndex) {
        if (!isOpen()) { log.warn("[Smithing] Cannot select material: interface is not open"); return false; }
        if (gridIndex < 0 || gridIndex >= COMP_MATERIAL_GRIDS.length) {
            log.warn("[Smithing] Cannot select material: gridIndex {} out of range (0-4)", gridIndex);
            return false;
        }
        int hash = (INTERFACE_ID << 16) | COMP_MATERIAL_GRIDS[gridIndex];
        api.queueAction(new GameAction(ActionTypes.COMPONENT, 1, subIndex, hash));
        return true;
    }

    /**
     * Select a product from one of the 5 product grid rows.
     * <p>Grid components: comp(37, 103/114/125/136/147).</p>
     * <p>Queues: {@code GameAction(COMPONENT, 1, subIndex, (37<<16)|gridCompId)}</p>
     *
     * @param gridIndex the 0-based grid row index (0–4)
     * @param subIndex  the sub-component index within the grid
     * @return {@code true} if the action was queued
     */
    public boolean selectProduct(int gridIndex, int subIndex) {
        if (!isOpen()) { log.warn("[Smithing] Cannot select product: interface is not open"); return false; }
        if (gridIndex < 0 || gridIndex >= COMP_PRODUCT_GRIDS.length) {
            log.warn("[Smithing] Cannot select product: gridIndex {} out of range (0-4)", gridIndex);
            return false;
        }
        int hash = (INTERFACE_ID << 16) | COMP_PRODUCT_GRIDS[gridIndex];
        api.queueAction(new GameAction(ActionTypes.COMPONENT, 1, subIndex, hash));
        return true;
    }

    // ========================== Grid Discovery ==========================

    /**
     * Get all material entries from a specific material grid row.
     *
     * @param gridIndex the 0-based grid row index (0–4)
     * @return a list of grid entries, or empty if not open or invalid index
     */
    public List<GridEntry> getMaterialEntries(int gridIndex) {
        if (!isOpen() || gridIndex < 0 || gridIndex >= COMP_MATERIAL_GRIDS.length) return List.of();
        return readGridEntries(COMP_MATERIAL_GRIDS[gridIndex], gridIndex);
    }

    /**
     * Get all product entries from a specific product grid row.
     *
     * @param gridIndex the 0-based grid row index (0–4)
     * @return a list of grid entries, or empty if not open or invalid index
     */
    public List<GridEntry> getProductEntries(int gridIndex) {
        if (!isOpen() || gridIndex < 0 || gridIndex >= COMP_PRODUCT_GRIDS.length) return List.of();
        return readGridEntries(COMP_PRODUCT_GRIDS[gridIndex], gridIndex);
    }

    /**
     * Get all material entries across all 5 grid rows.
     *
     * @return a list of all material grid entries
     */
    public List<GridEntry> getAllMaterialEntries() {
        List<GridEntry> all = new ArrayList<>();
        for (int i = 0; i < COMP_MATERIAL_GRIDS.length; i++) {
            all.addAll(getMaterialEntries(i));
        }
        return all;
    }

    /**
     * Get all product entries across all 5 grid rows.
     *
     * @return a list of all product grid entries
     */
    public List<GridEntry> getAllProductEntries() {
        List<GridEntry> all = new ArrayList<>();
        for (int i = 0; i < COMP_PRODUCT_GRIDS.length; i++) {
            all.addAll(getProductEntries(i));
        }
        return all;
    }

    private List<GridEntry> readGridEntries(int componentId, int gridIndex) {
        try {
            List<Component> children = api.getComponentChildren(INTERFACE_ID, componentId);
            List<GridEntry> entries = new ArrayList<>();
            for (Component c : children) {
                if (c.itemId() > 0) {
                    entries.add(new GridEntry(gridIndex, c.subComponentId(), c.itemId()));
                }
            }
            return entries;
        } catch (Exception e) {
            return List.of();
        }
    }

    // ========================== Bonus Detection ==========================

    /**
     * Get the smithing outfit bonus state from varbit 47760.
     *
     * @return the outfit bonus bitfield value
     */
    public int getOutfitBonusState() {
        return api.getVarbit(VARBIT_OUTFIT_BONUS_1);
    }

    /**
     * Get the heat efficiency modifier from varbit 20138.
     *
     * @return the heat efficiency value
     */
    public int getHeatEfficiency() {
        return api.getVarbit(VARBIT_HEAT_EFFICIENCY);
    }

    /**
     * Check if a specific bonus item is currently equipped.
     *
     * @param itemId the item ID to check
     * @return {@code true} if the item is in the equipment inventory
     */
    public boolean isWearingBonusItem(int itemId) {
        try {
            List<InventoryItem> items = api.queryInventoryItems(
                    InventoryFilter.builder().inventoryId(EQUIPMENT_INVENTORY_ID).itemId(itemId).build());
            return items != null && !items.isEmpty();
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Get all currently equipped bonus items from {@link #ALL_BONUS_ITEMS}.
     *
     * @return a list of equipped bonus item IDs
     */
    public List<Integer> getActiveBonusItems() {
        List<Integer> active = new ArrayList<>();
        for (int itemId : ALL_BONUS_ITEMS) {
            if (isWearingBonusItem(itemId)) active.add(itemId);
        }
        return active;
    }

    /**
     * Check if all 5 pieces of the Blacksmith's outfit are equipped.
     *
     * @return {@code true} if the full outfit is worn
     */
    public boolean isWearingBlacksmithOutfit() {
        for (int id : BLACKSMITH_OUTFIT) {
            if (!isWearingBonusItem(id)) return false;
        }
        return true;
    }

    /**
     * Check if any variant of the Varrock armour is equipped.
     *
     * @return {@code true} if any Varrock armour (1-4) is worn
     */
    public boolean isWearingVarrockArmour() {
        return isWearingBonusItem(VARROCK_ARMOUR_1)
                || isWearingBonusItem(VARROCK_ARMOUR_2)
                || isWearingBonusItem(VARROCK_ARMOUR_3)
                || isWearingBonusItem(VARROCK_ARMOUR_4);
    }

    /**
     * Check if the current max quantity exceeds the standard backpack size of 28.
     * <p>This indicates items will be sent directly to the bank, likely due to
     * bonus equipment or quest rewards.</p>
     *
     * @return {@code true} if the server allows making more than 28 items
     */
    public boolean canExceedBackpackLimit() {
        return getQuantity() > 28;
    }

    // ========================== Active Smithing — Detection ==========================

    /**
     * Check whether the player is actively smithing (has unfinished items in backpack).
     * <p>Scans the backpack for item ID 47068 ("Unfinished smithing item"). This is
     * independent of the selection interface (37) being open — the interface closes
     * once smithing begins.</p>
     * <p>Note: varclients 5121/5122 are only set while hovering over an item tooltip
     * and cannot be used for continuous monitoring.</p>
     *
     * @return {@code true} if any backpack slot contains an unfinished smithing item
     */
    public boolean isActivelySmithing() {
        try {
            for (int slot = 0; slot < 28; slot++) {
                if (!api.isInventoryItemValid(BACKPACK_INVENTORY, slot)) continue;
                InventoryItem item = api.getInventoryItem(BACKPACK_INVENTORY, slot);
                if (item != null && item.itemId() == UNFINISHED_SMITHING_ITEM) return true;
            }
            return false;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Get the inventory ID referenced by the active smithing varclient.
     * <p>Note: only valid while hovering over an unfinished item (tooltip active).</p>
     *
     * @return the inventory ID from varc 5121
     */
    public int getActiveInventoryId() {
        return api.getVarcInt(VARC_ACTIVE_INVENTORY);
    }

    /**
     * Get the slot index referenced by the active smithing varclient.
     * <p>Note: only valid while hovering over an unfinished item (tooltip active).</p>
     *
     * @return the slot index from varc 5122
     */
    public int getActiveSlot() {
        return api.getVarcInt(VARC_ACTIVE_SLOT);
    }

    // ========================== Active Smithing — Item Reading ==========================

    /**
     * Read the active unfinished item (the first one found in the backpack).
     * <p>Returns the first unfinished smithing item (ID 47068) found in the backpack.
     * When multiple unfinished items exist, the game always works on the first one.</p>
     *
     * @return the active item snapshot, or {@code null} if no unfinished items found
     */
    public UnfinishedItem getActiveItem() {
        try {
            for (int slot = 0; slot < 28; slot++) {
                if (!api.isInventoryItemValid(BACKPACK_INVENTORY, slot)) continue;
                InventoryItem item = api.getInventoryItem(BACKPACK_INVENTORY, slot);
                if (item != null && item.itemId() == UNFINISHED_SMITHING_ITEM) {
                    return readUnfinishedItem(BACKPACK_INVENTORY, slot);
                }
            }
        } catch (Exception e) {
            log.debug("[Smithing] Failed to get active item: {}", e.getMessage());
        }
        return null;
    }

    /**
     * Scan the entire backpack for all unfinished smithing items and read their progress.
     * <p>Each unfinished item has its own heat, progress, and XP tracked independently.
     * Uses {@code getItemVars()} to read sequential var indices 0–3.</p>
     *
     * @return a list of all unfinished items with per-item progress data
     */
    public List<UnfinishedItem> getAllUnfinishedItems() {
        List<UnfinishedItem> items = new ArrayList<>();
        for (int slot = 0; slot < 28; slot++) {
            try {
                if (!api.isInventoryItemValid(BACKPACK_INVENTORY, slot)) continue;
                InventoryItem item = api.getInventoryItem(BACKPACK_INVENTORY, slot);
                if (item == null || item.itemId() != UNFINISHED_SMITHING_ITEM) continue;
                UnfinishedItem ufi = readUnfinishedItem(BACKPACK_INVENTORY, slot);
                if (ufi != null) items.add(ufi);
            } catch (Exception ignored) {}
        }
        return items;
    }

    /**
     * Read an unfinished smithing item from a specific inventory slot using {@code getItemVars()}.
     * <p>The RPC returns sequential var IDs (0–3) rather than the CS2 var IDs (43222–43225).
     * Mapping: 0=creating, 1=progress, 2=XP×10, 3=heat.</p>
     */
    private UnfinishedItem readUnfinishedItem(int inv, int slot) {
        try {
            InventoryItem item = api.getInventoryItem(inv, slot);
            if (item == null || item.itemId() <= 0) return null;

            // Read all item vars at once — returns sequential IDs 0-3
            List<ItemVar> vars = api.getItemVars(inv, slot);
            if (vars == null || vars.isEmpty()) return null;

            // v1 is packed: lower 16 bits = creating key, upper 16 bits = progress
            int v1 = getVarValue(vars, SEQVAR_CREATING_PROGRESS);
            int creatingKey = v1 & 0xFFFF;
            int progress = (v1 >>> 16) & 0xFFFF;

            // v2 is XP remaining × 10 (direct value)
            int xpLeftRaw = getVarValue(vars, SEQVAR_XP_LEFT);

            // v3 is packed: lower 16 bits = heat
            int heat = getVarValue(vars, SEQVAR_HEAT_PACKED) & 0xFFFF;

            return buildUnfinishedItem(slot, item.itemId(), creatingKey, progress, heat, xpLeftRaw);
        } catch (Exception e) {
            return null;
        }
    }

    private UnfinishedItem buildUnfinishedItem(int slot, int itemId,
                                                int creatingKey, int progress, int heat,
                                                int xpLeftRaw) {
        // Resolve creating item via enum 15095
        int creatingItemId = resolveCreatingItemId(creatingKey);
        String creatingName = creatingItemId > 0 ? resolveItemName(creatingItemId) : null;

        // Get max progress from item param 7801 of the target item
        int maxProgress = 0;
        if (creatingItemId > 0) {
            maxProgress = getIntParam(creatingItemId, PARAM_MAX_PROGRESS);
        }
        int progressPercent = maxProgress > 0 ? (progress * 100) / maxProgress : 0;

        // XP left — stored as value × 10
        int xpLeft = xpLeftRaw / 10;

        return new UnfinishedItem(slot, itemId, creatingItemId,
                creatingName, progress, maxProgress, progressPercent, heat, xpLeft);
    }

    /**
     * Get the value of a specific sequential var ID from a list of item vars.
     *
     * @param vars  the item var list from {@code getItemVars()}
     * @param varId the sequential var ID (0–3)
     * @return the value, or 0 if not found
     */
    private static int getVarValue(List<ItemVar> vars, int varId) {
        for (ItemVar v : vars) {
            if (v.varId() == varId) return v.value();
        }
        return 0;
    }

    // ========================== Active Smithing — Heat ==========================

    /**
     * Get the current heat of the active (first) unfinished item.
     *
     * @return the heat value, or 0 if no unfinished items found
     */
    public int getCurrentHeat() {
        UnfinishedItem active = getActiveItem();
        return active != null ? active.currentHeat() : 0;
    }

    /**
     * Calculate the maximum heat capacity based on player levels and metal category.
     * <p>Formula: {@code 300 + (smithingLevel × 3) + (firemakingLevel × 3) + materialBonus}</p>
     * <p>Material bonus is +50 or +100 depending on level vs metal tier thresholds (CS2 script 2546).</p>
     *
     * @return the maximum heat, or 0 if levels cannot be read
     */
    public int getMaxHeat() {
        try {
            PlayerStat smith = api.getPlayerStat(SKILL_SMITHING);
            PlayerStat fm = api.getPlayerStat(SKILL_FIREMAKING);
            if (smith == null || fm == null) return 0;
            int base = 300 + (smith.level() * 3) + (fm.level() * 3);

            // Try to resolve metal category from active item
            UnfinishedItem active = getActiveItem();
            if (active != null && active.creatingItemId() > 0) {
                int category = getMetalCategory(active.creatingItemId());
                base += getMaterialBonus(category, smith.level());
            }
            return base;
        } catch (Exception e) { return 0; }
    }

    /**
     * Calculate max heat for a specific item being created.
     *
     * @param creatingItemId the item ID being created
     * @return the maximum heat capacity
     */
    public int getMaxHeatForItem(int creatingItemId) {
        try {
            PlayerStat smith = api.getPlayerStat(SKILL_SMITHING);
            PlayerStat fm = api.getPlayerStat(SKILL_FIREMAKING);
            if (smith == null || fm == null) return 0;
            int base = 300 + (smith.level() * 3) + (fm.level() * 3);
            int category = getMetalCategory(creatingItemId);
            return base + getMaterialBonus(category, smith.level());
        } catch (Exception e) { return 0; }
    }

    /**
     * Get the heat percentage for the active item.
     *
     * @return heat percentage (0–100), or 0 if not actively smithing
     */
    public int getHeatPercent() {
        int max = getMaxHeat();
        if (max <= 0) return 0;
        return (getCurrentHeat() * 100) / max;
    }

    /**
     * Get the current heat band name.
     *
     * @return "High" (67–100%), "Medium" (34–66%), "Low" (1–33%), or "Zero" (0%)
     */
    public String getHeatBand() {
        int pct = getHeatPercent();
        if (pct >= 67) return "High";
        if (pct >= 34) return "Medium";
        if (pct >= 1) return "Low";
        return "Zero";
    }

    /**
     * Get the progress per strike based on current heat band.
     *
     * @return 20 (High), 16 (Medium), 13 (Low), or 10 (Zero)
     */
    public int getProgressPerStrike() {
        int pct = getHeatPercent();
        if (pct >= 67) return 20;
        if (pct >= 34) return 16;
        if (pct >= 1) return 13;
        return 10;
    }

    /**
     * Calculate the reheating rate at a forge (heat gained per tick).
     * <p>Formula: {@code 50 + floor(smithingLevel / 2 + firemakingLevel / 2)}</p>
     *
     * @return heat per tick while reheating, or 0 if levels cannot be read
     */
    public int getReheatingRate() {
        try {
            PlayerStat smith = api.getPlayerStat(SKILL_SMITHING);
            PlayerStat fm = api.getPlayerStat(SKILL_FIREMAKING);
            if (smith == null || fm == null) return 0;
            return 50 + (smith.level() / 2) + (fm.level() / 2);
        } catch (Exception e) { return 0; }
    }

    // ========================== Active Smithing — Progress ==========================

    /**
     * Get the current progress of the active (first) unfinished item.
     *
     * @return progress value, or 0 if no unfinished items found
     */
    public int getCurrentProgress() {
        UnfinishedItem active = getActiveItem();
        return active != null ? active.currentProgress() : 0;
    }

    /**
     * Get the maximum progress required to complete the active item.
     *
     * @return max progress from item param 7801, or 0 if unavailable
     */
    public int getMaxProgress() {
        UnfinishedItem active = getActiveItem();
        return active != null ? active.maxProgress() : 0;
    }

    /**
     * Get the progress percentage for the active item.
     *
     * @return progress percentage (0–100), or 0 if not actively smithing
     */
    public int getActiveProgressPercent() {
        UnfinishedItem active = getActiveItem();
        return active != null ? active.progressPercent() : 0;
    }

    /**
     * Get the experience remaining in the active (first) unfinished item.
     *
     * @return XP remaining, or 0 if no unfinished items found
     */
    public int getExperienceLeft() {
        UnfinishedItem active = getActiveItem();
        return active != null ? active.experienceLeft() : 0;
    }

    // ========================== Active Smithing — Item Resolution ==========================

    /**
     * Resolve the item being created from an enum 15095 key.
     *
     * @param enumKey the value from item var 43222
     * @return the resolved item ID, or -1 if unresolvable
     */
    public int resolveCreatingItemId(int enumKey) {
        if (enumKey <= 0) return -1;
        try {
            EnumType e = api.getEnumType(ENUM_CREATING_ITEM);
            if (e == null || e.entries() == null) return -1;
            Object val = e.entries().get(String.valueOf(enumKey));
            if (val instanceof Number n) return n.intValue();
            return -1;
        } catch (Exception e) { return -1; }
    }

    /**
     * Resolve an item name from an item ID.
     *
     * @param itemId the item ID
     * @return the item name, or {@code null} if unresolvable
     */
    public String resolveItemName(int itemId) {
        if (itemId <= 0) return null;
        try {
            ItemType type = api.getItemType(itemId);
            return type != null ? type.name() : null;
        } catch (Exception e) { return null; }
    }

    /**
     * Resolve the metal category for an item by following the item param chain.
     * <p>Chain: param 7802 (is smithing item?) → param 7806 (base item) → param 2655
     * (craft category = 47066?) → param 5456 (metal category).</p>
     *
     * @param itemId the item ID (can be unfinished or the target product)
     * @return the metal category (18–44), or 0 if unresolvable
     */
    public int getMetalCategory(int itemId) {
        try {
            ItemType type = api.getItemType(itemId);
            if (type == null || type.params() == null) return 0;
            Map<String, Object> params = type.params();

            // If this is a smithing item (param 7802), follow to base item (param 7806)
            int resolvedId = itemId;
            if (getParamBool(params, PARAM_IS_SMITHING_ITEM)) {
                int baseItem = getParamInt(params, PARAM_BASE_ITEM);
                if (baseItem > 0) {
                    resolvedId = baseItem;
                    type = api.getItemType(resolvedId);
                    if (type == null || type.params() == null) return 0;
                    params = type.params();
                } else {
                    // Fallback: use param 2655 directly
                    int craftCat = getParamInt(params, PARAM_CRAFT_CATEGORY);
                    if (craftCat == METAL_BANK_OBJECT) {
                        return getParamInt(params, PARAM_METAL_CATEGORY);
                    }
                }
            }

            // Check if the resolved item is a metal bank object
            int craftCat = getParamInt(params, PARAM_CRAFT_CATEGORY);
            if (craftCat == METAL_BANK_OBJECT) {
                return getParamInt(params, PARAM_METAL_CATEGORY);
            }
            return 0;
        } catch (Exception e) { return 0; }
    }

    /**
     * Calculate the material heat bonus for a given metal category and smithing level.
     *
     * @param metalCategory the metal category (18–44)
     * @param smithingLevel the player's smithing level
     * @return 0, +50, or +100 heat bonus
     */
    public static int getMaterialBonus(int metalCategory, int smithingLevel) {
        for (int[] row : HEAT_THRESHOLDS) {
            if (row[0] == metalCategory) {
                int bonus = 0;
                if (row[1] > 0 && smithingLevel >= row[1]) bonus += 50;
                if (row[2] > 0 && smithingLevel >= row[2]) bonus += 50;
                return bonus;
            }
        }
        return 0;
    }

    // ========================== Private Helpers ==========================

    private int getIntParam(int itemId, int paramId) {
        try {
            ItemType type = api.getItemType(itemId);
            if (type == null || type.params() == null) return 0;
            return getParamInt(type.params(), paramId);
        } catch (Exception e) { return 0; }
    }

    private static int getParamInt(Map<String, Object> params, int paramId) {
        Object v = params.get(String.valueOf(paramId));
        if (v instanceof Number n) return n.intValue();
        return 0;
    }

    private static boolean getParamBool(Map<String, Object> params, int paramId) {
        Object v = params.get(String.valueOf(paramId));
        if (v instanceof Boolean b) return b;
        if (v instanceof Number n) return n.intValue() != 0;
        return false;
    }

    // ========================== Convenience (Blocking) ==========================

    /**
     * Wait for the smithing/smelting interface to open within a timeout.
     * <p>Polls {@link #isOpen()} at 100ms intervals. Sleeps on the current virtual thread.</p>
     *
     * @param timeoutMs maximum time to wait in milliseconds
     * @return {@code true} if the interface opened within the timeout
     */
    public boolean awaitOpen(int timeoutMs) {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (!isOpen() && System.currentTimeMillis() < deadline) {
            Delays.sleep(100);
        }
        return isOpen();
    }

    /**
     * Select a quality tier, wait for the UI to update, then click Make.
     * <p>Blocking — sleeps on the current virtual thread.</p>
     *
     * @param quality the quality tier to select
     * @return {@code true} if the make action was queued
     */
    public boolean selectQualityAndMake(Quality quality) {
        if (!selectQuality(quality)) return false;
        Delays.sleep(Delays.randomDelay());
        return make();
    }

    // ========================== Records ==========================

    /**
     * Represents an entry in a material or product grid.
     *
     * @param gridIndex the 0-based grid row index (0–4)
     * @param subIndex  the sub-component index within the grid
     * @param itemId    the item ID
     */
    public record GridEntry(int gridIndex, int subIndex, int itemId) {}

    /**
     * Snapshot of an unfinished smithing item's state.
     *
     * @param slot            backpack slot index (0–27)
     * @param itemId          the unfinished item's item ID
     * @param creatingItemId  resolved target item ID (what it becomes when complete)
     * @param creatingName    target item name, or {@code null}
     * @param currentProgress current progress (0 → maxProgress)
     * @param maxProgress     max progress from item param 7801
     * @param progressPercent calculated progress percentage (0–100)
     * @param currentHeat     current heat level
     * @param experienceLeft  XP remaining (item var 43224 / 10)
     */
    public record UnfinishedItem(
            int slot,
            int itemId,
            int creatingItemId,
            String creatingName,
            int currentProgress,
            int maxProgress,
            int progressPercent,
            int currentHeat,
            int experienceLeft
    ) {}

    // ========================== Quality Enum ==========================

    /**
     * Quality tiers for the smithing interface.
     * <p>Each tier maps to a varbit value and a component ID for the tier button.</p>
     */
    public enum Quality {
        /** Base quality (+0). */
        BASE(0, 149),
        /** +1 quality. */
        PLUS_1(1, 161),
        /** +2 quality. */
        PLUS_2(2, 159),
        /** +3 quality. */
        PLUS_3(3, 157),
        /** +4 quality. */
        PLUS_4(4, 155),
        /** +5 quality. */
        PLUS_5(5, 153),
        /** Burial quality (special). */
        BURIAL(50, 151);

        private final int varbitValue;
        private final int componentId;

        Quality(int varbitValue, int componentId) {
            this.varbitValue = varbitValue;
            this.componentId = componentId;
        }

        /** Returns the varbit value stored in varbit 43239 for this tier. */
        public int getVarbitValue() { return varbitValue; }

        /** Returns the interface component ID for this tier's button. */
        public int getComponentId() { return componentId; }

        /** Returns the component hash for this tier's button: {@code (37 << 16) | componentId}. */
        public int getComponentHash() { return (37 << 16) | componentId; }

        /**
         * Look up a quality tier by its varbit value.
         *
         * @param varbitValue the varbit 43239 value
         * @return the matching quality, or {@code null} if unknown
         */
        public static Quality fromVarbit(int varbitValue) {
            for (Quality q : values()) {
                if (q.varbitValue == varbitValue) return q;
            }
            return null;
        }
    }
}
