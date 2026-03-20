package com.xapi.debugger;

import com.botwithus.bot.api.*;
import com.botwithus.bot.api.inventory.WoodBox;
import com.botwithus.bot.api.log.BotLogger;
import com.botwithus.bot.api.log.LoggerFactory;
import com.botwithus.bot.api.model.InventoryItem;

import java.util.List;

/**
 * WoodBox API test — tier detection, stored item queries, fill operation.
 * <p>Prerequisites: have a wood box and some logs in backpack.</p>
 */
@ScriptManifest(
        name = "Xapi Test",
        version = "1.0",
        author = "Xapi",
        description = "WoodBox API test — tier, canStore, fill, queries",
        category = ScriptCategory.UTILITY
)
public class TestScript implements BotScript {

    private static final BotLogger log = LoggerFactory.getLogger(TestScript.class);

    private WoodBox woodBox;
    private int step = 0;
    private int passed = 0;
    private int failed = 0;

    @Override
    public void onStart(ScriptContext ctx) {
        this.woodBox = new WoodBox(ctx.getGameAPI());
        log.info("[WoodBoxTest] Started — have a wood box + logs in backpack");
    }

    @Override
    public int onLoop() {
        switch (step) {

            // ---- Tier detection ----
            case 0 -> {
                log.info("[WoodBoxTest] === Tier Detection ===");
                boolean has = woodBox.hasWoodBox();
                check("hasWoodBox()", has);
                if (!has) {
                    log.error("[WoodBoxTest] No wood box in backpack — cannot continue");
                    return -1;
                }
                WoodBox.Tier tier = woodBox.getEquippedTier();
                log.info("[WoodBoxTest] Equipped tier: {} (level {}, itemId {}, base capacity {})",
                        tier.name, tier.level, tier.itemId, tier.baseCapacity);
                check("getEquippedTier() not null", tier != null);
                check("getBaseCapacity() > 0", woodBox.getBaseCapacity() > 0);
                step++;
                return 1000;
            }

            // ---- canStore checks ----
            case 1 -> {
                log.info("[WoodBoxTest] === canStore() ===");
                WoodBox.Tier tier = woodBox.getEquippedTier();
                // Should always be able to store basic logs
                check("canStore(LOGS)", woodBox.canStore(WoodBox.LogType.LOGS));
                check("canStore(\"Logs\")", woodBox.canStore("Logs"));
                // Check a log type at the tier's own level
                for (WoodBox.LogType lt : WoodBox.LogType.values()) {
                    if (lt.requiredTier == tier.level) {
                        check("canStore(" + lt.name + ")", woodBox.canStore(lt));
                        break;
                    }
                }
                // Unknown log name
                check("canStore(\"Fake logs\") = false", !woodBox.canStore("Fake logs"));
                step++;
                return 1000;
            }

            // ---- Stored item queries (before fill) ----
            case 2 -> {
                log.info("[WoodBoxTest] === Stored Items (before fill) ===");
                List<InventoryItem> stored = woodBox.getStoredItems();
                log.info("[WoodBoxTest] Stored items: {} types, {} total",
                        woodBox.storedTypes(), woodBox.getTotalStored());
                for (InventoryItem item : stored) {
                    log.info("[WoodBoxTest]   itemId={}, qty={}", item.itemId(), item.quantity());
                }
                boolean empty = woodBox.isEmpty();
                log.info("[WoodBoxTest] isEmpty = {}", empty);
                step++;
                return 1000;
            }

            // ---- Fill ----
            case 3 -> {
                log.info("[WoodBoxTest] === fill() ===");
                boolean result = woodBox.fill();
                check("fill() returned true", result);
                step++;
                return 2000;
            }

            // ---- Stored item queries (after fill) ----
            case 4 -> {
                log.info("[WoodBoxTest] === Stored Items (after fill) ===");
                List<InventoryItem> stored = woodBox.getStoredItems();
                log.info("[WoodBoxTest] Stored items: {} types, {} total",
                        woodBox.storedTypes(), woodBox.getTotalStored());
                for (InventoryItem item : stored) {
                    log.info("[WoodBoxTest]   itemId={}, qty={}", item.itemId(), item.quantity());
                }
                check("getTotalStored() > 0 after fill", woodBox.getTotalStored() > 0);
                step++;
                return 1000;
            }

            // ---- Summary ----
            case 5 -> {
                log.info("[WoodBoxTest] ==============================");
                log.info("[WoodBoxTest] TEST COMPLETE: {} passed, {} failed", passed, failed);
                log.info("[WoodBoxTest] ==============================");
                return -1;
            }

            default -> { return -1; }
        }
    }

    private void check(String name, boolean condition) {
        if (condition) {
            passed++;
            log.info("[WoodBoxTest] PASS: {}", name);
        } else {
            failed++;
            log.error("[WoodBoxTest] FAIL: {}", name);
        }
    }

    @Override
    public void onStop() {
        log.info("[WoodBoxTest] Stopped at step {} — {} passed, {} failed", step, passed, failed);
    }
}
