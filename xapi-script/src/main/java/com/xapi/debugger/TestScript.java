package com.xapi.debugger;

import com.botwithus.bot.api.*;
import com.botwithus.bot.api.inventory.Bank;
import com.botwithus.bot.api.log.BotLogger;
import com.botwithus.bot.api.log.LoggerFactory;

/**
 * Bank API test — View All tab auto-switch on contains/count/withdraw.
 * <p>Prerequisites: open bank, switch to any tab OTHER than "View all",
 * have Swordfish in bank, empty backpack.</p>
 */
@ScriptManifest(
        name = "Xapi Test",
        version = "1.0",
        author = "Xapi",
        description = "Bank API test — View All tab auto-switch on all query methods",
        category = ScriptCategory.UTILITY
)
public class TestScript implements BotScript {

    private static final BotLogger log = LoggerFactory.getLogger(TestScript.class);
    private static final int SWORDFISH_ID = 373;

    private Bank bank;
    private int step = 0;
    private int passed = 0;
    private int failed = 0;

    @Override
    public void onStart(ScriptContext ctx) {
        this.bank = new Bank(ctx.getGameAPI());
        log.info("[BankTest] Started — switch to a NON 'View all' tab before running");
    }

    @Override
    public int onLoop() {
        switch (step) {

            case 0 -> {
                log.info("[BankTest] === Prereqs ===");
                if (!bank.isOpen()) {
                    log.error("[BankTest] Bank is NOT open!");
                    return -1;
                }
                check("Bank is open", true);
                bank.depositAll();
                step++;
                return 2000;
            }

            // ---- Test contains(int) auto-switches to View All ----
            case 1 -> {
                log.info("[BankTest] === contains(SWORDFISH) — should auto-switch to View All ===");
                boolean result = bank.contains(SWORDFISH_ID);
                check("contains(SWORDFISH) returned true", result);
                check("isViewAllTab() after contains", bank.isViewAllTab());
                step++;
                return 1000;
            }

            // ---- Test count(int) auto-switches ----
            case 2 -> {
                log.info("[BankTest] === count(SWORDFISH) — should auto-switch to View All ===");
                int cnt = bank.count(SWORDFISH_ID);
                check("count(SWORDFISH) > 0", cnt > 0);
                log.info("[BankTest] count = {}", cnt);
                check("isViewAllTab() after count", bank.isViewAllTab());
                step++;
                return 1000;
            }

            // ---- Test withdraw(int, TransferAmount) auto-switches ----
            case 3 -> {
                log.info("[BankTest] === withdraw(SWORDFISH, ONE) — should auto-switch to View All ===");
                boolean result = bank.withdraw(SWORDFISH_ID, Bank.TransferAmount.ONE);
                check("withdraw(SWORDFISH, ONE) returned true", result);
                step++;
                return 2000;
            }
            case 4 -> {
                check("isViewAllTab() after withdraw", bank.isViewAllTab());
                int bp = bank.backpackCount(SWORDFISH_ID);
                check("Backpack has 1 swordfish", bp == 1);
                log.info("[BankTest] Backpack: {} (expected 1)", bp);
                bank.depositAll();
                step++;
                return 2000;
            }

            // ---- Test withdraw(int, int) custom amount ----
            case 5 -> {
                log.info("[BankTest] === withdraw(SWORDFISH, 7) — custom amount ===");
                boolean result = bank.withdraw(SWORDFISH_ID, 7);
                check("withdraw(SWORDFISH, 7) returned true", result);
                step++;
                return 2000;
            }
            case 6 -> {
                int bp = bank.backpackCount(SWORDFISH_ID);
                check("Backpack has 7 swordfish", bp == 7);
                log.info("[BankTest] Backpack: {} (expected 7)", bp);
                bank.depositAll();
                step++;
                return 2000;
            }

            // ---- Cleanup ----
            case 7 -> {
                bank.setTransferMode(Bank.TransferAmount.ONE);
                bank.close();
                step++;
                return 2000;
            }
            case 8 -> {
                check("Bank is closed", !bank.isOpen());
                log.info("[BankTest] ==============================");
                log.info("[BankTest] TEST COMPLETE: {} passed, {} failed", passed, failed);
                log.info("[BankTest] ==============================");
                return -1;
            }

            default -> { return -1; }
        }
    }

    private void check(String name, boolean condition) {
        if (condition) {
            passed++;
            log.info("[BankTest] PASS: {}", name);
        } else {
            failed++;
            log.error("[BankTest] FAIL: {}", name);
        }
    }

    @Override
    public void onStop() {
        log.info("[BankTest] Stopped at step {} — {} passed, {} failed", step, passed, failed);
    }
}
