package com.szsn.srm.resource;

/**
 * 执行实例状态迁移。终态保持不变，普通停止对同一终态幂等。
 * 本类不启动线程，也不分配内存。
 */
public final class ExecutionTransitions {
    private ExecutionTransitions() {
    }

    public static ExecutionStatus markStarting() {
        return ExecutionStatus.STARTING;
    }

    public static ExecutionStatus markSkipped() {
        return ExecutionStatus.SKIPPED;
    }

    public static ExecutionStatus markInterrupted() {
        return ExecutionStatus.INTERRUPTED;
    }

    public static ExecutionStatus startSucceeded(ExecutionStatus current) {
        require(current == ExecutionStatus.STARTING, current, "START_OK");
        return ExecutionStatus.RUNNING;
    }

    public static ExecutionStatus startFailed(ExecutionStatus current) {
        require(current == ExecutionStatus.STARTING, current, "START_FAIL");
        return ExecutionStatus.FAILED;
    }

    public static ExecutionStatus beginPause(ExecutionStatus current) {
        require(current == ExecutionStatus.RUNNING, current, "PAUSE");
        return ExecutionStatus.PAUSING;
    }

    public static ExecutionStatus pauseCompleted(ExecutionStatus current) {
        require(current == ExecutionStatus.PAUSING, current, "PAUSE_DONE");
        return ExecutionStatus.PAUSED;
    }

    public static ExecutionStatus resumeAccepted(ExecutionStatus current) {
        require(current == ExecutionStatus.PAUSED, current, "RESUME_OK");
        return ExecutionStatus.STARTING;
    }

    public static ExecutionStatus resumeRejected(ExecutionStatus current) {
        require(current == ExecutionStatus.PAUSED, current, "RESUME_REJECT");
        return ExecutionStatus.PAUSED;
    }

    public static ExecutionStatus requestStop(ExecutionStatus current) {
        if (current.terminal()) {
            return current;
        }
        if (current == ExecutionStatus.STOPPING) {
            return ExecutionStatus.STOPPING;
        }
        return ExecutionStatus.STOPPING;
    }

    public static ExecutionStatus finishStop(ExecutionStatus current, StopKind kind) {
        if (current.terminal()) {
            return current;
        }
        require(current == ExecutionStatus.STOPPING, current, "FINISH");
        return switch (kind) {
            case USER -> ExecutionStatus.STOPPED;
            case DURATION -> ExecutionStatus.COMPLETED;
            case SAFETY -> ExecutionStatus.SAFETY_STOPPED;
            case FAILURE -> ExecutionStatus.FAILED;
        };
    }

    private static void require(boolean expression, ExecutionStatus current, String event) {
        if (!expression) {
            throw new IllegalStateException(event + " 不能从 " + current + " 发生");
        }
    }
}
