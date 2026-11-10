package com.szsn.srm.resource;

/**
 * 一次启动或幂等重放的结果。{@code replay} 为真时没有再次调用启动动作。
 */
public record StartedExecution(
        String executionId,
        ExecutionStatus status,
        long version,
        String configSnapshotJson,
        boolean replay) {
}
