package com.xapi.debugger;

import com.botwithus.bot.api.*;
import com.botwithus.bot.api.antiban.Pace;
import com.botwithus.bot.api.entities.*;
import com.botwithus.bot.api.log.BotLogger;
import com.botwithus.bot.api.log.LoggerFactory;
import com.botwithus.bot.api.model.*;
import com.botwithus.bot.api.inventory.*;
import com.botwithus.bot.api.query.*;
import com.botwithus.bot.api.util.Conditions;

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

    private ScriptContext ctx;
    private Pace pace;
    private Backpack backpack;
    private SceneObjects objects;
    private SoilBox soilBox;
    private GameAPI api;
    private int step = 0;
    private int passed = 0;
    private int failed = 0;
    private int storedBefore = 0;

    @Override
    public void onStart(ScriptContext ctx) {
        this.ctx = ctx;
        this.api = ctx.getGameAPI();
        this.pace = ctx.getPace();
        this.backpack = new Backpack(api);
        this.objects = new SceneObjects(api);
        this.soilBox = new SoilBox(api);
        log.info("[SoilBoxTest] Started — have soil box with soil stored, bank open");
    }


// Required fields: private ScriptContext ctx; private Pace pace; private Backpack backpack; private int step = 0;
// In onStart(): this.ctx = ctx; this.pace = ctx.getPace(); this.backpack = new Backpack(ctx.getGameAPI());


// Required fields: private ScriptContext ctx; private Pace pace; private Backpack backpack; private int step = 0;
// In onStart(): this.ctx = ctx; this.pace = ctx.getPace(); this.backpack = new Backpack(ctx.getGameAPI());

    @Override
    public int onLoop() {
        GameAPI api = ctx.getGameAPI();
        var player = api.getLocalPlayer();
        pace.breakCheck();

        if (player.isMoving()) return (int) pace.idle("walk");

        switch (step) {
            case 0 -> { // Intent: Walking
                api.walkToAsync(3092, 3239);
                Conditions.waitUntil(() -> !api.getLocalPlayer().isMoving(), 10000);
                step++;
                return (int) pace.delay("walk");
            }
            case 1 -> { // Intent: Starting new gathering trip
                if (backpack.isFull()) { step++; return (int) pace.delay("react"); }
                if (distanceTo(player, 3088, 3234) > 15) {
                    api.walkToAsync(3088, 3234);
                    return (int) pace.delay("walk");
                }
                if (player.animationId() != -1) return (int) pace.idle("gather");
                var target = objects.query().named("Willow").nearest();
                if (target == null) { step++; return (int) pace.delay("react"); }
                target.interact("Chop down");
                return (int) pace.idle("gather");
            }
            case 2 -> { // Intent: Object interaction: bank
                if (distanceTo(player, 3091, 3244) > 15) {
                    api.walkToAsync(3091, 3244);
                    return (int) pace.delay("walk");
                }
                if (player.animationId() != -1) return (int) pace.idle("gather");
                var target = objects.query().named("Counter").nearest();
                if (target == null) { step++; return (int) pace.delay("react"); }
                target.interact("Bank");
                return (int) pace.idle("gather");
            }
            case 3 -> { // Intent: UI interaction
                if (!api.isInterfaceOpen(517)) { step = 2; return (int) pace.delay("react"); }
                api.queryComponents(ComponentFilter.builder().interfaceId(517).build()).stream().filter(c -> c.componentId() == 39).findFirst().ifPresent(comp -> ComponentHelper.interactComponent(api, comp, "Deposit carried items"));  // "Deposit carried items" iface:517 comp:39 sub:-1 option:1
                step++;
                return (int) pace.delay("menu");
            }
            case 4 -> { // Intent: UI interaction
                if (!api.isInterfaceOpen(517)) { step = 2; return (int) pace.delay("react"); }
                api.queryComponents(ComponentFilter.builder().interfaceId(517).build()).stream().filter(c -> c.componentId() == 317).findFirst().ifPresent(comp -> ComponentHelper.queueComponentAction(api, comp, 1));  // iface:517 comp:317 sub:-1 option:1
                step = 0;
                return (int) pace.delay("menu");
            }
            default -> {
                return -1; // Done — stop script
            }
        }
    }

    private static int distanceTo(LocalPlayer p, int x, int y) {
        return Math.max(Math.abs(p.tileX() - x), Math.abs(p.tileY() - y));
    }

    @Override
    public void onStop() {
        log.info("[SoilBoxTest] Stopped at step {} — {} passed, {} failed", step, passed, failed);
    }
}
