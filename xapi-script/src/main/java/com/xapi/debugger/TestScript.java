package com.xapi.debugger;

import com.botwithus.bot.api.BotScript;
import com.botwithus.bot.api.GameAPI;
import com.botwithus.bot.api.ScriptCategory;
import com.botwithus.bot.api.ScriptContext;
import com.botwithus.bot.api.ScriptManifest;
import com.botwithus.bot.api.inventory.ActionBar;
import com.botwithus.bot.api.inventory.ActionTypes;
import com.botwithus.bot.api.inventory.Backpack;
import com.botwithus.bot.api.inventory.ComponentHelper;
import com.botwithus.bot.api.log.BotLogger;
import com.botwithus.bot.api.log.LoggerFactory;
import com.botwithus.bot.api.model.Component;
import com.botwithus.bot.api.model.GameAction;
import com.botwithus.bot.api.model.InventoryItem;
import com.botwithus.bot.api.model.ItemType;
import com.botwithus.bot.api.ui.ScriptUI;
import imgui.ImGui;
import imgui.flag.ImGuiTableFlags;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CopyOnWriteArrayList;

@ScriptManifest(
        name = "Backpack Test",
        version = "1.1",
        author = "Xapi",
        description = "Backpack inspector with per-item drop actions",
        category = ScriptCategory.UTILITY
)
public class TestScript implements BotScript {

    private static final BotLogger log = LoggerFactory.getLogger(TestScript.class);
    private static final int IDLE_DELAY_MS = 250;
    private static final int ACTIVE_DELAY_MS = 120;
    private static final int DROP_BUFFER_MIN_MS = 300;
    private static final int DROP_BUFFER_MAX_MS = 600;
    private static final int DROP_OPTION_INDEX = 8;

    private final CopyOnWriteArrayList<BackpackEntry> backpackEntries = new CopyOnWriteArrayList<>();
    private final CopyOnWriteArrayList<ActionBarSlotEntry> actionBarEntries = new CopyOnWriteArrayList<>();
    private final CopyOnWriteArrayList<CompTextEntry> compTextEntries = new CopyOnWriteArrayList<>();
    private final Queue<String> dropQueue = new ConcurrentLinkedQueue<>();
    private final ScriptUI ui = new BackpackUi();

    private GameAPI api;
    private Backpack backpack;
    private ActionBar actionBar;

    private volatile String activeDropName;
    private volatile String statusMessage = "Starting...";
    private volatile int totalSlots;
    private volatile int occupiedSlots;
    private volatile int freeSlots;
    private volatile long nextDropAt;

    // Action bar state
    private volatile int activeBarPreset;
    private volatile int adrenaline;
    private volatile boolean barOpen;
    private volatile boolean barLocked;
    private volatile boolean barMinimized;
    private volatile boolean textScanRequested;
    private volatile boolean textScanDone;

    @Override
    public void onStart(ScriptContext ctx) {
        this.api = ctx.getGameAPI();
        this.backpack = new Backpack(api);
        this.actionBar = new ActionBar(api);
        refreshBackpackSnapshot();
        refreshActionBarSnapshot();
        statusMessage = "Ready";
        log.info("[BackpackTest] Started");
    }

    @Override
    public ScriptUI getUI() {
        return ui;
    }

    @Override
    public int onLoop() {
        refreshBackpackSnapshot();
        refreshActionBarSnapshot();
        processDropQueue();
        return activeDropName == null && dropQueue.isEmpty() ? IDLE_DELAY_MS : ACTIVE_DELAY_MS;
    }

    @Override
    public void onStop() {
        log.info("[BackpackTest] Stopped");
    }

    private void refreshBackpackSnapshot() {
        if (backpack == null || api == null) {
            return;
        }

        List<InventoryItem> slots = backpack.getAllSlots();
        totalSlots = slots.size();

        Map<String, BackpackAccumulator> grouped = new LinkedHashMap<>();
        int emptySlots = 0;

        for (InventoryItem item : slots) {
            if (item.itemId() == -1) {
                emptySlots++;
                continue;
            }

            ItemType itemType = api.getItemType(item.itemId());
            String itemName = resolveItemName(item.itemId(), itemType);
            grouped.computeIfAbsent(itemName, BackpackAccumulator::new).add(item);
        }

        freeSlots = emptySlots;
        occupiedSlots = totalSlots - freeSlots;

        backpackEntries.clear();
        grouped.values().stream()
                .map(BackpackAccumulator::toEntry)
                .forEach(backpackEntries::add);
    }

    private static final int[] ACTION_BAR_INTERFACES = {1430, 1670};

    private void refreshActionBarSnapshot() {
        if (api == null) {
            return;
        }

        barOpen = api.isInterfaceOpen(1430);
        activeBarPreset = actionBar.getActiveBar();
        adrenaline = actionBar.getAdrenaline();
        barLocked = actionBar.isLocked();
        barMinimized = actionBar.isMinimized();

        // Build a sprite → ability name map from all 15 action bar presets
        Map<Integer, String> spriteToName = new java.util.HashMap<>();
        try {
            spriteToName = actionBar.buildSpriteNameMap();
        } catch (Exception e) {
            log.debug("[ActionBar] Sprite map build failed: {}", e.getMessage());
        }

        // Query components with options from action bar interfaces
        List<ActionBarSlotEntry> entries = new ArrayList<>();

        for (int ifaceId : ACTION_BAR_INTERFACES) {
            if (!api.isInterfaceOpen(ifaceId)) continue;

            List<Component> comps = api.queryComponents(
                    com.botwithus.bot.api.query.ComponentFilter.builder()
                            .interfaceId(ifaceId)
                            .build());
            if (comps == null) continue;

            for (Component comp : comps) {
                int itemId = comp.itemId();
                int spriteId = comp.spriteId();

                // Resolve name: item name from cache, or ability name from sprite lookup
                String name = "";
                if (itemId > 0) {
                    ItemType type = api.getItemType(itemId);
                    name = (type != null && type.name() != null) ? type.name() : "Item " + itemId;
                } else if (spriteId > 0 && spriteToName.containsKey(spriteId)) {
                    name = spriteToName.get(spriteId);
                }

                // Fetch options, filter blanks
                List<String> rawOptions = api.getComponentOptions(comp.interfaceId(), comp.componentId());
                List<String> options = (rawOptions == null) ? List.of()
                        : rawOptions.stream().filter(o -> o != null && !o.isBlank()).toList();

                // Skip components with no options
                if (options.isEmpty()) continue;

                entries.add(new ActionBarSlotEntry(
                        ifaceId, comp.componentId(), comp.subComponentId(),
                        itemId, spriteId, name, options));
            }
        }

        actionBarEntries.clear();
        actionBarEntries.addAll(entries);

        // Run text scan if requested from UI
        if (textScanRequested) {
            textScanRequested = false;
            scanComponentTexts();
            textScanDone = true;
        }
    }

    /**
     * Scans action bar interfaces by fetching children of component 0
     * (the same way the Xapi debugger does), then reads text/options for each child.
     */
    private void scanComponentTexts() {
        List<CompTextEntry> results = new ArrayList<>();

        for (int ifaceId : ACTION_BAR_INTERFACES) {
            if (!api.isInterfaceOpen(ifaceId)) continue;

            // Get all children of the root component (0), same as InspectorCollector
            List<Component> children = api.getComponentChildren(ifaceId, 0);
            if (children == null || children.isEmpty()) continue;

            for (Component child : children) {
                int compId = child.componentId();

                String text = api.getComponentText(child.interfaceId(), compId);
                if (text == null) text = "";

                int spriteId = child.spriteId();

                List<String> rawOptions = api.getComponentOptions(child.interfaceId(), compId);
                List<String> options = (rawOptions == null) ? List.of()
                        : rawOptions.stream().filter(o -> o != null && !o.isBlank()).toList();

                // Get sub-children count
                List<Component> subChildren = api.getComponentChildren(child.interfaceId(), compId);
                int childCount = (subChildren != null) ? subChildren.size() : 0;

                // Skip components with no options
                if (options.isEmpty()) continue;

                results.add(new CompTextEntry(ifaceId, compId, text, spriteId, options, childCount));
            }
        }

        compTextEntries.clear();
        compTextEntries.addAll(results);
    }

    private void processDropQueue() {
        if (backpack == null) {
            return;
        }

        if (activeDropName == null) {
            String nextItem = dropQueue.poll();
            if (nextItem == null) {
                if (totalSlots == 0) {
                    statusMessage = "Waiting for backpack data...";
                } else if ("Starting...".equals(statusMessage) || "Waiting for backpack data...".equals(statusMessage)) {
                    statusMessage = "Ready";
                }
                return;
            }

            activeDropName = nextItem;
            statusMessage = "Dropping all " + nextItem;
            nextDropAt = 0L;
            log.info("[BackpackTest] Starting drop for {}", nextItem);
        }

        long now = System.currentTimeMillis();
        if (now < nextDropAt) {
            return;
        }

        String targetName = activeDropName;
        int remainingCount = backpack.container().countExact(targetName);
        if (remainingCount <= 0) {
            statusMessage = "Finished dropping " + targetName;
            activeDropName = null;
            nextDropAt = 0L;
            refreshBackpackSnapshot();
            log.info("[BackpackTest] Finished dropping {}", targetName);
            return;
        }

        DropTarget target = findNextDropTarget(targetName);
        if (target == null) {
            statusMessage = "Could not find slot to drop for " + targetName;
            log.warn("[BackpackTest] Could not find slot to drop for {}", targetName);
            activeDropName = null;
            nextDropAt = 0L;
            return;
        }

        api.queueAction(new GameAction(
                ActionTypes.CONTAINER_ACTION,
                DROP_OPTION_INDEX,
                target.component().subComponentId(),
                ComponentHelper.componentHash(target.component())
        ));

        int bufferMs = ThreadLocalRandom.current().nextInt(DROP_BUFFER_MIN_MS, DROP_BUFFER_MAX_MS + 1);
        nextDropAt = now + bufferMs;
        statusMessage = "Dropping " + targetName + " from slot " + (target.slot() + 1)
                + " (" + remainingCount + " remaining, next in " + bufferMs + "ms)";
    }

    private void queueDrop(String itemName) {
        if (itemName == null || itemName.isBlank()) {
            return;
        }

        if (itemName.equalsIgnoreCase(activeDropName) || isQueued(itemName)) {
            return;
        }

        dropQueue.offer(itemName);
        statusMessage = "Queued drop for " + itemName;
        log.info("[BackpackTest] Queued drop for {}", itemName);
    }

    private void queueDropAll() {
        int queuedCount = 0;
        for (BackpackEntry entry : backpackEntries) {
            String itemName = entry.name();
            if (itemName.equalsIgnoreCase(activeDropName) || isQueued(itemName)) {
                continue;
            }
            dropQueue.offer(itemName);
            queuedCount++;
        }

        if (queuedCount > 0) {
            statusMessage = "Queued drop all for " + queuedCount + " item groups";
            log.info("[BackpackTest] Queued drop all for {} item groups", queuedCount);
        }
    }

    private boolean isQueued(String itemName) {
        for (String queuedItem : dropQueue) {
            if (queuedItem.equalsIgnoreCase(itemName)) {
                return true;
            }
        }
        return false;
    }

    private static String resolveItemName(int itemId, ItemType itemType) {
        if (itemType == null || itemType.name() == null || itemType.name().isBlank()) {
            return "Item " + itemId;
        }
        return itemType.name();
    }

    private DropTarget findNextDropTarget(String targetName) {
        for (InventoryItem item : backpack.getAllSlots()) {
            if (item.itemId() == -1) {
                continue;
            }

            ItemType itemType = api.getItemType(item.itemId());
            String itemName = resolveItemName(item.itemId(), itemType);
            if (!itemName.equalsIgnoreCase(targetName)) {
                continue;
            }

            Component component = api.getComponentChildren(Backpack.INTERFACE_ID, Backpack.COMPONENT_ID).stream()
                    .filter(c -> c.subComponentId() == item.slot())
                    .findFirst()
                    .orElse(null);
            if (component != null) {
                return new DropTarget(itemName, item.slot(), component);
            }
        }
        return null;
    }

    /**
     * Resolves the action bar slot number (1-14) from an interface ID and component ID.
     * Interface 1430: slot 1 = comp 64, stride 13 (64, 77, 90, 103, ...)
     * Interface 1670: slot 1 = comp 21, stride 13 (21, 34, 47, 60, ...)
     *
     * @return slot number 1-14, or -1 if not a slot component
     */
    private static int resolveSlotNumber(int interfaceId, int componentId) {
        int base;
        if (interfaceId == 1430) {
            base = 64;
        } else if (interfaceId == 1670) {
            base = 21;
        } else {
            return -1;
        }
        int offset = componentId - base;
        if (offset < 0 || offset % 13 != 0) return -1;
        int slot = offset / 13 + 1;
        return (slot >= 1 && slot <= 14) ? slot : -1;
    }

    private static String formatSlots(List<Integer> slots) {
        StringBuilder builder = new StringBuilder("Slots: ");
        for (int i = 0; i < slots.size(); i++) {
            if (i > 0) {
                builder.append(", ");
            }
            builder.append(slots.get(i) + 1);
        }
        return builder.toString();
    }

    private final class BackpackUi implements ScriptUI {

        @Override
        public void render() {
            ImGui.text("Backpack Inspector");
            ImGui.separator();
            ImGui.text("Status: " + statusMessage);
            ImGui.text(String.format("Slots: %d/%d used  |  %d free", occupiedSlots, totalSlots, freeSlots));

            if (ImGui.button("DROP ALL")) {
                queueDropAll();
            }

            if (activeDropName != null) {
                ImGui.sameLine();
                ImGui.textColored(0.95f, 0.55f, 0.25f, 1.0f, "Active drop: " + activeDropName);
            } else if (!dropQueue.isEmpty()) {
                ImGui.sameLine();
                ImGui.textColored(0.85f, 0.80f, 0.25f, 1.0f, "Queued drops: " + dropQueue.size());
            }

            ImGui.spacing();

            if (backpackEntries.isEmpty()) {
                ImGui.text(totalSlots == 0 ? "Waiting for backpack snapshot..." : "Backpack is empty.");
            } else {
                int flags = ImGuiTableFlags.Borders | ImGuiTableFlags.RowBg | ImGuiTableFlags.Resizable;
                if (ImGui.beginTable("##backpack_items", 4, flags)) {
                ImGui.tableSetupColumn("Item");
                ImGui.tableSetupColumn("Quantity");
                ImGui.tableSetupColumn("Slots");
                ImGui.tableSetupColumn("Action");
                ImGui.tableHeadersRow();

                for (BackpackEntry entry : backpackEntries) {
                    ImGui.tableNextRow();

                    ImGui.tableNextColumn();
                    ImGui.text(entry.name());

                    ImGui.tableNextColumn();
                    ImGui.text(entry.quantity() + "x");

                    ImGui.tableNextColumn();
                    if (entry.slotCount() == 1) {
                        ImGui.text("Slot " + (entry.slots().getFirst() + 1));
                    } else {
                        ImGui.text(entry.slotCount() + " slots");
                    }
                    if (ImGui.isItemHovered()) {
                        ImGui.setTooltip(formatSlots(entry.slots()));
                    }

                    ImGui.tableNextColumn();
                    if (entry.name().equalsIgnoreCase(activeDropName)) {
                        ImGui.textColored(0.95f, 0.55f, 0.25f, 1.0f, "Dropping...");
                    } else if (isQueued(entry.name())) {
                        ImGui.textColored(0.85f, 0.80f, 0.25f, 1.0f, "Queued");
                    } else if (ImGui.button("DROP##" + entry.name())) {
                        queueDrop(entry.name());
                    }
                }

                    ImGui.endTable();
                }
            }

            // ========================== Action Bar Section ==========================
            ImGui.spacing();
            ImGui.spacing();
            ImGui.separator();
            ImGui.text("Action Bar Inspector");
            ImGui.separator();

            ImGui.text(String.format("Open: %s  |  Bar: %d  |  Adrenaline: %d%%  |  Locked: %s  |  Minimized: %s",
                    barOpen, activeBarPreset, adrenaline, barLocked, barMinimized));
            ImGui.spacing();

            if (actionBarEntries.isEmpty()) {
                ImGui.text(barOpen ? "No components with options found." : "Action bar not visible.");
            } else {
                int abFlags = ImGuiTableFlags.Borders | ImGuiTableFlags.RowBg | ImGuiTableFlags.Resizable;

                for (int ifaceId : ACTION_BAR_INTERFACES) {
                    List<ActionBarSlotEntry> ifaceEntries = actionBarEntries.stream()
                            .filter(e -> e.interfaceId() == ifaceId)
                            .toList();
                    if (ifaceEntries.isEmpty()) continue;

                    ImGui.spacing();
                    ImGui.textColored(0.4f, 0.8f, 1.0f, 1.0f,
                            String.format("Interface %d  (%d components)", ifaceId, ifaceEntries.size()));

                    if (ImGui.beginTable("##actionbar_" + ifaceId, 7, abFlags)) {
                        ImGui.tableSetupColumn("Slot");
                        ImGui.tableSetupColumn("Comp ID");
                        ImGui.tableSetupColumn("Sub ID");
                        ImGui.tableSetupColumn("Name");
                        ImGui.tableSetupColumn("Item ID");
                        ImGui.tableSetupColumn("Sprite ID");
                        ImGui.tableSetupColumn("Options");
                        ImGui.tableHeadersRow();

                        for (ActionBarSlotEntry entry : ifaceEntries) {
                            ImGui.tableNextRow();

                            ImGui.tableNextColumn();
                            int slot = resolveSlotNumber(entry.interfaceId(), entry.componentId());
                            if (slot > 0) {
                                ImGui.text(String.valueOf(slot));
                            } else {
                                ImGui.textColored(0.5f, 0.5f, 0.5f, 1.0f, "-");
                            }

                            ImGui.tableNextColumn();
                            ImGui.text(String.valueOf(entry.componentId()));

                            ImGui.tableNextColumn();
                            ImGui.text(entry.subComponentId() >= 0 ? String.valueOf(entry.subComponentId()) : "-");

                            ImGui.tableNextColumn();
                            if (!entry.name().isEmpty()) {
                                ImGui.text(entry.name());
                            } else {
                                ImGui.textColored(0.5f, 0.5f, 0.5f, 1.0f, "-");
                            }

                            ImGui.tableNextColumn();
                            ImGui.text(entry.itemId() > 0 ? String.valueOf(entry.itemId()) : "-");

                            ImGui.tableNextColumn();
                            ImGui.text(entry.spriteId() >= 0 ? String.valueOf(entry.spriteId()) : "-");

                            ImGui.tableNextColumn();
                            String optStr = String.join(", ", entry.options());
                            ImGui.text(optStr);
                            if (ImGui.isItemHovered()) {
                                StringBuilder tooltip = new StringBuilder();
                                for (int i = 0; i < entry.options().size(); i++) {
                                    if (i > 0) tooltip.append("\n");
                                    tooltip.append((i + 1)).append(": ").append(entry.options().get(i));
                                }
                                ImGui.setTooltip(tooltip.toString());
                            }
                        }

                        ImGui.endTable();
                    }
                }
            }

            // ========================== Component Text Scan ==========================
            ImGui.spacing();
            ImGui.separator();
            if (ImGui.button("Scan Component Texts (0-200)")) {
                textScanRequested = true;
                textScanDone = false;
            }
            if (textScanDone) {
                ImGui.sameLine();
                ImGui.text("Found " + compTextEntries.size() + " components with content");
            }

            if (!compTextEntries.isEmpty()) {
                ImGui.spacing();
                int scanFlags = ImGuiTableFlags.Borders | ImGuiTableFlags.RowBg
                        | ImGuiTableFlags.ScrollY | ImGuiTableFlags.Resizable;

                // Group by interface and render a table per interface
                for (int ifaceId : ACTION_BAR_INTERFACES) {
                    List<CompTextEntry> ifaceEntries = compTextEntries.stream()
                            .filter(e -> e.interfaceId() == ifaceId)
                            .toList();
                    if (ifaceEntries.isEmpty()) continue;

                    ImGui.spacing();
                    ImGui.textColored(0.4f, 1.0f, 0.6f, 1.0f,
                            String.format("Interface %d - Text Scan (%d hits)", ifaceId, ifaceEntries.size()));

                    if (ImGui.beginTable("##textscan_" + ifaceId, 5, scanFlags)) {
                        ImGui.tableSetupColumn("Comp ID");
                        ImGui.tableSetupColumn("Text");
                        ImGui.tableSetupColumn("Sprite ID");
                        ImGui.tableSetupColumn("Children");
                        ImGui.tableSetupColumn("Options");
                        ImGui.tableHeadersRow();

                        for (CompTextEntry te : ifaceEntries) {
                            ImGui.tableNextRow();

                            ImGui.tableNextColumn();
                            ImGui.text(String.valueOf(te.componentId()));

                            ImGui.tableNextColumn();
                            if (te.text().isEmpty()) {
                                ImGui.textColored(0.5f, 0.5f, 0.5f, 1.0f, "-");
                            } else {
                                ImGui.text(te.text());
                                if (ImGui.isItemHovered() && te.text().length() > 30) {
                                    ImGui.setTooltip(te.text());
                                }
                            }

                            ImGui.tableNextColumn();
                            ImGui.text(te.spriteId() >= 0 ? String.valueOf(te.spriteId()) : "-");

                            ImGui.tableNextColumn();
                            ImGui.text(te.childCount() > 0 ? String.valueOf(te.childCount()) : "-");

                            ImGui.tableNextColumn();
                            if (te.options().isEmpty()) {
                                ImGui.textColored(0.5f, 0.5f, 0.5f, 1.0f, "-");
                            } else {
                                String optStr = String.join(", ", te.options());
                                ImGui.text(optStr);
                                if (ImGui.isItemHovered()) {
                                    StringBuilder tooltip = new StringBuilder();
                                    for (int i = 0; i < te.options().size(); i++) {
                                        if (i > 0) tooltip.append("\n");
                                        tooltip.append((i + 1)).append(": ").append(te.options().get(i));
                                    }
                                    ImGui.setTooltip(tooltip.toString());
                                }
                            }
                        }

                        ImGui.endTable();
                    }
                }
            }
        }
    }

    private record BackpackEntry(String name, int quantity, int slotCount, List<Integer> slots) {
    }

    private static final class BackpackAccumulator {

        private final String name;
        private final List<Integer> slots = new ArrayList<>();
        private int quantity;

        private BackpackAccumulator(String name) {
            this.name = name;
        }

        private void add(InventoryItem item) {
            quantity += item.quantity();
            slots.add(item.slot());
        }

        private BackpackEntry toEntry() {
            return new BackpackEntry(name, quantity, slots.size(), List.copyOf(slots));
        }
    }

    private record DropTarget(String name, int slot, Component component) {
    }


    private record ActionBarSlotEntry(
            int interfaceId, int componentId, int subComponentId,
            int itemId, int spriteId, String name, List<String> options
    ) {}

    private record CompTextEntry(
            int interfaceId, int componentId, String text,
            int spriteId, List<String> options, int childCount
    ) {}
}
