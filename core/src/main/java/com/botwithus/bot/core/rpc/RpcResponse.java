package com.botwithus.bot.core.rpc;

import java.util.Map;

public record RpcResponse(int id, Object result, Map<String, Object> error) {}
