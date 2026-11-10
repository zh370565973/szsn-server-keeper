package com.szsn.srm.resource;

public record Operator(String name, String sourceIp, String requestId) {
    public static Operator system() {
        return new Operator("system", null, null);
    }
}
