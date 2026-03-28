package com.botwithus.bot.scripts.woodcutting.task;

import com.botwithus.bot.api.entities.SceneObject;
import com.botwithus.bot.api.inventory.ComponentHelper;
import com.botwithus.bot.api.log.BotLogger;
import com.botwithus.bot.api.log.LoggerFactory;
import com.botwithus.bot.api.script.Task;
import com.botwithus.bot.api.util.Conditions;
import com.botwithus.bot.scripts.woodcutting.Quirks;
import com.botwithus.bot.scripts.woodcutting.WoodcuttingConfig;
import com.botwithus.bot.scripts.woodcutting.WoodcuttingContext;

import java.util.List;

/**
 * Default task: find and chop trees.
 * Integrates quirks: not-nearest tree, idle stare, tree loyalty,
 * fidgeting (inspect woodbox / examine tree).
 */
public final class ChoppingTask implements Task {

    private static final BotLogger log = LoggerFactory.getLogger(ChoppingTask.class);

    private final WoodcuttingContext wctx;

    public ChoppingTask(WoodcuttingContext wctx) {
        this.wctx = wctx;
    }

    @Override
    public String name() {
        return "Chopping";
    }

    @Override
    public int priority() {
        return 0;
    }

    @Override
    public boolean validate() {
        return true;
    }

    @Override
    public int execute() {
        WoodcuttingConfig.TreeType tree = wctx.config.getTreeType();
        Quirks quirks = wctx.quirks;

        // Still chopping — idle (delay state is computed by overlay from tick-updated fields)
        if (wctx.playerHelper.isAnimating()) {
            // Quirk 7: fidget while chopping — inspect woodbox or examine a nearby tree
            if (quirks.shouldFidget()) {
                doFidget(tree);
            }
            return (int) wctx.pace.idle("gather");
        }

        // Animation ended — pick ONE pause behaviour (mutually exclusive):
        // break, idle stare, or just the normal post-chop delay.
        wctx.pace.after("gather");

        long beforeBreak = System.currentTimeMillis();
        wctx.pace.breakCheck();
        boolean brokeJustNow = (System.currentTimeMillis() - beforeBreak) > 500;

        // If we didn't just take a break, maybe idle stare instead
        if (!brokeJustNow) {
            long stareMs = quirks.idleStareMs();
            if (stareMs > 0) {
                wctx.logAction("Zoned out for " + (stareMs / 1000) + "s");
                return (int) stareMs;
            }
        }

        // Too far from trees — let WalkToTreesTask handle it
        if (wctx.playerHelper.distanceTo(wctx.config.getTreeAreaX(), wctx.config.getTreeAreaY()) > wctx.config.getWalkRadius()) {
            return (int) wctx.pace.delay("react");
        }

        // Quirk 6: tree loyalty — click a depleted tree (no "Chop down" option), fail, then find another
        if (quirks.shouldClickDepletedTree()) {
            SceneObject depleted = wctx.objects.query()
                    .named(tree.objectName)
                    .within(wctx.config.getTreeAreaX(), wctx.config.getTreeAreaY(), wctx.config.getWalkRadius())
                    .visible()
                    .filter(o -> !o.hasOption(tree.interactOption))
                    .nearest();
            if (depleted != null) {
                wctx.logAction("Clicked depleted " + depleted.name() + " (oops)");
                depleted.interact(tree.interactOption); // will fail or do nothing
                return (int) wctx.pace.delay("react");
            }
        }

        // Quirk 2: not-nearest tree — occasionally pick a random tree instead of nearest
        SceneObject target;
        int offset = quirks.treeSelectionOffset();
        if (offset > 0) {
            // Quirk fired: pick from all visible trees (unsorted), skipping the first few
            List<SceneObject> all = wctx.objects.query()
                    .named(tree.objectName)
                    .within(wctx.config.getTreeAreaX(), wctx.config.getTreeAreaY(), wctx.config.getWalkRadius())
                    .visible()
                    .filter(o -> o.hasOption(tree.interactOption))
                    .all();
            if (all.isEmpty()) {
                return (int) wctx.pace.delay("idle");
            }
            int index = Math.min(offset, all.size() - 1);
            target = all.get(index);
        } else {
            // Normal: always pick nearest (proper A* distance sorting)
            target = wctx.objects.query()
                    .named(tree.objectName)
                    .within(wctx.config.getTreeAreaX(), wctx.config.getTreeAreaY(), wctx.config.getWalkRadius())
                    .visible()
                    .filter(o -> o.hasOption(tree.interactOption))
                    .nearest();
        }

        if (target == null) {
            log.debug("[Woodcutting] No choppable {} found nearby", tree.objectName);
            return (int) wctx.pace.delay("idle");
        }

        wctx.logAction(tree.interactOption + " " + target.name() + " at (" + target.tileX() + ", " + target.tileY() + ")");
        if (!target.interact(tree.interactOption)) {
            log.warn("[Woodcutting] Failed to interact with {} at ({}, {})", target.name(), target.tileX(), target.tileY());
            wctx.logAction("Failed to " + tree.interactOption + " " + target.name());
            return (int) wctx.pace.delay("react");
        }

        boolean started = Conditions.waitUntil(
                () -> wctx.playerHelper.isAnimating() || wctx.playerHelper.isMoving(), 1200);

        if (!started) {
            log.debug("[Woodcutting] Interaction queued but chopping did not start");
            return (int) wctx.pace.delay("react");
        }

        return (int) wctx.pace.idle("gather");
    }

    /**
     * Quirk 7: fidget — either inspect the wood box or examine a nearby tree.
     */
    private void doFidget(WoodcuttingConfig.TreeType tree) {
        if (wctx.woodBox.hasWoodBox() && Math.random() < 0.5) {
            // Inspect wood box via component interface
            wctx.api.getComponentChildren(1473, 5).stream()
                    .filter(c -> c.subComponentId() == 0)
                    .findFirst()
                    .ifPresent(comp -> {
                        ComponentHelper.queueComponentAction(wctx.api, comp, 3);
                        wctx.logAction("Inspected wood box (fidget)");
                    });
        } else {
            // Examine a nearby tree
            SceneObject nearby = wctx.objects.query()
                    .named(tree.objectName)
                    .within(wctx.config.getTreeAreaX(), wctx.config.getTreeAreaY(), wctx.config.getWalkRadius())
                    .visible()
                    .nearest();
            if (nearby != null) {
                nearby.interact("Examine");
                wctx.logAction("Examined " + nearby.name() + " (fidget)");
            }
        }
    }
}
