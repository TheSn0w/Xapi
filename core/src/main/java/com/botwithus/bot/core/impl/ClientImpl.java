package com.botwithus.bot.core.impl;

import com.botwithus.bot.api.Client;
import com.botwithus.bot.api.GameAPI;
import com.botwithus.bot.api.event.EventBus;

import java.util.function.BooleanSupplier;

public class ClientImpl implements Client {

    private final String name;
    private final GameAPI gameAPI;
    private final EventBus eventBus;
    private final BooleanSupplier connectedCheck;

    public ClientImpl(String name, GameAPI gameAPI, EventBus eventBus, BooleanSupplier connectedCheck) {
        this.name = name;
        this.gameAPI = gameAPI;
        this.eventBus = eventBus;
        this.connectedCheck = connectedCheck;
    }

    @Override
    public String getName() { return name; }

    @Override
    public GameAPI getGameAPI() { return gameAPI; }

    @Override
    public EventBus getEventBus() { return eventBus; }

    @Override
    public boolean isConnected() { return connectedCheck.getAsBoolean(); }
}
