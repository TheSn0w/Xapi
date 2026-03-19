package com.botwithus.bot.core.blueprint.registry;

import java.util.Map;

/**
 * Result of executing a blueprint node.
 *
 * @param outputs     the output values keyed by pin ID
 * @param nextExecPin the ID of the next exec output pin to follow, or {@code null} for data-only nodes
 */
public record ExecutionResult(Map<String, Object> outputs, String nextExecPin) {

    /**
     * Creates a data-only result with no execution flow.
     *
     * @param outputs the output values
     * @return a result with outputs and no next exec pin
     */
    public static ExecutionResult data(Map<String, Object> outputs) {
        return new ExecutionResult(outputs, null);
    }

    /**
     * Creates a flow result with no data outputs.
     *
     * @param nextExecPin the next exec pin to follow
     * @return a result with the specified flow pin and no outputs
     */
    public static ExecutionResult flow(String nextExecPin) {
        return new ExecutionResult(Map.of(), nextExecPin);
    }

    /**
     * Creates a flow result with data outputs.
     *
     * @param nextExecPin the next exec pin to follow
     * @param outputs     the output values
     * @return a result with both flow and data
     */
    public static ExecutionResult flow(String nextExecPin, Map<String, Object> outputs) {
        return new ExecutionResult(outputs, nextExecPin);
    }

    /**
     * Creates an empty result with no outputs and no flow.
     *
     * @return an empty execution result
     */
    public static ExecutionResult empty() {
        return new ExecutionResult(Map.of(), null);
    }
}
