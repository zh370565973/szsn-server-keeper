package com.szsn.srm.resource;

import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.UnaryOperator;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * 运行时只保留一个活动会话槽位。普通变更进入同一把锁；急停只递增 generation 并置停止标志，
 * 不进入这把锁，返回前也不等待 {@link OrdinaryJournal#record}。本类不启动 CPU Worker，也不分配内存。
 */
@Component
public class ResourceLoadManager {
    private final Object ordinary = new Object();
    private final AtomicLong generation = new AtomicLong(1);
    private final AtomicBoolean stopFlag = new AtomicBoolean(false);
    private final AtomicReference<Slot> active = new AtomicReference<>();
    private final AtomicReference<Slot> lastFinished = new AtomicReference<>();
    private final OrdinaryJournal journal;

    public ResourceLoadManager() {
        this(OrdinaryJournal.NONE);
    }

    @Autowired
    public ResourceLoadManager(OrdinaryJournal journal) {
        this.journal = journal == null ? OrdinaryJournal.NONE : journal;
    }

    public SessionClaim claim(String executionId) {
        requireId(executionId);
        synchronized (ordinary) {
            Slot current = active.get();
            if (occupies(current)) {
                throw new SessionRejectedException(SessionRejectedException.Reason.SLOT_OCCUPIED, "已有活动会话");
            }
            long issued = generation.get();
            stopFlag.set(false);
            if (generation.get() != issued || stopFlag.get() || occupies(active.get())) {
                throw preempted();
            }
            Slot starting = Slot.starting(executionId, issued);
            journal.record(executionId, ExecutionStatus.STARTING);
            if (generation.get() != issued || stopFlag.get() || occupies(active.get())) {
                throw preempted();
            }
            if (!active.compareAndSet(current, starting)) {
                throw preempted();
            }
            if (generation.get() != issued || stopFlag.get()) {
                abandonUnissued(executionId, issued);
                throw preempted();
            }
            WorkerPermit permit = new WorkerPermit(executionId, issued, generation, stopFlag);
            if (!permit.valid()) {
                abandonUnissued(executionId, issued);
                throw preempted();
            }
            return new SessionClaim(executionId, issued, permit);
        }
    }

    public void markRunning(String executionId) {
        mutate(executionId, slot -> slot.withStatus(ExecutionTransitions.startSucceeded(slot.status)));
    }

    public void beginPause(String executionId) {
        mutate(executionId, slot -> slot.withStatus(ExecutionTransitions.beginPause(slot.status)));
    }

    public void completePause(String executionId) {
        mutate(executionId, slot -> slot.withStatus(ExecutionTransitions.pauseCompleted(slot.status)));
    }

    public void acceptResume(String executionId) {
        mutate(executionId, slot -> slot.withStatus(ExecutionTransitions.resumeAccepted(slot.status)));
    }

    public void rejectResume(String executionId) {
        mutate(executionId, slot -> slot.withStatus(ExecutionTransitions.resumeRejected(slot.status)));
    }

    public void requestStop(String executionId, StopKind kind) {
        requireId(executionId);
        if (kind == null) {
            throw new IllegalArgumentException("停止类型不能为空");
        }
        synchronized (ordinary) {
            Slot current = active.get();
            if (current == null || !current.executionId.equals(executionId)) {
                Slot finished = lastFinished.get();
                if (finished != null && finished.executionId.equals(executionId) && finished.status.terminal()) {
                    return;
                }
                throw new SessionRejectedException(SessionRejectedException.Reason.NOT_ACTIVE, "没有可停止的活动会话");
            }
            if (current.status == ExecutionStatus.STOPPING || current.status.terminal()) {
                return;
            }
            generation.incrementAndGet();
            stopFlag.set(true);
            Slot stopping = current.withStop(kind, ExecutionStatus.STOPPING);
            if (!active.compareAndSet(current, stopping)) {
                Slot now = active.get();
                if (now != null && now.executionId.equals(executionId)) {
                    reconcileAndRecord(now);
                }
                return;
            }
            journal.record(executionId, ExecutionStatus.STOPPING);
            reconcileAndRecord(active.get() == null ? stopping : active.get());
        }
    }

    /**
     * 使已签发的 Worker 许可立即失效。不获取普通操作锁，不调用日志，也不等待数据库。
     *
     * @return 失效之后的 generation
     */
    public long emergencyStop() {
        long next = generation.incrementAndGet();
        stopFlag.set(true);
        while (true) {
            Slot current = active.get();
            if (current == null || current.status.terminal()) {
                return next;
            }
            Slot stopping = current.withStop(StopKind.SAFETY, ExecutionStatus.STOPPING);
            if (active.compareAndSet(current, stopping)) {
                Slot finished = reconcile(stopping);
                if (finished != null) {
                    journal.recordLater(finished.executionId, finished.status);
                }
                return next;
            }
        }
    }

    public void confirmWorkerExit(WorkerPermit permit) {
        if (permit == null) {
            throw new IllegalArgumentException("许可不能为空");
        }
        permit.acknowledgeExit();
        synchronized (ordinary) {
            while (true) {
                Slot current = active.get();
                if (current == null || !current.executionId.equals(permit.executionId())) {
                    return;
                }
                if (current.issuedGeneration != permit.issuedGeneration()) {
                    return;
                }
                if (current.workerExited) {
                    reconcileAndRecord(current);
                    return;
                }
                Slot exited = current.withWorkerExited(true);
                if (active.compareAndSet(current, exited)) {
                    reconcileAndRecord(exited);
                    return;
                }
            }
        }
    }

    /**
     * 记录当前会话上报的负载持有量，供槽位放行判断。不分配内存。
     */
    public void observeHeldBytes(String executionId, long heldBytes) {
        requireId(executionId);
        if (heldBytes < 0) {
            throw new IllegalArgumentException("持有量不能为负");
        }
        synchronized (ordinary) {
            while (true) {
                Slot current = active.get();
                if (current == null || !current.executionId.equals(executionId)) {
                    throw new SessionRejectedException(SessionRejectedException.Reason.NOT_ACTIVE, "没有可记录的活动会话");
                }
                Slot updated = current.withHeldBytes(heldBytes);
                if (active.compareAndSet(current, updated)) {
                    reconcileAndRecord(updated);
                    return;
                }
            }
        }
    }

    public Optional<SessionView> active() {
        Slot slot = active.get();
        return slot == null ? Optional.empty() : Optional.of(slot.view());
    }

    public Optional<SessionView> lastFinished() {
        Slot slot = lastFinished.get();
        return slot == null ? Optional.empty() : Optional.of(slot.view());
    }

    private void mutate(String executionId, UnaryOperator<Slot> transition) {
        requireId(executionId);
        synchronized (ordinary) {
            Slot current = requireActive(executionId);
            long issued = generation.get();
            if (current.issuedGeneration != issued || stopFlag.get()) {
                throw preempted();
            }
            Slot next = transition.apply(current);
            journal.record(executionId, next.status);
            if (generation.get() != issued || stopFlag.get() || active.get() != current) {
                throw preempted();
            }
            if (!active.compareAndSet(current, next)) {
                throw preempted();
            }
            if (generation.get() != issued || stopFlag.get()) {
                retireToStopping(StopKind.SAFETY);
                throw preempted();
            }
        }
    }

    private Slot requireActive(String executionId) {
        Slot current = active.get();
        if (current == null || !current.executionId.equals(executionId) || current.status.terminal()) {
            throw new SessionRejectedException(SessionRejectedException.Reason.NOT_ACTIVE, "没有可变更的活动会话");
        }
        return current;
    }

    private void retireToStopping(StopKind kind) {
        while (true) {
            Slot current = active.get();
            if (current == null || current.status.terminal() || current.status == ExecutionStatus.STOPPING) {
                if (current != null) {
                    reconcileAndRecord(current);
                }
                return;
            }
            Slot stopping = current.withStop(kind, ExecutionStatus.STOPPING);
            if (active.compareAndSet(current, stopping)) {
                reconcileAndRecord(stopping);
                return;
            }
        }
    }

    private void abandonUnissued(String executionId, long issuedGeneration) {
        while (true) {
            Slot current = active.get();
            if (current == null
                    || !current.executionId.equals(executionId)
                    || current.issuedGeneration != issuedGeneration) {
                return;
            }
            StopKind kind = current.stopKind == null ? StopKind.SAFETY : current.stopKind;
            Slot abandoned = new Slot(current.executionId, ExecutionStatus.STOPPING, current.issuedGeneration,
                    kind, true, current.heldBytes);
            if (active.compareAndSet(current, abandoned)) {
                reconcileAndRecord(abandoned);
                return;
            }
        }
    }

    private Slot reconcile(Slot current) {
        if (current == null
                || current.status != ExecutionStatus.STOPPING
                || !current.workerExited
                || current.heldBytes != 0
                || current.stopKind == null) {
            return null;
        }
        Slot finished = current.withStatus(ExecutionTransitions.finishStop(current.status, current.stopKind));
        if (active.compareAndSet(current, null)) {
            lastFinished.set(finished);
            return finished;
        }
        return null;
    }

    private void reconcileAndRecord(Slot current) {
        Slot finished = reconcile(current);
        if (finished != null) {
            journal.record(finished.executionId, finished.status);
        }
    }

    private static boolean occupies(Slot slot) {
        if (slot == null) {
            return false;
        }
        if (slot.status.occupiesSlot()) {
            return true;
        }
        return !slot.workerExited || slot.heldBytes != 0;
    }

    private static void requireId(String executionId) {
        if (executionId == null || executionId.isBlank()) {
            throw new IllegalArgumentException("执行编号不能为空");
        }
    }

    private static SessionRejectedException preempted() {
        return new SessionRejectedException(SessionRejectedException.Reason.EMERGENCY_PREEMPTED, "急停已使当前签发失效");
    }

    private static final class Slot {
        private final String executionId;
        private final ExecutionStatus status;
        private final long issuedGeneration;
        private final StopKind stopKind;
        private final boolean workerExited;
        private final long heldBytes;

        private Slot(String executionId, ExecutionStatus status, long issuedGeneration, StopKind stopKind,
                boolean workerExited, long heldBytes) {
            this.executionId = executionId;
            this.status = status;
            this.issuedGeneration = issuedGeneration;
            this.stopKind = stopKind;
            this.workerExited = workerExited;
            this.heldBytes = heldBytes;
        }

        private static Slot starting(String executionId, long issuedGeneration) {
            return new Slot(executionId, ExecutionStatus.STARTING, issuedGeneration, null, false, 0);
        }

        private Slot withStatus(ExecutionStatus status) {
            return new Slot(executionId, status, issuedGeneration, stopKind, workerExited, heldBytes);
        }

        private Slot withStop(StopKind kind, ExecutionStatus status) {
            return new Slot(executionId, status, issuedGeneration, kind, workerExited, heldBytes);
        }

        private Slot withWorkerExited(boolean workerExited) {
            return new Slot(executionId, status, issuedGeneration, stopKind, workerExited, heldBytes);
        }

        private Slot withHeldBytes(long heldBytes) {
            return new Slot(executionId, status, issuedGeneration, stopKind, workerExited, heldBytes);
        }

        private SessionView view() {
            return new SessionView(executionId, status, issuedGeneration, stopKind, workerExited, heldBytes);
        }
    }
}
