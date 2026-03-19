package com.botwithus.bot.core.blueprint.registry;

import com.botwithus.bot.api.GameAPI;
import com.botwithus.bot.api.blueprint.NodeInstance;

import java.util.HashMap;
import java.util.Map;
import java.util.function.Function;

/**
 * Context provided to a {@link NodeExecutor} during node execution.
 * Provides access to inputs, outputs, blueprint variables, and the game API.
 */
public class ExecutionContext {

    private final GameAPI api;
    private final NodeInstance node;
    private final Function<String, Object> inputResolver;
    private final Map<String, Object> outputs = new HashMap<>();
    private final Map<String, Object> variables;
    private LoopBodyExecutor loopBodyExecutor;

    /**
     * Functional interface for executing loop body exec chains.
     * The interpreter provides this so loop nodes can execute their body.
     */
    @FunctionalInterface
    public interface LoopBodyExecutor {
        /**
         * Executes the exec chain starting from the given exec pin on the current node.
         *
         * @param execPinId the exec output pin ID to start from
         */
        void execute(String execPinId);
    }

    /**
     * Creates a new execution context.
     *
     * @param api           the game API instance
     * @param node          the node instance being executed
     * @param inputResolver function that resolves input pin values by pin ID
     * @param variables     the blueprint-level variable store
     */
    public ExecutionContext(GameAPI api, NodeInstance node, Function<String, Object> inputResolver,
                           Map<String, Object> variables) {
        this.api = api;
        this.node = node;
        this.inputResolver = inputResolver;
        this.variables = variables;
    }

    /**
     * Returns the game API instance.
     *
     * @return the game API
     */
    public GameAPI getApi() {
        return api;
    }

    /**
     * Returns the node instance being executed.
     *
     * @return the current node instance
     */
    public NodeInstance getNode() {
        return node;
    }

    /**
     * Reads an input value from a connected pin, performing type coercion if needed.
     * If the pin is not connected, returns {@code null}.
     *
     * @param pinId the input pin ID
     * @param type  the expected type
     * @param <T>   the return type
     * @return the input value, or {@code null} if not connected
     */
    @SuppressWarnings("unchecked")
    public <T> T readInput(String pinId, Class<T> type) {
        Object value = inputResolver.apply(pinId);
        if (value == null) {
            return null;
        }

        // INT <-> FLOAT coercion
        if (type == Integer.class || type == int.class) {
            if (value instanceof Float f) {
                return (T) Integer.valueOf(f.intValue());
            }
            if (value instanceof Double d) {
                return (T) Integer.valueOf(d.intValue());
            }
            if (value instanceof Number n) {
                return (T) Integer.valueOf(n.intValue());
            }
        }
        if (type == Float.class || type == float.class) {
            if (value instanceof Integer i) {
                return (T) Float.valueOf(i.floatValue());
            }
            if (value instanceof Double d) {
                return (T) Float.valueOf(d.floatValue());
            }
            if (value instanceof Number n) {
                return (T) Float.valueOf(n.floatValue());
            }
        }
        if (type == Boolean.class || type == boolean.class) {
            if (value instanceof Boolean) {
                return (T) value;
            }
            if (value instanceof Number n) {
                return (T) Boolean.valueOf(n.intValue() != 0);
            }
        }
        if (type == String.class) {
            return (T) String.valueOf(value);
        }

        if (type.isInstance(value)) {
            return (T) value;
        }
        return (T) value;
    }

    /**
     * Reads an input value, returning a default if the input is null.
     *
     * @param pinId        the input pin ID
     * @param type         the expected type
     * @param defaultValue the default value if input is null
     * @param <T>          the return type
     * @return the input value or the default
     */
    public <T> T readInput(String pinId, Class<T> type, T defaultValue) {
        T value = readInput(pinId, type);
        return value != null ? value : defaultValue;
    }

    /**
     * Reads a raw input value without type coercion.
     *
     * @param pinId the input pin ID
     * @return the raw input value, or {@code null}
     */
    public Object readInputRaw(String pinId) {
        return inputResolver.apply(pinId);
    }

    /**
     * Writes an output value for the specified pin.
     *
     * @param pinId the output pin ID
     * @param value the value to write
     */
    public void writeOutput(String pinId, Object value) {
        outputs.put(pinId, value);
    }

    /**
     * Returns all output values written during this execution.
     *
     * @return the output map
     */
    public Map<String, Object> getOutputs() {
        return outputs;
    }

    /**
     * Returns a blueprint-level variable value.
     *
     * @param name the variable name
     * @return the variable value, or {@code null} if not set
     */
    public Object getVariable(String name) {
        return variables.get(name);
    }

    /**
     * Sets a blueprint-level variable value.
     *
     * @param name  the variable name
     * @param value the variable value
     */
    public void setVariable(String name, Object value) {
        variables.put(name, value);
    }

    /**
     * Returns the node property value, falling back to a default.
     *
     * @param key          the property key
     * @param defaultValue the default value if property is not set
     * @return the property value or default
     */
    public Object getProperty(String key, Object defaultValue) {
        Object value = node.getProperty(key);
        return value != null ? value : defaultValue;
    }

    /**
     * Returns the node property value as a string.
     *
     * @param key          the property key
     * @param defaultValue the default value if property is not set
     * @return the property value as string
     */
    public String getPropertyString(String key, String defaultValue) {
        Object value = node.getProperty(key);
        return value != null ? value.toString() : defaultValue;
    }

    /**
     * Returns the node property value as an integer.
     *
     * @param key          the property key
     * @param defaultValue the default value if property is not set
     * @return the property value as int
     */
    public int getPropertyInt(String key, int defaultValue) {
        Object value = node.getProperty(key);
        if (value instanceof Number n) return n.intValue();
        if (value instanceof String s) {
            try { return Integer.parseInt(s); } catch (NumberFormatException e) { return defaultValue; }
        }
        return defaultValue;
    }

    /**
     * Returns the node property value as a float.
     *
     * @param key          the property key
     * @param defaultValue the default value if property is not set
     * @return the property value as float
     */
    public float getPropertyFloat(String key, float defaultValue) {
        Object value = node.getProperty(key);
        if (value instanceof Number n) return n.floatValue();
        if (value instanceof String s) {
            try { return Float.parseFloat(s); } catch (NumberFormatException e) { return defaultValue; }
        }
        return defaultValue;
    }

    /**
     * Returns the node property value as a boolean.
     *
     * @param key          the property key
     * @param defaultValue the default value if property is not set
     * @return the property value as boolean
     */
    public boolean getPropertyBoolean(String key, boolean defaultValue) {
        Object value = node.getProperty(key);
        if (value instanceof Boolean b) return b;
        if (value instanceof String s) return Boolean.parseBoolean(s);
        return defaultValue;
    }

    /**
     * Sets the loop body executor for loop nodes to use.
     *
     * @param executor the loop body executor
     */
    public void setLoopBodyExecutor(LoopBodyExecutor executor) {
        this.loopBodyExecutor = executor;
    }

    /**
     * Returns the loop body executor.
     *
     * @return the loop body executor, or {@code null} if not set
     */
    public LoopBodyExecutor getLoopBodyExecutor() {
        return loopBodyExecutor;
    }
}
