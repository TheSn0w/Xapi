package com.botwithus.bot.core.runtime;

import com.botwithus.bot.api.ScriptManifest;
import com.botwithus.bot.api.config.ConfigField;
import com.botwithus.bot.api.config.ScriptConfig;
import com.botwithus.bot.api.script.ManagementScript;

import java.util.List;

/**
 * Runs a single {@link ManagementScript} on its own virtual thread.
 * Uses {@link com.botwithus.bot.api.script.ManagementContext} instead of
 * {@link com.botwithus.bot.api.ScriptContext}.
 */
public class ManagementScriptRunner extends AbstractScriptRunner<ManagementScript> {

    private final com.botwithus.bot.api.script.ManagementContext context;

    public ManagementScriptRunner(ManagementScript script,
                                  com.botwithus.bot.api.script.ManagementContext context) {
        super(script);
        this.context = context;
    }

    public void start() {
        doStart("mgmt-script-");
    }

    @Override
    public String getScriptName() {
        ScriptManifest manifest = getManifest();
        return manifest != null ? manifest.name() : script.getClass().getSimpleName();
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
        try {
            runLoop(
                    () -> script.onStart(context),
                    script::onLoop,
                    script::onStop,
                    null // no profiler for management scripts
            );
        } finally {
            signalStopped();
        }
    }
}
