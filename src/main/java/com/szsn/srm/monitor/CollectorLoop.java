package com.szsn.srm.monitor;

import com.szsn.srm.history.HistoryService;
import com.szsn.srm.resource.ControlState;
import com.szsn.srm.resource.ControlSwitch;
import com.szsn.srm.safety.SafetyLoop;
import com.szsn.srm.safety.SafetySignal;
import com.szsn.srm.system.EnvironmentProbe;
import com.szsn.srm.system.EnvironmentReport;
import com.szsn.srm.system.StorageHealth;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;

@Component
public class CollectorLoop implements SmartLifecycle {
    private static final Logger log = LoggerFactory.getLogger(CollectorLoop.class);

    private final OshiMetricSampler sampler;
    private final SnapshotStore store;
    private final SseHub hub;
    private final HistoryService history;
    private final EnvironmentProbe probe;
    private final StorageHealth storageHealth;
    private final ControlSwitch controlSwitch;
    private final SafetyLoop safetyLoop;
    private final String dataDir;
    private final AtomicLong sequence = new AtomicLong();
    private final String bootId = UUID.randomUUID().toString();
    private ScheduledExecutorService executor;
    private volatile boolean running;
    private volatile EnvironmentReport environment;
    private int ticks;

    public CollectorLoop(OshiMetricSampler sampler, SnapshotStore store, SseHub hub, HistoryService history,
                         EnvironmentProbe probe, StorageHealth storageHealth, ControlSwitch controlSwitch,
                         SafetyLoop safetyLoop, com.szsn.srm.common.SrmProperties properties) {
        this.sampler = sampler;
        this.store = store;
        this.hub = hub;
        this.history = history;
        this.probe = probe;
        this.storageHealth = storageHealth;
        this.controlSwitch = controlSwitch;
        this.safetyLoop = safetyLoop;
        this.dataDir = properties.getDataDir();
    }

    public String bootId() {
        return bootId;
    }

    public EnvironmentReport environment() {
        EnvironmentReport current = environment;
        if (current == null) {
            current = probe.probe(Path.of(dataDir));
            environment = current;
            noteDisk(current);
        }
        return current;
    }

    @Override
    public void start() {
        executor = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "srm-monitor");
            thread.setDaemon(true);
            return thread;
        });
        executor.scheduleAtFixedRate(this::tick, 0, 1, TimeUnit.SECONDS);
        running = true;
    }

    @Override
    public void stop() {
        running = false;
        history.flush();
        hub.shutdown();
        if (executor != null) {
            executor.shutdownNow();
        }
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    private void tick() {
        try {
            if (environment == null || ticks % 30 == 0) {
                environment = probe.probe(Path.of(dataDir));
                noteDisk(environment);
            }
            ticks++;
            MetricSnapshot snapshot = sampler.sample(sequence.incrementAndGet(), bootId);
            store.add(snapshot);
            history.accept(snapshot);
            EnvironmentReport report = environment;
            ControlState control = controlSwitch.state();
            SafetySignal safety = safetyLoop.current();
            List<String> reasons = new ArrayList<>();
            reasons.addAll(report.controlDisabledReasons());
            if (!storageHealth.healthy() && storageHealth.fault() != null) {
                reasons.add(storageHealth.fault());
            }
            if (control.emergencyLatched()) {
                reasons.add("急停已锁存");
            }
            if ("OFF".equals(control.controlSwitch())) {
                reasons.add("控制开关已关闭");
            }
            boolean capable = report.controlCapable() && storageHealth.healthy();
            hub.publish(new SseHub.LiveFrame(
                    snapshot,
                    new SseHub.ResourcePulse(control.controlSwitch(), control.emergencyLatched(), capable, List.copyOf(reasons)),
                    toPulse(snapshot, safety)));
        } catch (Throwable throwable) {
            log.warn("采集周期失败");
        }
    }

    private void noteDisk(EnvironmentReport report) {
        if (report.controlDisabledReasons().contains("数据盘剩余空间不足")) {
            storageHealth.markFault("数据盘剩余空间不足");
        }
    }

    private static SseHub.SafetyPulse toPulse(MetricSnapshot snapshot, SafetySignal safety) {
        if (safety == null) {
            return new SseHub.SafetyPulse(snapshot.quality(), snapshot.ageMs(), true, null);
        }
        return new SseHub.SafetyPulse(
                snapshot.quality(),
                snapshot.ageMs(),
                safety.storageHealthy(),
                safety.storageFault(),
                safety.forbidCpuGrowth(),
                safety.zeroDuty(),
                safety.forbidMemoryGrowth(),
                safety.safetyStop(),
                safety.latch(),
                safety.reasons());
    }
}
