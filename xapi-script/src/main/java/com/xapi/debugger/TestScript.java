package com.xapi.debugger;

import com.botwithus.bot.api.*;
import com.botwithus.bot.api.inventory.Bank;
import com.botwithus.bot.api.log.BotLogger;
import com.botwithus.bot.api.log.LoggerFactory;

/**
 * Bank API test — empty slot holder edge case with Anchovies.
 * <p>Prerequisites: open bank with Anchovies slot (0 qty) in bank.</p>
 */
@ScriptManifest(
        name = "Xapi Test",
        version = "1.0",
        author = "Xapi",
        description = "Bank API test — empty slot holder edge case",
        category = ScriptCategory.UTILITY
)
public class TestScript implements BotScript {

    private static final BotLogger log = LoggerFactory.getLogger(TestScript.class);

    private Bank bank;
    private int step = 0;

    @Override
    public void onStart(ScriptContext ctx) {
        this.bank = new Bank(ctx.getGameAPI());
        log.info("[BankTest] Started — testing empty slot holder (Anchovies)");
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
                log.info("[BankTest] Bank is open");
                step++;
                return 1000;
            }

            // ---- Test contains() ----
            case 1 -> {
                log.info("[BankTest] === contains(\"Anchovies\") ===");
                boolean containsById = bank.contains("Anchovies");
                log.info("[BankTest] contains(\"Anchovies\") = {}", containsById);
                step++;
                return 1000;
            }

            // ---- Test count() ----
            case 2 -> {
                log.info("[BankTest] === count(\"Anchovies\") ===");
                int count = bank.count("Anchovies");
                log.info("[BankTest] count(\"Anchovies\") = {}", count);
                step++;
                return 1000;
            }

            // ---- Test withdraw with TransferAmount ----
            case 3 -> {
                log.info("[BankTest] === withdraw(\"Anchovies\", ONE) ===");
                try {
                    boolean result = bank.withdraw("Anchovies", Bank.TransferAmount.ONE);
                    log.info("[BankTest] withdraw(\"Anchovies\", ONE) = {}", result);
                } catch (Exception e) {
                    log.error("[BankTest] withdraw(\"Anchovies\", ONE) threw: {}", e.getMessage());
                }
                step++;
                return 1000;
            }

            // ---- Test withdraw with int amount ----
            case 4 -> {
                log.info("[BankTest] === withdraw(\"Anchovies\", 5) ===");
                try {
                    boolean result = bank.withdraw("Anchovies", 5);
                    log.info("[BankTest] withdraw(\"Anchovies\", 5) = {}", result);
                } catch (Exception e) {
                    log.error("[BankTest] withdraw(\"Anchovies\", 5) threw: {}", e.getMessage());
                }
                step++;
                return 1000;
            }

            // ---- Test withdrawAll ----
            case 5 -> {
                log.info("[BankTest] === withdrawAll(\"Anchovies\") ===");
                try {
                    boolean result = bank.withdrawAll("Anchovies");
                    log.info("[BankTest] withdrawAll(\"Anchovies\") = {}", result);
                } catch (Exception e) {
                    log.error("[BankTest] withdrawAll(\"Anchovies\") threw: {}", e.getMessage());
                }
                step++;
                return 1000;
            }

            // ---- Test backpack state ----
            case 6 -> {
                log.info("[BankTest] === backpackCount(\"Anchovies\" area) ===");
                int bpSlots = bank.backpackOccupiedSlots();
                boolean bpEmpty = bank.backpackIsEmpty();
                log.info("[BankTest] backpackOccupiedSlots = {}", bpSlots);
                log.info("[BankTest] backpackIsEmpty = {}", bpEmpty);
                step++;
                return 1000;
            }

            case 7 -> {
                log.info("[BankTest] ==============================");
                log.info("[BankTest] TEST COMPLETE — check logs for errors/exceptions");
                log.info("[BankTest] ==============================");
                return -1;
            }

            default -> { return -1; }
        }
    }

    @Override
    public void onStop() {
        log.info("[BankTest] Stopped at step {}", step);
    }
}
