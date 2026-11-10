package com.szsn.srm.resource;

/**
 * 内存预算所需的关键样本。缺失字段用 null；预算门闸在缺关键值时按不可用处理。
 */
public record MemorySample(
        Long memTotalBytes,
        Long memAvailableBytes,
        Long heapMaxBytes,
        Long heapUsedBytes) {
}
