package com.szsn.srm.resource;

/**
 * 真实计算燃烧器：每段产生可观察校验值，避免被 JIT 消除；每不超过 5ms 检查许可。
 * 仅在获准环境由调用方显式使用；单元测试应注入不自旋的 {@link CpuBurner}。
 */
public final class ChecksumCpuBurner implements CpuBurner {
    static final long CHECK_NANOS = 5_000_000L;

    @Override
    public long burn(long durationNanos, WorkerPermit permit) {
        if (permit == null) {
            throw new IllegalArgumentException("许可不能为空");
        }
        if (durationNanos <= 0 || !permit.valid()) {
            return 0L;
        }
        long deadline = System.nanoTime() + durationNanos;
        long checksum = 0L;
        long nextCheck = System.nanoTime() + CHECK_NANOS;
        long i = 1L;
        while (permit.valid()) {
            long now = System.nanoTime();
            if (now >= deadline) {
                break;
            }
            checksum += Long.rotateLeft(checksum ^ i, 7) + i;
            i++;
            if (now >= nextCheck) {
                nextCheck = now + CHECK_NANOS;
            }
        }
        return checksum == 0L ? 1L : checksum;
    }
}
