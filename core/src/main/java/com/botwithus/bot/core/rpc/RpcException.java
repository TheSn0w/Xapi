package com.botwithus.bot.core.rpc;

public class RpcException extends RuntimeException {
    public RpcException(String message) { super(message); }
    public RpcException(String message, Throwable cause) { super(message, cause); }
}
