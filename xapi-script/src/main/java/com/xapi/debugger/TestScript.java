package com.xapi.debugger;

import com.botwithus.bot.api.*;
import com.botwithus.bot.api.inventory.SoilBox;
import com.botwithus.bot.api.log.BotLogger;
import com.botwithus.bot.api.log.LoggerFactory;

/**
 * SoilBox API test — emptyAtBank().
 * <p>Prerequisites: have a soil box with some soil stored,
 * open the bank, then start the script.</p>
 */
@ScriptManifest(
        name = "Xapi Test",
        version = "1.0",
        author = "Xapi",
        description = "SoilBox API test — empty at bank",
        category = ScriptCategory.UTILITY
)
public class TestScript implements BotScript {

    private static final BotLogger log = LoggerFactory.getLogger(TestScript.class);

    private SoilBox soilBox;
    private GameAPI api;
    private int step = 0;
    private int passed = 0;
    private int failed = 0;
    private int storedBefore = 0;

    @Override
    public void onStart(ScriptContext ctx) {
        this.api = ctx.getGameAPI();
        this.soilBox = new SoilBox(api);
        log.info("[SoilBoxTest] Started — have soil box with soil stored, bank open");
    }

    @Override
    public int onLoop() {
        switch (step) {

            // ---- Prereqs ----
            case 0 -> {
                log.info("[SoilBoxTest] === Prereqs ===");
                check("hasSoilBox()", soilBox.hasSoilBox());
                if (!soilBox.hasSoilBox()) {
                    log.error("[SoilBoxTest] No soil box — cannot continue");
                    return -1;
                }
                check("Bank is open", api.isInterfaceOpen(517));
                if (!api.isInterfaceOpen(517)) {
                    log.error("[SoilBoxTest] Bank is NOT open — open bank first");
                    return -1;
                }
                storedBefore = soilBox.getTotalStored();
                log.info("[SoilBoxTest] Stored before empty: {}", storedBefore);
                for (String line : soilBox.getBreakdown()) {
                    log.info("[SoilBoxTest]   {}", line);
                }
                check("Soil box has items stored", storedBefore > 0);
                if (storedBefore == 0) {
                    log.error("[SoilBoxTest] Soil box is empty — fill it first");
                    return -1;
                }
                step++;
                return 1000;
            }

            // ---- Empty at bank ----
            case 1 -> {
                log.info("[SoilBoxTest] === emptyAtBank() ===");
                boolean result = soilBox.emptyAtBank();
                check("emptyAtBank() returned true", result);
                step++;
                return 3000;
            }

            // ---- Verify emptied ----
            case 2 -> {
                int storedAfter = soilBox.getTotalStored();
                log.info("[SoilBoxTest] Stored after empty: {} (was {})", storedAfter, storedBefore);
                for (String line : soilBox.getBreakdown()) {
                    log.info("[SoilBoxTest]   {}", line);
                }
                check("Soil box is now empty", storedAfter == 0);
                check("isEmpty() = true", soilBox.isEmpty());
                step++;
                return 1000;
            }

            // ---- Summary ----
            case 3 -> {
                log.info("[SoilBoxTest] ==============================");
                log.info("[SoilBoxTest] TEST COMPLETE: {} passed, {} failed", passed, failed);
                log.info("[SoilBoxTest] ==============================");
                return -1;
            }

            default -> { return -1; }
        }
    }

    private void check(String name, boolean condition) {
        if (condition) {
            passed++;
            log.info("[SoilBoxTest] PASS: {}", name);
        } else {
            failed++;
            log.error("[SoilBoxTest] FAIL: {}", name);
        }
    }

    @Override
    public void onStop() {
        log.info("[SoilBoxTest] Stopped at step {} — {} passed, {} failed", step, passed, failed);
    }
}
