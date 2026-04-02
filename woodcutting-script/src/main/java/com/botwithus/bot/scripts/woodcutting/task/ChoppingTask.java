package com.botwithus.bot.scripts.woodcutting.task;

import com.botwithus.bot.api.entities.SceneObject;
import com.botwithus.bot.api.inventory.ComponentHelper;
import com.botwithus.bot.api.log.BotLogger;
import com.botwithus.bot.api.log.LoggerFactory;
import com.botwithus.bot.api.script.Task;
import com.botwithus.bot.api.util.Conditions;
import com.botwithus.bot.scripts.woodcutting.HotspotProfile;
import com.botwithus.bot.scripts.woodcutting.TileAnchor;
import com.botwithus.bot.scripts.woodcutting.TreeMode;
import com.botwithus.bot.scripts.woodcutting.TreeProfile;
import com.botwithus.bot.scripts.woodcutting.WoodcuttingContext;

import java.util.List;

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
        TreeProfile profile = wctx.profile();
        HotspotProfile hotspot = wctx.hotspot();

        if (wctx.animationId != -1) {
            if (wctx.quirks.shouldFidget()) {
                doFidget(profile);
            }
            return (int) wctx.pace.idle("gather");
        }

        wctx.pace.after("gather");
        long beforeBreak = System.currentTimeMillis();
        wctx.pace.breakCheck();
        boolean brokeJustNow = (System.currentTimeMillis() - beforeBreak) > 500;
        if (!brokeJustNow) {
            long stareMs = wctx.quirks.idleStareMs();
            if (stareMs > 0) {
                wctx.logAction("QUIRK: Idle stare for " + (stareMs / 1000) + "s");
                return (int) stareMs;
            }
        }

        if (!wctx.isNearCurrentTreeArea()) {
            return (int) wctx.pace.delay("react");
        }

        if (wctx.quirks.shouldClickDepletedTree()) {
            SceneObject depleted = findDepletedTree(profile, hotspot);
            if (depleted != null) {
                wctx.logAction("QUIRK: Clicked depleted " + depleted.name());
                depleted.interact(profile.primaryAction());
                return (int) wctx.pace.delay("react");
            }
        }

        return switch (profile.mode()) {
            case ROUTE_ROTATION, ROUTE_SPECIAL -> executeRouteProfile(profile);
            case ACTIVE_WORLD_STATE -> executeCrystalProfile(profile);
            case FIXED_REGION -> executeFixedRegionProfile(profile);
            default -> executeStandardProfile(profile);
        };
    }

    private int executeStandardProfile(TreeProfile profile) {
        SceneObject target = chooseTarget(profile, wctx.currentTreeAnchor());
        if (target == null) {
            wctx.clearCurrentTarget();
            return (int) wctx.pace.delay("idle");
        }
        return interactTree(profile, target, profile.primaryAction());
    }

    private int executeRouteProfile(TreeProfile profile) {
        TileAnchor anchor = wctx.currentTreeAnchor();
        SceneObject target = chooseTarget(profile, anchor);
        if (target == null) {
            wctx.advanceRoute("no active tree");
            return (int) wctx.pace.delay("react");
        }
        return interactTree(profile, target, profile.primaryAction());
    }

    private int executeCrystalProfile(TreeProfile profile) {
        SceneObject target = chooseTarget(profile, wctx.currentTreeAnchor());
        if (target != null) {
            String action = target.hasOption("Harvest") ? "Harvest" : "Chop down";
            return interactTree(profile, target, action);
        }

        SceneObject helper = findHelperObject(profile, wctx.currentTreeAnchor());
        if (helper != null) {
            wctx.rememberTarget(helper);
            if (helper.hasOption("Look at")) {
                helper.interact("Look at");
                wctx.logAction("TASK: Locating active crystal tree");
                return (int) wctx.pace.delay("react");
            }
            if (helper.hasOption("Check")) {
                helper.interact("Check");
                wctx.logAction("TASK: Checking crystal tree state");
                return (int) wctx.pace.delay("react");
            }
        }

        wctx.logGuarded("No active crystal tree found in the current hotspot.");
        return (int) wctx.pace.delay("idle");
    }

    private int executeFixedRegionProfile(TreeProfile profile) {
        SceneObject target = chooseTarget(profile, wctx.currentTreeAnchor());
        if (target != null) {
            return interactTree(profile, target, profile.primaryAction());
        }

        if ("eternal_magic".equals(profile.id())) {
            SceneObject identify = wctx.objects.query()
                    .named(profile.objectName())
                    .within(wctx.currentTreeAnchor().x(), wctx.currentTreeAnchor().y(), wctx.hotspot().radius())
                    .visible()
                    .filter(object -> object.hasOption("Identify"))
                    .nearest();
            if (identify != null) {
                wctx.rememberTarget(identify);
                identify.interact("Identify");
                wctx.logAction("TASK: Identify eternal magic tree");
                return (int) wctx.pace.delay("react");
            }
        }

        wctx.logGuarded("No active " + profile.displayName() + " found in the current region.");
        return (int) wctx.pace.delay("idle");
    }

    private int interactTree(TreeProfile profile, SceneObject target, String action) {
        wctx.rememberTarget(target);
        wctx.logAction("TASK: " + action + " " + target.name() + " @ " + target.tileX() + "," + target.tileY());
        if (!target.interact(action)) {
            log.warn("[Woodcutting] Failed to interact with {} at ({}, {})", target.name(), target.tileX(), target.tileY());
            wctx.logAction("WARN: Failed to " + action + " " + target.name());
            return (int) wctx.pace.delay("react");
        }

        boolean started = Conditions.waitUntil(() -> {
            var player = wctx.api.getLocalPlayer();
            return player.animationId() != -1 || player.isMoving();
        }, 1200, 600);
        if (!started) {
            wctx.logAction("WARN: Chop interaction did not start");
            return (int) wctx.pace.delay("react");
        }

        return (int) wctx.pace.idle("gather");
    }

    private SceneObject chooseTarget(TreeProfile profile, TileAnchor anchor) {
        List<SceneObject> all = wctx.objects.query()
                .named(profile.objectName())
                .within(anchor.x(), anchor.y(), wctx.hotspot().radius())
                .visible()
                .filter(object -> isLiveCandidate(profile, object))
                .all();

        if (all.isEmpty()) {
            return null;
        }

        int offset = wctx.quirks.treeSelectionOffset();
        int index = Math.min(offset, all.size() - 1);
        return all.get(index);
    }

    private SceneObject findDepletedTree(TreeProfile profile, HotspotProfile hotspot) {
        TileAnchor anchor = wctx.currentTreeAnchor();
        return wctx.objects.query()
                .named(profile.objectName())
                .within(anchor.x(), anchor.y(), hotspot.radius())
                .visible()
                .filter(object -> sameName(profile, object) && !object.hasOption(profile.primaryAction()))
                .nearest();
    }

    private SceneObject findHelperObject(TreeProfile profile, TileAnchor anchor) {
        return wctx.objects.query()
                .named(profile.objectName())
                .within(anchor.x(), anchor.y(), wctx.hotspot().radius())
                .visible()
                .filter(object -> sameName(profile, object) && (profile.isHelperObject(object) || object.hasOption("Look at") || object.hasOption("Check") || object.hasOption("Identify")))
                .nearest();
    }

    private boolean isLiveCandidate(TreeProfile profile, SceneObject object) {
        if (!sameName(profile, object)) {
            return false;
        }
        if (profile.isActiveTree(object)) {
            return true;
        }
        if (profile.ignoreIds().contains(object.typeId())) {
            return false;
        }
        return profile.activeIds().isEmpty() && profile.allActions().stream().anyMatch(object::hasOption);
    }

    private boolean sameName(TreeProfile profile, SceneObject object) {
        return object != null && object.name() != null && profile.objectName().equalsIgnoreCase(object.name());
    }

    private void doFidget(TreeProfile profile) {
        if (wctx.hasWoodBox && Math.random() < 0.5) {
            var children = wctx.api.getComponentChildren(1473, 5);
            if (children != null) {
                children.stream()
                        .filter(component -> component.subComponentId() == 0)
                        .findFirst()
                        .ifPresent(component -> {
                            ComponentHelper.queueComponentAction(wctx.api, component, 3);
                            wctx.logAction("QUIRK: Inspected wood box");
                        });
            }
            return;
        }

        SceneObject nearby = wctx.objects.query()
                .named(profile.objectName())
                .within(wctx.currentTreeAnchor().x(), wctx.currentTreeAnchor().y(), wctx.hotspot().radius())
                .visible()
                .nearest();
        if (nearby != null) {
            nearby.interact("Examine");
            wctx.logAction("QUIRK: Examined " + nearby.name());
        }
    }
}
