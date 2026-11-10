package com.szsn.srm.resource;

import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

/**
 * 占用控制槽位的一个执行实例。状态只存在于内存。
 */
final class ActiveSession {
    private final String executionId;
    private final SessionGeneration.Epoch epoch;
    private final Consumer<ActiveSession> onSettled;
    private final CopyOnWriteArrayList<LoadWorker> workers = new CopyOnWriteArrayList<>();
    private final AtomicReference<Phase> phase = new AtomicReference<>(
            new Phase(ExecutionStatus.STARTING, false, StopKind.USER));

    ActiveSession(String executionId, SessionGeneration.Epoch epoch, Consumer<ActiveSession> onSettled) {
        this.executionId = executionId;
        this.epoch = epoch;
        this.onSettled = onSettled;
    }

    String executionId() {
        return executionId;
    }

    SessionGeneration.Epoch epoch() {
        return epoch;
    }

    ExecutionStatus status() {
        return phase.get().status;
    }

    boolean acceptsLoad() {
        ExecutionStatus status = status();
        return status == ExecutionStatus.STARTING || status == ExecutionStatus.RUNNING;
    }

    long heldBytes() {
        long sum = 0;
        for (LoadWorker worker : workers) {
            sum += worker.heldBytes();
        }
        return sum;
    }

    boolean workersExited() {
        for (LoadWorker worker : workers) {
            if (!worker.exited()) {
                return false;
            }
        }
        return true;
    }

    int openWorkers() {
        int count = 0;
        for (LoadWorker worker : workers) {
            if (!worker.exited()) {
                count++;
            }
        }
        return count;
    }

    LoadWorker registerWorker() {
        LoadWorker worker = LoadWorker.registered(epoch, this, () -> onSettled.accept(this));
        workers.add(worker);
        return worker;
    }

    void markRunning() {
        transition(current -> ExecutionTransitions.startSucceeded(current));
    }

    void beginPause() {
        transition(current -> ExecutionTransitions.beginPause(current));
    }

    void pauseCompleted() {
        transition(current -> ExecutionTransitions.pauseCompleted(current));
    }

    void markUserStop() {
        phase.updateAndGet(current -> {
            if (current.status.terminal() || current.emergency) {
                return current;
            }
            return new Phase(ExecutionTransitions.requestStop(current.status), false, StopKind.USER);
        });
    }

    void markEmergency() {
        phase.updateAndGet(current -> {
            if (current.status.terminal()) {
                return new Phase(current.status, true, StopKind.SAFETY);
            }
            return new Phase(ExecutionTransitions.requestStop(current.status), true, StopKind.SAFETY);
        });
    }

    /**
     * Worker 已退出且持有量为 0 时写入终态。返回 true 表示槽位可以释放。
     */
    boolean releaseIfQuiescent() {
        if (!LoadAdmission.quiescent(workersExited(), heldBytes())) {
            return false;
        }
        while (true) {
            Phase current = phase.get();
            if (current.status.terminal()) {
                return true;
            }
            if (current.status != ExecutionStatus.STOPPING) {
                return false;
            }
            StopKind kind = current.emergency ? StopKind.SAFETY : current.kind;
            Phase done = new Phase(ExecutionTransitions.finishStop(current.status, kind), current.emergency, kind);
            if (phase.compareAndSet(current, done)) {
                return true;
            }
        }
    }

    ResourceLoadManager.SessionView view() {
        return new ResourceLoadManager.SessionView(
                executionId, status(), epoch.generation(), heldBytes(), openWorkers());
    }

    private void transition(java.util.function.UnaryOperator<ExecutionStatus> change) {
        phase.updateAndGet(current -> {
            if (current.emergency && current.status == ExecutionStatus.STOPPING) {
                throw new IllegalStateException("急停后不能继续普通迁移");
            }
            return new Phase(change.apply(current.status), current.emergency, current.kind);
        });
    }

    private record Phase(ExecutionStatus status, boolean emergency, StopKind kind) {
    }
}
