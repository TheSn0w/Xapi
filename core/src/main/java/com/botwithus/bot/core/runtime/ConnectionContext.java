package com.botwithus.bot.core.runtime;

/**
 * Thread-local holder for the connection name associated with the current thread.
 * Uses {@link InheritableThreadLocal} so virtual threads spawned from a tagged
 * parent automatically inherit the connection name.
 */
public final class ConnectionContext {

    private static final InheritableThreadLocal<String> CURRENT = new InheritableThreadLocal<>();

    private ConnectionContext() {}

    public static void set(String connectionName) {
        CURRENT.set(connectionName);
    }

    public static String get() {
        return CURRENT.get();
    }

    public static void clear() {
        CURRENT.remove();
    }
}
