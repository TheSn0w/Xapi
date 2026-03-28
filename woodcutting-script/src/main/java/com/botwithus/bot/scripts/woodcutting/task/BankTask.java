package com.botwithus.bot.scripts.woodcutting.task;

import com.botwithus.bot.api.entities.SceneObject;
import com.botwithus.bot.api.log.BotLogger;
import com.botwithus.bot.api.log.LoggerFactory;
import com.botwithus.bot.api.script.Task;
import com.botwithus.bot.api.util.Conditions;
import com.botwithus.bot.scripts.woodcutting.WoodcuttingContext;

/**
 * Walks to bank, deposits items, empties wood box, and closes.
 * Integrates quirks: early banking (bank before full), mis-ordered operations.
 */
public final class BankTask implements Task {

    private static final BotLogger log = LoggerFactory.getLogger(BankTask.class);

    private final WoodcuttingContext wctx;

    public BankTask(WoodcuttingContext wctx) {
        this.wctx = wctx;
    }

    @Override
    public String name() {
        return "Banking";
    }

    @Override
    public int priority() {
        return 20;
    }

    @Override
    public boolean validate() {
        if (wctx.backpack.isFull()) return true;
        // Quirk 4: early banking — bank with a few empty slots left
        return wctx.quirks.shouldEarlyBank(wctx.backpack.freeSlots());
    }

    @Override
    public int execute() {
        if (!wctx.bank.isOpen()) {
            SceneObject counter = wctx.objects.query().withId(2012).nearest();
            if (counter == null) {
                log.warn("[Woodcutting] No bank Counter found nearby");
                wctx.logAction("No Counter found - retrying");
                return (int) wctx.pace.delay("react");
            }

            wctx.logAction("Bank at Counter (" + counter.tileX() + ", " + counter.tileY() + ")");
            counter.interact("Bank");
            Conditions.waitUntil(wctx.bank::isOpen, 30000);
            return (int) wctx.pace.delay("bank");
        }

        // Quirk 5: mis-ordered banking — try deposit before emptying woodbox
        if (wctx.quirks.shouldMisorderBank()) {
            return executeMisOrdered();
        }

        return executeNormal();
    }

    /** Normal order: empty woodbox → deposit backpack → close. */
    private int executeNormal() {
        // Empty wood box first
        if (wctx.woodBox.hasWoodBox() && !wctx.woodBox.isEmpty()) {
            wctx.logAction("Emptying wood box (" + wctx.woodBox.getTotalStored() + " items)");
            wctx.woodBox.empty();
            Conditions.waitUntil(wctx.woodBox::isEmpty, 3000);
            return (int) wctx.pace.delay("bank");
        }

        return depositAndClose();
    }

    /** Mis-ordered: deposit backpack first (fails partially), then empty woodbox, then deposit again. */
    private int executeMisOrdered() {
        wctx.logAction("Depositing backpack (keeping wood box)");
        wctx.bank.depositAllExcept(WoodcuttingContext.WOOD_BOX_KEEP_IDS);
        Conditions.waitUntil(
                () -> wctx.bank.backpackOccupiedSlots() <= WoodcuttingContext.WOOD_BOX_KEEP_IDS.length + 2, 3000);

        // Now notice woodbox still has items, empty it
        if (wctx.woodBox.hasWoodBox() && !wctx.woodBox.isEmpty()) {
            wctx.logAction("Oh, emptying wood box too (" + wctx.woodBox.getTotalStored() + " items)");
            wctx.woodBox.empty();
            Conditions.waitUntil(wctx.woodBox::isEmpty, 3000);
            // Deposit again for the woodbox contents
            wctx.bank.depositAllExcept(WoodcuttingContext.WOOD_BOX_KEEP_IDS);
            Conditions.waitUntil(
                    () -> wctx.bank.backpackOccupiedSlots() <= WoodcuttingContext.WOOD_BOX_KEEP_IDS.length, 3000);
        }

        return closeBank();
    }

    private int depositAndClose() {
        wctx.logAction("Depositing backpack (keeping wood box)");
        wctx.bank.depositAllExcept(WoodcuttingContext.WOOD_BOX_KEEP_IDS);
        Conditions.waitUntil(
                () -> wctx.bank.backpackOccupiedSlots() <= WoodcuttingContext.WOOD_BOX_KEEP_IDS.length, 3000);
        return closeBank();
    }

    private int closeBank() {
        wctx.logAction("Banking complete");
        wctx.bank.close();
        Conditions.waitUntil(() -> !wctx.bank.isOpen(), 2000);

        wctx.bankTrips++;
        wctx.pace.after("bank");
        wctx.pace.breakCheck();
        return (int) wctx.pace.delay("bank");
    }
}
