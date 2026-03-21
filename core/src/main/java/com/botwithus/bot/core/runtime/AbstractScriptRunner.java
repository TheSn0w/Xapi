package com.botwithus.bot.core.runtime;

import com.botwithus.bot.api.ScriptManifest;
import com.botwithus.bot.api.config.ConfigField;
import com.botwithus.bot.api.config.ScriptConfig;
import com.botwithus.bot.core.config.ScriptConfigStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Shared lifecycle logic for script runners.
 * Subclasses provide the script/context types and override {@link #run()}.
 *
 * @param <S> the script type (e.g. BotScript, ManagementScript)
 */
public abstract class AbstractScriptRunner<S> implements Runnable {

    private static final Logger log = LoggerFactory.getLogger(AbstractScriptRunner.class);

    protected final S script;
    protected final AtomicBoolean running = new AtomicBoolean(false);
    private final AtomicBoolean disposed = new AtomicBoolean(false);
    private final AtomicReference<ScriptConfig> currentConfig = new AtomicReference<>();
    private volatile CountDownLatch stopLatch;
    protected Thread thread;

    @FunctionalInterface
    public interface ErrorHandler {
        void onError(String scriptName, String phase, Throwable error);
    }

    protected ErrorHandler errorHandler;

    protected AbstractScriptRunner(S script) {
        this.script = script;
    }

    public void setErrorHandler(ErrorHandler errorHandler) {
        this.errorHandler = errorHandler;
    }

    /**
     * Starts the script on a new virtual thread.
     *
     * @param threadPrefix prefix for the virtual thread name (e.g. "script-", "mgmt-script-")
     */
    protected void doStart(String threadPrefix) {
        if (running.compareAndSet(false, true)) {
            stopLatch = new CountDownLatch(1);
            String name = getScriptName();
            this.thread = Thread.ofVirtual().name(threadPrefix + name).start(this);
        }
    }

    public void stop() {
        running.set(false);
        if (thread != null) {
            thread.interrupt();
        }
    }

    public void dispose() {
        disposed.set(true);
        stop();
    }

    public boolean isDisposed() {
        return disposed.get();
    }

    public boolean awaitStop(long timeoutMs) {
        CountDownLatch latch = this.stopLatch;
        if (latch == null) return true;
        try {
            return latch.await(timeoutMs, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    public boolean isRunning() {
        return running.get();
    }

    public S getScript() {
        return script;
    }

    public ScriptManifest getManifest() {
        return script.getClass().getAnnotation(ScriptManifest.class);
    }

    public abstract String getScriptName();

    public abstract List<ConfigField> getConfigFields();

    public ScriptConfig getCurrentConfig() {
        return currentConfig.get();
    }

    public void applyConfig(ScriptConfig config) {
        currentConfig.set(config);
        String name = getScriptName();
        Thread.startVirtualThread(() -> ScriptConfigStore.save(name, config));
        try {
            onConfigUpdate(config);
        } catch (Exception e) {
            log.error("Error in onConfigUpdate for {}: {}", getScriptName(), e.getMessage());
        }
    }

    /** Called by applyConfig — subclasses delegate to their script's onConfigUpdate. */
    protected abstract void onConfigUpdate(ScriptConfig config);

    protected void notifyError(String scriptName, String phase, Throwable error) {
        ErrorHandler handler = this.errorHandler;
        if (handler != null) {
            try {
                handler.onError(scriptName, phase, error);
            } catch (Exception e) {
                log.error("Error handler threw for {}/{}: {}", scriptName, phase, e.getMessage());
            }
        }
    }

    /**
     * Runs the main script loop: onStart → loop(onLoop + sleep) → onStop.
     * Subclasses call this from their {@link #run()} method after setting up
     * any thread-local context (MDC, ConnectionContext, etc.).
     * <p>
     * The stop latch is counted down after this method returns, so subclasses
     * can perform additional cleanup in their {@code run()} method after
     * this call and before {@link #signalStopped()}.
     *
     * @param onStart runs the script's onStart
     * @param onLoop runs the script's onLoop, returns delay
     * @param onStop runs the script's onStop
     * @param profilerCallback optional callback after each loop iteration (nullable)
     */
    protected void runLoop(Runnable onStart, java.util.function.IntSupplier onLoop,
                           Runnable onStop, java.util.function.LongConsumer profilerCallback) {
        String name = getScriptName();
        MDC.put("script.name", name);

        try {
            onStart.run();
        } catch (Exception e) {
            log.error("onStart error in {}: {}", name, e.getMessage());
            notifyError(name, "onStart", e);
            running.set(false);
            MDC.clear();
            return;
        }

        // Load persisted config after onStart
        try {
            List<ConfigField> fields = getConfigFields();
            if (fields != null && !fields.isEmpty()) {
                ScriptConfig config = ScriptConfigStore.load(name, fields);
                currentConfig.set(config);
                onConfigUpdate(config);
            }
        } catch (Exception e) {
            log.error("Config load error in {}: {}", name, e.getMessage());
        }

        try {
            while (running.get() && !Thread.currentThread().isInterrupted()) {
                long loopStart = System.nanoTime();
                int delay = onLoop.getAsInt();
                if (profilerCallback != null) {
                    profilerCallback.accept(System.nanoTime() - loopStart);
                }
                if (delay < 0) break;
                if (delay > 0) {
                    Thread.sleep(delay);
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            log.error("onLoop error in {}: {}", name, e.getMessage());
            notifyError(name, "onLoop", e);
        } finally {
            running.set(false);
            Thread.interrupted(); // clear interrupt flag so onStop can do NIO I/O (Files.writeString etc.)
            try {
                onStop.run();
            } catch (Exception e) {
                log.error("onStop error in {}: {}", name, e.getMessage());
                notifyError(name, "onStop", e);
            }
            MDC.clear();
        }
    }

    /**
     * Counts down the stop latch, unblocking any {@link #awaitStop(long)} callers.
     * Subclasses must call this at the very end of their {@link #run()} method,
     * after all cleanup (including subclass-specific cleanup) is complete.
     */
    protected void signalStopped() {
        CountDownLatch latch = this.stopLatch;
        if (latch != null) latch.countDown();
    }
}
