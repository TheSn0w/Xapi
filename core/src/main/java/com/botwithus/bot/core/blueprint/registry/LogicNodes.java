package com.botwithus.bot.core.blueprint.registry;

import com.botwithus.bot.api.blueprint.NodeDefinition;
import com.botwithus.bot.api.blueprint.NodeDefinition.PropertyDef;
import com.botwithus.bot.api.blueprint.PinDefinition;
import com.botwithus.bot.api.blueprint.PinDirection;
import com.botwithus.bot.api.blueprint.PinType;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Registers logic nodes: comparison, boolean operators, and switch.
 */
public final class LogicNodes {

    private LogicNodes() {}

    /**
     * Registers all logic nodes into the given registry.
     *
     * @param registry the node registry
     */
    public static void registerAll(NodeRegistry registry) {
        registerCompare(registry);
        registerAnd(registry);
        registerOr(registry);
        registerNot(registry);
        registerSwitch(registry);
    }

    private static void registerCompare(NodeRegistry registry) {
        registry.register(
                new NodeDefinition("logic.compare", "Compare", "Logic",
                        List.of(
                                new PinDefinition("a", "A", PinType.ANY, PinDirection.INPUT, null),
                                new PinDefinition("b", "B", PinType.ANY, PinDirection.INPUT, null),
                                new PinDefinition("result", "Result", PinType.BOOLEAN, PinDirection.OUTPUT, null)
                        ),
                        Map.of("operator", new PropertyDef("Operator", PinType.STRING, "==",
                                new String[]{"==", "!=", "<", ">", "<=", ">="}))),
                ctx -> {
                    Object a = ctx.readInputRaw("a");
                    Object b = ctx.readInputRaw("b");
                    String operator = ctx.getPropertyString("operator", "==");
                    boolean result = compareValues(a, b, operator);
                    return ExecutionResult.data(Map.of("result", result));
                }
        );
    }

    @SuppressWarnings("unchecked")
    private static boolean compareValues(Object a, Object b, String operator) {
        if (a == null && b == null) return "==".equals(operator);
        if (a == null || b == null) return "!=".equals(operator);

        return switch (operator) {
            case "==" -> a.equals(b) || (a instanceof Number na && b instanceof Number nb
                    && Double.compare(na.doubleValue(), nb.doubleValue()) == 0);
            case "!=" -> !a.equals(b) && !(a instanceof Number na && b instanceof Number nb
                    && Double.compare(na.doubleValue(), nb.doubleValue()) == 0);
            case "<" -> {
                if (a instanceof Number na && b instanceof Number nb)
                    yield Double.compare(na.doubleValue(), nb.doubleValue()) < 0;
                if (a instanceof Comparable ca && b instanceof Comparable cb) {
                    try { yield ca.compareTo(cb) < 0; } catch (ClassCastException e) { yield false; }
                }
                yield false;
            }
            case ">" -> {
                if (a instanceof Number na && b instanceof Number nb)
                    yield Double.compare(na.doubleValue(), nb.doubleValue()) > 0;
                if (a instanceof Comparable ca && b instanceof Comparable cb) {
                    try { yield ca.compareTo(cb) > 0; } catch (ClassCastException e) { yield false; }
                }
                yield false;
            }
            case "<=" -> {
                if (a instanceof Number na && b instanceof Number nb)
                    yield Double.compare(na.doubleValue(), nb.doubleValue()) <= 0;
                if (a instanceof Comparable ca && b instanceof Comparable cb) {
                    try { yield ca.compareTo(cb) <= 0; } catch (ClassCastException e) { yield false; }
                }
                yield false;
            }
            case ">=" -> {
                if (a instanceof Number na && b instanceof Number nb)
                    yield Double.compare(na.doubleValue(), nb.doubleValue()) >= 0;
                if (a instanceof Comparable ca && b instanceof Comparable cb) {
                    try { yield ca.compareTo(cb) >= 0; } catch (ClassCastException e) { yield false; }
                }
                yield false;
            }
            default -> false;
        };
    }

    private static void registerAnd(NodeRegistry registry) {
        registry.register(
                new NodeDefinition("logic.and", "And", "Logic",
                        List.of(
                                new PinDefinition("a", "A", PinType.BOOLEAN, PinDirection.INPUT, false),
                                new PinDefinition("b", "B", PinType.BOOLEAN, PinDirection.INPUT, false),
                                new PinDefinition("result", "Result", PinType.BOOLEAN, PinDirection.OUTPUT, null)
                        ),
                        Map.of()),
                ctx -> {
                    boolean a = ctx.readInput("a", Boolean.class, false);
                    boolean b = ctx.readInput("b", Boolean.class, false);
                    return ExecutionResult.data(Map.of("result", a && b));
                }
        );
    }

    private static void registerOr(NodeRegistry registry) {
        registry.register(
                new NodeDefinition("logic.or", "Or", "Logic",
                        List.of(
                                new PinDefinition("a", "A", PinType.BOOLEAN, PinDirection.INPUT, false),
                                new PinDefinition("b", "B", PinType.BOOLEAN, PinDirection.INPUT, false),
                                new PinDefinition("result", "Result", PinType.BOOLEAN, PinDirection.OUTPUT, null)
                        ),
                        Map.of()),
                ctx -> {
                    boolean a = ctx.readInput("a", Boolean.class, false);
                    boolean b = ctx.readInput("b", Boolean.class, false);
                    return ExecutionResult.data(Map.of("result", a || b));
                }
        );
    }

    private static void registerNot(NodeRegistry registry) {
        registry.register(
                new NodeDefinition("logic.not", "Not", "Logic",
                        List.of(
                                new PinDefinition("value", "Value", PinType.BOOLEAN, PinDirection.INPUT, false),
                                new PinDefinition("result", "Result", PinType.BOOLEAN, PinDirection.OUTPUT, null)
                        ),
                        Map.of()),
                ctx -> {
                    boolean value = ctx.readInput("value", Boolean.class, false);
                    return ExecutionResult.data(Map.of("result", !value));
                }
        );
    }

    private static void registerSwitch(NodeRegistry registry) {
        List<PinDefinition> pins = new ArrayList<>();
        pins.add(new PinDefinition("exec_in", "Exec", PinType.EXEC, PinDirection.INPUT, null));
        pins.add(new PinDefinition("value", "Value", PinType.INT, PinDirection.INPUT, 0));
        pins.add(new PinDefinition("default", "Default", PinType.EXEC, PinDirection.OUTPUT, null));

        registry.register(
                new NodeDefinition("logic.switch", "Switch", "Logic",
                        pins,
                        Map.of("cases", new PropertyDef("Cases", PinType.STRING, "0,1,2", null))),
                ctx -> {
                    int value = ctx.readInput("value", Integer.class, 0);
                    String casesStr = ctx.getPropertyString("cases", "0,1,2");
                    String[] cases = casesStr.split(",");
                    for (String c : cases) {
                        String trimmed = c.trim();
                        try {
                            if (Integer.parseInt(trimmed) == value) {
                                return ExecutionResult.flow("case_" + trimmed);
                            }
                        } catch (NumberFormatException ignored) {
                        }
                    }
                    return ExecutionResult.flow("default");
                }
        );
    }
}
