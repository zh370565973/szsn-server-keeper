package com.szsn.srm.resource;

/**
 * 公开启动与改目标入口的硬边界。目标按整机约 90% 设计，允许改低。
 * 保护线放在目标之上，90% 可以稳住，再高才停止。
 */
public final class LoadBounds {
    /** 默认限时下限：10 秒。 */
    public static final long DURATION_MIN_SECONDS = 10L;
    /** 默认限时上限：7 天。 */
    public static final long DURATION_MAX_SECONDS = 7L * 24 * 60 * 60;
    public static final double CPU_TARGET_MIN = 1D;
    public static final double CPU_TARGET_MAX = 90D;
    public static final double MEMORY_TARGET_PCT_MIN = 1D;
    public static final double MEMORY_TARGET_PCT_MAX = 90D;
    /** 连续 3 秒达到后，CPU 出力归零。 */
    public static final double CPU_DUTY_ZERO_PCT = 94D;
    /** 连续 3 秒达到后，停止并锁存。 */
    public static final double CPU_LATCH_PCT = 97D;
    /** 低于该值连续 10 秒后，允许 CPU 再加力。 */
    public static final double CPU_RECOVER_BELOW_PCT = 90D;
    /** 可用内存低于总内存的该百分比时不再增加。 */
    public static final int SYSTEM_FORBID_PERCENT = 7;
    /** 可用内存低于总内存的该百分比并连续 2 秒时停止并锁存。 */
    public static final int SYSTEM_STOP_PERCENT = 6;
    /** 负载可占到堆上限的该百分比。 */
    public static final int HEAP_LOAD_PERCENT = 90;
    /** 堆预留，给 JVM 和 93% 保护线留出空隙。 */
    public static final int HEAP_RESERVE_PERCENT = 8;
    /** 堆使用达到该比例后不再增加内存。 */
    public static final double HEAP_FORBID_RATIO = 0.93D;
    /** 堆使用达到该比例并连续 2 秒后停止并锁存。 */
    public static final double HEAP_LATCH_RATIO = 0.95D;

    private LoadBounds() {
    }
}
