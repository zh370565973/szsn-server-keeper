package com.szsn.srm.resource;

/**
 * 运行中或暂停时改目标的变更记录点。失败不得谎报更新成功。
 */
@FunctionalInterface
public interface TargetChangeJournal {
    TargetChangeJournal NONE = (executionId, fromVersion, toVersion, targetPct) -> {
    };

    void record(String executionId, long fromVersion, long toVersion, double targetPct);
}
