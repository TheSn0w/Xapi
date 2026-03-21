package com.botwithus.bot.core.impl;

import com.botwithus.bot.api.ClientProvider;
import com.botwithus.bot.api.GameAPI;
import com.botwithus.bot.api.Navigation;
import com.botwithus.bot.api.ScriptContext;
import com.botwithus.bot.api.antiban.Pace;
import com.botwithus.bot.api.event.EventBus;
import com.botwithus.bot.api.isc.MessageBus;
import com.botwithus.bot.api.isc.SharedState;
import com.botwithus.bot.api.script.ScriptManager;

public class ScriptContextImpl implements ScriptContext {

    private final GameAPI gameAPI;
    private final EventBus eventBus;
    private final MessageBus messageBus;
    private final ClientProvider clientProvider;
    private final SharedState sharedState;
    private final Navigation navigation;
    private final Pace pace;
    private final String connectionName;
    private volatile ScriptManager scriptManager;

    public ScriptContextImpl(GameAPI gameAPI, EventBus eventBus, MessageBus messageBus, ClientProvider clientProvider, SharedState sharedState, String connectionName) {
        this.gameAPI = gameAPI;
        this.eventBus = eventBus;
        this.messageBus = messageBus;
        this.clientProvider = clientProvider;
        this.sharedState = sharedState;
        this.connectionName = connectionName;
        if (eventBus instanceof EventBusImpl eventBusImpl) {
            this.navigation = new Walker(gameAPI, eventBusImpl);
        } else {
            throw new IllegalArgumentException("EventBus must be EventBusImpl for Walker support");
        }
        this.pace = new Pace();
    }

    public ScriptContextImpl(GameAPI gameAPI, EventBus eventBus, MessageBus messageBus, ClientProvider clientProvider, SharedState sharedState) {
        this(gameAPI, eventBus, messageBus, clientProvider, sharedState, null);
    }

    public ScriptContextImpl(GameAPI gameAPI, EventBus eventBus, MessageBus messageBus, ClientProvider clientProvider) {
        this(gameAPI, eventBus, messageBus, clientProvider, new SharedStateImpl(), null);
    }

    /**
     * Sets the script manager. Called after the runtime is created,
     * since ScriptManager needs ScriptRuntime which needs ScriptContext.
     */
    public void setScriptManager(ScriptManager scriptManager) {
        this.scriptManager = scriptManager;
    }

    @Override
    public GameAPI getGameAPI() { return gameAPI; }

    @Override
    public EventBus getEventBus() { return eventBus; }

    @Override
    public MessageBus getMessageBus() { return messageBus; }

    @Override
    public ClientProvider getClientProvider() { return clientProvider; }

    @Override
    public SharedState getSharedState() { return sharedState; }

    @Override
    public ScriptManager getScriptManager() { return scriptManager; }

    @Override
    public Navigation getNavigation() { return navigation; }

    @Override
    public Pace getPace() { return pace; }

    @Override
    public String getConnectionName() { return connectionName; }
}
