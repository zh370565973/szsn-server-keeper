package com.szsn.srm.resource;

/**
 * 普通控制变更在串行段内留下的记录点。急停返回前不调用 {@link #record}，因此不会等待数据库。
 * 急停当时已经把槽位收成终态时，只调用 {@link #recordLater}。默认实现仍是同步写入；
 * 数据库实现改成异步，避免急停等待落库。
 */
@FunctionalInterface
public interface OrdinaryJournal {
    OrdinaryJournal NONE = (executionId, status) -> {
    };

    void record(String executionId, ExecutionStatus status);

    default void recordLater(String executionId, ExecutionStatus status) {
        record(executionId, status);
    }
}
