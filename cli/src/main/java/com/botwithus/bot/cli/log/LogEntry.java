package com.botwithus.bot.cli.log;

import java.time.Instant;

public record LogEntry(Instant timestamp, String source, String level, String message, String connection) {

    public LogEntry(String source, String level, String message) {
        this(Instant.now(), source, level, message, null);
    }

    public LogEntry(String source, String level, String message, String connection) {
        this(Instant.now(), source, level, message, connection);
    }
}
