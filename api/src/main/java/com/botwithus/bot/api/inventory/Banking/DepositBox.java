package com.botwithus.bot.api.inventory.Banking;

import com.botwithus.bot.api.antiban.Delays;
import com.botwithus.bot.api.GameAPI;
import com.botwithus.bot.api.inventory.ActionTypes;
import com.botwithus.bot.api.log.BotLogger;
import com.botwithus.bot.api.log.LoggerFactory;
import com.botwithus.bot.api.model.Component;
import com.botwithus.bot.api.model.GameAction;

import java.util.Arrays;
import java.util.List;
import java.util.Set;

import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static com.botwithus.bot.api.inventory.ComponentHelper.queueComponentAction;

/**
 * Provides access to the deposit box interface (interface 11).
 * <p>Deposit boxes allow depositing items to the bank without a full bank interface.
 * Only deposit operations are available &mdash; no withdrawals.</p>
 *
 * <p>The backpack grid is displayed in component 19 with sub-components 0&ndash;27
 * representing slots 1&ndash;28. Items are read via
 * {@link GameAPI#getComponentChildren(int, int)} rather than an inventory ID.</p>
 */
public final class DepositBox {

    private static final BotLogger log = LoggerFactory.getLogger(DepositBox.class);

    /** Deposit box interface ID. */
    public static final int INTERFACE_ID = 11;
    /** Backpack item grid component (sub-components 0–27 = slots 1–28). */
    public static final int BACKPACK_COMPONENT = 19;
    /** Maximum number of backpack slots visible in the deposit box. */
    public static final int MAX_SLOTS = 28;

    // Button component IDs within interface 11
    private static final int COMP_CARRIED  = 5;
    private static final int COMP_WORN     = 8;
    private static final int COMP_FAMILIAR = 11;
    private static final int COMP_MONEY    = 14;

    // Pre-computed component hashes (interfaceId << 16 | componentId)
    private static final int HASH_CARRIED  = INTERFACE_ID << 16 | COMP_CARRIED;
    private static final int HASH_WORN     = INTERFACE_ID << 16 | COMP_WORN;
    private static final int HASH_FAMILIAR = INTERFACE_ID << 16 | COMP_FAMILIAR;
    private static final int HASH_MONEY    = INTERFACE_ID << 16 | COMP_MONEY;

    private final GameAPI api;

    /**
     * Creates a new deposit box API wrapper.
     *
     * @param api the game API instance
     */
    public DepositBox(GameAPI api) {
        this.api = api;
    }

    // ========================== State ==========================

    /**
     * Check whether the deposit box interface is currently open.
     *
     * @return {@code true} if the deposit box is open
     */
    public boolean isOpen() {
        return api.isInterfaceOpen(INTERFACE_ID);
    }

    // ========================== Item Reading ==========================

    /**
     * Returns all non-empty items currently shown in the deposit box backpack grid.
     * Items with quantity 0 (placeholders) are excluded.
     *
     * @return a list of visible item components
     */
    public List<Component> getItems() {
        return api.getComponentChildren(INTERFACE_ID, BACKPACK_COMPONENT).stream()
                .filter(c -> c.itemId() != -1 && c.itemCount() > 0)
                .toList();
    }

    /**
     * Check if the deposit box backpack contains an item with at least 1 quantity.
     *
     * @param itemId the item ID to search for
     * @return {@code true} if the item is present with quantity &gt; 0
     */
    public boolean contains(int itemId) {
        return count(itemId) > 0;
    }

    /**
     * Check if the deposit box backpack contains an item by exact name (case-insensitive).
     *
     * @param name the exact item name
     * @return {@code true} if a matching item is present with quantity &gt; 0
     */
    public boolean contains(String name) {
        return count(name) > 0;
    }

    /**
     * Count the total quantity of an item in the deposit box backpack.
     *
     * @param itemId the item ID
     * @return the total quantity across all matching slots
     */
    public int count(int itemId) {
        return api.getComponentChildren(INTERFACE_ID, BACKPACK_COMPONENT).stream()
                .filter(c -> c.itemId() == itemId && c.itemCount() > 0)
                .mapToInt(Component::itemCount)
                .sum();
    }

    /**
     * Count the total quantity of an item by exact name (case-insensitive).
     *
     * @param name the exact item name
     * @return the total quantity across all matching slots
     */
    public int count(String name) {
        String lower = name.toLowerCase();
        return api.getComponentChildren(INTERFACE_ID, BACKPACK_COMPONENT).stream()
                .filter(c -> c.itemId() != -1 && c.itemCount() > 0)
                .filter(c -> {
                    var type = api.getItemType(c.itemId());
                    return type != null && type.name() != null
                            && type.name().toLowerCase().equals(lower);
                })
                .mapToInt(Component::itemCount)
                .sum();
    }

    /**
     * Check if the deposit box backpack is empty (no items with quantity &gt; 0).
     *
     * @return {@code true} if there are no items to deposit
     */
    public boolean isEmpty() {
        return getItems().isEmpty();
    }

    /**
     * Count the number of occupied slots in the deposit box backpack.
     *
     * @return the number of slots with items
     */
    public int occupiedSlots() {
        return getItems().size();
    }

    /**
     * Count the number of free (empty) slots in the deposit box backpack.
     *
     * @return the number of empty slots
     */
    public int freeSlots() {
        return MAX_SLOTS - occupiedSlots();
    }

    // ========================== Single-Item Deposit ==========================

    /**
     * Deposit an item by ID with a preset transfer amount.
     *
     * <p>Option mapping: ONE&rarr;1, FIVE&rarr;2, TEN&rarr;3, ALL&rarr;4.</p>
     *
     * @param itemId the item ID to deposit
     * @param amount the transfer amount
     * @return {@code true} if the action was queued
     */
    public boolean deposit(int itemId, TransferAmount amount) {
        if (!isOpen()) { log.warn("[DepositBox] Cannot deposit: deposit box is not open"); return false; }
        Component comp = findItem(itemId);
        if (comp == null) { log.warn("[DepositBox] Cannot deposit item {}: not found in backpack", itemId); return false; }
        return queueComponentAction(api, comp, mapOption(amount));
    }

    /**
     * Deposit an exact number of an item, blocking until complete.
     * <p>Composes the amount from fixed deposits (10, 5, 1) with random delays
     * (400&ndash;700ms) between each action. Sleeps on the current (virtual) thread.</p>
     *
     * <pre>{@code
     * // Deposits 14: D10 -> sleep -> D1x4
     * depositBox.deposit(TROUT_ID, 14);
     * }</pre>
     *
     * @param itemId the item ID to deposit
     * @param amount the exact number to deposit (must be &gt; 0)
     * @return {@code true} if all actions were queued successfully
     */
    public boolean deposit(int itemId, int amount) {
        if (!isOpen()) { log.warn("[DepositBox] Cannot deposit: deposit box is not open"); return false; }
        if (amount <= 0) { log.warn("[DepositBox] Cannot deposit item {}: amount must be > 0", itemId); return false; }
        if (!contains(itemId)) { log.warn("[DepositBox] Cannot deposit item {}: not found in backpack", itemId); return false; }
        int remaining = amount;
        while (remaining > 0) {
            Component comp = findItem(itemId);
            if (comp == null) {
                log.warn("[DepositBox] Cannot deposit item {}: no more left in backpack ({} remaining)", itemId, remaining);
                return false;
            }
            if (remaining >= 10) {
                queueComponentAction(api, comp, mapOption(TransferAmount.TEN));
                remaining -= 10;
            } else if (remaining >= 5) {
                queueComponentAction(api, comp, mapOption(TransferAmount.FIVE));
                remaining -= 5;
            } else {
                queueComponentAction(api, comp, mapOption(TransferAmount.ONE));
                remaining -= 1;
            }
            if (remaining > 0) sleep(randomDelay());
        }
        return true;
    }

    /**
     * Deposit an item by exact name with a preset transfer amount.
     *
     * @param name   the exact item name (case-insensitive)
     * @param amount the transfer amount
     * @return {@code true} if the action was queued
     */
    public boolean deposit(String name, TransferAmount amount) {
        if (!isOpen()) { log.warn("[DepositBox] Cannot deposit: deposit box is not open"); return false; }
        Component comp = findItem(name);
        if (comp == null) { log.warn("[DepositBox] Cannot deposit '{}': not found in backpack", name); return false; }
        return queueComponentAction(api, comp, mapOption(amount));
    }

    /**
     * Deposit an exact number of an item by name, blocking until complete.
     *
     * @param name   the exact item name (case-insensitive)
     * @param amount the exact number to deposit
     * @return {@code true} if all actions were queued successfully
     * @see #deposit(int, int)
     */
    public boolean deposit(String name, int amount) {
        if (!isOpen()) { log.warn("[DepositBox] Cannot deposit: deposit box is not open"); return false; }
        Component comp = findItem(name);
        if (comp == null) { log.warn("[DepositBox] Cannot deposit '{}': not found in backpack", name); return false; }
        return deposit(comp.itemId(), amount);
    }

    /**
     * Deposit all of an item by ID.
     *
     * @param itemId the item ID
     * @return {@code true} if the action was queued
     */
    public boolean depositAll(int itemId) {
        return deposit(itemId, TransferAmount.ALL);
    }

    /**
     * Deposit all of an item by exact name.
     *
     * @param name the exact item name (case-insensitive)
     * @return {@code true} if the action was queued
     */
    public boolean depositAll(String name) {
        return deposit(name, TransferAmount.ALL);
    }

    // ========================== Bulk Deposit ==========================

    /**
     * Deposit all carried items (entire backpack contents).
     *
     * @return {@code true} if the action was queued
     */
    public boolean depositCarriedItems() {
        if (!isOpen()) { log.warn("[DepositBox] Cannot deposit: deposit box is not open"); return false; }
        if (isEmpty()) { log.warn("[DepositBox] Cannot deposit carried items: backpack is empty"); return false; }
        api.queueAction(new GameAction(ActionTypes.COMPONENT, 1, -1, HASH_CARRIED));
        return true;
    }

    /**
     * Deposit all worn equipment.
     *
     * @return {@code true} if the action was queued
     */
    public boolean depositWornItems() {
        if (!isOpen()) { log.warn("[DepositBox] Cannot deposit: deposit box is not open"); return false; }
        api.queueAction(new GameAction(ActionTypes.COMPONENT, 1, -1, HASH_WORN));
        return true;
    }

    /**
     * Deposit all items carried by the player's familiar.
     *
     * @return {@code true} if the action was queued
     */
    public boolean depositFamiliarItems() {
        if (!isOpen()) { log.warn("[DepositBox] Cannot deposit: deposit box is not open"); return false; }
        api.queueAction(new GameAction(ActionTypes.COMPONENT, 1, -1, HASH_FAMILIAR));
        return true;
    }

    /**
     * Deposit the money pouch contents.
     *
     * @return {@code true} if the action was queued
     */
    public boolean depositMoneyPouch() {
        if (!isOpen()) { log.warn("[DepositBox] Cannot deposit: deposit box is not open"); return false; }
        api.queueAction(new GameAction(ActionTypes.COMPONENT, 1, -1, HASH_MONEY));
        return true;
    }

    /**
     * Deposit everything: carried items, worn equipment, familiar items, and money pouch.
     * Each action is separated by a random delay (400&ndash;700ms).
     *
     * @return {@code true} if at least the carried items deposit was queued
     */
    public boolean depositEverything() {
        if (!isOpen()) { log.warn("[DepositBox] Cannot deposit: deposit box is not open"); return false; }
        depositCarriedItems();
        sleep(randomDelay());
        depositWornItems();
        sleep(randomDelay());
        depositFamiliarItems();
        sleep(randomDelay());
        depositMoneyPouch();
        return true;
    }

    // ========================== Selective Deposit ==========================

    /**
     * Deposit all items except those with the specified item IDs.
     * Uses Deposit-All (option 4) per unique item, with random delays between actions.
     *
     * @param exceptItemIds item IDs to keep in the backpack
     * @return {@code true} if at least one item was deposited
     */
    public boolean depositAllExcept(int... exceptItemIds) {
        if (!isOpen()) { log.warn("[DepositBox] Cannot deposit: deposit box is not open"); return false; }
        Set<Integer> keepSet = IntStream.of(exceptItemIds).boxed().collect(Collectors.toSet());
        List<Component> items = getItems();
        boolean deposited = false;
        // Track already-deposited item IDs to avoid duplicate actions for stacked items
        Set<Integer> processed = new java.util.HashSet<>();
        for (Component comp : items) {
            if (keepSet.contains(comp.itemId())) continue;
            if (!processed.add(comp.itemId())) continue;
            Component fresh = findItem(comp.itemId());
            if (fresh != null) {
                if (deposited) sleep(randomDelay());
                queueComponentAction(api, fresh, mapOption(TransferAmount.ALL));
                deposited = true;
            }
        }
        return deposited;
    }

    /**
     * Deposit all items except those whose names match exactly (case-insensitive).
     * Uses Deposit-All (option 4) per unique item, with random delays between actions.
     *
     * @param exceptNames exact item names to keep
     * @return {@code true} if at least one item was deposited
     */
    public boolean depositAllExcept(String... exceptNames) {
        if (!isOpen()) { log.warn("[DepositBox] Cannot deposit: deposit box is not open"); return false; }
        Set<String> keepNames = Arrays.stream(exceptNames).map(String::toLowerCase).collect(Collectors.toSet());
        List<Component> items = getItems();
        boolean deposited = false;
        Set<Integer> processed = new java.util.HashSet<>();
        for (Component comp : items) {
            var type = api.getItemType(comp.itemId());
            if (type != null && type.name() != null
                    && keepNames.contains(type.name().toLowerCase())) continue;
            if (!processed.add(comp.itemId())) continue;
            Component fresh = findItem(comp.itemId());
            if (fresh != null) {
                if (deposited) sleep(randomDelay());
                queueComponentAction(api, fresh, mapOption(TransferAmount.ALL));
                deposited = true;
            }
        }
        return deposited;
    }

    // ========================== Helpers ==========================

    /**
     * Find a component in the backpack grid that holds the given item (quantity &gt; 0).
     */
    private Component findItem(int itemId) {
        return api.getComponentChildren(INTERFACE_ID, BACKPACK_COMPONENT).stream()
                .filter(c -> c.itemId() == itemId && c.itemCount() > 0)
                .findFirst().orElse(null);
    }

    /**
     * Find a component in the backpack grid by exact item name (case-insensitive).
     */
    private Component findItem(String name) {
        String lower = name.toLowerCase();
        return api.getComponentChildren(INTERFACE_ID, BACKPACK_COMPONENT).stream()
                .filter(c -> c.itemId() != -1 && c.itemCount() > 0)
                .filter(c -> {
                    var type = api.getItemType(c.itemId());
                    return type != null && type.name() != null
                            && type.name().toLowerCase().equals(lower);
                })
                .findFirst().orElse(null);
    }

    /**
     * Map a transfer amount to the deposit box right-click option index.
     * <ul>
     *   <li>ONE &rarr; 1 (Deposit-1)</li>
     *   <li>FIVE &rarr; 2 (Deposit-5)</li>
     *   <li>TEN &rarr; 3 (Deposit-10)</li>
     *   <li>ALL &rarr; 4 (Deposit-All)</li>
     * </ul>
     */
    private static int mapOption(TransferAmount amount) {
        return switch (amount) {
            case ONE  -> 1;
            case FIVE -> 2;
            case TEN  -> 3;
            case ALL  -> 4;
        };
    }

    /** @see com.botwithus.bot.api.antiban.Delays#randomDelay() */
    private static int randomDelay() { return Delays.randomDelay(); }

    /** @see com.botwithus.bot.api.antiban.Delays#sleep(long) */
    private static void sleep(int ms) { Delays.sleep(ms); }

    // ========================== Enums ==========================

    /**
     * Transfer quantity presets for deposit box operations.
     * <p>The deposit box does not support custom (X) amounts &mdash; only
     * fixed presets of 1, 5, 10, or All.</p>
     */
    public enum TransferAmount {
        ONE, FIVE, TEN, ALL
    }
}
