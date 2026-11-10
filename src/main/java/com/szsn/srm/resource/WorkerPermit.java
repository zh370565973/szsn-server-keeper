package com.szsn.srm.resource;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 签发时绑定的 generation。急停递增 generation 或置上停止标志后，许可立即失效。
 * 判断只读原子量，不访问数据库，也不进入普通操作队列。
 */
public final class WorkerPermit {
    private final String executionId;
    private final long issuedGeneration;
    private final AtomicLong generation;
    private final AtomicBoolean stopFlag;
    private final AtomicBoolean exited = new AtomicBoolean(false);

    WorkerPermit(String executionId, long issuedGeneration, AtomicLong generation, AtomicBoolean stopFlag) {
        this.executionId = executionId;
        this.issuedGeneration = issuedGeneration;
        this.generation = generation;
        this.stopFlag = stopFlag;
    }

    public String executionId() {
        return executionId;
    }

    public long issuedGeneration() {
        return issuedGeneration;
    }

    public boolean valid() {
        return !exited.get() && !stopFlag.get() && generation.get() == issuedGeneration;
    }

    public boolean exited() {
        return exited.get();
    }

    public void acknowledgeExit() {
        exited.set(true);
    }
}
