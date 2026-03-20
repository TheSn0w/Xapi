package com.xapi.debugger;

import com.botwithus.bot.api.*;
import com.botwithus.bot.api.inventory.Bank;
import com.botwithus.bot.api.log.BotLogger;
import com.botwithus.bot.api.log.LoggerFactory;
import com.botwithus.bot.api.model.Component;

import java.util.List;

/**
 * Bank API test harness — tests withdraw + transfer mode with Swordfish (ID 373).
 * <p>Prerequisites: open bank with Swordfish in bank and EMPTY backpack.</p>
 * <p>Uses interface 517 component 15 to read backpack while bank is open.</p>
 */
@ScriptManifest(
        name = "Xapi Test",
        version = "1.0",
        author = "Xapi",
        description = "Bank API test — open bank with Swordfish, empty backpack",
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

            // ---- Step 1: Prereqs ----
            case 0 -> {
                log.info("[BankTest] === Step 1: Prereqs ===");
                if (!bank.isOpen()) {
                    log.error("[BankTest] Bank is NOT open!");
                    return -1;
                }
                check("Bank is open", true);
                check("Bank contains Swordfish", bank.contains(SWORDFISH_ID));
                if (!bank.contains(SWORDFISH_ID)) return -1;
                log.info("[BankTest] Swordfish in bank: {}", bank.count(SWORDFISH_ID));
                bank.depositAll();
                step++;
                return 2000;
            }
            case 1 -> {
                check("Backpack empty", countBackpackSwordfish() == 0);
                step++;
                return 1000;
            }

            // ---- Step 2: Set transfer mode to 1, verify, withdraw 1 ----
            case 2 -> {
                log.info("[BankTest] === Step 2: setTransferMode(ONE) ===");
                boolean result = bank.setTransferMode(Bank.TransferAmount.ONE);
                check("setTransferMode(ONE) returns true", result);
                step++;
                return 2000;
            }
            case 3 -> {
                Bank.TransferAmount mode = bank.transferMode();
                check("transferMode() == ONE", mode == Bank.TransferAmount.ONE);
                log.info("[BankTest] transferMode after set: {}", mode);

                log.info("[BankTest] Withdraw 1 (mode=ONE, should use opt 1)");
                bank.withdraw(SWORDFISH_ID, Bank.TransferAmount.ONE);
                step++;
                return 2000;
            }
            case 4 -> {
                int bp = countBackpackSwordfish();
                check("W1 from mode ONE: backpack=1", bp == 1);
                log.info("[BankTest] Backpack: {} (expected 1)", bp);
                bank.depositAll();
                step++;
                return 2000;
            }

            // ---- Step 3: Set transfer mode to 5, verify, withdraw 1 and 5 ----
            case 5 -> {
                log.info("[BankTest] === Step 3: setTransferMode(FIVE) ===");
                bank.setTransferMode(Bank.TransferAmount.FIVE);
                step++;
                return 2000;
            }
            case 6 -> {
                Bank.TransferAmount mode = bank.transferMode();
                check("transferMode() == FIVE", mode == Bank.TransferAmount.FIVE);
                log.info("[BankTest] transferMode after set: {}", mode);

                log.info("[BankTest] Withdraw 1 (mode=FIVE, should use opt 2)");
                bank.withdraw(SWORDFISH_ID, Bank.TransferAmount.ONE);
                step++;
                return 2000;
            }
            case 7 -> {
                int bp = countBackpackSwordfish();
                check("W1 from mode FIVE: backpack=1", bp == 1);
                log.info("[BankTest] Backpack: {} (expected 1)", bp);
                bank.depositAll();
                step++;
                return 2000;
            }
            case 8 -> {
                log.info("[BankTest] Withdraw 5 (mode=FIVE, should use opt 1)");
                bank.withdraw(SWORDFISH_ID, Bank.TransferAmount.FIVE);
                step++;
                return 2000;
            }
            case 9 -> {
                int bp = countBackpackSwordfish();
                check("W5 from mode FIVE: backpack=5", bp == 5);
                log.info("[BankTest] Backpack: {} (expected 5)", bp);
                bank.depositAll();
                step++;
                return 2000;
            }

            // ---- Step 4: Set transfer mode to 10, verify, withdraw 1, 5, 10 ----
            case 10 -> {
                log.info("[BankTest] === Step 4: setTransferMode(TEN) ===");
                bank.setTransferMode(Bank.TransferAmount.TEN);
                step++;
                return 2000;
            }
            case 11 -> {
                Bank.TransferAmount mode = bank.transferMode();
                check("transferMode() == TEN", mode == Bank.TransferAmount.TEN);
                log.info("[BankTest] transferMode after set: {}", mode);

                log.info("[BankTest] Withdraw 1 (mode=TEN, should use opt 2)");
                bank.withdraw(SWORDFISH_ID, Bank.TransferAmount.ONE);
                step++;
                return 2000;
            }
            case 12 -> {
                int bp = countBackpackSwordfish();
                check("W1 from mode TEN: backpack=1", bp == 1);
                log.info("[BankTest] Backpack: {} (expected 1)", bp);
                bank.depositAll();
                step++;
                return 2000;
            }
            case 13 -> {
                log.info("[BankTest] Withdraw 5 (mode=TEN, should use opt 3)");
                bank.withdraw(SWORDFISH_ID, Bank.TransferAmount.FIVE);
                step++;
                return 2000;
            }
            case 14 -> {
                int bp = countBackpackSwordfish();
                check("W5 from mode TEN: backpack=5", bp == 5);
                log.info("[BankTest] Backpack: {} (expected 5)", bp);
                bank.depositAll();
                step++;
                return 2000;
            }
            case 15 -> {
                log.info("[BankTest] Withdraw 10 (mode=TEN, should use opt 1)");
                bank.withdraw(SWORDFISH_ID, Bank.TransferAmount.TEN);
                step++;
                return 2000;
            }
            case 16 -> {
                int bp = countBackpackSwordfish();
                check("W10 from mode TEN: backpack=10", bp == 10);
                log.info("[BankTest] Backpack: {} (expected 10)", bp);
                bank.depositAll();
                step++;
                return 2000;
            }

            // ---- Step 5: Set transfer mode to ALL, verify, withdraw 1, 5, 10 ----
            case 17 -> {
                log.info("[BankTest] === Step 5: setTransferMode(ALL) ===");
                bank.setTransferMode(Bank.TransferAmount.ALL);
                step++;
                return 2000;
            }
            case 18 -> {
                Bank.TransferAmount mode = bank.transferMode();
                check("transferMode() == ALL", mode == Bank.TransferAmount.ALL);
                log.info("[BankTest] transferMode after set: {}", mode);

                log.info("[BankTest] Withdraw 1 (mode=ALL, should use opt 2)");
                bank.withdraw(SWORDFISH_ID, Bank.TransferAmount.ONE);
                step++;
                return 2000;
            }
            case 19 -> {
                int bp = countBackpackSwordfish();
                check("W1 from mode ALL: backpack=1", bp == 1);
                log.info("[BankTest] Backpack: {} (expected 1)", bp);
                bank.depositAll();
                step++;
                return 2000;
            }
            case 20 -> {
                log.info("[BankTest] Withdraw 5 (mode=ALL, should use opt 3)");
                bank.withdraw(SWORDFISH_ID, Bank.TransferAmount.FIVE);
                step++;
                return 2000;
            }
            case 21 -> {
                int bp = countBackpackSwordfish();
                check("W5 from mode ALL: backpack=5", bp == 5);
                log.info("[BankTest] Backpack: {} (expected 5)", bp);
                bank.depositAll();
                step++;
                return 2000;
            }
            case 22 -> {
                log.info("[BankTest] Withdraw 10 (mode=ALL, should use opt 4)");
                bank.withdraw(SWORDFISH_ID, Bank.TransferAmount.TEN);
                step++;
                return 2000;
            }
            case 23 -> {
                int bp = countBackpackSwordfish();
                check("W10 from mode ALL: backpack=10", bp == 10);
                log.info("[BankTest] Backpack: {} (expected 10)", bp);
                bank.depositAll();
                step++;
                return 2000;
            }
            case 24 -> {
                log.info("[BankTest] Withdraw ALL (mode=ALL, should use opt 7)");
                bank.withdraw(SWORDFISH_ID, Bank.TransferAmount.ALL);
                step++;
                return 2000;
            }
            case 25 -> {
                int bp = countBackpackSwordfish();
                check("W-ALL from mode ALL: backpack=28", bp == 28);
                log.info("[BankTest] Backpack: {} (expected 28, capped by slots)", bp);
                bank.depositAll();
                step++;
                return 2000;
            }

            // ---- Restore mode to 1, close, summary ----
            case 26 -> {
                bank.setTransferMode(Bank.TransferAmount.ONE);
                step++;
                return 2000;
            }
            case 27 -> {
                bank.close();
                step++;
                return 2000;
            }
            case 28 -> {
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
