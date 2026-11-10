package com.szsn.srm.safety;

/**
 * 用相邻两次 Swap 已用字节计算换页速率。缺上一样本、时钟回退、无 Swap 或计数回绕时返回 null，
 * 不把缺省值当成零压力。
 */
public final class SwapRateWindow {
    private Long previousUsed;
    private long previousAt = Long.MIN_VALUE;

    public Double observe(boolean swapPresent, Long swapUsedBytes, long observedAt) {
        if (!swapPresent || swapUsedBytes == null || swapUsedBytes < 0) {
            previousUsed = null;
            previousAt = Long.MIN_VALUE;
            return null;
        }
        Long previous = previousUsed;
        long previousTime = previousAt;
        previousUsed = swapUsedBytes;
        previousAt = observedAt;
        if (previous == null || previousTime == Long.MIN_VALUE) {
            return null;
        }
        if (observedAt <= previousTime) {
            return null;
        }
        if (swapUsedBytes < previous) {
            return null;
        }
        double elapsedSec = (observedAt - previousTime) / 1000D;
        if (elapsedSec <= 0) {
            return null;
        }
        return (swapUsedBytes - previous) / elapsedSec;
    }

    public void reset() {
        previousUsed = null;
        previousAt = Long.MIN_VALUE;
    }
}
