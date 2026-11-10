package com.szsn.srm.safety;

import com.szsn.srm.monitor.MetricSnapshot;
import com.szsn.srm.monitor.SnapshotStore;
import com.szsn.srm.resource.ControlSwitch;
import com.szsn.srm.resource.Operator;
import com.szsn.srm.system.StorageHealth;
import java.time.Clock;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;

/**
 * 独立安全检查线程。不与采集、历史写入、SSE 或未来的 CPU Worker 共用执行器。
 * 阈值窗口使用 {@link ProtectionEvaluator}；锁存动作走 {@link ControlSwitch#safetyLatch}。
 * 本类不启动 CPU 引擎，也不分配内存。
 */
@Component
public class SafetyLoop implements SmartLifecycle {
    public static final String THREAD_NAME = "srm-safety";
    public static final String STORAGE_REASON = "STORAGE_UNAVAILABLE";

    private static final Logger log = LoggerFactory.getLogger(SafetyLoop.class);

    private final SnapshotStore store;
    private final ControlSwitch controlSwitch;
    private final LatchHealthWindow healthWindow;
    private final StorageHealth storageHealth;
    private final Clock clock;
    private final ProtectionEvaluator evaluator;
    private final SwapRateWindow swapRate = new SwapRateWindow();
    private final AtomicReference<SafetySignal> signal = new AtomicReference<>(
            SafetySignal.unavailable(0, true, null));

    private ScheduledExecutorService executor;
    private volatile boolean running;
    private volatile String lastLatchReason;

    @Autowired
    public SafetyLoop(SnapshotStore store, ControlSwitch controlSwitch, LatchHealthWindow healthWindow,
                      StorageHealth storageHealth, Clock clock) {
        this(store, controlSwitch, healthWindow, storageHealth, clock, new ProtectionEvaluator());
    }

    SafetyLoop(SnapshotStore store, ControlSwitch controlSwitch, LatchHealthWindow healthWindow,
               StorageHealth storageHealth, Clock clock, ProtectionEvaluator evaluator) {
        this.store = store;
        this.controlSwitch = controlSwitch;
        this.healthWindow = healthWindow;
        this.storageHealth = storageHealth;
        this.clock = clock;
        this.evaluator = evaluator == null ? new ProtectionEvaluator() : evaluator;
    }

    public SafetySignal current() {
        return signal.get();
    }

    @Override
    public void start() {
        executor = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, THREAD_NAME);
            thread.setDaemon(true);
            return thread;
        });
        executor.scheduleAtFixedRate(this::tick, 0, 1, TimeUnit.SECONDS);
        running = true;
    }

    @Override
    public void stop() {
        running = false;
        if (executor != null) {
            executor.shutdownNow();
        }
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    /**
     * 供测试直接注入一份快照并同步评估，不经过调度线程。
     */
    void evaluate(MetricSnapshot raw) {
        long now = clock.millis();
        boolean storageOk = storageHealth.healthy();
        String storageFault = storageHealth.fault();
        if (raw == null) {
            signal.set(SafetySignal.unavailable(now, storageOk, storageFault));
            reactStorage(storageOk, storageFault);
            return;
        }
        MetricSnapshot snapshot = raw.freshen(now);
        Long swapUsed = snapshot.memory() == null ? null : snapshot.memory().swapUsedBytes();
        Long swapTotal = snapshot.memory() == null ? null : snapshot.memory().swapTotalBytes();
        boolean swapPresent = swapTotal != null && swapTotal > 0;
        Double rate = swapRate.observe(swapPresent, swapUsed, snapshot.observedAt());
        ProtectionDecision decision = evaluator.onSample(ProtectionSamples.protection(snapshot, rate));
        healthWindow.observe(ProtectionSamples.keyMetric(snapshot, rate));
        SafetySignal next = new SafetySignal(
                now,
                snapshot.sequence(),
                snapshot.ageMs(),
                snapshot.quality(),
                storageOk,
                storageFault,
                decision.forbidCpuGrowth(),
                decision.zeroDuty(),
                decision.forbidMemoryGrowth(),
                decision.safetyStop(),
                decision.latch(),
                decision.reasons());
        signal.set(next);
        if (!storageOk) {
            reactStorage(false, storageFault);
            return;
        }
        if (decision.latch() && decision.safetyStop()) {
            latch(primaryReason(decision.reasons()));
        }
    }

    private void tick() {
        try {
            evaluate(store.latest());
        } catch (Throwable throwable) {
            log.warn("安全检查周期失败");
        }
    }

    private void reactStorage(boolean storageOk, String storageFault) {
        if (storageOk) {
            return;
        }
        latch(STORAGE_REASON);
    }

    private void latch(String reason) {
        String normalized = reason == null || reason.isBlank() ? "SAFETY_STOP" : reason;
        if (normalized.equals(lastLatchReason) && controlSwitch.state().emergencyLatched()) {
            return;
        }
        try {
            controlSwitch.safetyLatch(normalized, Operator.system());
            lastLatchReason = normalized;
        } catch (RuntimeException exception) {
            log.warn("安全锁存未能完成");
        }
    }

    private static String primaryReason(List<String> reasons) {
        if (reasons == null || reasons.isEmpty()) {
            return "SAFETY_STOP";
        }
        for (String reason : reasons) {
            if (reason != null && !reason.isBlank()) {
                return reason;
            }
        }
        return "SAFETY_STOP";
    }
}
