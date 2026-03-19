package com.botwithus.bot.core.impl;

import com.botwithus.bot.api.model.GameAction;

import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Singleton that intercepts action queue calls for debugging.
 * When recording is enabled, every action attempt is logged.
 * When blocking is enabled, actions are logged but not sent to the game.
 * Selective blocking allows blocking only specific action types.
 */
public final class ActionDebugger {

    private static final ActionDebugger INSTANCE = new ActionDebugger();

    public static ActionDebugger get() {
        return INSTANCE;
    }

    public record LoggedAction(GameAction action, long timestamp, boolean wasBlocked) {}

    private volatile boolean recording;
    private volatile boolean blocking;
    private volatile boolean selectiveBlocking;
    private final Set<Integer> blockedActionIds = ConcurrentHashMap.newKeySet();
    private final List<LoggedAction> log = new CopyOnWriteArrayList<>();

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
        }
        return !shouldBlock;
    }
}
