package com.szsn.srm.task;

/**
 * 执行记录已经提交为 STARTING 之后才调用。实现不得在拒绝时留下 Worker 或内存块。
 */
public interface ScheduleLauncher {
    void launch(String executionId, String snapshotJson);
}
