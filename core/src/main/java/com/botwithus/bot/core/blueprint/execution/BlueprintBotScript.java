package com.botwithus.bot.core.blueprint.execution;

import com.botwithus.bot.api.BotScript;
import com.botwithus.bot.api.ScriptContext;
import com.botwithus.bot.api.blueprint.BlueprintGraph;
import com.botwithus.bot.api.blueprint.BlueprintMetadata;
import com.botwithus.bot.api.blueprint.PinType;
import com.botwithus.bot.core.blueprint.registry.*;

/**
 * A {@link BotScript} implementation that executes a blueprint graph.
 * Bridges the visual scripting system with the standard script lifecycle.
 */
public class BlueprintBotScript implements BotScript {

    private final BlueprintGraph graph;
    private BlueprintInterpreter interpreter;

    /**
     * Creates a new blueprint bot script from a graph.
     *
     * @param graph the blueprint graph to execute
     */
    public BlueprintBotScript(BlueprintGraph graph) {
        this.graph = graph;
    }

    /**
     * Returns the blueprint metadata (name, version, author, description).
     *
     * @return the blueprint metadata
     */
    public BlueprintMetadata getMetadata() {
        return graph.getMetadata();
    }

    /**
     * Returns the underlying blueprint graph.
     *
     * @return the blueprint graph
     */
    public BlueprintGraph getGraph() {
        return graph;
    }

    @Override
    public void onStart(ScriptContext ctx) {
        NodeRegistry registry = new NodeRegistry();
        FlowControlNodes.registerAll(registry);
        DataNodes.registerAll(registry);
        LogicNodes.registerAll(registry);
        DebugNodes.registerAll(registry);
        GameApiNodes.registerAll(registry);

        interpreter = new BlueprintInterpreter(graph, registry, ctx.getGameAPI());

        // Initialize variables with type-appropriate defaults
        for (var entry : graph.getVariables().entrySet()) {
            interpreter.setVariable(entry.getKey(), getDefaultForType(entry.getValue()));
        }

        interpreter.executeFrom("flow.onStart");
    }

    @Override
    public int onLoop() {
        if (interpreter == null) {
            return -1;
        }
        interpreter.clearFrameCache();
        return interpreter.executeFrom("flow.onLoop");
    }

    @Override
    public void onStop() {
        if (interpreter != null) {
            interpreter.executeFrom("flow.onStop");
        }
    }

    /**
     * Returns the default value for a given pin type.
     *
     * @param type the pin type
     * @return the default value
     */
    private Object getDefaultForType(PinType type) {
        return switch (type) {
            case BOOLEAN -> false;
            case INT -> 0;
            case FLOAT -> 0.0f;
            case STRING -> "";
            default -> null;
        };
    }
}
