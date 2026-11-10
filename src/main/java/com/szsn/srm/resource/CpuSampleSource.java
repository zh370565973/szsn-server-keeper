package com.szsn.srm.resource;

/**
 * 整机 CPU busy 百分比来源。可注入固定值或假采集器；缺失时返回 null。
 */
@FunctionalInterface
public interface CpuSampleSource {
    Double overallBusyPct();
}
