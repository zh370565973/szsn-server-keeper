package com.szsn.srm.resource;

/**
 * 已经提交的启动记录。调用方可以据此签发 Worker 许可，但不得假定引擎已经进入 RUNNING。
 */
public record CommittedStart(String executionId, String configSnapshotJson, long version) {
}
