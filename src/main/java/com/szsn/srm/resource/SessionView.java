package com.szsn.srm.resource;

public record SessionView(
        String executionId,
        ExecutionStatus status,
        long issuedGeneration,
        StopKind stopKind,
        boolean workerExited,
        long heldBytes) {
}
