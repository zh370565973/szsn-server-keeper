package com.szsn.srm.resource;

public enum ExecutionStatus {
    STARTING,
    RUNNING,
    PAUSING,
    PAUSED,
    STOPPING,
    STOPPED,
    COMPLETED,
    SAFETY_STOPPED,
    FAILED,
    INTERRUPTED,
    SKIPPED;

    public boolean terminal() {
        return switch (this) {
            case STOPPED, COMPLETED, SAFETY_STOPPED, FAILED, INTERRUPTED, SKIPPED -> true;
            case STARTING, RUNNING, PAUSING, PAUSED, STOPPING -> false;
        };
    }

    public boolean occupiesSlot() {
        return !terminal();
    }
}
