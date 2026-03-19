package com.botwithus.bot.core.blueprint.registry;

import com.botwithus.bot.api.blueprint.NodeDefinition;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Registry of available node types and their executors.
 * Node registration modules add their definitions and executors here.
 */
public class NodeRegistry {

    private final Map<String, NodeDefinition> definitions = new LinkedHashMap<>();
    private final Map<String, NodeExecutor> executors = new LinkedHashMap<>();

    /**
     * Registers a node definition and its executor.
     *
     * @param def      the node definition
     * @param executor the executor that implements the node's logic
     */
    public void register(NodeDefinition def, NodeExecutor executor) {
        definitions.put(def.typeId(), def);
        executors.put(def.typeId(), executor);
    }

    /**
     * Returns the node definition for a given type ID.
     *
     * @param typeId the node type ID
     * @return the definition, or {@code null} if not registered
     */
    public NodeDefinition getDefinition(String typeId) {
        return definitions.get(typeId);
    }

    /**
     * Returns the executor for a given node type ID.
     *
     * @param typeId the node type ID
     * @return the executor, or {@code null} if not registered
     */
    public NodeExecutor getExecutor(String typeId) {
        return executors.get(typeId);
    }

    /**
     * Returns all registered node definitions.
     *
     * @return an unmodifiable collection of all definitions
     */
    public Collection<NodeDefinition> getAllDefinitions() {
        return Collections.unmodifiableCollection(definitions.values());
    }

    /**
     * Returns all definitions grouped by category.
     *
     * @return a map of category name to list of definitions
     */
    public Map<String, List<NodeDefinition>> getCategories() {
        return definitions.values().stream()
                .collect(Collectors.groupingBy(
                        NodeDefinition::category,
                        LinkedHashMap::new,
                        Collectors.toList()
                ));
    }
}
