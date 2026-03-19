package com.botwithus.bot.api.blueprint;

/**
 * A connection between two pins in a blueprint graph.
 *
 * @param id          unique link identifier
 * @param sourcePinId the output pin ID (nodeId * 1000 + pinIndex)
 * @param targetPinId the input pin ID (nodeId * 1000 + pinIndex)
 */
public record Link(long id, long sourcePinId, long targetPinId) {}
