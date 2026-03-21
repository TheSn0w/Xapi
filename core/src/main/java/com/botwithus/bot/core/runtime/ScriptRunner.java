package com.botwithus.bot.core.runtime;

import com.botwithus.bot.api.BotScript;
import com.botwithus.bot.api.ScriptContext;
import com.botwithus.bot.api.config.ConfigField;
import com.botwithus.bot.api.config.ScriptConfig;
import com.botwithus.bot.core.blueprint.execution.BlueprintBotScript;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

import java.util.List;

/**
 * Runs a single BotScript on its own virtual thread.
 * Lifecycle: onStart -> loop(onLoop + sleep) -> onStop
 */
public class ScriptRunner extends AbstractScriptRunner<BotScript> {

    private static final Logger log = LoggerFactory.getLogger(ScriptRunner.class);
    private final ScriptContext context;
    private String connectionName;
    private final ScriptProfiler profiler = new ScriptProfiler();

    public ScriptRunner(BotScript script, ScriptContext context) {
        super(script);
        this.context = context;
    }

    public ScriptProfiler getProfiler() {
        return profiler;
    }

    public void start() {
        doStart("script-");
    }

    @Override
    public String getScriptName() {
        if (script instanceof BlueprintBotScript bp) {
            return bp.getMetadata().name();
        }
        var manifest = getManifest();
        return manifest != null ? manifest.name() : script.getClass().getSimpleName();
    }

    public void setConnectionName(String connectionName) {
        this.connectionName = connectionName;
    }

    public String getConnectionName() {
        return connectionName;
    }

    @Override
    public List<ConfigField> getConfigFields() {
        return script.getConfigFields();
    }

    @Override
    protected void onConfigUpdate(ScriptConfig config) {
        script.onConfigUpdate(config);
    }

    @Override
    public void run() {
        if (connectionName != null) {
            ConnectionContext.set(connectionName);
            MDC.put("connection.name", connectionName);
        }
        try {
            runLoop(
                    () -> script.onStart(context),
                    script::onLoop,
                    script::onStop,
                    profiler::recordLoop
            );
            // Navigation cleanup (ScriptRunner-specific, after onStop)
            try {
                context.getNavigation().cleanup();
            } catch (Exception e) {
                log.debug("Navigation cleanup error in {}: {}", getScriptName(), e.getMessage());
            }
        } finally {
            ConnectionContext.clear();
            signalStopped();
        }
    }
}
