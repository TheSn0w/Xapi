package com.botwithus.bot.api.blueprint;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * A mutable instance of a node placed in a blueprint graph.
 */
public class NodeInstance {

    private long id;
    private String typeId;
    private float x;
    private float y;
    private final Map<String, Object> propertyValues = new LinkedHashMap<>();

    public NodeInstance(long id, String typeId, float x, float y) {
        this.id = id;
        this.typeId = typeId;
        this.x = x;
        this.y = y;
    }

    public long getId() { return id; }
    public void setId(long id) { this.id = id; }

    public String getTypeId() { return typeId; }
    public void setTypeId(String typeId) { this.typeId = typeId; }

    public float getX() { return x; }
    public void setX(float x) { this.x = x; }

    public float getY() { return y; }
    public void setY(float y) { this.y = y; }

    public Map<String, Object> getPropertyValues() { return propertyValues; }

    public Object getProperty(String key) { return propertyValues.get(key); }
    public void setProperty(String key, Object value) { propertyValues.put(key, value); }

    /**
     * Computes the pin ID for a given pin index on this node.
     * Pin ID = nodeId * 1000 + pinIndex
     */
    public long pinId(int pinIndex) {
        return id * 1000 + pinIndex;
    }
}
