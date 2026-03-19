package com.botwithus.bot.core.blueprint.registry;

import com.botwithus.bot.api.blueprint.NodeDefinition;
import com.botwithus.bot.api.blueprint.NodeDefinition.PropertyDef;
import com.botwithus.bot.api.blueprint.PinDefinition;
import com.botwithus.bot.api.blueprint.PinDirection;
import com.botwithus.bot.api.blueprint.PinType;
import com.botwithus.bot.api.model.Entity;

import java.util.List;
import java.util.Map;

/**
 * Registers data nodes: constants, variables, math operations, string operations, and list operations.
 */
public final class DataNodes {

    private DataNodes() {}

    /**
     * Registers all data nodes into the given registry.
     *
     * @param registry the node registry
     */
    public static void registerAll(NodeRegistry registry) {
        registerConstants(registry);
        registerVariables(registry);
        registerMathOps(registry);
        registerStringOps(registry);
        registerListOps(registry);
    }

    private static void registerConstants(NodeRegistry registry) {
        // data.const_int
        registry.register(
                new NodeDefinition("data.const_int", "Constant Int", "Data",
                        List.of(new PinDefinition("value", "Value", PinType.INT, PinDirection.OUTPUT, null)),
                        Map.of("value", new PropertyDef("Value", PinType.INT, 0, null))),
                ctx -> ExecutionResult.data(Map.of("value", ctx.getPropertyInt("value", 0)))
        );

        // data.const_float
        registry.register(
                new NodeDefinition("data.const_float", "Constant Float", "Data",
                        List.of(new PinDefinition("value", "Value", PinType.FLOAT, PinDirection.OUTPUT, null)),
                        Map.of("value", new PropertyDef("Value", PinType.FLOAT, 0.0f, null))),
                ctx -> ExecutionResult.data(Map.of("value", ctx.getPropertyFloat("value", 0.0f)))
        );

        // data.const_string
        registry.register(
                new NodeDefinition("data.const_string", "Constant String", "Data",
                        List.of(new PinDefinition("value", "Value", PinType.STRING, PinDirection.OUTPUT, null)),
                        Map.of("value", new PropertyDef("Value", PinType.STRING, "", null))),
                ctx -> ExecutionResult.data(Map.of("value", ctx.getPropertyString("value", "")))
        );

        // data.const_bool
        registry.register(
                new NodeDefinition("data.const_bool", "Constant Boolean", "Data",
                        List.of(new PinDefinition("value", "Value", PinType.BOOLEAN, PinDirection.OUTPUT, null)),
                        Map.of("value", new PropertyDef("Value", PinType.BOOLEAN, false, null))),
                ctx -> ExecutionResult.data(Map.of("value", ctx.getPropertyBoolean("value", false)))
        );
    }

    private static void registerVariables(NodeRegistry registry) {
        // data.get_variable
        registry.register(
                new NodeDefinition("data.get_variable", "Get Variable", "Data",
                        List.of(new PinDefinition("value", "Value", PinType.ANY, PinDirection.OUTPUT, null)),
                        Map.of("name", new PropertyDef("Name", PinType.STRING, "", null))),
                ctx -> {
                    String name = ctx.getPropertyString("name", "");
                    Object value = ctx.getVariable(name);
                    return ExecutionResult.data(Map.of("value", value != null ? value : 0));
                }
        );

        // data.set_variable
        registry.register(
                new NodeDefinition("data.set_variable", "Set Variable", "Data",
                        List.of(
                                new PinDefinition("exec_in", "Exec", PinType.EXEC, PinDirection.INPUT, null),
                                new PinDefinition("value", "Value", PinType.ANY, PinDirection.INPUT, null),
                                new PinDefinition("exec_out", "Exec", PinType.EXEC, PinDirection.OUTPUT, null)
                        ),
                        Map.of("name", new PropertyDef("Name", PinType.STRING, "", null))),
                ctx -> {
                    String name = ctx.getPropertyString("name", "");
                    Object value = ctx.readInputRaw("value");
                    if (!name.isEmpty()) {
                        ctx.setVariable(name, value);
                    }
                    return ExecutionResult.flow("exec_out");
                }
        );
    }

    private static void registerMathOps(NodeRegistry registry) {
        registerBinaryMathOp(registry, "data.add", "Add", (a, b) -> a + b);
        registerBinaryMathOp(registry, "data.subtract", "Subtract", (a, b) -> a - b);
        registerBinaryMathOp(registry, "data.multiply", "Multiply", (a, b) -> a * b);
        registerBinaryMathOp(registry, "data.divide", "Divide", (a, b) -> b != 0 ? a / b : 0f);
    }

    @FunctionalInterface
    private interface FloatBinaryOp {
        float apply(float a, float b);
    }

    private static void registerBinaryMathOp(NodeRegistry registry, String typeId, String name, FloatBinaryOp op) {
        registry.register(
                new NodeDefinition(typeId, name, "Data/Math",
                        List.of(
                                new PinDefinition("a", "A", PinType.FLOAT, PinDirection.INPUT, 0.0f),
                                new PinDefinition("b", "B", PinType.FLOAT, PinDirection.INPUT, 0.0f),
                                new PinDefinition("result", "Result", PinType.FLOAT, PinDirection.OUTPUT, null)
                        ),
                        Map.of()),
                ctx -> {
                    float a = ctx.readInput("a", Float.class, 0.0f);
                    float b = ctx.readInput("b", Float.class, 0.0f);
                    return ExecutionResult.data(Map.of("result", op.apply(a, b)));
                }
        );
    }

    private static void registerStringOps(NodeRegistry registry) {
        // data.concat
        registry.register(
                new NodeDefinition("data.concat", "Concat", "Data/String",
                        List.of(
                                new PinDefinition("a", "A", PinType.STRING, PinDirection.INPUT, ""),
                                new PinDefinition("b", "B", PinType.STRING, PinDirection.INPUT, ""),
                                new PinDefinition("result", "Result", PinType.STRING, PinDirection.OUTPUT, null)
                        ),
                        Map.of()),
                ctx -> {
                    String a = ctx.readInput("a", String.class, "");
                    String b = ctx.readInput("b", String.class, "");
                    return ExecutionResult.data(Map.of("result", a + b));
                }
        );

        // data.contains
        registry.register(
                new NodeDefinition("data.contains", "Contains", "Data/String",
                        List.of(
                                new PinDefinition("haystack", "Haystack", PinType.STRING, PinDirection.INPUT, ""),
                                new PinDefinition("needle", "Needle", PinType.STRING, PinDirection.INPUT, ""),
                                new PinDefinition("result", "Result", PinType.BOOLEAN, PinDirection.OUTPUT, null)
                        ),
                        Map.of()),
                ctx -> {
                    String haystack = ctx.readInput("haystack", String.class, "");
                    String needle = ctx.readInput("needle", String.class, "");
                    return ExecutionResult.data(Map.of("result", haystack.contains(needle)));
                }
        );

        // data.format
        registry.register(
                new NodeDefinition("data.format", "Format", "Data/String",
                        List.of(
                                new PinDefinition("template", "Template", PinType.STRING, PinDirection.INPUT, ""),
                                new PinDefinition("arg", "Arg", PinType.STRING, PinDirection.INPUT, ""),
                                new PinDefinition("result", "Result", PinType.STRING, PinDirection.OUTPUT, null)
                        ),
                        Map.of()),
                ctx -> {
                    String template = ctx.readInput("template", String.class, "");
                    String arg = ctx.readInput("arg", String.class, "");
                    String result;
                    try {
                        result = String.format(template, arg);
                    } catch (Exception e) {
                        result = template;
                    }
                    return ExecutionResult.data(Map.of("result", result));
                }
        );
    }

    @SuppressWarnings("unchecked")
    private static void registerListOps(NodeRegistry registry) {
        // data.list_size
        registry.register(
                new NodeDefinition("data.list_size", "List Size", "Data/List",
                        List.of(
                                new PinDefinition("list", "List", PinType.ENTITY_LIST, PinDirection.INPUT, null),
                                new PinDefinition("size", "Size", PinType.INT, PinDirection.OUTPUT, null)
                        ),
                        Map.of()),
                ctx -> {
                    List<?> list = ctx.readInput("list", List.class);
                    return ExecutionResult.data(Map.of("size", list != null ? list.size() : 0));
                }
        );

        // data.list_get
        registry.register(
                new NodeDefinition("data.list_get", "List Get", "Data/List",
                        List.of(
                                new PinDefinition("list", "List", PinType.ENTITY_LIST, PinDirection.INPUT, null),
                                new PinDefinition("index", "Index", PinType.INT, PinDirection.INPUT, 0),
                                new PinDefinition("element", "Element", PinType.ENTITY, PinDirection.OUTPUT, null)
                        ),
                        Map.of()),
                ctx -> {
                    List<?> list = ctx.readInput("list", List.class);
                    int index = ctx.readInput("index", Integer.class, 0);
                    Object element = null;
                    if (list != null && index >= 0 && index < list.size()) {
                        element = list.get(index);
                    }
                    return ExecutionResult.data(Map.of("element", element != null ? element : 0));
                }
        );

        // data.list_isEmpty
        registry.register(
                new NodeDefinition("data.list_isEmpty", "List Is Empty", "Data/List",
                        List.of(
                                new PinDefinition("list", "List", PinType.ENTITY_LIST, PinDirection.INPUT, null),
                                new PinDefinition("empty", "Empty", PinType.BOOLEAN, PinDirection.OUTPUT, null)
                        ),
                        Map.of()),
                ctx -> {
                    List<?> list = ctx.readInput("list", List.class);
                    return ExecutionResult.data(Map.of("empty", list == null || list.isEmpty()));
                }
        );

        // data.list_first
        registry.register(
                new NodeDefinition("data.list_first", "List First", "Data/List",
                        List.of(
                                new PinDefinition("list", "List", PinType.ENTITY_LIST, PinDirection.INPUT, null),
                                new PinDefinition("element", "Element", PinType.ENTITY, PinDirection.OUTPUT, null)
                        ),
                        Map.of()),
                ctx -> {
                    List<?> list = ctx.readInput("list", List.class);
                    Object element = null;
                    if (list != null && !list.isEmpty()) {
                        element = list.getFirst();
                    }
                    return ExecutionResult.data(Map.of("element", element != null ? element : 0));
                }
        );
    }
}
