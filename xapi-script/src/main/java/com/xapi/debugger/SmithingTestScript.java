package com.xapi.debugger;

import com.botwithus.bot.api.*;
import com.botwithus.bot.api.inventory.Production;
import com.botwithus.bot.api.inventory.Smithing;
import com.botwithus.bot.api.inventory.Smithing.Quality;
import com.botwithus.bot.api.log.BotLogger;
import com.botwithus.bot.api.log.LoggerFactory;
import com.botwithus.bot.api.ui.ScriptUI;

import imgui.ImGui;
import imgui.flag.ImGuiCol;
import imgui.flag.ImGuiTableFlags;
import imgui.flag.ImGuiTreeNodeFlags;
import imgui.type.ImInt;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Interactive test script for the Smithing &amp; Production APIs.
 * <p>Open a smithing/smelting/production interface in-game, then use
 * the GUI buttons to exercise every API method and view the results live.</p>
 */
@ScriptManifest(
        name = "Smithing Test",
        version = "1.0",
        author = "Xapi",
        description = "Interactive test bench for the Smithing & Production APIs",
        category = ScriptCategory.SMITHING
)
public class SmithingTestScript implements BotScript {

    private static final BotLogger log = LoggerFactory.getLogger(SmithingTestScript.class);

    private ScriptContext ctx;
    private GameAPI api;
    private Smithing smithing;
    private Production production;

    // ── Live state (collected per-tick) ──────────────────────────────────
    private volatile boolean smithOpen;
    private volatile boolean smithIsSmelting;
    private volatile int smithSelectedItem;
    private volatile String smithSelectedName;
    private volatile int smithQuantity;
    private volatile int smithQualityTier;
    private volatile String smithProductName;
    private volatile int smithMaterial;
    private volatile int smithProduct;
    private volatile int smithLocation;
    private volatile int smithOutfit1;
    private volatile int smithOutfit2;
    private volatile int smithHeatEff;
    private volatile List<Smithing.GridEntry> smithMaterials = List.of();
    private volatile List<Smithing.GridEntry> smithProducts = List.of();
    private volatile List<Integer> smithBonuses = List.of();
    private volatile boolean smithFullOutfit;
    private volatile boolean smithVarrockArmour;
    private volatile boolean smithExceedsBackpack;

    // Active smithing progress
    private volatile boolean activelySmithing;
    private volatile Smithing.UnfinishedItem activeItem;
    private volatile List<Smithing.UnfinishedItem> allUnfinished = List.of();
    private volatile int maxHeat;
    private volatile int heatPercent;
    private volatile String heatBand = "Zero";
    private volatile int progPerStrike;
    private volatile int reheatRate;

    private volatile boolean prodOpen;
    private volatile boolean prodProducing;
    private volatile boolean prodComplete;
    private volatile int prodTotal;
    private volatile int prodRemaining;
    private volatile int prodMade;
    private volatile int prodPercent;
    private volatile String prodProductName;
    private volatile String prodTimeText;
    private volatile String prodCounterText;

    // ── Test log ─────────────────────────────────────────────────────────
    private final List<String> testLog = new CopyOnWriteArrayList<>();
    private static final int MAX_LOG = 200;

    // ── UI state ─────────────────────────────────────────────────────────
    private final ImInt qualityCombo = new ImInt(0);
    private final ImInt gridRowInput = new ImInt(0);
    private final ImInt gridSubInput = new ImInt(0);

    @Override
    public void onStart(ScriptContext ctx) {
        this.ctx = ctx;
        this.api = ctx.getGameAPI();
        this.smithing = new Smithing(api);
        this.production = new Production(api);

        logTest("Script started — open a smithing, smelting, or production interface to test");
    }

    @Override
    public int onLoop() {
        collectData();
        return 600;
    }

    @Override
    public void onStop() {
        log.info("[SmithingTest] Stopped — {} log entries", testLog.size());
    }

    @Override
    public ScriptUI getUI() {
        return () -> renderUI();
    }

    // ── Data collection (called from onLoop every ~600ms) ─────────────────

    private void collectData() {

        // Smithing state
        smithOpen = smithing.isOpen();
        if (smithOpen) {
            smithIsSmelting = smithing.isSmelting();
            smithSelectedItem = smithing.getSelectedItem();
            smithSelectedName = smithing.getSelectedItemName();
            smithQuantity = smithing.getQuantity();
            smithQualityTier = smithing.getQualityTier();
            smithProductName = smithing.getProductName();
            smithMaterial = smithing.getMaterial();
            smithProduct = smithing.getProduct();
            smithLocation = smithing.getLocation();
            smithOutfit1 = smithing.getOutfitBonusState();
            smithOutfit2 = api.getVarbit(Smithing.VARBIT_OUTFIT_BONUS_2);
            smithHeatEff = smithing.getHeatEfficiency();
            smithMaterials = smithing.getAllMaterialEntries();
            smithProducts = smithing.getAllProductEntries();
            smithBonuses = smithing.getActiveBonusItems();
            smithFullOutfit = smithing.isWearingBlacksmithOutfit();
            smithVarrockArmour = smithing.isWearingVarrockArmour();
            smithExceedsBackpack = smithing.canExceedBackpackLimit();
        }

        // Active smithing progress (independent of interface)
        // Scan backpack for all unfinished items using getItemVars()
        List<Smithing.UnfinishedItem> unfinished = smithing.getAllUnfinishedItems();
        allUnfinished = unfinished;
        activelySmithing = !unfinished.isEmpty();
        if (activelySmithing) {
            Smithing.UnfinishedItem first = unfinished.get(0);
            activeItem = first;
            // Compute heat from the active item directly
            int mh = first.creatingItemId() > 0
                    ? smithing.getMaxHeatForItem(first.creatingItemId())
                    : smithing.getMaxHeat();
            maxHeat = mh;
            heatPercent = mh > 0 ? (first.currentHeat() * 100) / mh : 0;
            if (heatPercent >= 67) { heatBand = "High"; progPerStrike = 20; }
            else if (heatPercent >= 34) { heatBand = "Medium"; progPerStrike = 16; }
            else if (heatPercent >= 1) { heatBand = "Low"; progPerStrike = 13; }
            else { heatBand = "Zero"; progPerStrike = 10; }
            reheatRate = smithing.getReheatingRate();
        } else {
            activeItem = null;
            maxHeat = 0;
            heatPercent = 0;
            heatBand = "Zero";
            progPerStrike = 10;
            reheatRate = 0;
        }

        // Production progress state
        prodProducing = production.isProducing();
        prodOpen = production.isOpen();
        if (prodProducing) {
            prodComplete = production.isProductionComplete();
            prodTotal = production.getProgressTotal();
            prodRemaining = production.getProgressRemaining();
            prodMade = production.getProgressMade();
            prodPercent = production.getProgressPercent();
            prodProductName = production.getProgressProductName();
            prodTimeText = production.getProgressTimeText();
            prodCounterText = production.getProgressCounterText();
        }
    }

    // ── UI Rendering ─────────────────────────────────────────────────────

    private void renderUI() {
        if (ImGui.beginTabBar("##smithtest_tabs")) {
            if (ImGui.beginTabItem("Smithing")) {
                renderSmithingTab();
                ImGui.endTabItem();
            }
            if (ImGui.beginTabItem("Active Progress")) {
                renderActiveProgressTab();
                ImGui.endTabItem();
            }
            if (ImGui.beginTabItem("Production Progress")) {
                renderProductionTab();
                ImGui.endTabItem();
            }
            if (ImGui.beginTabItem("Test Log")) {
                renderLogTab();
                ImGui.endTabItem();
            }
            ImGui.endTabBar();
        }
    }

    // ── Smithing Tab ─────────────────────────────────────────────────────

    private void renderSmithingTab() {
        // Status
        ImGui.text("Interface: ");
        ImGui.sameLine();
        if (smithOpen) {
            ImGui.textColored(0.2f, 1f, 0.2f, 1f, "OPEN");
            ImGui.sameLine();
            ImGui.text(" — ");
            ImGui.sameLine();
            if (smithIsSmelting) {
                ImGui.textColored(1f, 0.6f, 0.2f, 1f, "SMELTING");
            } else {
                ImGui.textColored(0.4f, 0.7f, 1f, 1f, "SMITHING");
            }
        } else {
            ImGui.textColored(1f, 0.3f, 0.3f, 1f, "CLOSED");
            ImGui.spacing();
            ImGui.textColored(0.6f, 0.6f, 0.6f, 1f, "Open a smithing/smelting interface to begin testing.");
            return;
        }

        ImGui.spacing();
        ImGui.separator();

        // ── Live State ──
        if (ImGui.collapsingHeader("Live State", ImGuiTreeNodeFlags.DefaultOpen)) {
            int flags = ImGuiTableFlags.Borders | ImGuiTableFlags.RowBg | ImGuiTableFlags.SizingStretchProp;
            if (ImGui.beginTable("##st_state", 2, flags)) {
                ImGui.tableSetupColumn("Property", 0, 1.5f);
                ImGui.tableSetupColumn("Value", 0, 2f);
                ImGui.tableHeadersRow();

                stateRow("Mode", smithIsSmelting ? "Smelting" : "Smithing");
                stateRow("Selected Item", smithSelectedName != null
                        ? smithSelectedName + " (ID: " + smithSelectedItem + ")" : String.valueOf(smithSelectedItem));
                stateRow("Product Name", smithProductName);
                stateRow("Quantity", String.valueOf(smithQuantity));
                stateRow("Quality Tier", smithQualityTier + " (" + qualityName(smithQualityTier) + ")");
                stateRow("Material (dbrow)", String.valueOf(smithMaterial));
                stateRow("Product (dbrow)", String.valueOf(smithProduct));
                stateRow("Location", String.valueOf(smithLocation));
                stateRow("Outfit Bonus 1", String.valueOf(smithOutfit1));
                stateRow("Outfit Bonus 2", String.valueOf(smithOutfit2));
                stateRow("Heat Efficiency", String.valueOf(smithHeatEff));
                stateRow("Exceeds Backpack", String.valueOf(smithExceedsBackpack));
                stateRow("Full Blacksmith Outfit", String.valueOf(smithFullOutfit));
                stateRow("Varrock Armour", String.valueOf(smithVarrockArmour));
                stateRow("Active Bonuses", smithBonuses.isEmpty() ? "None" : smithBonuses.toString());

                ImGui.endTable();
            }
        }

        ImGui.spacing();
        ImGui.separator();

        // ── Actions ──
        if (ImGui.collapsingHeader("Actions", ImGuiTreeNodeFlags.DefaultOpen)) {
            // Make
            if (ImGui.button("Make")) {
                boolean ok = smithing.make();
                logTest("make() -> " + ok);
            }
            ImGui.sameLine();
            if (ImGui.button("Decrease Qty")) {
                boolean ok = smithing.decreaseQuantity();
                logTest("decreaseQuantity() -> " + ok);
            }
            ImGui.sameLine();
            if (ImGui.button("Increase Qty")) {
                boolean ok = smithing.increaseQuantity();
                logTest("increaseQuantity() -> " + ok);
            }

            ImGui.spacing();

            // Quality selection
            String[] qualityNames = {"Base", "+1", "+2", "+3", "+4", "+5", "Burial"};
            ImGui.setNextItemWidth(120);
            ImGui.combo("##quality", qualityCombo, qualityNames);
            ImGui.sameLine();
            if (ImGui.button("Select Quality")) {
                Quality q = qualityFromCombo(qualityCombo.get());
                if (q != null) {
                    boolean ok = smithing.selectQuality(q);
                    logTest("selectQuality(" + q + ") -> " + ok);
                }
            }
            ImGui.sameLine();
            if (ImGui.button("Select Quality + Make")) {
                Quality q = qualityFromCombo(qualityCombo.get());
                if (q != null) {
                    // Run blocking call on a virtual thread so it doesn't freeze the UI
                    Thread.startVirtualThread(() -> {
                        boolean ok = smithing.selectQualityAndMake(q);
                        logTest("selectQualityAndMake(" + q + ") -> " + ok);
                    });
                }
            }

            ImGui.spacing();

            // Grid selection
            ImGui.setNextItemWidth(80);
            ImGui.inputInt("Grid Row##gr", gridRowInput);
            ImGui.sameLine();
            ImGui.setNextItemWidth(80);
            ImGui.inputInt("Sub Index##si", gridSubInput);
            ImGui.sameLine();
            if (ImGui.button("Select Material")) {
                boolean ok = smithing.selectMaterial(gridRowInput.get(), gridSubInput.get());
                logTest("selectMaterial(" + gridRowInput.get() + ", " + gridSubInput.get() + ") -> " + ok);
            }
            ImGui.sameLine();
            if (ImGui.button("Select Product")) {
                boolean ok = smithing.selectProduct(gridRowInput.get(), gridSubInput.get());
                logTest("selectProduct(" + gridRowInput.get() + ", " + gridSubInput.get() + ") -> " + ok);
            }

            ImGui.spacing();

            // Await open
            if (ImGui.button("Await Open (3s)")) {
                Thread.startVirtualThread(() -> {
                    boolean ok = smithing.awaitOpen(3000);
                    logTest("awaitOpen(3000) -> " + ok);
                });
            }
        }

        ImGui.spacing();
        ImGui.separator();

        // ── Material Grid ──
        renderGridTable("Material Grid", smithMaterials, true);

        ImGui.spacing();
        ImGui.separator();

        // ── Product Grid ──
        renderGridTable("Product Grid", smithProducts, false);
    }

    private void renderGridTable(String label, List<Smithing.GridEntry> entries, boolean isMaterial) {
        if (ImGui.collapsingHeader(label)) {
            if (entries.isEmpty()) {
                ImGui.textColored(0.6f, 0.6f, 0.6f, 1f, "No entries.");
                return;
            }
            int flags = ImGuiTableFlags.Borders | ImGuiTableFlags.RowBg
                    | ImGuiTableFlags.SizingStretchProp | ImGuiTableFlags.ScrollY;
            float h = Math.min(entries.size() * 25f + 30f, 200f);
            String id = isMaterial ? "##st_mat" : "##st_prod";
            if (ImGui.beginTable(id, 4, flags, 0, h)) {
                ImGui.tableSetupColumn("Grid", 0, 0.3f);
                ImGui.tableSetupColumn("Sub", 0, 0.3f);
                ImGui.tableSetupColumn("Item ID", 0, 0.5f);
                ImGui.tableSetupColumn("Select", 0, 0.5f);
                ImGui.tableSetupScrollFreeze(0, 1);
                ImGui.tableHeadersRow();

                for (Smithing.GridEntry e : entries) {
                    ImGui.tableNextRow();
                    ImGui.tableSetColumnIndex(0);
                    ImGui.text(String.valueOf(e.gridIndex()));
                    ImGui.tableSetColumnIndex(1);
                    ImGui.text(String.valueOf(e.subIndex()));
                    ImGui.tableSetColumnIndex(2);
                    ImGui.text(String.valueOf(e.itemId()));
                    ImGui.tableSetColumnIndex(3);
                    if (ImGui.smallButton("Select##" + (isMaterial ? "m" : "p") + e.gridIndex() + "_" + e.subIndex())) {
                        boolean ok = isMaterial
                                ? smithing.selectMaterial(e.gridIndex(), e.subIndex())
                                : smithing.selectProduct(e.gridIndex(), e.subIndex());
                        logTest((isMaterial ? "selectMaterial" : "selectProduct")
                                + "(" + e.gridIndex() + ", " + e.subIndex() + ") -> " + ok);
                    }
                }
                ImGui.endTable();
            }
        }
    }

    // ── Production Progress Tab ──────────────────────────────────────────

    // ── Active Progress Tab ────────────────────────────────────────────

    private void renderActiveProgressTab() {
        // Status
        ImGui.text("Active Smithing: ");
        ImGui.sameLine();
        if (activelySmithing) {
            ImGui.textColored(0.2f, 1f, 0.2f, 1f, "YES");
        } else {
            ImGui.textColored(1f, 0.3f, 0.3f, 1f, "NO");
            ImGui.spacing();
            ImGui.textColored(0.6f, 0.6f, 0.6f, 1f,
                    "Start smithing at an anvil to see active progress here.");

            ImGui.spacing();
            ImGui.separator();
            ImGui.spacing();

            // Test buttons that work even when not active
            if (ImGui.collapsingHeader("API Tests", ImGuiTreeNodeFlags.DefaultOpen)) {
                if (ImGui.button("Check isActivelySmithing()")) {
                    logTest("isActivelySmithing() -> " + smithing.isActivelySmithing());
                }
                ImGui.sameLine();
                if (ImGui.button("Read varc 5121/5122")) {
                    logTest("varc 5121 (inv) = " + api.getVarcInt(5121)
                            + ", varc 5122 (slot) = " + api.getVarcInt(5122));
                }
                ImGui.sameLine();
                if (ImGui.button("Scan Unfinished")) {
                    List<Smithing.UnfinishedItem> items = smithing.getAllUnfinishedItems();
                    logTest("getAllUnfinishedItems() -> " + items.size() + " items");
                    for (Smithing.UnfinishedItem item : items) {
                        logTest("  slot=" + item.slot() + " creating=" + item.creatingName()
                                + " progress=" + item.currentProgress() + "/" + item.maxProgress()
                                + " heat=" + item.currentHeat() + " xp=" + item.experienceLeft());
                    }
                }

                ImGui.spacing();
                if (ImGui.button("Dump Backpack")) {
                    logTest("=== Backpack Dump (inv 93) ===");
                    for (int s = 0; s < 28; s++) {
                        try {
                            if (!api.isInventoryItemValid(93, s)) continue;
                            var item = api.getInventoryItem(93, s);
                            if (item == null || item.itemId() <= 0) continue;
                            String name = "?";
                            try {
                                var t = api.getItemType(item.itemId());
                                if (t != null) name = t.name();
                            } catch (Exception ignored) {}
                            int v22 = 0, v23 = 0, v24 = 0, v25 = 0;
                            try { v22 = api.getItemVarValue(93, s, 43222); } catch (Exception ignored) {}
                            try { v23 = api.getItemVarValue(93, s, 43223); } catch (Exception ignored) {}
                            try { v24 = api.getItemVarValue(93, s, 43224); } catch (Exception ignored) {}
                            try { v25 = api.getItemVarValue(93, s, 43225); } catch (Exception ignored) {}
                            logTest("  [" + s + "] " + name + " (ID:" + item.itemId()
                                    + ") vars: 43222=" + v22 + " 43223=" + v23
                                    + " 43224=" + v24 + " 43225=" + v25);
                        } catch (Exception e) {
                            logTest("  [" + s + "] ERROR: " + e.getMessage());
                        }
                    }
                }
                ImGui.sameLine();
                if (ImGui.button("Dump Unfinished Vars")) {
                    // Find first unfinished smithing item (ID 47068)
                    for (int s = 0; s < 28; s++) {
                        try {
                            if (!api.isInventoryItemValid(93, s)) continue;
                            var item = api.getInventoryItem(93, s);
                            if (item == null || item.itemId() != 47068) continue;
                            logTest("=== Unfinished item at slot " + s + " (ID:" + item.itemId() + ") ===");
                            // Dump ALL item vars
                            var vars = api.getItemVars(93, s);
                            logTest("  getItemVars(93, " + s + ") -> " + (vars != null ? vars.size() : "null") + " vars");
                            if (vars != null) {
                                for (var v : vars) {
                                    logTest("    varId=" + v.varId() + " value=" + v.value());
                                }
                            }
                            // Also try specific known var IDs with broad range
                            logTest("  Specific var probes:");
                            for (int vid : new int[]{43222, 43223, 43224, 43225, 43226, 43229,
                                    7801, 7802, 7805, 7806, 7807, 7808, 2655, 5456}) {
                                try {
                                    int val = api.getItemVarValue(93, s, vid);
                                    if (val != 0) logTest("    var " + vid + " = " + val);
                                } catch (Exception ignored) {}
                            }
                            // Try item type params
                            logTest("  Item type params:");
                            try {
                                var type = api.getItemType(47068);
                                if (type != null && type.params() != null) {
                                    for (var entry : type.params().entrySet()) {
                                        logTest("    param " + entry.getKey() + " = " + entry.getValue());
                                    }
                                }
                            } catch (Exception e) {
                                logTest("    getItemType error: " + e.getMessage());
                            }
                            break; // Only dump first one
                        } catch (Exception e) {
                            logTest("Slot " + s + " error: " + e.getMessage());
                        }
                    }
                }
                ImGui.sameLine();
                if (ImGui.button("Dump Varcs")) {
                    for (int v = 5119; v <= 5125; v++) {
                        try {
                            logTest("varc " + v + " = " + api.getVarcInt(v));
                        } catch (Exception e) {
                            logTest("varc " + v + " ERROR: " + e.getMessage());
                        }
                    }
                }

                ImGui.spacing();
                if (ImGui.button("3s Delay → Dump While Hovering")) {
                    logTest(">>> Hover over an unfinished item NOW! Reading in 3 seconds...");
                    Thread.startVirtualThread(() -> {
                        try { Thread.sleep(3000); } catch (InterruptedException ignored) {}
                        logTest("=== Reading varcs + item vars (hover active) ===");
                        // Read varcs
                        for (int v = 5119; v <= 5125; v++) {
                            try {
                                logTest("varc " + v + " = " + api.getVarcInt(v));
                            } catch (Exception e) {
                                logTest("varc " + v + " ERROR: " + e.getMessage());
                            }
                        }
                        // If varc 5121/5122 now have values, read item vars from them
                        int inv = api.getVarcInt(5121);
                        int slot = api.getVarcInt(5122);
                        logTest("varc 5121 (inv)=" + inv + " varc 5122 (slot)=" + slot);
                        if (inv >= 0 && slot >= 0) {
                            logTest("Reading item vars from inv=" + inv + " slot=" + slot + ":");
                            for (int vid : new int[]{43222, 43223, 43224, 43225}) {
                                try {
                                    logTest("  var " + vid + " = " + api.getItemVarValue(inv, slot, vid));
                                } catch (Exception e) {
                                    logTest("  var " + vid + " ERROR: " + e.getMessage());
                                }
                            }
                            // Also dump ALL vars on that slot
                            try {
                                var allVars = api.getItemVars(inv, slot);
                                logTest("  getItemVars(" + inv + ", " + slot + ") -> "
                                        + (allVars != null ? allVars.size() : "null") + " vars");
                                if (allVars != null) {
                                    for (var v : allVars) {
                                        logTest("    varId=" + v.varId() + " value=" + v.value());
                                    }
                                }
                            } catch (Exception e) {
                                logTest("  getItemVars error: " + e.getMessage());
                            }
                        } else {
                            logTest("varcs still -1 — hover not detected by API");
                            // Fallback: try reading from backpack slot with first unfinished item
                            for (int s = 0; s < 28; s++) {
                                try {
                                    var item = api.getInventoryItem(93, s);
                                    if (item != null && item.itemId() == 47068) {
                                        logTest("Fallback: trying getItemVars(93, " + s + "):");
                                        var allVars = api.getItemVars(93, s);
                                        logTest("  -> " + (allVars != null ? allVars.size() : "null") + " vars");
                                        if (allVars != null) {
                                            for (var v : allVars) {
                                                logTest("    varId=" + v.varId() + " value=" + v.value());
                                            }
                                        }
                                        break;
                                    }
                                } catch (Exception ignored) {}
                            }
                        }
                        logTest("=== Done ===");
                    });
                }
            }
            return;
        }

        ImGui.spacing();
        ImGui.separator();

        // Active item details
        Smithing.UnfinishedItem active = activeItem;
        if (active != null && ImGui.collapsingHeader("Active Item", ImGuiTreeNodeFlags.DefaultOpen)) {
            int flags = ImGuiTableFlags.Borders | ImGuiTableFlags.RowBg | ImGuiTableFlags.SizingStretchProp;
            if (ImGui.beginTable("##active_state", 2, flags)) {
                ImGui.tableSetupColumn("Property", 0, 1.5f);
                ImGui.tableSetupColumn("Value", 0, 2f);
                ImGui.tableHeadersRow();

                stateRow("Creating", active.creatingName() != null
                        ? active.creatingName() + " (ID: " + active.creatingItemId() + ")"
                        : "ID: " + active.creatingItemId());
                stateRow("Slot", String.valueOf(active.slot()));
                stateRow("Item ID", String.valueOf(active.itemId()));
                stateRow("XP Remaining", String.valueOf(active.experienceLeft()));

                ImGui.endTable();
            }

            ImGui.spacing();

            // Heat bar
            ImGui.text("Heat: ");
            ImGui.sameLine();
            ImGui.textColored(0.5f, 0.9f, 1f, 1f,
                    active.currentHeat() + " / " + maxHeat + "  (" + heatPercent + "%)  [" + heatBand + "]");

            float heatPct = maxHeat > 0 ? (float) active.currentHeat() / maxHeat : 0f;
            int heatColor = heatPct >= 0.67f
                    ? ImGui.colorConvertFloat4ToU32(1f, 0.5f, 0f, 1f)
                    : heatPct >= 0.34f
                    ? ImGui.colorConvertFloat4ToU32(1f, 0.8f, 0f, 1f)
                    : heatPct > 0f
                    ? ImGui.colorConvertFloat4ToU32(1f, 0.3f, 0.3f, 1f)
                    : ImGui.colorConvertFloat4ToU32(0.4f, 0.4f, 0.4f, 1f);
            ImGui.pushStyleColor(ImGuiCol.PlotHistogram, heatColor);
            ImGui.progressBar(heatPct, 300, 16, active.currentHeat() + " / " + maxHeat);
            ImGui.popStyleColor();

            ImGui.text("Progress per strike: ");
            ImGui.sameLine();
            ImGui.textColored(0.4f, 1f, 0.4f, 1f, String.valueOf(progPerStrike));
            ImGui.sameLine();
            ImGui.text("  |  Reheat rate: ");
            ImGui.sameLine();
            ImGui.textColored(0.7f, 0.7f, 0.7f, 1f, reheatRate + " heat/tick");

            ImGui.spacing();

            // Progress bar
            ImGui.text("Progress: ");
            ImGui.sameLine();
            ImGui.textColored(0.5f, 0.9f, 1f, 1f,
                    active.currentProgress() + " / " + active.maxProgress()
                            + "  (" + active.progressPercent() + "%)");

            float progPct = active.maxProgress() > 0
                    ? (float) active.currentProgress() / active.maxProgress() : 0f;
            ImGui.pushStyleColor(ImGuiCol.PlotHistogram,
                    ImGui.colorConvertFloat4ToU32(0.2f, 0.6f, 1f, 1f));
            ImGui.progressBar(progPct, 300, 16,
                    active.currentProgress() + " / " + active.maxProgress());
            ImGui.popStyleColor();
        }

        ImGui.spacing();
        ImGui.separator();

        // All unfinished items
        List<Smithing.UnfinishedItem> items = allUnfinished;
        if (!items.isEmpty() && ImGui.collapsingHeader("All Unfinished Items (" + items.size() + ")",
                ImGuiTreeNodeFlags.DefaultOpen)) {
            int flags = ImGuiTableFlags.Borders | ImGuiTableFlags.RowBg
                    | ImGuiTableFlags.SizingStretchProp | ImGuiTableFlags.ScrollY;
            float h = Math.min(items.size() * 25f + 30f, 200f);
            if (ImGui.beginTable("##all_unfinished", 6, flags, 0, h)) {
                ImGui.tableSetupColumn("Slot", 0, 0.3f);
                ImGui.tableSetupColumn("Creating", 0, 1.5f);
                ImGui.tableSetupColumn("Progress", 0, 0.8f);
                ImGui.tableSetupColumn("Heat", 0, 0.5f);
                ImGui.tableSetupColumn("XP Left", 0, 0.5f);
                ImGui.tableSetupColumn("%", 0, 0.4f);
                ImGui.tableSetupScrollFreeze(0, 1);
                ImGui.tableHeadersRow();

                for (Smithing.UnfinishedItem item : items) {
                    ImGui.tableNextRow();
                    if (active != null && item.slot() == active.slot()) {
                        ImGui.tableSetBgColor(1,
                                ImGui.colorConvertFloat4ToU32(0.2f, 0.5f, 0.2f, 0.3f));
                    }
                    ImGui.tableSetColumnIndex(0);
                    ImGui.text(String.valueOf(item.slot()));
                    ImGui.tableSetColumnIndex(1);
                    String n = item.creatingName() != null ? item.creatingName() : "ID:" + item.creatingItemId();
                    ImGui.textColored(0.5f, 0.9f, 1f, 1f, n);
                    ImGui.tableSetColumnIndex(2);
                    ImGui.text(item.currentProgress() + "/" + item.maxProgress());
                    ImGui.tableSetColumnIndex(3);
                    ImGui.text(String.valueOf(item.currentHeat()));
                    ImGui.tableSetColumnIndex(4);
                    ImGui.text(String.valueOf(item.experienceLeft()));
                    ImGui.tableSetColumnIndex(5);
                    ImGui.text(item.progressPercent() + "%");
                }
                ImGui.endTable();
            }
        }

        ImGui.spacing();
        ImGui.separator();
        ImGui.spacing();

        // Test buttons
        if (ImGui.collapsingHeader("API Tests", ImGuiTreeNodeFlags.DefaultOpen)) {
            if (ImGui.button("Read Item Vars")) {
                int inv = api.getVarcInt(5121);
                int slot = api.getVarcInt(5122);
                logTest("Active item vars (inv=" + inv + ", slot=" + slot + "):");
                logTest("  43222 (creating) = " + api.getItemVarValue(inv, slot, 43222));
                logTest("  43223 (progress) = " + api.getItemVarValue(inv, slot, 43223));
                logTest("  43224 (xp×10)    = " + api.getItemVarValue(inv, slot, 43224));
                logTest("  43225 (heat)     = " + api.getItemVarValue(inv, slot, 43225));
            }
            ImGui.sameLine();
            if (ImGui.button("Get Max Heat")) {
                logTest("getMaxHeat() -> " + smithing.getMaxHeat());
            }
            ImGui.sameLine();
            if (ImGui.button("Get Heat Band")) {
                logTest("getHeatBand() -> " + smithing.getHeatBand()
                        + " (" + smithing.getHeatPercent() + "%)");
            }
            ImGui.sameLine();
            if (ImGui.button("Resolve Item")) {
                Smithing.UnfinishedItem a = smithing.getActiveItem();
                if (a != null) {
                    logTest("Active: " + a.creatingName() + " (ID: " + a.creatingItemId()
                            + ") progress=" + a.currentProgress() + "/" + a.maxProgress());
                } else {
                    logTest("getActiveItem() -> null");
                }
            }

            if (ImGui.button("Scan All Unfinished")) {
                List<Smithing.UnfinishedItem> all = smithing.getAllUnfinishedItems();
                logTest("getAllUnfinishedItems() -> " + all.size() + " items");
                for (Smithing.UnfinishedItem item : all) {
                    logTest("  slot=" + item.slot() + " " + item.creatingName()
                            + " prog=" + item.currentProgress() + "/" + item.maxProgress()
                            + " heat=" + item.currentHeat() + " xp=" + item.experienceLeft());
                }
            }
            ImGui.sameLine();
            if (ImGui.button("Get Metal Category")) {
                if (active != null && active.creatingItemId() > 0) {
                    int cat = smithing.getMetalCategory(active.creatingItemId());
                    logTest("getMetalCategory(" + active.creatingItemId() + ") -> " + cat);
                } else {
                    logTest("No active item to check category");
                }
            }
            ImGui.sameLine();
            if (ImGui.button("Get Progress/Strike")) {
                logTest("getProgressPerStrike() -> " + smithing.getProgressPerStrike()
                        + " (heat band: " + smithing.getHeatBand() + ")");
            }

            ImGui.spacing();
            ImGui.separator();
            ImGui.text("Diagnostics:");
            ImGui.spacing();

            if (ImGui.button("Dump Backpack Slots")) {
                logTest("=== Backpack Dump (inv 93) ===");
                for (int s = 0; s < 28; s++) {
                    try {
                        if (!api.isInventoryItemValid(93, s)) continue;
                        var item = api.getInventoryItem(93, s);
                        if (item == null || item.itemId() <= 0) continue;
                        String name = "?";
                        try {
                            var t = api.getItemType(item.itemId());
                            if (t != null) name = t.name();
                        } catch (Exception ignored) {}
                        // Try reading item vars
                        int v22 = 0, v23 = 0, v24 = 0, v25 = 0;
                        try { v22 = api.getItemVarValue(93, s, 43222); } catch (Exception ignored) {}
                        try { v23 = api.getItemVarValue(93, s, 43223); } catch (Exception ignored) {}
                        try { v24 = api.getItemVarValue(93, s, 43224); } catch (Exception ignored) {}
                        try { v25 = api.getItemVarValue(93, s, 43225); } catch (Exception ignored) {}
                        logTest("  [" + s + "] " + name + " (ID:" + item.itemId()
                                + ") vars: 43222=" + v22 + " 43223=" + v23
                                + " 43224=" + v24 + " 43225=" + v25);
                    } catch (Exception e) {
                        logTest("  [" + s + "] ERROR: " + e.getMessage());
                    }
                }
            }
            ImGui.sameLine();
            if (ImGui.button("Dump All Item Vars Slot 0")) {
                try {
                    var item = api.getInventoryItem(93, 0);
                    logTest("Slot 0: itemId=" + (item != null ? item.itemId() : "null"));
                    var vars = api.getItemVars(93, 0);
                    logTest("  getItemVars() returned " + (vars != null ? vars.size() : "null") + " vars");
                    if (vars != null) {
                        for (var v : vars) {
                            logTest("    varId=" + v.varId() + " value=" + v.value());
                        }
                    }
                } catch (Exception e) {
                    logTest("Slot 0 error: " + e.getMessage());
                }
            }
            ImGui.sameLine();
            if (ImGui.button("Dump Varcs 5121-5125")) {
                for (int v = 5121; v <= 5125; v++) {
                    try {
                        logTest("varc " + v + " = " + api.getVarcInt(v));
                    } catch (Exception e) {
                        logTest("varc " + v + " ERROR: " + e.getMessage());
                    }
                }
            }
        }
    }

    // ── Production Progress Tab ──────────────────────────────────────────

    private void renderProductionTab() {
        // Status
        ImGui.text("Production Interface: ");
        ImGui.sameLine();
        if (prodOpen) {
            ImGui.textColored(0.2f, 1f, 0.2f, 1f, "OPEN");
        } else {
            ImGui.textColored(1f, 0.3f, 0.3f, 1f, "CLOSED");
        }
        ImGui.sameLine();
        ImGui.text("  |  Producing: ");
        ImGui.sameLine();
        if (prodProducing) {
            ImGui.textColored(0.2f, 0.8f, 1f, 1f, "YES");
        } else {
            ImGui.textColored(0.6f, 0.6f, 0.6f, 1f, "NO");
        }

        if (!prodProducing && !prodOpen) {
            ImGui.spacing();
            ImGui.textColored(0.6f, 0.6f, 0.6f, 1f,
                    "Click Make on a production interface, then watch the progress update here.");
            renderProductionActions();
            return;
        }

        ImGui.spacing();
        ImGui.separator();

        // Progress bar
        if (prodProducing) {
            if (ImGui.collapsingHeader("Progress", ImGuiTreeNodeFlags.DefaultOpen)) {
                float pct = prodTotal > 0 ? (float) prodMade / prodTotal : 0f;
                ImGui.pushStyleColor(ImGuiCol.PlotHistogram,
                        pct > 0.9f ? ImGui.colorConvertFloat4ToU32(0.2f, 0.8f, 0.2f, 1f)
                                : ImGui.colorConvertFloat4ToU32(0.2f, 0.6f, 0.8f, 1f));
                ImGui.progressBar(pct, 300, 20, prodMade + " / " + prodTotal + " (" + prodPercent + "%)");
                ImGui.popStyleColor();

                int flags = ImGuiTableFlags.Borders | ImGuiTableFlags.RowBg | ImGuiTableFlags.SizingStretchProp;
                if (ImGui.beginTable("##st_prog", 2, flags)) {
                    ImGui.tableSetupColumn("Property", 0, 1f);
                    ImGui.tableSetupColumn("Value", 0, 2f);
                    ImGui.tableHeadersRow();

                    stateRow("Product", prodProductName);
                    stateRow("Total", String.valueOf(prodTotal));
                    stateRow("Remaining", String.valueOf(prodRemaining));
                    stateRow("Made", String.valueOf(prodMade));
                    stateRow("Percent", prodPercent + "%");
                    stateRow("Complete", String.valueOf(prodComplete));
                    stateRow("Time Remaining", prodTimeText);
                    stateRow("Counter", prodCounterText);

                    ImGui.endTable();
                }
            }
        }

        ImGui.spacing();
        ImGui.separator();
        renderProductionActions();
    }

    private void renderProductionActions() {
        if (ImGui.collapsingHeader("Production Actions", ImGuiTreeNodeFlags.DefaultOpen)) {
            if (ImGui.button("Stop Production")) {
                boolean ok = production.stopProduction();
                logTest("stopProduction() -> " + ok);
            }
            ImGui.sameLine();
            if (ImGui.button("Await Completion (30s)")) {
                Thread.startVirtualThread(() -> {
                    logTest("awaitCompletion(30000) — waiting...");
                    boolean ok = production.awaitCompletion(30000);
                    logTest("awaitCompletion(30000) -> " + ok);
                });
            }
            ImGui.sameLine();
            if (ImGui.button("Await 5 Made (30s)")) {
                Thread.startVirtualThread(() -> {
                    logTest("awaitProgress(5, 30000) — waiting...");
                    boolean ok = production.awaitProgress(5, 30000);
                    logTest("awaitProgress(5, 30000) -> " + ok);
                });
            }

            ImGui.spacing();

            // isProducing / isComplete quick checks
            if (ImGui.button("Check isProducing()")) {
                logTest("isProducing() -> " + production.isProducing());
            }
            ImGui.sameLine();
            if (ImGui.button("Check isComplete()")) {
                logTest("isProductionComplete() -> " + production.isProductionComplete());
            }
        }
    }

    // ── Log Tab ──────────────────────────────────────────────────────────

    private void renderLogTab() {
        if (ImGui.button("Clear Log")) {
            testLog.clear();
        }
        ImGui.sameLine();
        ImGui.text("Entries: " + testLog.size());

        ImGui.spacing();
        ImGui.separator();
        ImGui.spacing();

        ImGui.beginChild("##log_scroll", 0, 0, false);
        for (int i = testLog.size() - 1; i >= 0; i--) {
            String entry = testLog.get(i);
            if (entry.contains("-> true") || entry.contains("-> YES")) {
                ImGui.textColored(0.4f, 1f, 0.4f, 1f, entry);
            } else if (entry.contains("-> false") || entry.contains("-> NO") || entry.contains("FAIL")) {
                ImGui.textColored(1f, 0.4f, 0.4f, 1f, entry);
            } else if (entry.contains("waiting")) {
                ImGui.textColored(0.8f, 0.8f, 0.3f, 1f, entry);
            } else {
                ImGui.text(entry);
            }
        }
        ImGui.endChild();
    }

    // ── Helpers ──────────────────────────────────────────────────────────

    private void logTest(String msg) {
        String ts = java.time.LocalTime.now().toString().substring(0, 8);
        String entry = "[" + ts + "] " + msg;
        testLog.add(entry);
        while (testLog.size() > MAX_LOG) testLog.remove(0);
        log.info("[SmithingTest] {}", msg);
    }

    private static void stateRow(String label, String value) {
        ImGui.tableNextRow();
        ImGui.tableSetColumnIndex(0);
        ImGui.text(label);
        ImGui.tableSetColumnIndex(1);
        ImGui.textColored(0.5f, 0.9f, 1f, 1f, value != null ? value : "null");
    }

    private static String qualityName(int tier) {
        return switch (tier) {
            case 0 -> "Base";
            case 1 -> "+1";
            case 2 -> "+2";
            case 3 -> "+3";
            case 4 -> "+4";
            case 5 -> "+5";
            case 50 -> "Burial";
            default -> "Unknown";
        };
    }

    private static Quality qualityFromCombo(int index) {
        return switch (index) {
            case 0 -> Quality.BASE;
            case 1 -> Quality.PLUS_1;
            case 2 -> Quality.PLUS_2;
            case 3 -> Quality.PLUS_3;
            case 4 -> Quality.PLUS_4;
            case 5 -> Quality.PLUS_5;
            case 6 -> Quality.BURIAL;
            default -> null;
        };
    }
}
