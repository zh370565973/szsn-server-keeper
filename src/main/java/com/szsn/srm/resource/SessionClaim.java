package com.szsn.srm.resource;

public final class SessionClaim {
    private final String executionId;
    private final long generation;
    private final WorkerPermit permit;

    SessionClaim(String executionId, long generation, WorkerPermit permit) {
        this.executionId = executionId;
        this.generation = generation;
        this.permit = permit;
    }

    public String executionId() {
        return executionId;
    }

    public long generation() {
        return generation;
    }

    public WorkerPermit permit() {
        return permit;
    }
}
