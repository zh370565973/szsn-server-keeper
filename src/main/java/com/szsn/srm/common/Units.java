package com.szsn.srm.common;

public final class Units {
    public static final long KIB = 1024L;
    public static final long MIB = 1024L * KIB;
    public static final long GIB = 1024L * MIB;
    public static final long BLOCK = 4L * MIB;
    public static final long GUARD = 64L * MIB;
    public static final long DEFAULT_TASK_CAP = 512L * MIB;
    public static final long DISK_FLOOR = 256L * MIB;
    /** 内存负载增长上限（PRD：32 MiB/s）。 */
    public static final long MEMORY_GROWTH_BYTES_PER_SEC = 32L * MIB;

    private Units() {
    }

    public static long clampLong(long value, long min, long max) {
        return Math.max(min, Math.min(max, value));
    }

    public static double clampDouble(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }
}
