package com.botwithus.bot.scripts.woodcutting.task;

import com.botwithus.bot.api.entities.SceneObject;
import com.botwithus.bot.api.log.BotLogger;
import com.botwithus.bot.api.log.LoggerFactory;
import com.botwithus.bot.api.model.InventoryItem;
import com.botwithus.bot.api.script.Task;
import com.botwithus.bot.api.util.Conditions;
import com.botwithus.bot.scripts.woodcutting.HotspotProfile;
import com.botwithus.bot.scripts.woodcutting.InventoryMode;
import com.botwithus.bot.scripts.woodcutting.TileAnchor;
import com.botwithus.bot.scripts.woodcutting.TreeProfile;
import com.botwithus.bot.scripts.woodcutting.WoodcuttingContext;

import java.util.List;

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
        // All values read from cached volatile fields (populated by collectUIState, no RPC here)
        InventoryMode mode = wctx.hotspot().inventoryMode();
        boolean inventoryPressure = wctx.backpackFull || (mode.isBankLike() && wctx.quirks.shouldEarlyBank(wctx.freeSlots));

        return switch (mode) {
            case BANK -> inventoryPressure || wctx.bankOpen;
            case DEPOSIT_BOX -> inventoryPressure || (wctx.depositOpen && (28 - wctx.freeSlots) > WoodcuttingContext.WOOD_BOX_KEEP_IDS.length);
            case DROP -> wctx.backpackFull && wctx.backpackProductCount > 0;
            case SPECIAL -> wctx.backpackFull && wctx.backpackProductCount > 0;
            case NONE -> false;
        };
    }

    @Override
    public int execute() {
        HotspotProfile hotspot = wctx.hotspot();
        return switch (hotspot.inventoryMode()) {
            case BANK -> executeBanking();
            case DEPOSIT_BOX -> executeDepositBox();
            case DROP -> executeDropMode("drop profile");
            case SPECIAL -> executeSpecialInventory();
            case NONE -> (int) wctx.pace.delay("idle");
        };
    }

    private int executeBanking() {
        if (!wctx.bank.isOpen()) {
            int openDelay = openStorageTarget(wctx.hotspot().bankAnchor(), wctx.hotspot().bankObjectNames(), wctx.hotspot().bankObjectIds(), wctx.hotspot().bankAction(), true);
            if (openDelay >= 0) {
                return openDelay;
            }
        }

        if (wctx.quirks.shouldMisorderBank()) {
            return executeMisOrdered();
        }
        return executeNormal();
    }

    private int executeDepositBox() {
        if (!wctx.depositBox.isOpen()) {
            int openDelay = openStorageTarget(wctx.hotspot().depositAnchor(), wctx.hotspot().depositObjectNames(), wctx.hotspot().depositObjectIds(), wctx.hotspot().depositAction(), false);
            if (openDelay >= 0) {
                return openDelay;
            }
        }

        wctx.logAction("TASK: Deposit box");
        wctx.depositBox.depositAllExcept(WoodcuttingContext.WOOD_BOX_KEEP_IDS);
        Conditions.waitUntil(() -> wctx.depositBox.occupiedSlots() <= WoodcuttingContext.WOOD_BOX_KEEP_IDS.length, 2500, 600);

        wctx.bankTrips++;
        wctx.quirks.resetEarlyBankLatch();
        wctx.invalidateWoodBoxCache();
        wctx.logAction("OK: Deposit complete");
        wctx.pace.after("bank");
        wctx.pace.breakCheck();
        return (int) wctx.pace.delay("bank");
    }

    private int executeSpecialInventory() {
        TreeProfile profile = wctx.profile();
        if ("crystal".equals(profile.id())) {
            wctx.logGuarded("Crystal tree mode expects you to manage blossoms manually for now.");
            return (int) wctx.pace.delay("idle");
        }
        return executeDropMode("special profile");
    }

    private int executeDropMode(String reason) {
        TreeProfile profile = wctx.profile();
        int dropped = 0;
        int before = wctx.trackedProductCount();

        for (InventoryItem item : wctx.backpack.getItems()) {
            if (!matchesTrackedProduct(profile, item)) {
                continue;
            }
            boolean queued = wctx.backpack.interact(item.itemId(), "Drop");
            if (!queued) {
                queued = wctx.backpack.interact(item.itemId(), "Destroy");
            }
            if (!queued) {
                continue;
            }
            dropped++;
            int itemId = item.itemId();
            Conditions.waitUntil(() -> !wctx.backpack.contains(itemId), 1200, 600);
            if (dropped >= 5) {
                break;
            }
        }

        if (dropped == 0) {
            log.warn("[Woodcutting] No tracked items available for {}", reason);
            wctx.logGuarded("No tracked resources available to clear for " + reason + ".");
            return (int) wctx.pace.delay("idle");
        }

        wctx.logAction("OK: Cleared " + dropped + " items (" + reason + ")");
        wctx.pace.after("bank");
        return (int) wctx.pace.delay("bank");
    }

    private int executeNormal() {
        TreeProfile profile = wctx.profile();
        if (profile.supportsWoodBox() && wctx.hasWoodBox && !wctx.woodBoxUnsupported && !wctx.woodBoxIsEmptyEffective()) {
            wctx.logAction("TASK: Emptying wood box");
            wctx.woodBox.empty();
            Conditions.waitUntil(() -> wctx.woodBox.getTotalStored() == 0, 3000, 600);
            wctx.markWoodBoxEmptied();
            return (int) wctx.pace.delay("bank");
        }
        return depositAndClose();
    }

    private int executeMisOrdered() {
        wctx.logAction("QUIRK: Mis-ordered banking");
        wctx.bank.depositAllExcept(WoodcuttingContext.WOOD_BOX_KEEP_IDS);
        Conditions.waitUntil(() -> wctx.bank.backpackOccupiedSlots() <= WoodcuttingContext.WOOD_BOX_KEEP_IDS.length + 2, 3000, 600);

        if (wctx.hasWoodBox && !wctx.woodBoxUnsupported && !wctx.woodBoxIsEmptyEffective()) {
            wctx.logAction("TASK: Emptying wood box after deposit");
            wctx.woodBox.empty();
            Conditions.waitUntil(() -> wctx.woodBox.getTotalStored() == 0, 3000, 600);
            wctx.markWoodBoxEmptied();
            wctx.bank.depositAllExcept(WoodcuttingContext.WOOD_BOX_KEEP_IDS);
            Conditions.waitUntil(() -> wctx.bank.backpackOccupiedSlots() <= WoodcuttingContext.WOOD_BOX_KEEP_IDS.length, 3000, 600);
        }

        return closeBank();
    }

    private int depositAndClose() {
        if (wctx.woodBoxUnsupported) {
            // Wood box can't store our log type — bank everything including the wood box
            wctx.logAction("TASK: Depositing all (unsupported wood box)");
            wctx.bank.depositAll();
            Conditions.waitUntil(() -> wctx.bank.backpackOccupiedSlots() == 0, 3000, 600);
            wctx.invalidateWoodBoxCache();
        } else {
            wctx.logAction("TASK: Depositing backpack");
            wctx.bank.depositAllExcept(WoodcuttingContext.WOOD_BOX_KEEP_IDS);
            Conditions.waitUntil(() -> wctx.bank.backpackOccupiedSlots() <= WoodcuttingContext.WOOD_BOX_KEEP_IDS.length, 3000, 600);
        }
        return closeBank();
    }

    private int closeBank() {
        wctx.bank.close();
        Conditions.waitUntil(() -> !wctx.bank.isOpen(), 2000, 600);
        wctx.bankTrips++;
        wctx.quirks.resetEarlyBankLatch();
        wctx.invalidateWoodBoxCache();
        wctx.logAction("OK: Banking complete");
        wctx.pace.after("bank");
        wctx.pace.breakCheck();
        return (int) wctx.pace.delay("bank");
    }

    private int openStorageTarget(TileAnchor anchor, List<String> names, List<Integer> ids, String action, boolean bankTarget) {
        if (anchor == null) {
            wctx.logGuarded("No storage anchor configured for " + wctx.hotspot().label() + ".");
            return (int) wctx.pace.delay("idle");
        }

        if (wctx.playerHelper.distanceTo(anchor.x(), anchor.y()) > 10) {
            wctx.logAction("MOVE: Walking to " + anchor.label());
            wctx.api.walkWorldPathAsync(anchor.x(), anchor.y(), anchor.plane());
            return (int) wctx.pace.delay("walk");
        }

        SceneObject target = findStorageTarget(anchor, names, ids, action);
        if (target == null) {
            log.warn("[Woodcutting] No {} object found near {}", bankTarget ? "bank" : "deposit", anchor.label());
            wctx.logAction("WARN: No " + (bankTarget ? "bank" : "deposit") + " object near " + anchor.label());
            return (int) wctx.pace.delay("react");
        }

        String verb = bankTarget ? "Bank" : "Deposit";
        wctx.logAction("TASK: " + verb + " at " + target.name());
        if (!interactStorageTarget(target, action, bankTarget)) {
            wctx.logAction("WARN: Failed to open " + verb.toLowerCase() + " target");
            return (int) wctx.pace.delay("react");
        }

        Conditions.waitUntil(bankTarget ? wctx.bank::isOpen : wctx.depositBox::isOpen, 3000, 600);
        return (int) wctx.pace.delay("bank");
    }

    private SceneObject findStorageTarget(TileAnchor anchor, List<String> names, List<Integer> ids, String action) {
        for (String name : names) {
            SceneObject object = wctx.objects.query()
                    .named(name)
                    .within(anchor.x(), anchor.y(), 16)
                    .visible()
                    .filter(sceneObject -> actionMatches(sceneObject, action))
                    .nearest();
            if (object != null) {
                return object;
            }
        }

        for (Integer id : ids) {
            SceneObject object = wctx.objects.query()
                    .withId(id)
                    .within(anchor.x(), anchor.y(), 16)
                    .visible()
                    .filter(sceneObject -> actionMatches(sceneObject, action))
                    .nearest();
            if (object != null) {
                return object;
            }
        }

        return null;
    }

    private boolean actionMatches(SceneObject object, String action) {
        if (action == null || action.isBlank()) {
            return true;
        }
        if (object.hasOption(action)) {
            return true;
        }
        if ("Bank".equals(action)) {
            return object.hasOption("Use");
        }
        if ("Deposit".equals(action)) {
            return object.hasOption("Deposit-All") || object.hasOption("Use");
        }
        return false;
    }

    private boolean interactStorageTarget(SceneObject object, String action, boolean bankTarget) {
        if (action != null && !action.isBlank() && object.interact(action)) {
            return true;
        }
        if (bankTarget && object.interact("Use")) {
            return true;
        }
        if (!bankTarget && object.interact("Deposit-All")) {
            return true;
        }
        return !bankTarget && object.interact("Use");
    }

    private boolean hasProductInBackpack(TreeProfile profile) {
        if (profile.logItemId() != null && wctx.backpack.count(profile.logItemId()) > 0) {
            return true;
        }
        return !profile.productName().isBlank() && wctx.backpack.count(profile.productName()) > 0;
    }

    private boolean matchesTrackedProduct(TreeProfile profile, InventoryItem item) {
        if (profile.logItemId() != null) {
            return item.itemId() == profile.logItemId();
        }
        if (profile.productName().isBlank()) {
            return false;
        }
        var type = wctx.api.getItemType(item.itemId());
        return type != null && profile.productName().equalsIgnoreCase(type.name());
    }
}
