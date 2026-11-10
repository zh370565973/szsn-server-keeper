package com.szsn.srm.resource;

import java.util.function.BooleanSupplier;

/**
 * CPU 时间片燃烧器。生产用可观察校验实现；测试可注入不自旋的替身，避免开发机满载。
 */
@FunctionalInterface
public interface CpuBurner {
    /**
     * 在许可有效且 {@code computeAllowed} 为真时尽量燃烧 {@code durationNanos}，
     * 期间至少每 5ms 检查一次停止/暂停/generation。
     *
     * @return 本段产生的可观察校验累计值
     */
    long burn(long durationNanos, WorkerPermit permit, BooleanSupplier computeAllowed);
}
