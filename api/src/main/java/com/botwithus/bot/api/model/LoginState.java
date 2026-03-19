package com.botwithus.bot.api.model;

/**
 * The current login state of the game client.
 *
 * @param state         the primary login state value
 * @param loginProgress the login progress indicator (e.g., loading percentage)
 * @param loginStatus   the login status code (e.g., success, invalid credentials)
 * @see com.botwithus.bot.api.GameAPI#getLoginState
 */
public record LoginState(int state, int loginProgress, int loginStatus) {}
