package com.szsn.srm.resource;

/**
 * 内存负载持有量接口。假分配器只维护计数，不分配 {@code byte[]}。
 */
public interface MemoryAllocator {
    long heldBytes();

    /**
     * 增加持有量。{@code deltaBytes} 必须非负。
     *
     * @return 增长后的持有量
     */
    long grow(long deltaBytes);

    /**
     * 减少持有量，不少于 0。
     *
     * @return 减少后的持有量
     */
    long release(long deltaBytes);

    /** 清除全部持有量。 */
    void clear();
}
