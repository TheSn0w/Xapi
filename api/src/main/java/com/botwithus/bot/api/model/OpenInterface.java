package com.botwithus.bot.api.model;

/**
 * Represents a currently open interface in the game client.
 *
 * @param parentHash  the packed hash of the parent component hosting this interface
 * @param interfaceId the interface ID
 * @see com.botwithus.bot.api.GameAPI#getOpenInterfaces
 */
public record OpenInterface(int parentHash, int interfaceId) {}
