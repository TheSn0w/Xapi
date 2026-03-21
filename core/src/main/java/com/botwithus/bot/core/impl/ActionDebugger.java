package com.botwithus.bot.core.impl;

import com.botwithus.bot.api.model.GameAction;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Per-connection action interceptor for debugging.
 * When recording is enabled, every action attempt is logged.
 * When blocking is enabled, actions are logged but not sent to the game.
 * Selective blocking allows blocking only specific action types.
 *
 * <p>Instances are keyed by connection name. Use {@link #forConnection(String)}
 * to get the debugger for a specific connection, and {@link #remove(String)}
 * to clean up when a connection is closed.</p>
 */
public final class ActionDebugger {

    private static final ConcurrentHashMap<String, ActionDebugger> INSTANCES = new ConcurrentHashMap<>();
    private static final ActionDebugger GLOBAL = new ActionDebugger();
    private static final int MAX_LOG_SIZE = 10_000;

    /**
     * Returns the debugger for the given connection, creating one if needed.
     */
    public static ActionDebugger forConnection(String connectionName) {
        if (connectionName == null) return GLOBAL;
        return INSTANCES.computeIfAbsent(connectionName, k -> new ActionDebugger());
    }

    /**
     * Returns the global (non-connection-specific) debugger instance.
     * Used by CLI panels when no specific connection is targeted.
     */
    public static ActionDebugger global() {
        return GLOBAL;
    }

    /**
     * Removes and returns the debugger for the given connection.
     * Call on disconnect to prevent memory leaks.
     */
    public static ActionDebugger remove(String connectionName) {
        return INSTANCES.remove(connectionName);
    }

    public record LoggedAction(GameAction action, long timestamp, boolean wasBlocked) {}

    private volatile boolean recording;
    private volatile boolean blocking;
    private volatile boolean selectiveBlocking;
    private final Set<Integer> blockedActionIds = ConcurrentHashMap.newKeySet();
    private final CopyOnWriteArrayList<LoggedAction> log = new CopyOnWriteArrayList<>();

    private ActionDebugger() {}

    public boolean isRecording() { return recording; }
    public void setRecording(boolean recording) { this.recording = recording; }

    public boolean isBlocking() { return blocking; }
    public void setBlocking(boolean blocking) { this.blocking = blocking; }

    public boolean isSelectiveBlocking() { return selectiveBlocking; }
    public void setSelectiveBlocking(boolean selectiveBlocking) { this.selectiveBlocking = selectiveBlocking; }

    public Set<Integer> getBlockedActionIds() { return blockedActionIds; }

    public List<LoggedAction> getLog() { return log; }

    public void clear() { log.clear(); }

    /**
     * Called before an action is sent over RPC.
     * @return {@code true} if the action should proceed, {@code false} if it should be blocked.
     */
    public boolean onAction(GameAction action) {
        boolean shouldBlock = blocking
                || (selectiveBlocking && blockedActionIds.contains(action.actionId()));

        if (recording || shouldBlock) {
            log.add(new LoggedAction(action, System.currentTimeMillis(), shouldBlock));
            if (log.size() > MAX_LOG_SIZE) {
                // Bulk trim: snapshot tail and replace (O(n) instead of O(n²) per remove(0))
                List<LoggedAction> keep = new ArrayList<>(log.subList(log.size() - MAX_LOG_SIZE + 1000, log.size()));
                log.clear();
                log.addAll(keep);
            }
        }
        return !shouldBlock;
    }
}
