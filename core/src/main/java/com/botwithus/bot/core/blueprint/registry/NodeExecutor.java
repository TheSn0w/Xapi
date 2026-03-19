package com.botwithus.bot.core.blueprint.registry;

/**
 * Functional interface for executing a blueprint node.
 * Each node type registers an executor that processes inputs and produces outputs.
 */
@FunctionalInterface
public interface NodeExecutor {

    /**
     * Executes the node logic within the given context.
     *
     * @param ctx the execution context providing input/output access and game API
     * @return the execution result containing outputs and optional next exec pin
     */
    ExecutionResult execute(ExecutionContext ctx);
}
