package com.botwithus.bot.api.model;

/**
 * Type information for an interface component.
 *
 * @param type     the numeric component type ID
 * @param typeName the human-readable type name
 * @see com.botwithus.bot.api.GameAPI#getComponentType
 */
public record ComponentTypeInfo(int type, String typeName) {}
