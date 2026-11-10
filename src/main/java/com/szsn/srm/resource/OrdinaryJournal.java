package com.szsn.srm.resource;

/**
 * 普通控制变更在串行段内留下的记录点。急停不会调用它，因此不会等待其中的数据库写入。
 */
@FunctionalInterface
public interface OrdinaryJournal {
    OrdinaryJournal NONE = (executionId, status) -> {
    };

    void record(String executionId, ExecutionStatus status);
}
