package com.szsn.srm.resource;

import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReentrantLock;
import org.springframework.stereotype.Component;

/**
 * 运行时单会话槽位。普通变更走同一把锁；急停只推进 generation 并标记停止，不等待这把锁或数据库。
 * 不启动 CPU 计算，也不分配内存。
 */
@Component
public class ResourceLoadManager {
    private final ReentrantLock ordinary = new ReentrantLock(true);
    private final SessionGeneration generations = new SessionGeneration();
    private final AtomicReference<ActiveSession> slot = new AtomicReference<>();
    private final AtomicReference<ActiveSession> last = new AtomicReference<>();

    public StartAttempt start(String executionId) {
        String id = requireId(executionId);
        ordinary.lock();
        try {
            ActiveSession current = detachIfFinished(slot.get());
            if (current != null) {
                String reason = LoadAdmission.rejection(current.status(), current.workersExited(), current.heldBytes());
                if (reason == null) {
                    reason = "SLOT_OCCUPIED";
                }
                return StartAttempt.rejected(id, current.view(), reason);
            }
            SessionGeneration.Epoch epoch = generations.current();
            ActiveSession created = new ActiveSession(id, epoch, this::clearIfQuiescent);
            if (!slot.compareAndSet(null, created)) {
                ActiveSession winner = slot.get();
                return StartAttempt.rejected(id, winner == null ? null : winner.view(), "SLOT_OCCUPIED");
            }
            if (!epoch.live() || slot.get() != created) {
                created.markEmergency();
                clearIfQuiescent(created);
                return StartAttempt.rejected(id, created.view(), "GENERATION_INVALID");
            }
            return StartAttempt.accepted(created.view());
        } finally {
            ordinary.unlock();
        }
    }

    public SessionView markRunning(String executionId) {
        return inSlot(executionId, ActiveSession::markRunning);
    }

    public SessionView beginPause(String executionId) {
        return inSlot(executionId, ActiveSession::beginPause);
    }

    public SessionView pauseCompleted(String executionId) {
        return inSlot(executionId, ActiveSession::pauseCompleted);
    }

    public SessionView requestStop(String executionId) {
        String id = requireId(executionId);
        ordinary.lock();
        try {
            ActiveSession session = locate(id);
            if (session.status().terminal()) {
                return session.view();
            }
            session.markUserStop();
            clearIfQuiescent(session);
            return viewOf(id);
        } finally {
            ordinary.unlock();
        }
    }

    public LoadWorker openWorker(String executionId) {
        String id = requireId(executionId);
        ordinary.lock();
        try {
            ActiveSession session = slot.get();
            if (session == null || !session.executionId().equals(id)) {
                throw new IllegalArgumentException("执行实例不在槽位中");
            }
            if (!session.epoch().live() || !session.acceptsLoad()) {
                return LoadWorker.inactive(session.epoch());
            }
            LoadWorker worker = session.registerWorker();
            if (!session.epoch().live() || !session.acceptsLoad()) {
                worker.dropReferences();
                worker.acknowledgeExit();
            }
            return worker;
        } finally {
            ordinary.unlock();
        }
    }

    /**
     * 立即使旧 generation 失效。不获取普通操作锁，不读取或写入数据库。
     */
    public EmergencyEffect emergencyStop() {
        SessionGeneration.Epoch next = generations.invalidate();
        ActiveSession session = slot.get();
        if (session == null) {
            return new EmergencyEffect(next.generation(), null, null);
        }
        session.epoch().stop();
        session.markEmergency();
        clearIfQuiescent(session);
        SessionView view = session.view();
        return new EmergencyEffect(next.generation(), view.executionId(), view.status());
    }

    public Optional<SessionView> active() {
        ActiveSession session = slot.get();
        if (session == null) {
            return Optional.empty();
        }
        return Optional.of(session.view());
    }

    public Optional<SessionView> find(String executionId) {
        ActiveSession session = slot.get();
        if (session != null && session.executionId().equals(executionId)) {
            return Optional.of(session.view());
        }
        ActiveSession finished = last.get();
        if (finished != null && finished.executionId().equals(executionId)) {
            return Optional.of(finished.view());
        }
        return Optional.empty();
    }

    void inOrdinaryQueue(Runnable action) {
        ordinary.lock();
        try {
            action.run();
        } finally {
            ordinary.unlock();
        }
    }

    boolean ordinaryBusy() {
        return ordinary.isLocked() && !ordinary.isHeldByCurrentThread();
    }

    private SessionView inSlot(String executionId, java.util.function.Consumer<ActiveSession> change) {
        String id = requireId(executionId);
        ordinary.lock();
        try {
            ActiveSession session = requireSlot(id);
            change.accept(session);
            return session.view();
        } finally {
            ordinary.unlock();
        }
    }

    private ActiveSession requireSlot(String executionId) {
        ActiveSession session = slot.get();
        if (session == null || !session.executionId().equals(executionId)) {
            throw new IllegalArgumentException("执行实例不在槽位中");
        }
        return session;
    }

    private ActiveSession locate(String executionId) {
        ActiveSession session = slot.get();
        if (session != null && session.executionId().equals(executionId)) {
            return session;
        }
        ActiveSession finished = last.get();
        if (finished != null && finished.executionId().equals(executionId)) {
            return finished;
        }
        throw new IllegalArgumentException("执行实例不存在");
    }

    private SessionView viewOf(String executionId) {
        return locate(executionId).view();
    }

    private ActiveSession detachIfFinished(ActiveSession current) {
        if (current == null) {
            return null;
        }
        if (current.status().terminal() && LoadAdmission.quiescent(current.workersExited(), current.heldBytes())) {
            slot.compareAndSet(current, null);
            return slot.get();
        }
        return current;
    }

    private void clearIfQuiescent(ActiveSession session) {
        if (!session.releaseIfQuiescent()) {
            return;
        }
        if (slot.compareAndSet(session, null)) {
            last.set(session);
        }
    }

    private static String requireId(String executionId) {
        if (executionId == null || executionId.isBlank()) {
            throw new IllegalArgumentException("执行实例编号不能为空");
        }
        return executionId;
    }

    public record SessionView(
            String executionId,
            ExecutionStatus status,
            long generation,
            long heldBytes,
            int openWorkers) {
    }

    public record StartAttempt(
            boolean accepted,
            String requestedId,
            String executionId,
            ExecutionStatus status,
            long generation,
            String reason) {

        static StartAttempt accepted(SessionView view) {
            return new StartAttempt(true, view.executionId(), view.executionId(), view.status(), view.generation(), null);
        }

        static StartAttempt rejected(String requestedId, SessionView occupant, String reason) {
            if (occupant == null) {
                return new StartAttempt(false, requestedId, null, null, 0, reason);
            }
            return new StartAttempt(false, requestedId, occupant.executionId(), occupant.status(), occupant.generation(), reason);
        }
    }

    public record EmergencyEffect(long generation, String executionId, ExecutionStatus status) {
    }
}
