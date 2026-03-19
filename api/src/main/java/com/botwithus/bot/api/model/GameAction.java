package com.botwithus.bot.api.model;

/**
 * A game action to be queued for execution via the pipe RPC.
 *
 * <p>Actions represent interactions such as clicking entities, using items, or
 * interacting with interface components. The meaning of the parameters depends on
 * the {@code actionId}.</p>
 *
 * @param actionId the action type ID (see {@link com.botwithus.bot.api.inventory.ActionTypes})
 * @param param1   first action parameter (often option index or sub-component)
 * @param param2   second action parameter (context-dependent)
 * @param param3   third action parameter (often a packed component hash)
 * @see com.botwithus.bot.api.GameAPI#queueAction
 */
public record GameAction(int actionId, int param1, int param2, int param3) {}
