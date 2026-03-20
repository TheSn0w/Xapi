package com.xapi.debugger;

import com.botwithus.bot.api.*;
import com.botwithus.bot.api.inventory.Bank;
import com.botwithus.bot.api.log.BotLogger;
import com.botwithus.bot.api.log.LoggerFactory;
import com.botwithus.bot.api.model.Component;

import java.util.List;

/**
 * Bank API test — withdraw(int) and deposit(int) with Swordfish (ID 373).
 * <p>Prerequisites: open bank with Swordfish in bank and EMPTY backpack.</p>
 */
@ScriptManifest(
        name = "Xapi Test",
        version = "1.0",
        author = "Xapi",
        description = "Bank API test — withdraw/deposit custom amounts",
        category = ScriptCategory.UTILITY
)
public class TestScript implements BotScript {

    private static final BotLogger log = LoggerFactory.getLogger(TestScript.class);
    private static final int SWORDFISH_ID = 373;
    private static final int BANK_INTERFACE = 517;
    private static final int BACKPACK_COMPONENT = 15;

    private GameAPI api;
    private Bank bank;
    private int step = 0;
    private int passed = 0;
    private int failed = 0;

    @Override
    public void onStart(ScriptContext ctx) {
        this.api = ctx.getGameAPI();
        this.bank = new Bank(api);
        log.info("[BankTest] Started — open bank with Swordfish in bank, empty backpack");
    }

    @Override
    public int onLoop() {
        switch (step) {

            // ---- Prereqs ----
            case 0 -> {
                log.info("[BankTest] === Prereqs ===");
                if (!bank.isOpen()) {
                    log.error("[BankTest] Bank is NOT open!");
                    return -1;
                }
                check("Bank is open", true);
                check("Bank contains Swordfish", bank.contains(SWORDFISH_ID));
                if (!bank.contains(SWORDFISH_ID)) return -1;
                bank.depositAll();
                step++;
                return 2000;
            }
            case 1 -> {
                check("Backpack empty", countBackpackSwordfish() == 0);
                step++;
                return 1000;
            }

            // ---- Withdraw 19 (blocking call with internal delays) ----
            case 2 -> {
                log.info("[BankTest] === withdraw(SWORDFISH, 19) ===");
                boolean result = bank.withdraw(SWORDFISH_ID, 19);
                check("withdraw(19) returned true", result);
                step++;
                return 2000;
            }
            case 3 -> {
                int bp = countBackpackSwordfish();
                check("Withdraw 19: backpack=19", bp == 19);
                log.info("[BankTest] Backpack: {} (expected 19)", bp);
                step++;
                return 1000;
            }

            // ---- Deposit 4 (blocking call with internal delays) ----
            case 4 -> {
                log.info("[BankTest] === deposit(SWORDFISH, 4) ===");
                boolean result = bank.deposit(SWORDFISH_ID, 4);
                check("deposit(4) returned true", result);
                step++;
                return 2000;
            }
            case 5 -> {
                int bp = countBackpackSwordfish();
                check("Deposit 4: backpack=15", bp == 15);
                log.info("[BankTest] Backpack: {} (expected 15)", bp);
                step++;
                return 1000;
            }

            // ---- Deposit 9 (blocking call with internal delays) ----
            case 6 -> {
                log.info("[BankTest] === deposit(SWORDFISH, 9) ===");
                boolean result = bank.deposit(SWORDFISH_ID, 9);
                check("deposit(9) returned true", result);
                step++;
                return 2000;
            }
            case 7 -> {
                int bp = countBackpackSwordfish();
                check("Deposit 9: backpack=6", bp == 6);
                log.info("[BankTest] Backpack: {} (expected 6)", bp);
                step++;
                return 1000;
            }

            // ---- Withdraw 22 (blocking call with internal delays) ----
            case 8 -> {
                log.info("[BankTest] === withdraw(SWORDFISH, 22) ===");
                boolean result = bank.withdraw(SWORDFISH_ID, 22);
                check("withdraw(22) returned true", result);
                step++;
                return 2000;
            }
            case 9 -> {
                int bp = countBackpackSwordfish();
                check("Withdraw 22: backpack=28", bp == 28);
                log.info("[BankTest] Backpack: {} (expected 28 = 6 + 22)", bp);
                step++;
                return 1000;
            }

            // ---- Deposit 13 (blocking call with internal delays) ----
            case 10 -> {
                log.info("[BankTest] === deposit(SWORDFISH, 13) ===");
                boolean result = bank.deposit(SWORDFISH_ID, 13);
                check("deposit(13) returned true", result);
                step++;
                return 2000;
            }
            case 11 -> {
                int bp = countBackpackSwordfish();
                check("Deposit 13: backpack=15", bp == 15);
                log.info("[BankTest] Backpack: {} (expected 15 = 28 - 13)", bp);
                step++;
                return 1000;
            }

            // ---- Cleanup ----
            case 12 -> {
                bank.depositAll();
                step++;
                return 2000;
            }
            case 13 -> {
                bank.setTransferMode(Bank.TransferAmount.ONE);
                bank.close();
                step++;
                return 2000;
            }
            case 14 -> {
                check("Bank is closed", !bank.isOpen());
                log.info("[BankTest] ==============================");
                log.info("[BankTest] TEST COMPLETE: {} passed, {} failed", passed, failed);
                log.info("[BankTest] ==============================");
                return -1;
            }

            default -> { return -1; }
        }
    }

    private int countBackpackSwordfish() {
        List<Component> children = api.getComponentChildren(BANK_INTERFACE, BACKPACK_COMPONENT);
        int count = 0;
        for (Component child : children) {
            if (child.itemId() == SWORDFISH_ID) {
                count++;
            }
        }
        return count;
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
