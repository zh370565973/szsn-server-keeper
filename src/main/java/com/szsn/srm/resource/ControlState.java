package com.szsn.srm.resource;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public record ControlState(
        String controlSwitch,
        boolean emergencyLatched,
        String latchReason,
        boolean latchPersisted,
        long healthyForMs,
        List<String> healthReasons,
        boolean latchResetReady,
        boolean storageHealthy,
        ActiveExecution activeExecution) {

    public Map<String, Object> toMap() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("controlSwitch", controlSwitch);
        body.put("emergencyLatched", emergencyLatched);
        body.put("latchReason", latchReason);
        body.put("latchPersisted", latchPersisted);
        body.put("healthyForMs", healthyForMs);
        body.put("healthReasons", healthReasons);
        body.put("latchResetReady", latchResetReady);
        body.put("storageHealthy", storageHealthy);
        if (activeExecution == null) {
            body.put("activeExecution", null);
        } else {
            Map<String, Object> execution = new LinkedHashMap<>();
            execution.put("executionId", activeExecution.executionId());
            execution.put("status", activeExecution.status());
            execution.put("heldBytes", activeExecution.heldBytes());
            body.put("activeExecution", execution);
        }
        return body;
    }

    public record ActiveExecution(String executionId, String status, long heldBytes) {
    }
}
