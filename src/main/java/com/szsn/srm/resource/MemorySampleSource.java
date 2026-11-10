package com.szsn.srm.resource;

/**
 * 内存预算样本来源。可注入固定值或快照派生；缺失时返回 null。
 */
@FunctionalInterface
public interface MemorySampleSource {
    MemorySample sample();
}
