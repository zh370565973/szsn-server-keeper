package com.szsn.srm.resource;

/**
 * 假分配器：只更新 heldBytes 计数，不触碰真实堆或 native 内存。
 * 供开发机与自动测试使用；真实分块分配器留到后续任务。
 */
public final class FakeMemoryAllocator implements MemoryAllocator {
    private final Object lock = new Object();
    private long heldBytes;

    @Override
    public long heldBytes() {
        synchronized (lock) {
            return heldBytes;
        }
    }

    @Override
    public long grow(long deltaBytes) {
        if (deltaBytes < 0) {
            throw new IllegalArgumentException("增长量不能为负");
        }
        synchronized (lock) {
            heldBytes = Math.addExact(heldBytes, deltaBytes);
            return heldBytes;
        }
    }

    @Override
    public long release(long deltaBytes) {
        if (deltaBytes < 0) {
            throw new IllegalArgumentException("释放量不能为负");
        }
        synchronized (lock) {
            heldBytes = Math.max(0L, heldBytes - deltaBytes);
            return heldBytes;
        }
    }

    @Override
    public void clear() {
        synchronized (lock) {
            heldBytes = 0L;
        }
    }
}
