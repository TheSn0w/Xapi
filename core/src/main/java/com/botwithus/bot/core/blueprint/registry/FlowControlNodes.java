package com.botwithus.bot.core.blueprint.registry;

import com.botwithus.bot.api.blueprint.NodeDefinition;
import com.botwithus.bot.api.blueprint.NodeDefinition.PropertyDef;
import com.botwithus.bot.api.blueprint.PinDefinition;
import com.botwithus.bot.api.blueprint.PinDirection;
import com.botwithus.bot.api.blueprint.PinType;

import java.util.List;
import java.util.Map;

/**
 * Registers flow control nodes: entry points, branching, loops, sequences, and delays.
 */
public final class FlowControlNodes {

    private FlowControlNodes() {}

    /**
     * Registers all flow control nodes into the given registry.
     *
     * @param registry the node registry
     */
    public static void registerAll(NodeRegistry registry) {
        registerOnStart(registry);
        registerOnLoop(registry);
        registerOnStop(registry);
        registerBranch(registry);
        registerForEach(registry);
        registerWhile(registry);
        registerDelay(registry);
        registerSequence(registry);
    }

    private static void registerOnStart(NodeRegistry registry) {
        NodeDefinition def = new NodeDefinition(
                "flow.onStart",
                "On Start",
                "Flow Control",
                List.of(
                        new PinDefinition("exec_out", "Exec", PinType.EXEC, PinDirection.OUTPUT, null)
                ),
                Map.of()
        );
        registry.register(def, ctx -> ExecutionResult.flow("exec_out"));
    }

    private static void registerOnLoop(NodeRegistry registry) {
        NodeDefinition def = new NodeDefinition(
                "flow.onLoop",
                "On Loop",
                "Flow Control",
                List.of(
                        new PinDefinition("exec_out", "Exec", PinType.EXEC, PinDirection.OUTPUT, null),
                        new PinDefinition("delay", "Delay (ms)", PinType.INT, PinDirection.OUTPUT, null)
                ),
                Map.of()
        );
        registry.register(def, ctx -> {
            int delay = ctx.readInput("delay", Integer.class, 600);
            return ExecutionResult.flow("exec_out", Map.of("delay", delay));
        });
    }

    private static void registerOnStop(NodeRegistry registry) {
        NodeDefinition def = new NodeDefinition(
                "flow.onStop",
                "On Stop",
                "Flow Control",
                List.of(
                        new PinDefinition("exec_out", "Exec", PinType.EXEC, PinDirection.OUTPUT, null)
                ),
                Map.of()
        );
        registry.register(def, ctx -> ExecutionResult.flow("exec_out"));
    }

    private static void registerBranch(NodeRegistry registry) {
        NodeDefinition def = new NodeDefinition(
                "flow.branch",
                "Branch",
                "Flow Control",
                List.of(
                        new PinDefinition("exec_in", "Exec", PinType.EXEC, PinDirection.INPUT, null),
                        new PinDefinition("condition", "Condition", PinType.BOOLEAN, PinDirection.INPUT, false),
                        new PinDefinition("true", "True", PinType.EXEC, PinDirection.OUTPUT, null),
                        new PinDefinition("false", "False", PinType.EXEC, PinDirection.OUTPUT, null)
                ),
                Map.of()
        );
        registry.register(def, ctx -> {
            Boolean condition = ctx.readInput("condition", Boolean.class, false);
            return ExecutionResult.flow(condition ? "true" : "false");
        });
    }

    private static void registerForEach(NodeRegistry registry) {
        NodeDefinition def = new NodeDefinition(
                "flow.forEach",
                "For Each",
                "Flow Control",
                List.of(
                        new PinDefinition("exec_in", "Exec", PinType.EXEC, PinDirection.INPUT, null),
                        new PinDefinition("list", "List", PinType.ENTITY_LIST, PinDirection.INPUT, null),
                        new PinDefinition("loop_body", "Loop Body", PinType.EXEC, PinDirection.OUTPUT, null),
                        new PinDefinition("completed", "Completed", PinType.EXEC, PinDirection.OUTPUT, null),
                        new PinDefinition("element", "Element", PinType.ENTITY, PinDirection.OUTPUT, null),
                        new PinDefinition("index", "Index", PinType.INT, PinDirection.OUTPUT, null)
                ),
                Map.of()
        );
        registry.register(def, ctx -> {
            List<?> list = ctx.readInput("list", List.class);
            if (list == null || list.isEmpty()) {
                return ExecutionResult.flow("completed");
            }
            ExecutionContext.LoopBodyExecutor loopExecutor = ctx.getLoopBodyExecutor();
            if (loopExecutor != null) {
                for (int i = 0; i < list.size(); i++) {
                    ctx.writeOutput("element", list.get(i));
                    ctx.writeOutput("index", i);
                    loopExecutor.execute("loop_body");
                }
            }
            return ExecutionResult.flow("completed");
        });
    }

    private static void registerWhile(NodeRegistry registry) {
        NodeDefinition def = new NodeDefinition(
                "flow.while",
                "While",
                "Flow Control",
                List.of(
                        new PinDefinition("exec_in", "Exec", PinType.EXEC, PinDirection.INPUT, null),
                        new PinDefinition("condition", "Condition", PinType.BOOLEAN, PinDirection.INPUT, false),
                        new PinDefinition("loop_body", "Loop Body", PinType.EXEC, PinDirection.OUTPUT, null),
                        new PinDefinition("completed", "Completed", PinType.EXEC, PinDirection.OUTPUT, null)
                ),
                Map.of()
        );
        registry.register(def, ctx -> {
            ExecutionContext.LoopBodyExecutor loopExecutor = ctx.getLoopBodyExecutor();
            int maxIterations = 10000;
            int iteration = 0;
            while (iteration < maxIterations) {
                Boolean condition = ctx.readInput("condition", Boolean.class, false);
                if (!condition) break;
                if (loopExecutor != null) {
                    loopExecutor.execute("loop_body");
                }
                iteration++;
            }
            return ExecutionResult.flow("completed");
        });
    }

    private static void registerDelay(NodeRegistry registry) {
        NodeDefinition def = new NodeDefinition(
                "flow.delay",
                "Delay",
                "Flow Control",
                List.of(
                        new PinDefinition("exec_in", "Exec", PinType.EXEC, PinDirection.INPUT, null),
                        new PinDefinition("milliseconds", "Milliseconds", PinType.INT, PinDirection.INPUT, 600),
                        new PinDefinition("exec_out", "Exec", PinType.EXEC, PinDirection.OUTPUT, null)
                ),
                Map.of()
        );
        registry.register(def, ctx -> {
            int ms = ctx.readInput("milliseconds", Integer.class, 600);
            return ExecutionResult.flow("exec_out", Map.of("delay", ms));
        });
    }

    private static void registerSequence(NodeRegistry registry) {
        NodeDefinition def = new NodeDefinition(
                "flow.sequence",
                "Sequence",
                "Flow Control",
                List.of(
                        new PinDefinition("exec_in", "Exec", PinType.EXEC, PinDirection.INPUT, null),
                        new PinDefinition("then_0", "Then 0", PinType.EXEC, PinDirection.OUTPUT, null),
                        new PinDefinition("then_1", "Then 1", PinType.EXEC, PinDirection.OUTPUT, null),
                        new PinDefinition("then_2", "Then 2", PinType.EXEC, PinDirection.OUTPUT, null),
                        new PinDefinition("then_3", "Then 3", PinType.EXEC, PinDirection.OUTPUT, null)
                ),
                Map.of()
        );
        registry.register(def, ctx -> {
            ExecutionContext.LoopBodyExecutor loopExecutor = ctx.getLoopBodyExecutor();
            if (loopExecutor != null) {
                loopExecutor.execute("then_0");
                loopExecutor.execute("then_1");
                loopExecutor.execute("then_2");
                loopExecutor.execute("then_3");
            }
            return ExecutionResult.empty();
        });
    }
}
