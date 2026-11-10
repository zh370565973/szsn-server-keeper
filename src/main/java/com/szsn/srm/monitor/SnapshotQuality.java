package com.szsn.srm.monitor;

public final class SnapshotQuality {
    public static final long STALE_AFTER_MS = 3_000;
    public static final long UNUSABLE_AFTER_MS = 10_000;

    private SnapshotQuality() {
    }

    public static Quality assess(boolean criticalComplete, boolean optionalMissing, long ageMs) {
        if (!criticalComplete || ageMs > UNUSABLE_AFTER_MS) {
            return Quality.UNAVAILABLE;
        }
        if (ageMs > STALE_AFTER_MS) {
            return Quality.STALE;
        }
        if (optionalMissing) {
            return Quality.PARTIAL;
        }
        return Quality.VALID;
    }
}
