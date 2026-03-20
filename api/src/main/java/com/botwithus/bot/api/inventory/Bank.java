package com.botwithus.bot.api.inventory;

import com.botwithus.bot.api.GameAPI;
import com.botwithus.bot.api.log.BotLogger;
import com.botwithus.bot.api.log.LoggerFactory;
import com.botwithus.bot.api.model.Component;
import com.botwithus.bot.api.model.GameAction;
import com.botwithus.bot.api.model.InventoryItem;
import com.botwithus.bot.api.query.ComponentFilter;

import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static com.botwithus.bot.api.inventory.ComponentHelper.componentHash;
import static com.botwithus.bot.api.inventory.ComponentHelper.queueComponentAction;

/**
 * Provides access to the bank interface (inventory ID 95, interface 517).
 * Ported from the legacy BotWithUs API to use the pipe RPC.
 *
 * <p>Interaction methods queue game actions through the pipe. Varbit/varc checks
 * use the RPC variable-reading methods.</p>
 */
public final class Bank {

    private static final BotLogger log = LoggerFactory.getLogger(Bank.class);

    public static final int INVENTORY_ID = 95;
    public static final int INTERFACE_ID = 517;
    /** Bank item grid component (interface 517, component 201). */
    public static final int BANK_COMPONENT = 201;
    /** Backpack items shown inside the bank interface. */
    public static final int BACKPACK_COMPONENT = 15;
    /** Withdraw-X / Deposit-X input dialog interface. */
    public static final int INPUT_INTERFACE = 1469;

    // Varbit IDs used by the bank interface
    private static final int VARBIT_HIDDEN_OPTION = 45189;
    private static final int VARBIT_SIDE_VIEW = 45139;
    private static final int VARBIT_BANK_SETTING = 45191;
    private static final int VARBIT_PRESET_PAGE = 49662;

    // Varc IDs
    private static final int VARC_CUSTOM_INPUT_STATE = 2873;
    private static final int VARC_CUSTOM_INPUT_TYPE = 2236;

    // Varp IDs
    private static final int VARP_WITHDRAW_AMOUNT = 111;
    private static final int VARP_WITHDRAW_MODE = 160;

    // Component hashes for specific bank buttons
    private static final int HASH_PRESETS_BUTTON = INTERFACE_ID << 16 | 177;
    /** Preset grid component — sub-components 1-10 are preset slots. */
    private static final int PRESET_COMPONENT = 119;
    /** Close button: interface 517, component 317. */
    private static final int HASH_CLOSE_BUTTON = INTERFACE_ID << 16 | 317;
    /** Withdraw mode toggle (Item/Note): interface 517, component 127. */
    private static final int HASH_WITHDRAW_MODE_TOGGLE = INTERFACE_ID << 16 | 127;

    private final GameAPI api;
    private final InventoryContainer container;

    /**
     * Creates a new bank wrapper.
     *
     * @param api the game API instance
     */
    public Bank(GameAPI api) {
        this.api = api;
        this.container = new InventoryContainer(api, INVENTORY_ID);
    }

    /**
     * Returns the underlying {@link InventoryContainer} for advanced queries.
     *
     * @return the inventory container
     */
    public InventoryContainer container() {
        return container;
    }

    // ========================== State Queries ==========================

    /**
     * Check if the bank interface is open.
     */
    public boolean isOpen() {
        return api.isInterfaceOpen(INTERFACE_ID);
    }

    /**
     * Check if the withdraw/deposit X input dialog is open.
     */
    public boolean isInputOpen() {
        return api.isInterfaceOpen(INPUT_INTERFACE);
    }

    /**
     * Check if the player is currently editing a custom amount.
     */
    public boolean isEditingCustomAmount() {
        return api.getVarcInt(VARC_CUSTOM_INPUT_STATE) == 11
                && api.getVarcInt(VARC_CUSTOM_INPUT_TYPE) == 7;
    }

    /**
     * Checks if the bank contains the specified item with quantity &gt; 0.
     * <p>Placeholder slots (qty 0) return {@code false}.</p>
     *
     * @param itemId the item ID to look for
     * @return {@code true} if the item is in the bank with at least 1 quantity
     */
    public boolean contains(int itemId) {
        return container.count(itemId) > 0;
    }

    /**
     * Checks if the bank contains at least the specified amount of an item.
     *
     * @param itemId the item ID to look for
     * @param amount the minimum quantity required
     * @return {@code true} if enough of the item is in the bank
     */
    public boolean contains(int itemId, int amount) {
        return container.count(itemId) >= amount;
    }

    /**
     * Checks if the bank contains an item whose name exactly matches (case-insensitive)
     * with quantity &gt; 0. Placeholder slots (qty 0) return {@code false}.
     *
     * @param name the exact item name to search for
     * @return {@code true} if a matching item is in the bank
     */
    public boolean contains(String name) {
        return container.countExact(name) > 0;
    }

    /**
     * Checks if the bank contains an item whose name contains the given substring (case-insensitive)
     * with quantity &gt; 0. Placeholder slots (qty 0) return {@code false}.
     *
     * @param name the name substring to search for
     * @return {@code true} if a matching item is in the bank
     */
    public boolean containsPartial(String name) {
        return container.count(name) > 0;
    }

    /**
     * Counts the total quantity of an item in the bank.
     *
     * @param itemId the item ID to count
     * @return the total quantity
     */
    public int count(int itemId) {
        return container.count(itemId);
    }

    /**
     * Counts the total quantity of items whose name exactly matches (case-insensitive).
     *
     * @param name the exact item name to search for
     * @return the total quantity of matching items
     */
    public int count(String name) {
        return container.countExact(name);
    }

    /**
     * Counts the total quantity of items whose name contains the given substring (case-insensitive).
     *
     * @param name the name substring to search for
     * @return the total quantity of matching items
     */
    public int countPartial(String name) {
        return container.count(name);
    }

    /**
     * Returns all non-empty items in the bank.
     *
     * @return a list of bank items
     */
    public List<InventoryItem> getItems() {
        return container.getItems();
    }

    /**
     * Checks if the bank contains ALL of the specified items.
     *
     * @param itemIds the item IDs to check
     * @return {@code true} if every item is present in the bank
     */
    public boolean containsAll(int... itemIds) {
        for (int id : itemIds) {
            if (!contains(id)) return false;
        }
        return true;
    }

    /**
     * Checks if the bank contains ANY of the specified items.
     *
     * @param itemIds the item IDs to check
     * @return {@code true} if at least one item is present
     */
    public boolean containsAny(int... itemIds) {
        for (int id : itemIds) {
            if (contains(id)) return true;
        }
        return false;
    }

    /**
     * Checks if the bank has no items.
     *
     * @return {@code true} if the bank is empty
     */
    public boolean isEmpty() {
        return container.isEmpty();
    }

    /**
     * Checks if the bank has no free slots.
     *
     * @return {@code true} if the bank is full
     */
    public boolean isFull() {
        return container.isFull();
    }

    /**
     * Returns the number of distinct items (occupied slots) in the bank.
     *
     * @return the count of occupied bank slots
     */
    public int getCount() {
        return container.occupiedSlots();
    }

    // ========================== Backpack (live while bank is open) ==========================

    /**
     * Count how many of an item are in the backpack while the bank is open.
     * <p>Uses interface 517 component 15 which reflects the live backpack state
     * (inventory 93 is stale while the bank interface is open).</p>
     *
     * @param itemId the item ID to count
     * @return the number of matching items in the backpack
     */
    public int backpackCount(int itemId) {
        if (!isOpen()) return 0;
        List<Component> children = api.getComponentChildren(INTERFACE_ID, BACKPACK_COMPONENT);
        int count = 0;
        for (Component child : children) {
            if (child.itemId() == itemId) count++;
        }
        return count;
    }

    /**
     * Check if the backpack contains an item while the bank is open.
     *
     * @param itemId the item ID to check
     * @return {@code true} if at least one is in the backpack
     */
    public boolean backpackContains(int itemId) {
        return backpackCount(itemId) > 0;
    }

    /**
     * Check if the backpack is empty while the bank is open.
     *
     * @return {@code true} if no items are in the backpack
     */
    public boolean backpackIsEmpty() {
        if (!isOpen()) return false;
        List<Component> children = api.getComponentChildren(INTERFACE_ID, BACKPACK_COMPONENT);
        return children.stream().noneMatch(c -> c.itemId() > 0);
    }

    /**
     * Count total occupied slots in the backpack while the bank is open.
     *
     * @return the number of non-empty backpack slots
     */
    public int backpackOccupiedSlots() {
        if (!isOpen()) return 0;
        List<Component> children = api.getComponentChildren(INTERFACE_ID, BACKPACK_COMPONENT);
        return (int) children.stream().filter(c -> c.itemId() > 0).count();
    }

    /**
     * Count free slots in the backpack while the bank is open.
     *
     * @return the number of empty backpack slots
     */
    public int backpackFreeSlots() {
        if (!isOpen()) return 0;
        List<Component> children = api.getComponentChildren(INTERFACE_ID, BACKPACK_COMPONENT);
        return (int) children.stream().filter(c -> c.itemId() <= 0).count();
    }

    // ========================== Deposit Methods ==========================

    /**
     * Deposit all carried items.
     */
    public boolean depositAll() {
        if (!isOpen() || backpackIsEmpty()) return false;
        return interactByOption("Deposit carried items");
    }

    /**
     * Deposit all worn equipment.
     */
    public boolean depositEquipment() {
        if (!isOpen()) return false;
        return interactByOption("Deposit worn items");
    }

    /**
     * Deposit familiar inventory.
     */
    public boolean depositFamiliar() {
        if (!isOpen()) return false;
        return interactByOption("Deposit familiar items");
    }

    /**
     * Deposit coin pouch.
     */
    public boolean depositCoins() {
        if (!isOpen()) return false;
        return interactByOption("Deposit coin pouch");
    }

    /**
     * Deposit everything (carried items, equipment, familiar, coins).
     */
    public boolean depositEverything() {
        return depositAll() && depositEquipment() && depositFamiliar() && depositCoins();
    }

    /**
     * Deposit all carried items except those with the specified item IDs.
     * Useful for keeping supplies (food, potions) while depositing loot.
     *
     * @param exceptItemIds item IDs to keep in the backpack
     * @return {@code true} if at least one item was deposited
     */
    public boolean depositAllExcept(int... exceptItemIds) {
        if (!isOpen()) return false;
        Set<Integer> keepSet = IntStream.of(exceptItemIds).boxed().collect(Collectors.toSet());
        List<InventoryItem> backpackItems = new InventoryContainer(api, Backpack.INVENTORY_ID).getItems();
        boolean deposited = false;
        for (InventoryItem item : backpackItems) {
            if (keepSet.contains(item.itemId())) continue;
            Component comp = findBackpackItem(item.itemId());
            if (comp != null) {
                queueComponentAction(api, comp, 7); // 7 = deposit All
                deposited = true;
            }
        }
        return deposited;
    }

    /**
     * Deposit all carried items except those whose names match (case-insensitive substring).
     * Useful for keeping supplies by name (e.g. "Shark", "Prayer potion").
     *
     * @param exceptNames item name substrings to keep
     * @return {@code true} if at least one item was deposited
     */
    public boolean depositAllExcept(String... exceptNames) {
        if (!isOpen()) return false;
        Set<String> keepNames = Arrays.stream(exceptNames).map(String::toLowerCase).collect(Collectors.toSet());
        List<InventoryItem> backpackItems = new InventoryContainer(api, Backpack.INVENTORY_ID).getItems();
        boolean deposited = false;
        for (InventoryItem item : backpackItems) {
            var type = api.getItemType(item.itemId());
            if (type != null && type.name() != null
                    && keepNames.stream().anyMatch(n -> type.name().toLowerCase().contains(n))) continue;
            Component comp = findBackpackItem(item.itemId());
            if (comp != null) {
                queueComponentAction(api, comp, 7); // 7 = deposit All
                deposited = true;
            }
        }
        return deposited;
    }

    /**
     * Deposit an item by ID with the specified transfer amount.
     */
    public boolean deposit(int itemId, TransferAmount amount) {
        if (!isOpen()) { log.warn("[Bank] Cannot deposit: bank is not open"); return false; }
        Component comp = findBackpackItem(itemId);
        if (comp == null) { log.warn("[Bank] Cannot deposit item {}: not found in backpack", itemId); return false; }
        int optionIndex = mapDepositOption(amount);
        if (optionIndex < 0) return false;
        return queueComponentAction(api, comp, optionIndex);
    }

    /**
     * Deposit an exact number of an item, blocking until complete.
     * <p>Composes the amount from fixed deposits (10, 5, 1) with random delays
     * (400–700ms) between each action. Sleeps on the current (virtual) thread.</p>
     *
     * <pre>{@code
     * // Deposits 9: D5 → sleep → D1×4
     * bank.deposit(SWORDFISH_ID, 9);
     * }</pre>
     *
     * @param itemId the item ID to deposit
     * @param amount the exact number to deposit (must be &gt; 0)
     * @return {@code true} if all actions were queued successfully
     */
    public boolean deposit(int itemId, int amount) {
        if (!isOpen()) { log.warn("[Bank] Cannot deposit: bank is not open"); return false; }
        if (amount <= 0) { log.warn("[Bank] Cannot deposit item {}: amount must be > 0", itemId); return false; }
        int remaining = amount;
        while (remaining > 0) {
            Component comp = findBackpackItem(itemId);
            if (comp == null) return false;
            if (remaining >= 10) {
                queueComponentAction(api, comp, mapDepositOption(TransferAmount.TEN));
                remaining -= 10;
            } else if (remaining >= 5) {
                queueComponentAction(api, comp, mapDepositOption(TransferAmount.FIVE));
                remaining -= 5;
            } else {
                queueComponentAction(api, comp, mapDepositOption(TransferAmount.ONE));
                remaining -= 1;
            }
            if (remaining > 0) sleep(randomDelay());
        }
        return true;
    }

    /**
     * Start a deposit-X interaction for an item (opens the input dialog).
     */
    public boolean startDepositX(int itemId) {
        if (!isOpen()) return false;
        Component comp = findBackpackItem(itemId);
        if (comp == null) return false;
        return queueComponentAction(api, comp, 6);
    }

    /**
     * Finish a deposit/withdraw-X by entering the amount.
     * Call after {@link #startDepositX} or when the input dialog is open.
     */
    public boolean finishTransferX(int amount) {
        if (!isOpen() || !api.isInterfaceOpen(INPUT_INTERFACE)) return false;
        api.fireKeyTrigger(INPUT_INTERFACE, 0, String.valueOf(amount));
        return true;
    }

    // ========================== Withdraw Methods ==========================

    /**
     * Withdraw an item by ID with the specified transfer amount.
     * <p>Uses the right-click option indices which are fixed when mode=ALL:
     * opt2=W1, opt3=W5, opt4=W10, opt5=WX, opt7=WAll.
     * If the current mode is not ALL, switches to ALL first so all options are available.</p>
     */
    public boolean withdraw(int itemId, TransferAmount amount) {
        if (!isOpen()) { log.warn("[Bank] Cannot withdraw: bank is not open"); return false; }
        if (!contains(itemId)) { log.warn("[Bank] Cannot withdraw item {}: not in bank or quantity is 0", itemId); return false; }
        Component comp = findBankItem(itemId);
        if (comp == null) return false;

        // Withdraw-All is always option 7 regardless of mode
        if (amount == TransferAmount.ALL) {
            return queueComponentAction(api, comp, 7);
        }
        // Withdraw-X is always option 5 regardless of mode
        if (amount == TransferAmount.CUSTOM) {
            return queueComponentAction(api, comp, 5);
        }

        // For W1/W5/W10: ensure mode is ALL so all right-click options are available
        // Mode=ALL gives: opt2=W1, opt3=W5, opt4=W10
        if (transferMode() != TransferAmount.ALL) {
            setTransferMode(TransferAmount.ALL);
        }
        int optionIndex = switch (amount) {
            case ONE -> 2;
            case FIVE -> 3;
            case TEN -> 4;
            default -> -1;
        };
        if (optionIndex < 0) return false;
        return queueComponentAction(api, comp, optionIndex);
    }

    /**
     * Withdraw an exact number of an item, blocking until complete.
     * <p>Composes the amount from fixed withdrawals (10, 5, 1) with random delays
     * (400–700ms) between each action. Sleeps on the current (virtual) thread.</p>
     *
     * <pre>{@code
     * // Withdraws 19: W10 → sleep → W5 → sleep → W1×4
     * bank.withdraw(SWORDFISH_ID, 19);
     * }</pre>
     *
     * @param itemId the item ID to withdraw
     * @param amount the exact number to withdraw (must be &gt; 0)
     * @return {@code true} if all actions were queued successfully
     */
    public boolean withdraw(int itemId, int amount) {
        if (!isOpen()) { log.warn("[Bank] Cannot withdraw: bank is not open"); return false; }
        if (!contains(itemId)) { log.warn("[Bank] Cannot withdraw item {}: not in bank or quantity is 0", itemId); return false; }
        if (amount <= 0) { log.warn("[Bank] Cannot withdraw item {}: amount must be > 0", itemId); return false; }
        // Ensure mode=ALL so all right-click options are available
        if (transferMode() != TransferAmount.ALL) {
            setTransferMode(TransferAmount.ALL);
            sleep(randomDelay());
        }
        int remaining = amount;
        while (remaining > 0) {
            Component comp = findBankItem(itemId);
            if (comp == null) return false;
            if (remaining >= 10) {
                queueComponentAction(api, comp, 4); // W10
                remaining -= 10;
            } else if (remaining >= 5) {
                queueComponentAction(api, comp, 3); // W5
                remaining -= 5;
            } else {
                queueComponentAction(api, comp, 2); // W1
                remaining -= 1;
            }
            if (remaining > 0) sleep(randomDelay());
        }
        return true;
    }

    /**
     * Withdraw all of an item by ID.
     */
    public boolean withdrawAll(int itemId) {
        return withdraw(itemId, TransferAmount.ALL);
    }

    /**
     * Withdraw an item by name with the specified transfer amount.
     * Finds the first bank item whose name contains the given string (case-insensitive).
     *
     * @param name   the item name substring to search for
     * @param amount the transfer amount
     * @return {@code true} if the action was queued
     */
    public boolean withdraw(String name, TransferAmount amount) {
        if (!isOpen()) { log.warn("[Bank] Cannot withdraw: bank is not open"); return false; }
        InventoryItem item = container.getFirstExact(name);
        if (item == null) { log.warn("[Bank] Cannot withdraw '{}': not found in bank", name); return false; }
        return withdraw(item.itemId(), amount);
    }

    /**
     * Withdraw an exact number of an item by name, blocking until complete.
     *
     * @param name   the item name substring to search for
     * @param amount the exact number to withdraw
     * @return {@code true} if all actions were queued successfully
     * @see #withdraw(int, int)
     */
    public boolean withdraw(String name, int amount) {
        if (!isOpen()) { log.warn("[Bank] Cannot withdraw: bank is not open"); return false; }
        InventoryItem item = container.getFirstExact(name);
        if (item == null) { log.warn("[Bank] Cannot withdraw '{}': not found in bank", name); return false; }
        return withdraw(item.itemId(), amount);
    }

    /**
     * Withdraw all of an item by name.
     *
     * @param name the item name substring to search for
     * @return {@code true} if the action was queued
     */
    public boolean withdrawAll(String name) {
        return withdraw(name, TransferAmount.ALL);
    }

    // ========================== Presets ==========================

    /**
     * Withdraw a bank preset by number (1-18).
     * Handles page switching if needed (presets 1-9 on page 0, 10-18 on page 1).
     */
    public boolean withdrawPreset(int presetNumber) {
        if (!isOpen() || presetNumber < 1 || presetNumber > 18) return false;

        // Switch to presets mode if not already there
        if (setting() != BankSetting.PRESETS) {
            api.queueAction(new GameAction(ActionTypes.COMPONENT, 1, -1, HASH_PRESETS_BUTTON));
            // Caller should wait for the setting to change
        }

        // Handle page switching
        int targetPage = presetNumber > 9 ? 1 : 0;
        if (getPresetPage() != targetPage) {
            api.queueAction(new GameAction(ActionTypes.COMPONENT, 1, 100, INTERFACE_ID << 16 | PRESET_COMPONENT));
        }

        int preset = presetNumber > 9 ? presetNumber - 9 : presetNumber;
        api.queueAction(new GameAction(ActionTypes.COMPONENT, 1, preset, INTERFACE_ID << 16 | PRESET_COMPONENT));
        return true;
    }

    // ========================== Transfer Mode ==========================

    /**
     * Get the current transfer mode (1, 5, 10, custom, all).
     */
    public TransferAmount transferMode() {
        return switch (api.getVarbit(VARBIT_HIDDEN_OPTION)) {
            case 3 -> TransferAmount.FIVE;
            case 4 -> TransferAmount.TEN;
            case 5 -> TransferAmount.CUSTOM;
            case 7 -> TransferAmount.ALL;
            default -> TransferAmount.ONE;
        };
    }

    /**
     * Set the transfer mode for subsequent bank operations.
     */
    public boolean setTransferMode(TransferAmount mode) {
        if (!isOpen()) return false;
        return switch (mode) {
            case ONE -> queueRawAction(1, -1, INTERFACE_ID << 16 | 93);
            case FIVE -> queueRawAction(2, -1, INTERFACE_ID << 16 | 96);
            case TEN -> queueRawAction(3, -1, INTERFACE_ID << 16 | 99);
            case ALL -> queueRawAction(4, -1, INTERFACE_ID << 16 | 103);
            case CUSTOM -> queueRawAction(5, -1, INTERFACE_ID << 16 | 106);
            default -> false;
        };
    }

    /**
     * Start editing the custom withdraw amount.
     */
    public boolean startCustomAmount() {
        if (!isOpen()) return false;
        if (isEditingCustomAmount()) return true;
        // Click the custom amount button (517 << 16 | 98)
        api.queueAction(new GameAction(ActionTypes.COMPONENT, 1, -1, INTERFACE_ID << 16 | 98));
        return true;
    }

    /**
     * Finish editing the custom amount by entering a value.
     */
    public boolean finishCustomAmount(int amount) {
        if (!isOpen() || !isEditingCustomAmount()) return false;
        api.fireKeyTrigger(INPUT_INTERFACE, 0, String.valueOf(amount));
        return true;
    }

    // ========================== Bank State ==========================

    /**
     * Returns the currently configured custom withdraw amount.
     *
     * @return the withdraw amount
     */
    public int getWithdrawAmount() {
        return api.getVarp(VARP_WITHDRAW_AMOUNT);
    }

    /**
     * Returns the currently selected preset page (0 for presets 1-9, 1 for presets 10-18).
     *
     * @return the preset page index
     */
    public int getPresetPage() {
        return api.getVarbit(VARBIT_PRESET_PAGE);
    }

    /**
     * Returns the currently selected side panel view in the bank interface.
     *
     * @return the side view (backpack, equipment, or familiar)
     */
    public SideView view() {
        return switch (api.getVarbit(VARBIT_SIDE_VIEW)) {
            case 0 -> SideView.BACKPACK;
            case 2 -> SideView.EQUIPMENT;
            default -> SideView.FAMILIAR;
        };
    }

    /**
     * Returns the current withdraw mode (item or noted form).
     *
     * @return the withdraw mode
     */
    public WithdrawMode withdrawMode() {
        return switch (api.getVarp(VARP_WITHDRAW_MODE)) {
            case 1 -> WithdrawMode.NOTE;
            default -> WithdrawMode.ITEM;
        };
    }

    /**
     * Sets the withdraw mode (item or noted form).
     * Clicks the toggle button on the bank interface to switch modes.
     *
     * @param mode the desired withdraw mode
     * @return {@code true} if the mode was set or already matches
     */
    public boolean setWithdrawMode(WithdrawMode mode) {
        if (!isOpen()) return false;
        if (withdrawMode() == mode) return true;
        api.queueAction(new GameAction(ActionTypes.COMPONENT, 1, -1, HASH_WITHDRAW_MODE_TOGGLE));
        return true;
    }

    /**
     * Closes the bank interface.
     *
     * @return {@code true} if the close action was queued (or bank was already closed)
     */
    public boolean close() {
        if (!isOpen()) return true;
        api.queueAction(new GameAction(ActionTypes.COMPONENT, 1, -1, HASH_CLOSE_BUTTON));
        return true;
    }

    /**
     * Returns the current bank interface setting (transfer or presets mode).
     *
     * @return the bank setting
     */
    public BankSetting setting() {
        return switch (api.getVarbit(VARBIT_BANK_SETTING)) {
            case 1 -> BankSetting.PRESETS;
            default -> BankSetting.TRANSFER;
        };
    }

    // ========================== Helpers ==========================

    /**
     * Find a component in the bank grid (component 201) that holds the given item.
     */
    private Component findBankItem(int itemId) {
        List<Component> comps = api.queryComponents(ComponentFilter.builder()
                .interfaceId(INTERFACE_ID)
                .itemId(itemId)
                .build());
        // Strictly match bank grid: componentId == 201
        return comps.stream()
                .filter(c -> c.componentId() == BANK_COMPONENT)
                .findFirst().orElse(null);
    }

    /**
     * Find a component in the backpack section (component 15) of the bank interface.
     */
    private Component findBackpackItem(int itemId) {
        List<Component> comps = api.queryComponents(ComponentFilter.builder()
                .interfaceId(INTERFACE_ID)
                .itemId(itemId)
                .build());
        // Strictly match backpack: componentId == 15
        return comps.stream()
                .filter(c -> c.componentId() == BACKPACK_COMPONENT)
                .findFirst().orElse(null);
    }

    /**
     * Find a component by its right-click option text within the bank interface.
     */
    private boolean interactByOption(String option) {
        List<Component> comps = api.queryComponents(ComponentFilter.builder()
                .interfaceId(INTERFACE_ID)
                .optionPattern(option)
                .optionMatchType("contains")
                .build());
        if (comps.isEmpty()) return false;
        Component comp = comps.getFirst();
        return interactComponent(comp, option);
    }

    private boolean interactComponent(Component comp, String option) {
        List<String> options = api.getComponentOptions(comp.interfaceId(), comp.componentId());
        for (int i = 0; i < options.size(); i++) {
            if (options.get(i).contains(option)) {
                return queueComponentAction(api, comp, i + 1);
            }
        }
        // Fallback: interact with default option
        return queueComponentAction(api, comp, 1);
    }

    private boolean queueRawAction(int optionIndex, int subComponent, int hash) {
        api.queueAction(new GameAction(ActionTypes.COMPONENT, optionIndex, subComponent, hash));
        return true;
    }

    private int mapDepositOption(TransferAmount amount) {
        return switch (amount) {
            case ONE -> 2;
            case FIVE -> 3;
            case TEN -> 4;
            case CUSTOM -> 5;
            case ALL -> 7;
            default -> 1;
        };
    }

    /** Random delay between 400–700ms for human-like interaction pacing. */
    private static int randomDelay() {
        return ThreadLocalRandom.current().nextInt(400, 701);
    }

    /** Sleep the current (virtual) thread. */
    private static void sleep(int ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    // ========================== Enums ==========================

    /**
     * Transfer quantity presets for bank deposit and withdraw operations.
     */
    public enum TransferAmount {
        ONE, FIVE, TEN, ALL, CUSTOM, OTHER
    }

    /**
     * Bank withdraw mode: items are withdrawn as physical items or bank notes.
     */
    public enum WithdrawMode {
        ITEM, NOTE
    }

    /**
     * The side panel view shown alongside the bank grid.
     */
    public enum SideView {
        BACKPACK, EQUIPMENT, FAMILIAR
    }

    /**
     * The bank interface mode: standard transfer controls or preset selection.
     */
    public enum BankSetting {
        TRANSFER, PRESETS
    }
}
