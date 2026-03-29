package com.botwithus.bot.api.inventory;

import com.botwithus.bot.api.GameAPI;
import com.botwithus.bot.api.antiban.Delays;
import com.botwithus.bot.api.inventory.Banking.Bank;
import com.botwithus.bot.api.log.BotLogger;
import com.botwithus.bot.api.log.LoggerFactory;
import com.botwithus.bot.api.model.Component;
import com.botwithus.bot.api.model.EnumType;
import com.botwithus.bot.api.model.GameAction;
import com.botwithus.bot.api.model.ItemType;

import java.util.ArrayList;
import java.util.List;

/**
 * Provides access to the RS3 production/make-x interface (interfaces 1370 + 1371).
 * <p>This unified interface opens when the player initiates a production action such as
 * crafting (needle + leather), fletching (knife + logs), cooking (food on range/fire),
 * smithing (bar on anvil, NOT the smithing interface 37), herblore (herb + vial),
 * and similar skills.</p>
 *
 * <h3>Two-Interface System</h3>
 * <ul>
 *   <li><b>1370</b> — Product detail panel: icon, name, XP/value, requirements, ingredients, Make button</li>
 *   <li><b>1371</b> — Category + product grid: product selection grid, quantity slider, category dropdown</li>
 * </ul>
 *
 * <h3>Product Grid Layout</h3>
 * <p>Each product in comp(1371,22) creates 4 sub-components:</p>
 * <ul>
 *   <li>{@code index*4+0} — Background graphic</li>
 *   <li>{@code index*4+1} — Clickable overlay (handles selection)</li>
 *   <li>{@code index*4+2} — Item icon (carries the itemId)</li>
 *   <li>{@code index*4+3} — Status indicator (members/requirements icon)</li>
 * </ul>
 *
 * <h3>Actions</h3>
 * <ul>
 *   <li><b>Make</b>: action type 30 (DIALOGUE), hash {@code (1370<<16)|30 = 89784350}</li>
 *   <li><b>Select product</b>: action type 57 (COMPONENT), sub={@code index*4+1}, hash {@code (1371<<16)|22}</li>
 *   <li><b>Decrease qty</b>: action type 57, sub=0, hash {@code (1371<<16)|19}</li>
 *   <li><b>Increase qty</b>: action type 57, sub=7, hash {@code (1371<<16)|19}</li>
 * </ul>
 *
 * <h3>Typical usage:</h3>
 * <pre>{@code
 * Production prod = new Production(ctx.getGameAPI());
 *
 * // Wait for the production interface to open
 * prod.awaitOpen(3000);
 *
 * // Select a product and make all
 * prod.selectProduct("Maple shortbow (u)");
 * Thread.sleep(300);
 * prod.setQuantity(prod.getMaxQuantity());
 * prod.make();
 *
 * // Or use the blocking convenience method
 * prod.selectAndMake("Maple shortbow (u)");
 * }</pre>
 *
 * @see Bank
 * @see Backpack
 */
public final class Production {

    private static final BotLogger log = LoggerFactory.getLogger(Production.class);

    // ========================== Interface IDs ==========================

    /** Product detail panel interface ID (icon, name, Make button). */
    public static final int DETAIL_INTERFACE = 1370;
    /** Category + product grid interface ID (product selection, slider, dropdown). */
    public static final int GRID_INTERFACE = 1371;

    // ========================== Component IDs ==========================

    /** Product grid component within the grid interface (4 sub-components per product). */
    public static final int COMP_PRODUCT_GRID = 22;
    /** Quantity slider component within the grid interface (sub:0=decrease, sub:7=increase). */
    public static final int COMP_QUANTITY_SLIDER = 19;
    /** Make button component within the detail interface. */
    public static final int COMP_MAKE_BUTTON = 30;
    /** Category dropdown button component within the grid interface. */
    public static final int COMP_CATEGORY_DROPDOWN = 28;

    // ========================== Component Hashes ==========================

    /** Hash for the Make button: {@code (1370 << 16) | 30 = 89784350}. */
    public static final int HASH_MAKE_BUTTON = (DETAIL_INTERFACE << 16) | COMP_MAKE_BUTTON;
    /** Hash for the product grid: {@code (1371 << 16) | 22}. */
    public static final int HASH_PRODUCT_GRID = (GRID_INTERFACE << 16) | COMP_PRODUCT_GRID;
    /** Hash for the quantity slider: {@code (1371 << 16) | 19 = 89849875}. */
    public static final int HASH_QUANTITY_SLIDER = (GRID_INTERFACE << 16) | COMP_QUANTITY_SLIDER;
    /** Hash for the category dropdown button: {@code (1371 << 16) | 28 = 89849884}. */
    public static final int HASH_CATEGORY_DROPDOWN = (GRID_INTERFACE << 16) | COMP_CATEGORY_DROPDOWN;
    /** Hash for the dropdown popup clickable entries: {@code (1477 << 16) | 896 = 96797568}. */
    public static final int HASH_DROPDOWN_POPUP = (1477 << 16) | 896;
    /** Hash for the dropdown popup text entries: {@code (1477 << 16) | 895 = 96797567}. */
    public static final int HASH_DROPDOWN_TEXT = (1477 << 16) | 895;

    // ========================== Varps ==========================

    /** Varp: category enum ID (set by server on interface open). */
    public static final int VARP_CATEGORY_ENUM = 1168;
    /** Varp: product list enum (maps index → item object). */
    public static final int VARP_PRODUCT_LIST_ENUM = 1169;
    /** Varp: currently selected product item ID. */
    public static final int VARP_SELECTED_PRODUCT = 1170;
    /** Varp: category dropdown enum ID. */
    public static final int VARP_CATEGORY_DROPDOWN = 7881;
    /** Varp: maximum producible quantity (calculated by script7121). */
    public static final int VARP_MAX_QUANTITY = 8846;
    /** Varp: currently chosen quantity (slider value). */
    public static final int VARP_CHOSEN_QUANTITY = 8847;

    // ========================== CS2 Script IDs ==========================

    /** CS2 script 6585: sets varplayer_8847 (chosen quantity). Args: {@code (int targetQty)}. */
    private static final int SCRIPT_SET_QUANTITY = 6585;
    /** CS2 script 7147: rebuilds slider display and sets "Make N ItemName" text. No args. */
    private static final int SCRIPT_REFRESH_SLIDER = 7147;

    // ========================== Progress Interface (1251) ==========================

    /** Production progress overlay interface ID (shown while actively crafting). */
    public static final int PROGRESS_INTERFACE = 1251;
    /** Progress icon component. */
    public static final int COMP_PROGRESS_ICON = 0;
    /** Progress item name text component. */
    public static final int COMP_PROGRESS_NAME = 1;
    /** Progress bar component. */
    public static final int COMP_PROGRESS_BAR = 23;
    /** Progress counter text component (displays "N/Total"). */
    public static final int COMP_PROGRESS_COUNTER = 27;
    /** Remaining time text component (displays "30s", "Done", or "-"). */
    public static final int COMP_PROGRESS_TIME = 10;
    /** Stop/cancel button (visible while producing). */
    public static final int COMP_PROGRESS_STOP = 14;
    /** Completion button (visible when done). */
    public static final int COMP_PROGRESS_DONE = 13;
    /** Hash for the stop button: {@code (1251 << 16) | 14}. */
    public static final int HASH_STOP_BUTTON = (PROGRESS_INTERFACE << 16) | COMP_PROGRESS_STOP;
    /** Hash for the completion button: {@code (1251 << 16) | 13}. */
    public static final int HASH_DONE_BUTTON = (PROGRESS_INTERFACE << 16) | COMP_PROGRESS_DONE;

    /** Varclient: total items to make in current production run. */
    public static final int VARC_TOTAL_TO_MAKE = 2228;
    /** Varclient: items remaining to make (decremented during production). */
    public static final int VARC_REMAINING = 2229;
    /** Varclient: crafting speed modifier. */
    public static final int VARC_SPEED_MODIFIER = 2227;
    /** Varp: currently producing product item ID. */
    public static final int VARP_PROGRESS_PRODUCT = 1175;
    /** Varp: progress interface visibility flag (1 = hidden). */
    public static final int VARP_PROGRESS_VISIBILITY = 3034;

    // ========================== Instance ==========================

    private final GameAPI api;

    /**
     * Creates a new production interface wrapper.
     *
     * @param api the game API instance
     */
    public Production(GameAPI api) {
        this.api = api;
    }

    // ========================== State Queries ==========================

    /**
     * Check whether the production interface is currently open.
     *
     * @return {@code true} if the detail panel (1370) is open
     */
    public boolean isOpen() {
        return api.isInterfaceOpen(DETAIL_INTERFACE);
    }

    /**
     * Get the item ID of the currently selected product.
     *
     * @return the selected product item ID, or -1 if the interface is not open
     */
    public int getSelectedProductId() {
        if (!isOpen()) return -1;
        return api.getVarp(VARP_SELECTED_PRODUCT);
    }

    /**
     * Get the name of the currently selected product.
     *
     * @return the product name, or {@code null} if the interface is not open or the item type cannot be resolved
     */
    public String getSelectedProductName() {
        int itemId = getSelectedProductId();
        if (itemId <= 0) return null;
        ItemType type = api.getItemType(itemId);
        return type != null ? type.name() : null;
    }

    /**
     * Get the maximum producible quantity for the currently selected product.
     * <p>This value is calculated by CS2 script7121 based on available materials.</p>
     *
     * @return the max quantity, or -1 if the interface is not open
     */
    public int getMaxQuantity() {
        if (!isOpen()) return -1;
        return api.getVarp(VARP_MAX_QUANTITY);
    }

    /**
     * Get the currently chosen quantity (slider value).
     *
     * @return the chosen quantity, or -1 if the interface is not open
     */
    public int getChosenQuantity() {
        if (!isOpen()) return -1;
        return api.getVarp(VARP_CHOSEN_QUANTITY);
    }

    /**
     * Get the category enum ID from the server.
     *
     * @return the category enum ID, or -1 if the interface is not open
     */
    public int getCategoryEnumId() {
        if (!isOpen()) return -1;
        return api.getVarp(VARP_CATEGORY_ENUM);
    }

    /**
     * Get the product list enum ID.
     *
     * @return the product list enum ID, or -1 if the interface is not open
     */
    public int getProductListEnumId() {
        if (!isOpen()) return -1;
        return api.getVarp(VARP_PRODUCT_LIST_ENUM);
    }

    /**
     * Check if the production interface has category options (e.g. Fletching wood types, Smithing tiers).
     * <p>Categories are present when both varp 1168 and varp 7881 are not -1 (from script7117).</p>
     *
     * @return {@code true} if categories are available
     */
    public boolean hasCategories() {
        if (!isOpen()) return false;
        return api.getVarp(VARP_CATEGORY_ENUM) != -1 && api.getVarp(VARP_CATEGORY_DROPDOWN) != -1;
    }

    // ========================== Product Discovery ==========================

    /**
     * Returns all available products in the production grid.
     * <p>Products are identified by their item icon sub-components (every 4th at offset +2)
     * within comp(1371, 22). Only entries with a valid item ID are returned.</p>
     *
     * @return a list of product entries, or empty if the interface is not open
     */
    public List<ProductEntry> getProducts() {
        if (!isOpen()) return List.of();
        List<Component> children = api.getComponentChildren(GRID_INTERFACE, COMP_PRODUCT_GRID);
        List<ProductEntry> products = new ArrayList<>();
        // Each product occupies 4 sub-components: [bg, clickable, icon, status]
        // The icon at offset +2 carries the itemId
        for (int i = 0; i < children.size(); i++) {
            Component c = children.get(i);
            if (i % 4 == 2 && c.itemId() > 0) {
                products.add(new ProductEntry(i / 4, c.itemId()));
            }
        }
        return products;
    }

    /**
     * Returns the number of available products.
     *
     * @return the product count
     */
    public int getProductCount() {
        return getProducts().size();
    }

    /**
     * Find the grid index of a product by item ID.
     *
     * @param itemId the item ID to search for
     * @return the 0-based product index, or -1 if not found
     */
    public int getProductIndex(int itemId) {
        for (ProductEntry p : getProducts()) {
            if (p.itemId() == itemId) return p.index();
        }
        return -1;
    }

    /**
     * Find the grid index of a product by exact name (case-insensitive).
     *
     * @param name the product name
     * @return the 0-based product index, or -1 if not found
     */
    public int getProductIndex(String name) {
        if (name == null) return -1;
        String lower = name.toLowerCase();
        for (ProductEntry p : getProducts()) {
            ItemType type = api.getItemType(p.itemId());
            if (type != null && type.name() != null && type.name().toLowerCase().equals(lower)) {
                return p.index();
            }
        }
        return -1;
    }

    /**
     * Check if the production grid contains a product with the given item ID.
     *
     * @param itemId the item ID to search for
     * @return {@code true} if the product is available
     */
    public boolean hasProduct(int itemId) {
        return getProductIndex(itemId) >= 0;
    }

    /**
     * Check if the production grid contains a product with the given name.
     *
     * @param name the product name (case-insensitive)
     * @return {@code true} if the product is available
     */
    public boolean hasProduct(String name) {
        return getProductIndex(name) >= 0;
    }

    // ========================== Product Selection ==========================

    /**
     * Select a product in the grid by item ID.
     * <p>Queues: {@code GameAction(COMPONENT, 1, index*4+1, HASH_PRODUCT_GRID)}</p>
     *
     * @param itemId the item ID of the product to select
     * @return {@code true} if the action was queued
     */
    public boolean selectProduct(int itemId) {
        if (!isOpen()) { log.warn("[Production] Cannot select: interface is not open"); return false; }
        int index = getProductIndex(itemId);
        if (index < 0) { log.warn("[Production] Cannot select item {}: not found in product grid", itemId); return false; }
        return selectProductAt(index);
    }

    /**
     * Select a product in the grid by exact name (case-insensitive).
     * <p>Queues: {@code GameAction(COMPONENT, 1, index*4+1, HASH_PRODUCT_GRID)}</p>
     *
     * @param name the product name
     * @return {@code true} if the action was queued
     */
    public boolean selectProduct(String name) {
        if (!isOpen()) { log.warn("[Production] Cannot select: interface is not open"); return false; }
        int index = getProductIndex(name);
        if (index < 0) { log.warn("[Production] Cannot select '{}': not found in product grid", name); return false; }
        return selectProductAt(index);
    }

    /**
     * Select a product at a specific index in the grid.
     * <p>The clickable overlay sub-component is at {@code index * 4 + 1}.</p>
     * <p>Queues: {@code GameAction(COMPONENT, 1, index*4+1, HASH_PRODUCT_GRID)}</p>
     *
     * @param index the 0-based product index
     * @return {@code true} if the action was queued
     */
    public boolean selectProductAt(int index) {
        if (!isOpen()) { log.warn("[Production] Cannot select: interface is not open"); return false; }
        if (index < 0) { log.warn("[Production] Cannot select at index {}: invalid", index); return false; }
        int subComponentId = index * 4 + 1;
        api.queueAction(new GameAction(ActionTypes.COMPONENT, 1, subComponentId, HASH_PRODUCT_GRID));
        return true;
    }

    // ========================== Make ==========================

    /**
     * Click the Make button to start production of the currently selected product.
     * <p>Uses action type 30 (DIALOGUE), not 57 (COMPONENT).</p>
     * <p>Queues: {@code GameAction(DIALOGUE, 0, -1, 89784350)}</p>
     *
     * @return {@code true} if the action was queued
     */
    public boolean make() {
        if (!isOpen()) { log.warn("[Production] Cannot make: interface is not open"); return false; }
        api.queueAction(new GameAction(ActionTypes.DIALOGUE, 0, -1, HASH_MAKE_BUTTON));
        return true;
    }

    // ========================== Quantity ==========================

    /**
     * Set the production quantity by executing CS2 scripts directly.
     * <p>Calls script 6585 to set {@code varplayer_8847}, then script 7147 to refresh
     * the slider display and "Make N ItemName" button text. This is instant — no need
     * to click increase/decrease buttons repeatedly.</p>
     *
     * @param amount the desired quantity (will be clamped to max by the script)
     * @return {@code true} if the scripts were executed
     */
    public boolean setQuantity(int amount) {
        if (!isOpen()) { log.warn("[Production] Cannot set quantity: interface is not open"); return false; }
        if (amount <= 0) { log.warn("[Production] Cannot set quantity: amount must be > 0"); return false; }
        try {
            // script6585(int0): if (varplayer_8846 > 0) varplayer_8847 = int0;
            long h6585 = api.getScriptHandle(SCRIPT_SET_QUANTITY);
            api.executeScript(h6585, new int[]{amount}, new String[]{}, new String[]{});
            api.destroyScriptHandle(h6585);

            // script7147(): rebuilds slider + sets IF_SETPAUSETEXT("Make N ItemName")
            long h7147 = api.getScriptHandle(SCRIPT_REFRESH_SLIDER);
            api.executeScript(h7147, new int[]{}, new String[]{}, new String[]{});
            api.destroyScriptHandle(h7147);
            return true;
        } catch (Exception e) {
            log.warn("[Production] Failed to set quantity via CS2 scripts: {}", e.getMessage());
            return false;
        }
    }

    /**
     * Click the slider decrease button (quantity -1).
     * <p>Queues: {@code GameAction(COMPONENT, 1, 0, HASH_QUANTITY_SLIDER)}</p>
     *
     * @return {@code true} if the action was queued
     */
    public boolean decreaseQuantity() {
        if (!isOpen()) { log.warn("[Production] Cannot decrease: interface is not open"); return false; }
        api.queueAction(new GameAction(ActionTypes.COMPONENT, 1, 0, HASH_QUANTITY_SLIDER));
        return true;
    }

    /**
     * Click the slider increase button (quantity +1).
     * <p>Queues: {@code GameAction(COMPONENT, 1, 7, HASH_QUANTITY_SLIDER)}</p>
     *
     * @return {@code true} if the action was queued
     */
    public boolean increaseQuantity() {
        if (!isOpen()) { log.warn("[Production] Cannot increase: interface is not open"); return false; }
        api.queueAction(new GameAction(ActionTypes.COMPONENT, 1, 7, HASH_QUANTITY_SLIDER));
        return true;
    }

    // ========================== Category System ==========================

    /**
     * Get the available category options by reading the dropdown enum from cache.
     * <p>Reads {@code api.getEnumType(varp_7881)} to extract category names and indices.</p>
     *
     * @return a list of category entries, or empty if no categories or interface not open
     */
    public List<CategoryEntry> getCategories() {
        if (!hasCategories()) return List.of();
        int dropdownEnumId = api.getVarp(VARP_CATEGORY_DROPDOWN);
        if (dropdownEnumId <= 0) return List.of();
        try {
            EnumType enumType = api.getEnumType(dropdownEnumId);
            if (enumType == null || enumType.entries() == null) return List.of();
            List<CategoryEntry> categories = new ArrayList<>();
            int i = 0;
            for (var entry : enumType.entries().entrySet()) {
                String name = entry.getValue() != null ? entry.getValue().toString() : "Category " + i;
                categories.add(new CategoryEntry(i, name));
                i++;
            }
            return categories;
        } catch (Exception e) {
            log.warn("[Production] Failed to read category enum {}: {}", dropdownEnumId, e.getMessage());
            return List.of();
        }
    }

    /**
     * Open the category dropdown popup.
     * <p>Queues: {@code GameAction(COMPONENT, 1, -1, HASH_CATEGORY_DROPDOWN)}</p>
     *
     * @return {@code true} if the action was queued
     */
    public boolean openCategoryDropdown() {
        if (!isOpen()) { log.warn("[Production] Cannot open dropdown: interface is not open"); return false; }
        if (!hasCategories()) { log.warn("[Production] Cannot open dropdown: no categories available"); return false; }
        api.queueAction(new GameAction(ActionTypes.COMPONENT, 1, -1, HASH_CATEGORY_DROPDOWN));
        return true;
    }

    /**
     * Select a category option by sub-component index in the dropdown popup.
     * <p>The dropdown popup renders on comp(1477, 896). Call after {@link #openCategoryDropdown()}
     * once the popup is visible.</p>
     * <p>Queues: {@code GameAction(COMPONENT, 1, subIndex, HASH_DROPDOWN_POPUP)}</p>
     *
     * @param subIndex the sub-component index of the category to select
     * @return {@code true} if the action was queued
     */
    public boolean selectCategoryAt(int subIndex) {
        if (!isOpen()) { log.warn("[Production] Cannot select category: interface is not open"); return false; }
        api.queueAction(new GameAction(ActionTypes.COMPONENT, 1, subIndex, HASH_DROPDOWN_POPUP));
        return true;
    }

    /**
     * Select a category by name (blocking, handles the two-step dropdown flow).
     * <p>Opens the dropdown, waits for the popup to appear, reads text entries from
     * comp(1477, 895) to find the matching name, then clicks the corresponding
     * clickable entry in comp(1477, 896). Sleeps on the current virtual thread.</p>
     *
     * @param name the category name to select (case-insensitive)
     * @return {@code true} if the category was selected
     */
    public boolean selectCategory(String name) {
        if (name == null) return false;
        if (!openCategoryDropdown()) return false;

        // Wait for the dropdown popup to appear
        long deadline = System.currentTimeMillis() + 3000;
        List<Component> textEntries = List.of();
        while (System.currentTimeMillis() < deadline) {
            sleep(100);
            textEntries = api.getComponentChildren(1477, 895);
            if (!textEntries.isEmpty()) break;
        }
        if (textEntries.isEmpty()) {
            log.warn("[Production] Category dropdown did not open within timeout");
            return false;
        }

        sleep(randomDelay());

        // Find matching text entry
        String lower = name.toLowerCase();
        for (int i = 0; i < textEntries.size(); i++) {
            Component textComp = textEntries.get(i);
            String text = api.getComponentText(1477, 895);
            // Try per-sub-component text if available; fall back to checking name in entry
            try {
                // Read text from each sub-component by querying children
                String subText = textComp.itemId() > 0
                        ? resolveItemName(textComp.itemId())
                        : null;
                if (subText != null && subText.toLowerCase().equals(lower)) {
                    return selectCategoryAt(i);
                }
            } catch (Exception ignored) {}
        }

        // Fallback: try matching by index from getCategories()
        List<CategoryEntry> categories = getCategories();
        for (CategoryEntry cat : categories) {
            if (cat.name() != null && cat.name().toLowerCase().equals(lower)) {
                return selectCategoryAt(cat.index());
            }
        }

        log.warn("[Production] Category '{}' not found in dropdown", name);
        return false;
    }

    // ========================== Convenience (Blocking) ==========================

    /**
     * Select a product by item ID and click Make. Blocking — sleeps on the current virtual thread.
     * <p>Selects the product, waits for the detail panel to update, sets quantity to max, then makes.</p>
     *
     * @param itemId the item ID to select and make
     * @return {@code true} if the make action was queued
     */
    public boolean selectAndMake(int itemId) {
        if (!selectProduct(itemId)) return false;
        sleep(randomDelay());
        setQuantity(getMaxQuantity());
        sleep(randomDelay());
        return make();
    }

    /**
     * Select a product by name and click Make. Blocking — sleeps on the current virtual thread.
     *
     * @param name the product name (case-insensitive)
     * @return {@code true} if the make action was queued
     */
    public boolean selectAndMake(String name) {
        if (!selectProduct(name)) return false;
        sleep(randomDelay());
        setQuantity(getMaxQuantity());
        sleep(randomDelay());
        return make();
    }

    /**
     * Wait for the production interface to open within a timeout.
     * <p>Polls {@link #isOpen()} at 100ms intervals. Sleeps on the current virtual thread.</p>
     *
     * @param timeoutMs maximum time to wait in milliseconds
     * @return {@code true} if the interface opened within the timeout
     */
    public boolean awaitOpen(int timeoutMs) {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (!isOpen() && System.currentTimeMillis() < deadline) {
            sleep(100);
        }
        return isOpen();
    }

    // ========================== Production Progress (Interface 1251) ==========================

    /**
     * Check whether the player is currently producing items.
     * <p>The progress overlay (interface 1251) is open and visible (varp 3034 != 1).</p>
     *
     * @return {@code true} if the player is actively producing
     */
    public boolean isProducing() {
        return api.isInterfaceOpen(PROGRESS_INTERFACE) && api.getVarp(VARP_PROGRESS_VISIBILITY) != 1;
    }

    /**
     * Check whether the current production run has completed.
     *
     * @return {@code true} if producing and remaining items is 0
     */
    public boolean isProductionComplete() {
        return isProducing() && getProgressRemaining() <= 0;
    }

    /**
     * Get the total number of items to make in the current production run.
     *
     * @return the total count, or -1 if not producing
     */
    public int getProgressTotal() {
        if (!isProducing()) return -1;
        return api.getVarcInt(VARC_TOTAL_TO_MAKE);
    }

    /**
     * Get the number of items remaining to make.
     *
     * @return the remaining count, or -1 if not producing
     */
    public int getProgressRemaining() {
        if (!isProducing()) return -1;
        return api.getVarcInt(VARC_REMAINING);
    }

    /**
     * Get the number of items already made in the current run.
     *
     * @return the made count, or -1 if not producing
     */
    public int getProgressMade() {
        int total = getProgressTotal();
        int remaining = getProgressRemaining();
        if (total < 0 || remaining < 0) return -1;
        return total - remaining;
    }

    /**
     * Get the production progress as a percentage (0–100).
     *
     * @return the progress percentage, or -1 if not producing
     */
    public int getProgressPercent() {
        int total = getProgressTotal();
        int remaining = getProgressRemaining();
        if (total <= 0 || remaining < 0) return -1;
        return ((total - remaining) * 100) / total;
    }

    /**
     * Get the item ID of the product currently being produced.
     *
     * @return the product item ID, or -1 if not producing
     */
    public int getProgressProductId() {
        if (!isProducing()) return -1;
        return api.getVarp(VARP_PROGRESS_PRODUCT);
    }

    /**
     * Get the name of the product currently being produced.
     *
     * @return the product name, or {@code null} if not producing or unresolvable
     */
    public String getProgressProductName() {
        int itemId = getProgressProductId();
        if (itemId <= 0) return null;
        return resolveItemName(itemId);
    }

    /**
     * Get the remaining time text from the progress interface (e.g. "30s", "Done", "-").
     *
     * @return the time text, or {@code null} if not producing
     */
    public String getProgressTimeText() {
        if (!isProducing()) return null;
        return api.getComponentText(PROGRESS_INTERFACE, COMP_PROGRESS_TIME);
    }

    /**
     * Get the counter text from the progress interface (e.g. "15/28").
     *
     * @return the counter text, or {@code null} if not producing
     */
    public String getProgressCounterText() {
        if (!isProducing()) return null;
        return api.getComponentText(PROGRESS_INTERFACE, COMP_PROGRESS_COUNTER);
    }

    /**
     * Get the crafting speed modifier varclient.
     *
     * @return the speed modifier, or -1 if not producing
     */
    public int getProgressSpeedModifier() {
        if (!isProducing()) return -1;
        return api.getVarcInt(VARC_SPEED_MODIFIER);
    }

    /**
     * Click the stop/cancel button to halt production.
     * <p>Queues: {@code GameAction(COMPONENT, 1, -1, HASH_STOP_BUTTON)}</p>
     *
     * @return {@code true} if the action was queued
     */
    public boolean stopProduction() {
        if (!isProducing()) { log.warn("[Production] Cannot stop: not currently producing"); return false; }
        api.queueAction(new GameAction(ActionTypes.COMPONENT, 1, -1, HASH_STOP_BUTTON));
        return true;
    }

    /**
     * Wait for the current production to complete within a timeout.
     * <p>Polls {@link #isProducing()} at 200ms intervals. Sleeps on the current virtual thread.</p>
     *
     * @param timeoutMs maximum time to wait in milliseconds
     * @return {@code true} if production completed within the timeout
     */
    public boolean awaitCompletion(int timeoutMs) {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (isProducing() && getProgressRemaining() > 0 && System.currentTimeMillis() < deadline) {
            sleep(200);
        }
        return !isProducing() || getProgressRemaining() <= 0;
    }

    /**
     * Wait until a target number of items have been produced.
     * <p>Polls {@link #getProgressMade()} at 200ms intervals. Sleeps on the current virtual thread.</p>
     *
     * @param targetCount the number of items to wait for
     * @param timeoutMs   maximum time to wait in milliseconds
     * @return {@code true} if the target was reached within the timeout
     */
    public boolean awaitProgress(int targetCount, int timeoutMs) {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            int made = getProgressMade();
            if (made >= targetCount || !isProducing()) return made >= targetCount;
            sleep(200);
        }
        return getProgressMade() >= targetCount;
    }

    // ========================== Helpers ==========================

    /**
     * Resolve an item name from its ID.
     */
    private String resolveItemName(int itemId) {
        if (itemId <= 0) return null;
        ItemType type = api.getItemType(itemId);
        return type != null ? type.name() : null;
    }

    /** @see com.botwithus.bot.api.antiban.Delays#randomDelay() */
    private static int randomDelay() { return Delays.randomDelay(); }

    /** @see com.botwithus.bot.api.antiban.Delays#sleep(long) */
    private static void sleep(int ms) { Delays.sleep(ms); }

    // ========================== Records ==========================

    /**
     * Represents a product entry in the production grid.
     *
     * @param index  the 0-based index in the grid
     * @param itemId the item ID of the product
     */
    public record ProductEntry(int index, int itemId) {}

    /**
     * Represents a category option in the dropdown.
     *
     * @param index the 0-based index in the dropdown
     * @param name  the category display name
     */
    public record CategoryEntry(int index, String name) {}
}
