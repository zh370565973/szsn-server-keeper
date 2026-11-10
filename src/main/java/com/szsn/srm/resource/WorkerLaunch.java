package com.szsn.srm.resource;

/**
 * STARTING 与配置快照提交之后才调用。这里不跑 CPU 引擎，也不分配负载内存。
 */
@FunctionalInterface
public interface WorkerLaunch {
    void launch(CommittedStart start);
}
