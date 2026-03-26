package com.botwithus.bot.api.entities;

import com.botwithus.bot.api.GameAPI;
import com.botwithus.bot.api.inventory.ActionTypes;
import com.botwithus.bot.api.model.Entity;
import com.botwithus.bot.api.model.EntityPosition;
import com.botwithus.bot.api.model.GameAction;
import com.botwithus.bot.api.model.LocationType;

import java.util.List;
import java.util.Map;

/**
 * Rich wrapper for a scene object (location) entity with convenient access
 * to the location definition and interaction helpers.
 *
 * <p>Obtain instances through {@link SceneObjects}:</p>
 * <pre>{@code
 * SceneObjects objects = new SceneObjects(api);
 * SceneObject booth = objects.nearest("Bank booth");
 * if (booth != null) {
 *     System.out.println(booth.name() + " options: " + booth.getOptions());
 *     System.out.println("Size: " + booth.sizeX() + "x" + booth.sizeY());
 * }
 * }</pre>
 *
 * @see SceneObjects
 * @see EntityContext
 */
public class SceneObject extends EntityContext {

    private LocationType cachedType;

    public SceneObject(GameAPI api, Entity raw) {
        super(api, raw);
    }

    /**
     * Returns the location cache definition, fetched lazily.
     */
    public LocationType getType() {
        if (cachedType == null) {
            cachedType = api.getLocationType(typeId());
        }
        return cachedType;
    }

    /** The right-click interaction options for this object (resolves transforms). */
    public List<String> getOptions() {
        List<String> opts = getType().options();
        // If base type has no usable options, try the resolved transform
        if (canTransform() && (opts == null || opts.stream().allMatch(s -> s == null || s.isEmpty()))) {
            LocationType resolved = resolveTransform();
            if (resolved != null && resolved.options() != null) {
                return resolved.options();
            }
        }
        return opts;
    }

    /**
     * Returns the raw (untransformed) options from the base type definition.
     * Use {@link #getOptions()} for the resolved options including transforms.
     */
    public List<String> getRawOptions() {
        return getType().options();
    }

    /** Whether this object has a specific right-click option (case-insensitive, resolves transforms). */
    public boolean hasOption(String option) {
        return containsOption(getOptions(), option);
    }

    /** X dimension of this object in tiles. */
    public int sizeX() {
        return getType().sizeX();
    }

    /** Y dimension of this object in tiles. */
    public int sizeY() {
        return getType().sizeY();
    }

    /** The interaction type from the definition. */
    public int getInteractType() {
        return getType().interactType();
    }

    /** The collision/solid type from the definition. */
    public int getSolidType() {
        return getType().solidType();
    }

    /** Whether this is a members-only object. */
    public boolean isMembers() {
        return getType().members();
    }

    /** The minimap sprite ID, or 0 if none. */
    public int getMapSpriteId() {
        return getType().mapSpriteId();
    }

    /** Additional parameters from the object definition. */
    public Map<String, Object> getParams() {
        return getType().params();
    }

    /**
     * Whether this object can transform based on a varbit/varp.
     */
    public boolean canTransform() {
        return getType().varbitId() != -1 || getType().varpId() != -1;
    }

    /** The varbit controlling transformation, or -1. */
    public int getVarbitId() {
        return getType().varbitId();
    }

    /** The varp controlling transformation, or -1. */
    public int getVarpId() {
        return getType().varpId();
    }

    /** The possible type IDs this object can transform into. */
    public List<Integer> getTransforms() {
        return getType().transforms();
    }

    /**
     * Resolves the current transform of this object based on varbit/varp state.
     * Returns this object's type ID if no transformation is active,
     * or the transformed type ID.
     */
    public int resolveTransformId() {
        LocationType type = getType();
        int value = -1;
        if (type.varbitId() != -1) {
            value = api.getVarbit(type.varbitId());
        } else if (type.varpId() != -1) {
            value = api.getVarp(type.varpId());
        }
        if (value < 0 || type.transforms().isEmpty()) {
            return typeId();
        }
        if (value < type.transforms().size()) {
            int transformed = type.transforms().get(value);
            return transformed != -1 ? transformed : typeId();
        }
        // Last entry is the default fallback
        int last = type.transforms().getLast();
        return last != -1 ? last : typeId();
    }

    /**
     * Returns the resolved {@link LocationType} after applying any varbit/varp transformation.
     */
    public LocationType resolveTransform() {
        int id = resolveTransformId();
        return id == typeId() ? getType() : api.getLocationType(id);
    }

    // ========================== Interaction ==========================

    /**
     * Interacts with this scene object using the given right-click option name.
     * Resolves varbit/varp transforms if the base type has empty options.
     * <p>
     * If the cache definition has no populated options (all empty/null — common for
     * RS3 objects like trees and bank counters), falls back to option index 1
     * (the default left-click action).
     *
     * @param option the option text (e.g. "Open", "Bank", "Mine"), case-insensitive
     * @return {@code true} if the option was found (or fallback used) and the action was queued
     */
    public boolean interact(String option) {
        List<String> opts = getOptions();
        int index = findOptionIndex(opts, option);
        if (index != -1) {
            interact(index);
            return true;
        }
        // Fallback: if all options are empty/null, use option 1 (default left-click)
        if (opts != null && opts.stream().allMatch(s -> s == null || s.isEmpty())) {
            interact(1);
            return true;
        }
        return false;
    }

    /**
     * Interacts with this scene object using the given 1-based option index.
     *
     * @param optionIndex the 1-based option index (1–6)
     */
    public void interact(int optionIndex) {
        if (optionIndex < 1 || optionIndex >= ActionTypes.OBJECT_OPTIONS.length) {
            throw new IllegalArgumentException("Object option index out of range: " + optionIndex);
        }
        api.queueAction(new GameAction(ActionTypes.OBJECT_OPTIONS[optionIndex], raw.typeId(), raw.tileX(), raw.tileY()));
    }

    @Override
    public String toString() {
        return "SceneObject{" + name() + " id=" + typeId()
                + " @" + tileX() + "," + tileY() + "," + plane() + "}";
    }
}
