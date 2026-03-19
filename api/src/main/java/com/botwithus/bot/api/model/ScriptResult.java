package com.botwithus.bot.api.model;

import java.util.List;

/**
 * The result of executing a client script via the pipe RPC.
 *
 * <p>The return values are typed based on the {@code returns} descriptors
 * passed to {@link com.botwithus.bot.api.GameAPI#executeScript}.</p>
 *
 * @param returns the list of return values from the script execution
 * @see com.botwithus.bot.api.GameAPI#executeScript
 */
public record ScriptResult(List<Object> returns) {}
