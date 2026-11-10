package com.szsn.srm.resource;

@FunctionalInterface
public interface ControlAudit {
    ControlAudit NONE = (actor, sourceIp, action, result, reason, requestId) -> {
    };

    void record(String actor, String sourceIp, String action, String result, String reason, String requestId);
}
