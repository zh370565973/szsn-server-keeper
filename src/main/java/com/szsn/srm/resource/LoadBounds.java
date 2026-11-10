package com.szsn.srm.resource;

/**
 * 公开启动与改目标入口的 PRD 硬边界。引擎内部仍可被测试直接调用并使用更宽的范围。
 */
public final class LoadBounds {
    /** 默认限时下限：10 秒。 */
    public static final long DURATION_MIN_SECONDS = 10L;
    /** 默认限时上限：7 天。 */
    public static final long DURATION_MAX_SECONDS = 7L * 24 * 60 * 60;
    public static final double MEMORY_TARGET_PCT_MIN = 1D;
    public static final double MEMORY_TARGET_PCT_MAX = 70D;

    private LoadBounds() {
    }
}
