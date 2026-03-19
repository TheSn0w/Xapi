package com.botwithus.bot.core.rpc;

import java.util.Map;

public record RpcRequest(String method, int id, Map<String, Object> params) {}
