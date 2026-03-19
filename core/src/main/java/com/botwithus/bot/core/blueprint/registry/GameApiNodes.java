package com.botwithus.bot.core.blueprint.registry;

import com.botwithus.bot.api.GameAPI;
import com.botwithus.bot.api.blueprint.NodeDefinition;
import com.botwithus.bot.api.blueprint.NodeDefinition.PropertyDef;
import com.botwithus.bot.api.blueprint.PinDefinition;
import com.botwithus.bot.api.blueprint.PinDirection;
import com.botwithus.bot.api.blueprint.PinType;
import com.botwithus.bot.api.model.*;
import com.botwithus.bot.api.query.EntityFilter;
import com.botwithus.bot.api.query.InventoryFilter;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Registers nodes wrapping GameAPI methods for use in blueprints.
 */
public final class GameApiNodes {

    private GameApiNodes() {}

    /**
     * Registers all game API nodes into the given registry.
     *
     * @param registry the node registry
     */
    public static void registerAll(NodeRegistry registry) {
        registerEntityQueryNodes(registry);
        registerEntityInfoNodes(registry);
        registerGameStateNodes(registry);
        registerInventoryNodes(registry);
        registerActionNodes(registry);
        registerVariableNodes(registry);
        registerPlayerStatNodes(registry);
        registerChatNodes(registry);
        registerConfigNodes(registry);
        registerWorldNodes(registry);
        registerMiscNodes(registry);
    }

    // ===== Helper to build pin lists and definitions easily =====

    private static PinDefinition execIn() {
        return new PinDefinition("exec_in", "Exec", PinType.EXEC, PinDirection.INPUT, null);
    }

    private static PinDefinition execOut() {
        return new PinDefinition("exec_out", "Exec", PinType.EXEC, PinDirection.OUTPUT, null);
    }

    private static PinDefinition inPin(String id, String name, PinType type, Object defaultValue) {
        return new PinDefinition(id, name, type, PinDirection.INPUT, defaultValue);
    }

    private static PinDefinition inPin(String id, String name, PinType type) {
        return new PinDefinition(id, name, type, PinDirection.INPUT, null);
    }

    private static PinDefinition outPin(String id, String name, PinType type) {
        return new PinDefinition(id, name, type, PinDirection.OUTPUT, null);
    }

    // ===== Entity Query Nodes =====

    private static void registerEntityQueryNodes(NodeRegistry registry) {
        // gameapi.queryEntities
        registry.register(
                new NodeDefinition("gameapi.queryEntities", "Query Entities", "Game API/Entities",
                        List.of(
                                inPin("type", "Type", PinType.STRING, "npc"),
                                inPin("namePattern", "Name Pattern", PinType.STRING),
                                inPin("typeId", "Type ID", PinType.INT, -1),
                                inPin("radius", "Radius", PinType.INT),
                                inPin("tileX", "Tile X", PinType.INT, 0),
                                inPin("tileY", "Tile Y", PinType.INT, 0),
                                inPin("visibleOnly", "Visible Only", PinType.BOOLEAN, false),
                                inPin("inCombat", "In Combat", PinType.BOOLEAN, false),
                                inPin("notInCombat", "Not In Combat", PinType.BOOLEAN, false),
                                inPin("sortByDistance", "Sort By Distance", PinType.BOOLEAN, false),
                                inPin("maxResults", "Max Results", PinType.INT, 0),
                                outPin("entities", "Entities", PinType.ENTITY_LIST)
                        ),
                        Map.of()),
                ctx -> {
                    GameAPI api = ctx.getApi();
                    EntityFilter.Builder builder = EntityFilter.builder();

                    String type = ctx.readInput("type", String.class, "npc");
                    if (type != null && !type.isEmpty()) builder.type(type);

                    String namePattern = ctx.readInput("namePattern", String.class);
                    if (namePattern != null && !namePattern.isEmpty()) builder.namePattern(namePattern);

                    int typeId = ctx.readInput("typeId", Integer.class, -1);
                    if (typeId != -1) builder.typeId(typeId);

                    Integer radius = ctx.readInput("radius", Integer.class);
                    if (radius != null && radius > 0) {
                        builder.radius(radius);
                        builder.tileX(ctx.readInput("tileX", Integer.class, 0));
                        builder.tileY(ctx.readInput("tileY", Integer.class, 0));
                    }

                    if (ctx.readInput("visibleOnly", Boolean.class, false)) builder.visibleOnly(true);
                    if (ctx.readInput("inCombat", Boolean.class, false)) builder.inCombat(true);
                    if (ctx.readInput("notInCombat", Boolean.class, false)) builder.notInCombat(true);
                    if (ctx.readInput("sortByDistance", Boolean.class, false)) builder.sortByDistance(true);

                    int maxResults = ctx.readInput("maxResults", Integer.class, 0);
                    if (maxResults > 0) builder.maxResults(maxResults);

                    List<Entity> entities = api.queryEntities(builder.build());
                    return ExecutionResult.data(Map.of("entities", entities != null ? entities : List.of()));
                }
        );
    }

    // ===== Entity Info Nodes =====

    private static void registerEntityInfoNodes(NodeRegistry registry) {
        // gameapi.getEntityInfo
        registry.register(
                new NodeDefinition("gameapi.getEntityInfo", "Get Entity Info", "Game API/Entities",
                        List.of(
                                inPin("handle", "Handle", PinType.INT, 0),
                                outPin("handle_out", "Handle", PinType.INT),
                                outPin("serverIndex", "Server Index", PinType.INT),
                                outPin("typeId", "Type ID", PinType.INT),
                                outPin("tileX", "Tile X", PinType.INT),
                                outPin("tileY", "Tile Y", PinType.INT),
                                outPin("tileZ", "Plane", PinType.INT),
                                outPin("name", "Name", PinType.STRING),
                                outPin("nameHash", "Name Hash", PinType.INT),
                                outPin("isMoving", "Is Moving", PinType.BOOLEAN),
                                outPin("isHidden", "Is Hidden", PinType.BOOLEAN),
                                outPin("health", "Health", PinType.INT),
                                outPin("maxHealth", "Max Health", PinType.INT),
                                outPin("combatLevel", "Combat Level", PinType.INT),
                                outPin("animationId", "Animation ID", PinType.INT),
                                outPin("stanceId", "Stance ID", PinType.INT),
                                outPin("followingIndex", "Following Index", PinType.INT),
                                outPin("overheadText", "Overhead Text", PinType.STRING)
                        ),
                        Map.of()),
                ctx -> {
                    int handle = ctx.readInput("handle", Integer.class, 0);
                    EntityInfo info = ctx.getApi().getEntityInfo(handle);
                    Map<String, Object> outputs = new HashMap<>();
                    if (info != null) {
                        outputs.put("handle_out", info.handle());
                        outputs.put("serverIndex", info.serverIndex());
                        outputs.put("typeId", info.typeId());
                        outputs.put("tileX", info.tileX());
                        outputs.put("tileY", info.tileY());
                        outputs.put("tileZ", info.tileZ());
                        outputs.put("name", info.name() != null ? info.name() : "");
                        outputs.put("nameHash", info.nameHash());
                        outputs.put("isMoving", info.isMoving());
                        outputs.put("isHidden", info.isHidden());
                        outputs.put("health", info.health());
                        outputs.put("maxHealth", info.maxHealth());
                        outputs.put("combatLevel", info.combatLevel());
                        outputs.put("animationId", info.animationId());
                        outputs.put("stanceId", info.stanceId());
                        outputs.put("followingIndex", info.followingIndex());
                        outputs.put("overheadText", info.overheadText() != null ? info.overheadText() : "");
                    } else {
                        outputs.put("handle_out", 0);
                        outputs.put("serverIndex", 0);
                        outputs.put("typeId", 0);
                        outputs.put("tileX", 0);
                        outputs.put("tileY", 0);
                        outputs.put("tileZ", 0);
                        outputs.put("name", "");
                        outputs.put("nameHash", 0);
                        outputs.put("isMoving", false);
                        outputs.put("isHidden", false);
                        outputs.put("health", 0);
                        outputs.put("maxHealth", 0);
                        outputs.put("combatLevel", 0);
                        outputs.put("animationId", -1);
                        outputs.put("stanceId", -1);
                        outputs.put("followingIndex", -1);
                        outputs.put("overheadText", "");
                    }
                    return ExecutionResult.data(outputs);
                }
        );

        // gameapi.getEntityHealth
        registry.register(
                new NodeDefinition("gameapi.getEntityHealth", "Get Entity Health", "Game API/Entities",
                        List.of(
                                inPin("handle", "Handle", PinType.INT, 0),
                                outPin("health", "Health", PinType.INT),
                                outPin("maxHealth", "Max Health", PinType.INT)
                        ),
                        Map.of()),
                ctx -> {
                    int handle = ctx.readInput("handle", Integer.class, 0);
                    EntityHealth h = ctx.getApi().getEntityHealth(handle);
                    if (h != null) {
                        return ExecutionResult.data(Map.of("health", h.health(), "maxHealth", h.maxHealth()));
                    }
                    return ExecutionResult.data(Map.of("health", 0, "maxHealth", 0));
                }
        );

        // gameapi.getEntityPosition
        registry.register(
                new NodeDefinition("gameapi.getEntityPosition", "Get Entity Position", "Game API/Entities",
                        List.of(
                                inPin("handle", "Handle", PinType.INT, 0),
                                outPin("tileX", "Tile X", PinType.INT),
                                outPin("tileY", "Tile Y", PinType.INT),
                                outPin("plane", "Plane", PinType.INT)
                        ),
                        Map.of()),
                ctx -> {
                    int handle = ctx.readInput("handle", Integer.class, 0);
                    EntityPosition p = ctx.getApi().getEntityPosition(handle);
                    if (p != null) {
                        return ExecutionResult.data(Map.of("tileX", p.tileX(), "tileY", p.tileY(), "plane", p.plane()));
                    }
                    return ExecutionResult.data(Map.of("tileX", 0, "tileY", 0, "plane", 0));
                }
        );

        // gameapi.getEntityName
        registry.register(
                new NodeDefinition("gameapi.getEntityName", "Get Entity Name", "Game API/Entities",
                        List.of(
                                inPin("handle", "Handle", PinType.INT, 0),
                                outPin("name", "Name", PinType.STRING)
                        ),
                        Map.of()),
                ctx -> {
                    int handle = ctx.readInput("handle", Integer.class, 0);
                    String name = ctx.getApi().getEntityName(handle);
                    return ExecutionResult.data(Map.of("name", name != null ? name : ""));
                }
        );

        // gameapi.isEntityValid
        registry.register(
                new NodeDefinition("gameapi.isEntityValid", "Is Entity Valid", "Game API/Entities",
                        List.of(
                                inPin("handle", "Handle", PinType.INT, 0),
                                outPin("valid", "Valid", PinType.BOOLEAN)
                        ),
                        Map.of()),
                ctx -> {
                    int handle = ctx.readInput("handle", Integer.class, 0);
                    return ExecutionResult.data(Map.of("valid", ctx.getApi().isEntityValid(handle)));
                }
        );

        // gameapi.getEntityAnimation
        registry.register(
                new NodeDefinition("gameapi.getEntityAnimation", "Get Entity Animation", "Game API/Entities",
                        List.of(
                                inPin("handle", "Handle", PinType.INT, 0),
                                outPin("animationId", "Animation ID", PinType.INT)
                        ),
                        Map.of()),
                ctx -> {
                    int handle = ctx.readInput("handle", Integer.class, 0);
                    return ExecutionResult.data(Map.of("animationId", ctx.getApi().getEntityAnimation(handle)));
                }
        );
    }

    // ===== Game State Nodes =====

    private static void registerGameStateNodes(NodeRegistry registry) {
        // gameapi.getLocalPlayer
        registry.register(
                new NodeDefinition("gameapi.getLocalPlayer", "Get Local Player", "Game API/Player",
                        List.of(
                                outPin("serverIndex", "Server Index", PinType.INT),
                                outPin("name", "Name", PinType.STRING),
                                outPin("tileX", "Tile X", PinType.INT),
                                outPin("tileY", "Tile Y", PinType.INT),
                                outPin("plane", "Plane", PinType.INT),
                                outPin("isMember", "Is Member", PinType.BOOLEAN),
                                outPin("isMoving", "Is Moving", PinType.BOOLEAN),
                                outPin("animationId", "Animation ID", PinType.INT),
                                outPin("stanceId", "Stance ID", PinType.INT),
                                outPin("health", "Health", PinType.INT),
                                outPin("maxHealth", "Max Health", PinType.INT),
                                outPin("combatLevel", "Combat Level", PinType.INT),
                                outPin("overheadText", "Overhead Text", PinType.STRING),
                                outPin("targetIndex", "Target Index", PinType.INT),
                                outPin("targetType", "Target Type", PinType.INT)
                        ),
                        Map.of()),
                ctx -> {
                    LocalPlayer lp = ctx.getApi().getLocalPlayer();
                    Map<String, Object> outputs = new HashMap<>();
                    if (lp != null) {
                        outputs.put("serverIndex", lp.serverIndex());
                        outputs.put("name", lp.name() != null ? lp.name() : "");
                        outputs.put("tileX", lp.tileX());
                        outputs.put("tileY", lp.tileY());
                        outputs.put("plane", lp.plane());
                        outputs.put("isMember", lp.isMember());
                        outputs.put("isMoving", lp.isMoving());
                        outputs.put("animationId", lp.animationId());
                        outputs.put("stanceId", lp.stanceId());
                        outputs.put("health", lp.health());
                        outputs.put("maxHealth", lp.maxHealth());
                        outputs.put("combatLevel", lp.combatLevel());
                        outputs.put("overheadText", lp.overheadText() != null ? lp.overheadText() : "");
                        outputs.put("targetIndex", lp.targetIndex());
                        outputs.put("targetType", lp.targetType());
                    } else {
                        outputs.put("serverIndex", 0);
                        outputs.put("name", "");
                        outputs.put("tileX", 0);
                        outputs.put("tileY", 0);
                        outputs.put("plane", 0);
                        outputs.put("isMember", false);
                        outputs.put("isMoving", false);
                        outputs.put("animationId", -1);
                        outputs.put("stanceId", -1);
                        outputs.put("health", 0);
                        outputs.put("maxHealth", 0);
                        outputs.put("combatLevel", 0);
                        outputs.put("overheadText", "");
                        outputs.put("targetIndex", -1);
                        outputs.put("targetType", 0);
                    }
                    return ExecutionResult.data(outputs);
                }
        );

        // gameapi.getGameCycle
        registry.register(
                new NodeDefinition("gameapi.getGameCycle", "Get Game Cycle", "Game API/State",
                        List.of(outPin("cycle", "Cycle", PinType.INT)),
                        Map.of()),
                ctx -> ExecutionResult.data(Map.of("cycle", ctx.getApi().getGameCycle()))
        );

        // gameapi.getLoginState
        registry.register(
                new NodeDefinition("gameapi.getLoginState", "Get Login State", "Game API/State",
                        List.of(
                                outPin("state", "State", PinType.INT),
                                outPin("loginProgress", "Login Progress", PinType.INT),
                                outPin("loginStatus", "Login Status", PinType.INT)
                        ),
                        Map.of()),
                ctx -> {
                    LoginState ls = ctx.getApi().getLoginState();
                    if (ls != null) {
                        return ExecutionResult.data(Map.of(
                                "state", ls.state(),
                                "loginProgress", ls.loginProgress(),
                                "loginStatus", ls.loginStatus()
                        ));
                    }
                    return ExecutionResult.data(Map.of("state", 0, "loginProgress", 0, "loginStatus", 0));
                }
        );

        // gameapi.ping
        registry.register(
                new NodeDefinition("gameapi.ping", "Ping", "Game API/State",
                        List.of(outPin("success", "Success", PinType.BOOLEAN)),
                        Map.of()),
                ctx -> ExecutionResult.data(Map.of("success", ctx.getApi().ping()))
        );
    }

    // ===== Inventory Nodes =====

    private static void registerInventoryNodes(NodeRegistry registry) {
        // gameapi.queryInventoryItems
        registry.register(
                new NodeDefinition("gameapi.queryInventoryItems", "Query Inventory Items", "Game API/Inventory",
                        List.of(
                                inPin("inventoryId", "Inventory ID", PinType.INT, -1),
                                inPin("itemId", "Item ID", PinType.INT, -1),
                                outPin("items", "Items", PinType.INVENTORY_ITEM_LIST)
                        ),
                        Map.of()),
                ctx -> {
                    int inventoryId = ctx.readInput("inventoryId", Integer.class, -1);
                    int itemId = ctx.readInput("itemId", Integer.class, -1);
                    InventoryFilter.Builder builder = InventoryFilter.builder().nonEmpty(true);
                    if (inventoryId != -1) builder.inventoryId(inventoryId);
                    if (itemId != -1) builder.itemId(itemId);
                    List<InventoryItem> items = ctx.getApi().queryInventoryItems(builder.build());
                    return ExecutionResult.data(Map.of("items", items != null ? items : List.of()));
                }
        );

        // gameapi.getInventoryItem
        registry.register(
                new NodeDefinition("gameapi.getInventoryItem", "Get Inventory Item", "Game API/Inventory",
                        List.of(
                                inPin("inventoryId", "Inventory ID", PinType.INT, 93),
                                inPin("slot", "Slot", PinType.INT, 0),
                                outPin("itemId", "Item ID", PinType.INT),
                                outPin("quantity", "Quantity", PinType.INT),
                                outPin("slot_out", "Slot", PinType.INT)
                        ),
                        Map.of()),
                ctx -> {
                    int inventoryId = ctx.readInput("inventoryId", Integer.class, 93);
                    int slot = ctx.readInput("slot", Integer.class, 0);
                    InventoryItem item = ctx.getApi().getInventoryItem(inventoryId, slot);
                    if (item != null) {
                        return ExecutionResult.data(Map.of(
                                "itemId", item.itemId(),
                                "quantity", item.quantity(),
                                "slot_out", item.slot()
                        ));
                    }
                    return ExecutionResult.data(Map.of("itemId", -1, "quantity", 0, "slot_out", slot));
                }
        );

        // gameapi.queryInventories
        registry.register(
                new NodeDefinition("gameapi.queryInventories", "Query Inventories", "Game API/Inventory",
                        List.of(outPin("count", "Count", PinType.INT)),
                        Map.of()),
                ctx -> {
                    List<InventoryInfo> inventories = ctx.getApi().queryInventories();
                    return ExecutionResult.data(Map.of("count", inventories != null ? inventories.size() : 0));
                }
        );
    }

    // ===== Action Nodes =====

    private static void registerActionNodes(NodeRegistry registry) {
        // gameapi.queueAction
        registry.register(
                new NodeDefinition("gameapi.queueAction", "Queue Action", "Game API/Actions",
                        List.of(
                                execIn(),
                                inPin("actionId", "Action ID", PinType.INT, 0),
                                inPin("param1", "Param 1", PinType.INT, 0),
                                inPin("param2", "Param 2", PinType.INT, 0),
                                inPin("param3", "Param 3", PinType.INT, 0),
                                execOut()
                        ),
                        Map.of()),
                ctx -> {
                    int actionId = ctx.readInput("actionId", Integer.class, 0);
                    int param1 = ctx.readInput("param1", Integer.class, 0);
                    int param2 = ctx.readInput("param2", Integer.class, 0);
                    int param3 = ctx.readInput("param3", Integer.class, 0);
                    ctx.getApi().queueAction(new GameAction(actionId, param1, param2, param3));
                    return ExecutionResult.flow("exec_out");
                }
        );

        // gameapi.clearActionQueue
        registry.register(
                new NodeDefinition("gameapi.clearActionQueue", "Clear Action Queue", "Game API/Actions",
                        List.of(execIn(), execOut()),
                        Map.of()),
                ctx -> {
                    ctx.getApi().clearActionQueue();
                    return ExecutionResult.flow("exec_out");
                }
        );

        // gameapi.getActionQueueSize
        registry.register(
                new NodeDefinition("gameapi.getActionQueueSize", "Get Action Queue Size", "Game API/Actions",
                        List.of(outPin("size", "Size", PinType.INT)),
                        Map.of()),
                ctx -> ExecutionResult.data(Map.of("size", ctx.getApi().getActionQueueSize()))
        );

        // gameapi.areActionsBlocked
        registry.register(
                new NodeDefinition("gameapi.areActionsBlocked", "Are Actions Blocked", "Game API/Actions",
                        List.of(outPin("blocked", "Blocked", PinType.BOOLEAN)),
                        Map.of()),
                ctx -> ExecutionResult.data(Map.of("blocked", ctx.getApi().areActionsBlocked()))
        );

        // gameapi.setActionsBlocked
        registry.register(
                new NodeDefinition("gameapi.setActionsBlocked", "Set Actions Blocked", "Game API/Actions",
                        List.of(
                                execIn(),
                                inPin("blocked", "Blocked", PinType.BOOLEAN, false),
                                execOut()
                        ),
                        Map.of()),
                ctx -> {
                    boolean blocked = ctx.readInput("blocked", Boolean.class, false);
                    ctx.getApi().setActionsBlocked(blocked);
                    return ExecutionResult.flow("exec_out");
                }
        );
    }

    // ===== Variable Nodes =====

    private static void registerVariableNodes(NodeRegistry registry) {
        // gameapi.getVarbit
        registry.register(
                new NodeDefinition("gameapi.getVarbit", "Get Varbit", "Game API/Variables",
                        List.of(
                                inPin("varbitId", "Varbit ID", PinType.INT, 0),
                                outPin("value", "Value", PinType.INT)
                        ),
                        Map.of()),
                ctx -> {
                    int varbitId = ctx.readInput("varbitId", Integer.class, 0);
                    return ExecutionResult.data(Map.of("value", ctx.getApi().getVarbit(varbitId)));
                }
        );

        // gameapi.getVarp
        registry.register(
                new NodeDefinition("gameapi.getVarp", "Get Varp", "Game API/Variables",
                        List.of(
                                inPin("varId", "Var ID", PinType.INT, 0),
                                outPin("value", "Value", PinType.INT)
                        ),
                        Map.of()),
                ctx -> {
                    int varId = ctx.readInput("varId", Integer.class, 0);
                    return ExecutionResult.data(Map.of("value", ctx.getApi().getVarp(varId)));
                }
        );

        // gameapi.getVarcInt
        registry.register(
                new NodeDefinition("gameapi.getVarcInt", "Get Varc Int", "Game API/Variables",
                        List.of(
                                inPin("varcId", "Varc ID", PinType.INT, 0),
                                outPin("value", "Value", PinType.INT)
                        ),
                        Map.of()),
                ctx -> {
                    int varcId = ctx.readInput("varcId", Integer.class, 0);
                    return ExecutionResult.data(Map.of("value", ctx.getApi().getVarcInt(varcId)));
                }
        );

        // gameapi.getVarcString
        registry.register(
                new NodeDefinition("gameapi.getVarcString", "Get Varc String", "Game API/Variables",
                        List.of(
                                inPin("varcId", "Varc ID", PinType.INT, 0),
                                outPin("value", "Value", PinType.STRING)
                        ),
                        Map.of()),
                ctx -> {
                    int varcId = ctx.readInput("varcId", Integer.class, 0);
                    String value = ctx.getApi().getVarcString(varcId);
                    return ExecutionResult.data(Map.of("value", value != null ? value : ""));
                }
        );
    }

    // ===== Player Stat Nodes =====

    private static void registerPlayerStatNodes(NodeRegistry registry) {
        // gameapi.getPlayerStat
        registry.register(
                new NodeDefinition("gameapi.getPlayerStat", "Get Player Stat", "Game API/Stats",
                        List.of(
                                inPin("skillId", "Skill ID", PinType.INT, 0),
                                outPin("level", "Level", PinType.INT),
                                outPin("boostedLevel", "Boosted Level", PinType.INT),
                                outPin("xp", "XP", PinType.INT)
                        ),
                        Map.of()),
                ctx -> {
                    int skillId = ctx.readInput("skillId", Integer.class, 0);
                    PlayerStat stat = ctx.getApi().getPlayerStat(skillId);
                    if (stat != null) {
                        return ExecutionResult.data(Map.of(
                                "level", stat.level(),
                                "boostedLevel", stat.boostedLevel(),
                                "xp", stat.xp()
                        ));
                    }
                    return ExecutionResult.data(Map.of("level", 0, "boostedLevel", 0, "xp", 0));
                }
        );

        // gameapi.getPlayerStats
        registry.register(
                new NodeDefinition("gameapi.getPlayerStats", "Get Player Stats", "Game API/Stats",
                        List.of(outPin("count", "Count", PinType.INT)),
                        Map.of()),
                ctx -> {
                    List<PlayerStat> stats = ctx.getApi().getPlayerStats();
                    return ExecutionResult.data(Map.of("count", stats != null ? stats.size() : 0));
                }
        );
    }

    // ===== Chat Nodes =====

    private static void registerChatNodes(NodeRegistry registry) {
        // gameapi.queryChatHistory
        registry.register(
                new NodeDefinition("gameapi.queryChatHistory", "Query Chat History", "Game API/Chat",
                        List.of(
                                inPin("messageType", "Message Type", PinType.INT, -1),
                                inPin("maxResults", "Max Results", PinType.INT, 50),
                                outPin("count", "Count", PinType.INT)
                        ),
                        Map.of()),
                ctx -> {
                    int messageType = ctx.readInput("messageType", Integer.class, -1);
                    int maxResults = ctx.readInput("maxResults", Integer.class, 50);
                    List<ChatMessage> messages = ctx.getApi().queryChatHistory(messageType, maxResults);
                    return ExecutionResult.data(Map.of("count", messages != null ? messages.size() : 0));
                }
        );
    }

    // ===== Config Lookup Nodes =====

    private static void registerConfigNodes(NodeRegistry registry) {
        // gameapi.getItemType
        registry.register(
                new NodeDefinition("gameapi.getItemType", "Get Item Type", "Game API/Config",
                        List.of(
                                inPin("id", "ID", PinType.INT, 0),
                                outPin("name", "Name", PinType.STRING),
                                outPin("members", "Members", PinType.BOOLEAN),
                                outPin("stackable", "Stackable", PinType.BOOLEAN),
                                outPin("shopPrice", "Shop Price", PinType.INT),
                                outPin("exchangeable", "Exchangeable", PinType.BOOLEAN)
                        ),
                        Map.of()),
                ctx -> {
                    int id = ctx.readInput("id", Integer.class, 0);
                    ItemType item = ctx.getApi().getItemType(id);
                    Map<String, Object> outputs = new HashMap<>();
                    if (item != null) {
                        outputs.put("name", item.name() != null ? item.name() : "");
                        outputs.put("members", item.members());
                        outputs.put("stackable", item.stackable());
                        outputs.put("shopPrice", item.shopPrice());
                        outputs.put("exchangeable", item.exchangeable());
                    } else {
                        outputs.put("name", "");
                        outputs.put("members", false);
                        outputs.put("stackable", false);
                        outputs.put("shopPrice", 0);
                        outputs.put("exchangeable", false);
                    }
                    return ExecutionResult.data(outputs);
                }
        );

        // gameapi.getNpcType
        registry.register(
                new NodeDefinition("gameapi.getNpcType", "Get NPC Type", "Game API/Config",
                        List.of(
                                inPin("id", "ID", PinType.INT, 0),
                                outPin("name", "Name", PinType.STRING),
                                outPin("combatLevel", "Combat Level", PinType.INT)
                        ),
                        Map.of()),
                ctx -> {
                    int id = ctx.readInput("id", Integer.class, 0);
                    NpcType npc = ctx.getApi().getNpcType(id);
                    if (npc != null) {
                        return ExecutionResult.data(Map.of(
                                "name", npc.name() != null ? npc.name() : "",
                                "combatLevel", npc.combatLevel()
                        ));
                    }
                    return ExecutionResult.data(Map.of("name", "", "combatLevel", 0));
                }
        );
    }

    // ===== World Nodes =====

    private static void registerWorldNodes(NodeRegistry registry) {
        // gameapi.getCurrentWorld
        registry.register(
                new NodeDefinition("gameapi.getCurrentWorld", "Get Current World", "Game API/World",
                        List.of(outPin("worldId", "World ID", PinType.INT)),
                        Map.of()),
                ctx -> {
                    World world = ctx.getApi().getCurrentWorld();
                    return ExecutionResult.data(Map.of("worldId", world != null ? world.worldId() : -1));
                }
        );

        // gameapi.setWorld
        registry.register(
                new NodeDefinition("gameapi.setWorld", "Set World", "Game API/World",
                        List.of(
                                execIn(),
                                inPin("worldId", "World ID", PinType.INT, 0),
                                execOut()
                        ),
                        Map.of()),
                ctx -> {
                    int worldId = ctx.readInput("worldId", Integer.class, 0);
                    ctx.getApi().setWorld(worldId);
                    return ExecutionResult.flow("exec_out");
                }
        );

        // gameapi.computeNameHash
        registry.register(
                new NodeDefinition("gameapi.computeNameHash", "Compute Name Hash", "Game API/World",
                        List.of(
                                inPin("name", "Name", PinType.STRING, ""),
                                outPin("hash", "Hash", PinType.INT)
                        ),
                        Map.of()),
                ctx -> {
                    String name = ctx.readInput("name", String.class, "");
                    return ExecutionResult.data(Map.of("hash", ctx.getApi().computeNameHash(name)));
                }
        );
    }

    // ===== Misc Nodes =====

    private static void registerMiscNodes(NodeRegistry registry) {
        // Already registered setActionsBlocked in action nodes
    }
}
