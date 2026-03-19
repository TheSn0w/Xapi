package com.botwithus.bot.api.blueprint;

import com.botwithus.bot.api.model.*;

import java.util.List;

/**
 * Data types for blueprint node pins.
 */
public enum PinType {
    EXEC(Void.class),
    BOOLEAN(Boolean.class),
    INT(Integer.class),
    FLOAT(Float.class),
    STRING(String.class),
    ENTITY(Entity.class),
    ENTITY_LIST(List.class),
    ENTITY_INFO(EntityInfo.class),
    LOCAL_PLAYER(LocalPlayer.class),
    INVENTORY_ITEM(InventoryItem.class),
    INVENTORY_ITEM_LIST(List.class),
    PLAYER_STAT(PlayerStat.class),
    COMPONENT(Component.class),
    COMPONENT_LIST(List.class),
    GAME_ACTION(GameAction.class),
    ANY(Object.class);

    private final Class<?> javaType;

    PinType(Class<?> javaType) {
        this.javaType = javaType;
    }

    public Class<?> javaType() {
        return javaType;
    }

    /**
     * Checks if a link from this pin type to the target pin type is valid.
     * ANY matches all types; INT and FLOAT auto-convert between each other.
     */
    public boolean isCompatibleWith(PinType target) {
        if (this == target) return true;
        if (this == ANY || target == ANY) return true;
        if (this == INT && target == FLOAT) return true;
        if (this == FLOAT && target == INT) return true;
        return false;
    }
}
