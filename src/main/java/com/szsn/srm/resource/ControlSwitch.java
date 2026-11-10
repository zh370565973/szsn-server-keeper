package com.szsn.srm.resource;

import com.szsn.srm.common.ApiException;
import com.szsn.srm.safety.LatchHealthWindow;
import com.szsn.srm.system.StorageHealth;
import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

/**
 * 控制开关只留在内存里，每次进程启动都是 OFF。急停先使 Worker 失效并锁存，再尝试写库。
 * 写库失败不撤销停止。解除锁存只能人工调用，且要求无活动会话、连续 30 秒关键指标健康、数据库可写。
 * 本类不运行 CPU 引擎，也不分配内存。
 */
@Service
public class ControlSwitch implements StartGate {
    static final String EMERGENCY_REASON = "EMERGENCY_STOP";
    static final String UNREAD_REASON = "LATCH_UNREAD";

    private static final Logger log = LoggerFactory.getLogger(ControlSwitch.class);

    private final ResourceLoadManager sessions;
    private final LatchStore store;
    private final LatchHealthWindow health;
    private final StorageHealth storageHealth;
    private final Clock clock;
    private final ControlAudit audit;
    private final Object stateLock = new Object();
    private final Object persistLock = new Object();

    private boolean enabled;
    private boolean latched;
    private String reason;
    private long latchedAt;
    private long epoch;
    private boolean persisted = true;

    public ControlSwitch(ResourceLoadManager sessions, LatchStore store, LatchHealthWindow health,
                         StorageHealth storageHealth, Clock clock, ControlAudit audit) {
        this.sessions = sessions;
        this.store = store;
        this.health = health;
        this.storageHealth = storageHealth;
        this.clock = clock;
        this.audit = audit == null ? ControlAudit.NONE : audit;
        reload();
    }

    @Override
    public void admit() {
        synchronized (stateLock) {
            if (latched) {
                throw new ApiException(HttpStatus.CONFLICT, "CONTROL_LATCHED", "急停锁存未解除，不能启动");
            }
            if (!enabled) {
                throw new ApiException(HttpStatus.CONFLICT, "CONTROL_DISABLED", "控制开关已关闭，不能启动");
            }
        }
    }

    public ControlState emergencyStop(Operator operator) {
        return latchAndStop(EMERGENCY_REASON, operator, "EMERGENCY_STOP");
    }

    /**
     * 安全线程触发的锁存。先使 Worker 失效并关闭开关，再尝试持久化；写库失败不撤销停止。
     */
    public ControlState safetyLatch(String latchReason, Operator operator) {
        String normalized = latchReason == null || latchReason.isBlank() ? "SAFETY_STOP" : latchReason;
        return latchAndStop(normalized, operator, "SAFETY_LATCH");
    }

    private ControlState latchAndStop(String latchReason, Operator operator, String auditAction) {
        boolean write;
        synchronized (stateLock) {
            if (!latched) {
                latched = true;
                reason = latchReason;
                latchedAt = clock.millis();
                epoch++;
                persisted = false;
            }
            enabled = false;
            sessions.emergencyStop();
            write = !persisted;
        }
        if (write && !flush()) {
            log.warn("急停锁存未能写入数据库，本进程保持锁存，下次启动开关仍为关闭");
        }
        ControlState state = state();
        audit(operator, auditAction, "SUCCESS", state.latchPersisted() ? null : "LATCH_PERSIST_FAILED");
        return state;
    }

    public ControlState setEnabled(boolean enabled, Operator operator) {
        String executionId = null;
        boolean rejected = false;
        synchronized (stateLock) {
            if (enabled && latched) {
                rejected = true;
            } else {
                this.enabled = enabled;
                if (!enabled) {
                    executionId = sessions.active().map(SessionView::executionId).orElse(null);
                }
            }
        }
        if (rejected) {
            audit(operator, "CONTROL_SWITCH_ON", "FAILURE", "CONTROL_LATCHED");
            throw new ApiException(HttpStatus.CONFLICT, "CONTROL_LATCHED", "急停仍处于锁存，打开开关不会解除锁存");
        }
        if (executionId != null) {
            try {
                sessions.requestStop(executionId, StopKind.USER);
            } catch (RuntimeException exception) {
                log.warn("关闭开关时停止活动会话未完成记录，停止标志已经设置");
            }
        }
        audit(operator, enabled ? "CONTROL_SWITCH_ON" : "CONTROL_SWITCH_OFF", "SUCCESS", null);
        return state();
    }

    public ControlState resetLatch(Operator operator) {
        StoredLatch previous;
        synchronized (stateLock) {
            if (!latched) {
                previous = null;
            } else {
                ensureResetAllowed();
                previous = snapshot();
                latched = false;
                reason = null;
                latchedAt = clock.millis();
                enabled = false;
                epoch++;
                persisted = false;
            }
        }
        if (previous == null) {
            audit(operator, "RESET_LATCH", "SUCCESS", "NOT_LATCHED");
            return state();
        }
        boolean wrote = flush();
        boolean relatched = false;
        boolean storageFailed = false;
        synchronized (stateLock) {
            if (latched) {
                relatched = true;
            } else if (!wrote || !persisted) {
                latched = true;
                reason = previous.reason();
                latchedAt = previous.at();
                enabled = false;
                epoch++;
                persisted = false;
                storageFailed = true;
            }
        }
        if (relatched) {
            audit(operator, "RESET_LATCH", "FAILURE", "CONTROL_LATCHED");
            throw new ApiException(HttpStatus.CONFLICT, "CONTROL_LATCHED", "解除期间再次急停，锁存仍然有效");
        }
        if (storageFailed) {
            audit(operator, "RESET_LATCH", "FAILURE", "STORAGE_UNAVAILABLE");
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "STORAGE_UNAVAILABLE", "数据库不可写，锁存未解除");
        }
        audit(operator, "RESET_LATCH", "SUCCESS", null);
        return state();
    }

    public ControlState state() {
        synchronized (stateLock) {
            return stateLocked();
        }
    }

    private void reload() {
        enabled = false;
        try {
            Optional<StoredLatch> loaded = store.load();
            if (loaded.isEmpty()) {
                latched = false;
                reason = null;
                latchedAt = 0;
                epoch = 0;
                persisted = true;
                return;
            }
            StoredLatch row = loaded.get();
            epoch = Math.max(0, row.epoch());
            latchedAt = row.at();
            persisted = true;
            if (row.latched()) {
                latched = true;
                reason = row.reason() == null ? EMERGENCY_REASON : row.reason();
            } else {
                latched = false;
                reason = null;
            }
        } catch (RuntimeException exception) {
            latched = true;
            reason = UNREAD_REASON;
            latchedAt = 0;
            persisted = false;
            log.warn("急停锁存无法读取，本进程按已锁存处理，控制开关保持关闭");
        }
    }

    private void ensureResetAllowed() {
        if (sessions.active().isPresent()) {
            throw new ApiException(HttpStatus.CONFLICT, "SESSION_ACTIVE", "仍有活动会话，不能解除锁存");
        }
        if (!storageHealth.healthy()) {
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "STORAGE_UNAVAILABLE", "数据库不可写，不能解除锁存");
        }
        LatchHealthWindow.View view = health.view();
        if (!view.ready()) {
            Map<String, Object> details = new LinkedHashMap<>();
            details.put("healthyForMs", view.healthyForMs());
            details.put("requiredMs", LatchHealthWindow.REQUIRED_MS);
            details.put("reasons", view.reasons());
            throw new ApiException(HttpStatus.CONFLICT, "METRICS_UNHEALTHY", "关键指标尚未连续健康 30 秒", details);
        }
    }

    private boolean flush() {
        synchronized (persistLock) {
            while (true) {
                StoredLatch record;
                synchronized (stateLock) {
                    record = snapshot();
                }
                try {
                    store.save(record);
                } catch (RuntimeException exception) {
                    synchronized (stateLock) {
                        if (epoch == record.epoch()) {
                            persisted = false;
                        }
                    }
                    return false;
                }
                synchronized (stateLock) {
                    if (epoch == record.epoch()) {
                        persisted = true;
                        return true;
                    }
                }
            }
        }
    }

    private StoredLatch snapshot() {
        return new StoredLatch(latched, reason, latchedAt, epoch);
    }

    private ControlState stateLocked() {
        LatchHealthWindow.View view = health.view();
        boolean sessionActive = sessions.active().isPresent();
        boolean ready = latched && !sessionActive && storageHealth.healthy() && view.ready();
        ControlState.ActiveExecution execution = sessions.active()
                .map(slot -> new ControlState.ActiveExecution(slot.executionId(), slot.status().name(), slot.heldBytes()))
                .orElse(null);
        return new ControlState(enabled ? "ON" : "OFF", latched, reason, persisted, view.healthyForMs(), view.reasons(),
                ready, storageHealth.healthy(), execution);
    }

    private void audit(Operator operator, String action, String result, String reason) {
        Operator who = operator == null ? Operator.system() : operator;
        try {
            audit.record(who.name(), who.sourceIp(), action, result, reason, who.requestId());
        } catch (RuntimeException exception) {
            log.warn("控制操作日志写入失败");
        }
    }
}
