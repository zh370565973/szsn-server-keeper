package com.szsn.srm.resource;

public final class SessionRejectedException extends RuntimeException {
    public enum Reason {
        SLOT_OCCUPIED,
        EMERGENCY_PREEMPTED,
        NOT_ACTIVE
    }

    private final Reason reason;

    public SessionRejectedException(Reason reason, String message) {
        super(message);
        this.reason = reason;
    }

    public Reason reason() {
        return reason;
    }
}
