package com.xapi.debugger;

import com.botwithus.bot.api.BotScript;
import com.botwithus.bot.api.GameAPI;
import com.botwithus.bot.api.ScriptCategory;
import com.botwithus.bot.api.ScriptManifest;
import com.botwithus.bot.api.ScriptContext;
import com.botwithus.bot.api.inventory.InventoryContainer;
import com.botwithus.bot.api.inventory.WoodBox;
import com.botwithus.bot.api.model.InventoryItem;
import com.botwithus.bot.api.ui.ScriptUI;
import imgui.ImGui;
import imgui.flag.ImGuiTableFlags;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

@ScriptManifest(
        name = "WoodBox Verifier",
        version = "3.0",
        author = "Xapi",
        description = "Verifies WoodBox API against live game data",
        category = ScriptCategory.UTILITY
)
public class WoodBoxTestScript implements BotScript {

    private GameAPI api;
    private WoodBox woodBox;
    private InventoryContainer storage;
    private final ScriptUI ui = new WoodBoxTestUI();

    private volatile String tierInfo = "None";
    private volatile int capacity = 0;
    private volatile int totalStored = 0;
    private volatile boolean empty = true;
    private final CopyOnWriteArrayList<LogEntry> logEntries = new CopyOnWriteArrayList<>();
    private final CopyOnWriteArrayList<ContainerEntry> containerEntries = new CopyOnWriteArrayList<>();

    @Override
    public void onStart(ScriptContext ctx) {
        this.api = ctx.getGameAPI();
        this.woodBox = new WoodBox(api);
        this.storage = new InventoryContainer(api, WoodBox.STORAGE_INVENTORY_ID);
        poll();
    }

    @Override public ScriptUI getUI() { return ui; }
    @Override public int onLoop() { poll(); return 500; }
    @Override public void onStop() {}

    private void poll() {
        if (api == null) return;

        WoodBox.Tier tier = woodBox.getEquippedTier();
        tierInfo = tier != null ? tier.name + " (level " + tier.level + ")" : "None";
        capacity = woodBox.getCapacity();
        totalStored = woodBox.getTotalStored();
        empty = woodBox.isEmpty();

        List<LogEntry> logs = new ArrayList<>();
        for (WoodBox.LogType lt : WoodBox.LogType.values()) {
            int count = woodBox.count(lt);
            boolean canStore = woodBox.canStore(lt);
            boolean full = woodBox.isFull(lt);
            logs.add(new LogEntry(lt.name(), lt.name, lt.itemId, lt.requiredTier, canStore, count, full));
        }
        logEntries.clear();
        logEntries.addAll(logs);

        List<InventoryItem> items = storage.getItems();
        List<ContainerEntry> cont = new ArrayList<>();
        for (InventoryItem item : items) {
            if (item.itemId() <= 0 || item.quantity() <= 0) continue;
            var type = api.getItemType(item.itemId());
            String name = (type != null && type.name() != null) ? type.name() : "?";
            cont.add(new ContainerEntry(item.slot(), item.itemId(), name, item.quantity()));
        }
        containerEntries.clear();
        containerEntries.addAll(cont);
    }

    private final class WoodBoxTestUI implements ScriptUI {
        @Override
        public void render() {
            ImGui.text("WoodBox Verifier v3");
            ImGui.separator();

            ImGui.text("Tier: " + tierInfo);
            ImGui.text("Capacity: " + capacity + "  |  Stored: " + totalStored + "  |  Empty: " + empty);
            ImGui.spacing();

            int flags = ImGuiTableFlags.Borders | ImGuiTableFlags.RowBg | ImGuiTableFlags.Resizable;
            if (ImGui.beginTable("##logs", 7, flags)) {
                ImGui.tableSetupColumn("Enum");
                ImGui.tableSetupColumn("Name");
                ImGui.tableSetupColumn("Item ID");
                ImGui.tableSetupColumn("Req Tier");
                ImGui.tableSetupColumn("Can Store?");
                ImGui.tableSetupColumn("Count");
                ImGui.tableSetupColumn("Full?");
                ImGui.tableHeadersRow();

                for (LogEntry e : logEntries) {
                    ImGui.tableNextRow();
                    col(e.enumName); col(e.logName); col(String.valueOf(e.itemId));
                    col(String.valueOf(e.reqTier));
                    ImGui.tableNextColumn();
                    colored(e.canStore, "YES", "no");
                    ImGui.tableNextColumn();
                    if (e.count > 0) ImGui.textColored(0.2f, 0.9f, 0.2f, 1f, String.valueOf(e.count));
                    else ImGui.text("0");
                    ImGui.tableNextColumn();
                    if (e.full) ImGui.textColored(1f, 0.3f, 0.3f, 1f, "FULL");
                    else ImGui.textColored(0.5f, 0.5f, 0.5f, 1f, "no");
                }
                ImGui.endTable();
            }

            ImGui.spacing();
            ImGui.text("Container 937 (" + containerEntries.size() + " types)");
            ImGui.separator();
            if (containerEntries.isEmpty()) {
                ImGui.textColored(0.5f, 0.5f, 0.5f, 1f, "Empty");
            } else if (ImGui.beginTable("##c937", 4, flags)) {
                ImGui.tableSetupColumn("Slot"); ImGui.tableSetupColumn("Item ID");
                ImGui.tableSetupColumn("Name"); ImGui.tableSetupColumn("Qty");
                ImGui.tableHeadersRow();
                for (ContainerEntry e : containerEntries) {
                    ImGui.tableNextRow();
                    col(String.valueOf(e.slot)); col(String.valueOf(e.itemId));
                    col(e.name); col(String.valueOf(e.qty));
                }
                ImGui.endTable();
            }
        }

        private void col(String t) { ImGui.tableNextColumn(); ImGui.text(t); }
        private void colored(boolean g, String yes, String no) {
            if (g) ImGui.textColored(0.2f, 0.9f, 0.2f, 1f, yes);
            else ImGui.textColored(0.5f, 0.5f, 0.5f, 1f, no);
        }
    }

    private record LogEntry(String enumName, String logName, int itemId, int reqTier,
                            boolean canStore, int count, boolean full) {}
    private record ContainerEntry(int slot, int itemId, String name, int qty) {}
}
