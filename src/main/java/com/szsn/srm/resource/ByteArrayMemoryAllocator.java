package com.szsn.srm.resource;

import java.util.ArrayDeque;

/**
 * 生产内存分配器：按调用方给出的块大小持有 {@code byte[]}，逐页写入非零字节，
 * 使负载真正占用堆内存。停止或暂停通过丢掉引用把 heldBytes 归零，不调用 {@code System.gc()}，
 * 也不把 RSS 下降当作释放成功。
 */
public final class ByteArrayMemoryAllocator implements MemoryAllocator {
    static final int PAGE_BYTES = 4096;

    private final Object lock = new Object();
    private final ArrayDeque<byte[]> chunks = new ArrayDeque<>();
    private long heldBytes;
    private int epoch;

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
        if (deltaBytes == 0) {
            return heldBytes();
        }
        if (deltaBytes > Integer.MAX_VALUE) {
            throw new IllegalArgumentException("单块超过 int 上限");
        }
        final int seen;
        synchronized (lock) {
            seen = epoch;
        }
        byte[] block = new byte[(int) deltaBytes];
        touch(block);
        synchronized (lock) {
            if (seen != epoch) {
                return heldBytes;
            }
            chunks.addLast(block);
            heldBytes += block.length;
            return heldBytes;
        }
    }

    /**
     * 每页至少写一个非零字节，避免操作系统延迟提交。不使用 Unsafe 或堆外内存。
     */
    static void touch(byte[] block) {
        for (int offset = 0; offset < block.length; offset += PAGE_BYTES) {
            block[offset] = 1;
        }
        if (block.length > 0) {
            block[block.length - 1] = 1;
        }
    }

    @Override
    public long release(long deltaBytes) {
        if (deltaBytes < 0) {
            throw new IllegalArgumentException("释放量不能为负");
        }
        synchronized (lock) {
            long remaining = deltaBytes;
            while (remaining > 0 && !chunks.isEmpty()) {
                byte[] chunk = chunks.removeLast();
                heldBytes -= chunk.length;
                remaining -= chunk.length;
            }
            if (heldBytes < 0) {
                heldBytes = 0L;
            }
            return heldBytes;
        }
    }

    @Override
    public void clear() {
        synchronized (lock) {
            chunks.clear();
            heldBytes = 0L;
            epoch++;
        }
    }

    int retainedChunks() {
        synchronized (lock) {
            return chunks.size();
        }
    }

    byte payloadSample() {
        synchronized (lock) {
            if (chunks.isEmpty()) {
                return 0;
            }
            byte[] first = chunks.peekFirst();
            return first.length == 0 ? 0 : first[0];
        }
    }
}
