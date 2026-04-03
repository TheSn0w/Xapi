package com.botwithus.bot.scripts.woodcutting.task;

import com.botwithus.bot.api.entities.SceneObject;
import com.botwithus.bot.api.log.BotLogger;
import com.botwithus.bot.api.log.LoggerFactory;
import com.botwithus.bot.api.model.InventoryItem;
import com.botwithus.bot.api.script.Task;
import com.botwithus.bot.scripts.woodcutting.HotspotProfile;
import com.botwithus.bot.scripts.woodcutting.InventoryMode;
import com.botwithus.bot.scripts.woodcutting.TileAnchor;
import com.botwithus.bot.scripts.woodcutting.TreeProfile;
import com.botwithus.bot.scripts.woodcutting.WoodcuttingContext;

import java.util.List;

/**
 * State-machine banking task. Each execute() call performs one atomic step
 * and returns a delay, allowing collectUIState() to run between steps.
 * No blocking Conditions.waitUntil loops — the main loop handles retries.
 */
public final class BankTask implements Task {

    private static final BotLogger log = LoggerFactory.getLogger(BankTask.class);

    private final WoodcuttingContext wctx;

    private enum Phase {
        WALK_TO_BANK,
        OPEN_BANK,
        EMPTY_WOOD_BOX,
        DEPOSIT,
        CLOSE_BANK,
        DROP
    }

    private Phase phase = Phase.WALK_TO_BANK;

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
        InventoryMode mode = wctx.hotspot().inventoryMode();
        boolean inventoryPressure = wctx.backpackFull || (mode.isBankLike() && wctx.quirks.shouldEarlyBank(wctx.freeSlots));

        boolean needed = switch (mode) {
            case BANK -> inventoryPressure || wctx.bankOpen;
            case DEPOSIT_BOX -> inventoryPressure || (wctx.depositOpen && (28 - wctx.freeSlots) > WoodcuttingContext.WOOD_BOX_KEEP_IDS.length);
            case DROP -> wctx.backpackFull && wctx.backpackProductCount > 0;
            case SPECIAL -> wctx.backpackFull && wctx.backpackProductCount > 0;
            case NONE -> false;
        };

        if (!needed) {
            phase = Phase.WALK_TO_BANK;
        }
        return needed;
    }

    @Override
    public int execute() {
        InventoryMode mode = wctx.hotspot().inventoryMode();

        if (mode == InventoryMode.DROP || mode == InventoryMode.SPECIAL) {
            return executeDrop();
        }

        boolean isBankMode = mode == InventoryMode.BANK;

        return switch (phase) {
            case WALK_TO_BANK -> stepWalkToBank(isBankMode);
            case OPEN_BANK -> stepOpenBank(isBankMode);
            case EMPTY_WOOD_BOX -> stepEmptyWoodBox();
            case DEPOSIT -> stepDeposit(isBankMode);
            case CLOSE_BANK -> stepCloseBank(isBankMode);
            case DROP -> executeDrop();
        };
    }

    // ── State machine steps ─────────────────────────────────────────

    private int stepWalkToBank(boolean isBankMode) {
        TileAnchor anchor = isBankMode ? wctx.hotspot().bankAnchor() : wctx.hotspot().depositAnchor();
        if (anchor == null) {
            wctx.logGuarded("No storage anchor configured for " + wctx.hotspot().label() + ".");
            return (int) wctx.pace.delay("idle");
        }

        // Already at bank? Skip to open.
        if (wctx.playerHelper.distanceTo(anchor.x(), anchor.y()) <= 10) {
            phase = Phase.OPEN_BANK;
            return (int) wctx.pace.delay("react");
        }

        wctx.logAction("MOVE: Walking to " + anchor.label());
        wctx.api.walkWorldPathAsync(anchor.x(), anchor.y(), anchor.plane());
        return (int) wctx.pace.delay("walk");
    }

    private int stepOpenBank(boolean isBankMode) {
        // Already open? Move on.
        if (isBankMode && wctx.bankOpen) {
            phase = shouldEmptyWoodBox() ? Phase.EMPTY_WOOD_BOX : Phase.DEPOSIT;
            return (int) wctx.pace.delay("react");
        }
        if (!isBankMode && wctx.depositOpen) {
            phase = Phase.DEPOSIT;
            return (int) wctx.pace.delay("react");
        }

        TileAnchor anchor = isBankMode ? wctx.hotspot().bankAnchor() : wctx.hotspot().depositAnchor();
        List<Integer> ids = isBankMode ? wctx.hotspot().bankObjectIds() : wctx.hotspot().depositObjectIds();
        List<String> names = isBankMode ? wctx.hotspot().bankObjectNames() : wctx.hotspot().depositObjectNames();
        String action = isBankMode ? wctx.hotspot().bankAction() : wctx.hotspot().depositAction();

        // Too far? Walk first.
        if (wctx.playerHelper.distanceTo(anchor.x(), anchor.y()) > 10) {
            phase = Phase.WALK_TO_BANK;
            return (int) wctx.pace.delay("react");
        }

        SceneObject target = findStorageTarget(anchor, names, ids, action);
        if (target == null) {
            String type = isBankMode ? "bank" : "deposit";
            log.warn("[Woodcutting] No {} object found near {}", type, anchor.label());
            wctx.logAction("WARN: No " + type + " object near " + anchor.label());
            return (int) wctx.pace.delay("react");
        }

        wctx.logAction("TASK: Opening " + target.name());
        interactStorageTarget(target, action, isBankMode);
        return (int) wctx.pace.delay("bank");
    }

    private int stepEmptyWoodBox() {
        if (!wctx.bankOpen) {
            phase = Phase.OPEN_BANK;
            return (int) wctx.pace.delay("react");
        }

        if (wctx.woodBoxIsEmptyEffective()) {
            phase = Phase.DEPOSIT;
            return (int) wctx.pace.delay("react");
        }

        wctx.logAction("TASK: Emptying wood box");
        wctx.woodBox.empty();
        wctx.markWoodBoxEmptied();
        return (int) wctx.pace.delay("bank");
    }

    private int stepDeposit(boolean isBankMode) {
        if (isBankMode) {
            if (!wctx.bankOpen) {
                phase = Phase.OPEN_BANK;
                return (int) wctx.pace.delay("react");
            }

            if (wctx.woodBoxUnsupported) {
                wctx.logAction("TASK: Depositing all (unsupported wood box)");
                wctx.bank.depositAll();
            } else {
                wctx.logAction("TASK: Depositing backpack");
                wctx.bank.depositAllExcept(WoodcuttingContext.WOOD_BOX_KEEP_IDS);
            }
            phase = Phase.CLOSE_BANK;
        } else {
            if (!wctx.depositOpen) {
                phase = Phase.OPEN_BANK;
                return (int) wctx.pace.delay("react");
            }

            wctx.logAction("TASK: Deposit box");
            wctx.depositBox.depositAllExcept(WoodcuttingContext.WOOD_BOX_KEEP_IDS);
            phase = Phase.CLOSE_BANK;
        }

        return (int) wctx.pace.delay("bank");
    }

    private int stepCloseBank(boolean isBankMode) {
        if (isBankMode && wctx.bankOpen) {
            wctx.bank.close();
            return (int) wctx.pace.delay("bank");
        }

        // Closed — finish up
        wctx.bankTrips++;
        wctx.quirks.resetEarlyBankLatch();
        wctx.invalidateWoodBoxCache();
        wctx.logAction("OK: Banking complete");
        wctx.pace.after("bank");
        wctx.pace.breakCheck();
        phase = Phase.WALK_TO_BANK;
        return (int) wctx.pace.delay("bank");
    }

    // ── Drop mode (no state machine needed — single step per call) ──

    private int executeDrop() {
        TreeProfile profile = wctx.profile();

        if ("crystal".equals(profile.id())) {
            wctx.logGuarded("Crystal tree mode expects you to manage blossoms manually for now.");
            return (int) wctx.pace.delay("idle");
        }

        int dropped = 0;
        for (InventoryItem item : wctx.backpack.getItems()) {
            if (!matchesTrackedProduct(profile, item)) {
                continue;
            }
            boolean queued = wctx.backpack.interact(item.itemId(), "Drop");
            if (!queued) {
                queued = wctx.backpack.interact(item.itemId(), "Destroy");
            }
            if (queued) {
                dropped++;
            }
            if (dropped >= 5) {
                break;
            }
        }

        if (dropped == 0) {
            wctx.logGuarded("No tracked resources available to clear.");
            return (int) wctx.pace.delay("idle");
        }

        wctx.logAction("OK: Cleared " + dropped + " items");
        wctx.pace.after("bank");
        return (int) wctx.pace.delay("bank");
    }

    // ── Helpers ──────────────────────────────────────────────────────

    private boolean shouldEmptyWoodBox() {
        TreeProfile profile = wctx.profile();
        return profile.supportsWoodBox() && wctx.hasWoodBox && !wctx.woodBoxUnsupported && !wctx.woodBoxIsEmptyEffective();
    }

    private SceneObject findStorageTarget(TileAnchor anchor, List<String> names, List<Integer> ids, String action) {
        for (Integer id : ids) {
            SceneObject object = wctx.objects.query()
                    .withId(id)
                    .within(anchor.x(), anchor.y(), 16)
                    .visible()
                    .nearest();
            if (object != null) {
                return object;
            }
        }

        for (String name : names) {
            SceneObject object = wctx.objects.query()
                    .named(name)
                    .within(anchor.x(), anchor.y(), 16)
                    .visible()
                    .nearest();
            if (object != null) {
                return object;
            }
        }

        return null;
    }

    private void interactStorageTarget(SceneObject object, String action, boolean bankTarget) {
        if (action != null && !action.isBlank() && object.interact(action)) {
            return;
        }
        if (bankTarget && object.interact("Use")) {
            return;
        }
        if (!bankTarget) {
            if (object.interact("Deposit-All")) return;
            object.interact("Use");
        }
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
