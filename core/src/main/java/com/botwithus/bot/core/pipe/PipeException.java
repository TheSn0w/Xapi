package com.botwithus.bot.core.pipe;

public class PipeException extends RuntimeException {
    public PipeException(String message) { super(message); }
    public PipeException(String message, Throwable cause) { super(message, cause); }
}
