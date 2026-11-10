package com.szsn.srm.resource;

import com.szsn.srm.monitor.SnapshotStore;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;

/**
 * 独立线程读取当前会话和最新快照，交给 {@link ExecutionLedger} 写心跳。
 * 不与采集、安全检查或 CPU Worker 共用队列，也不扫描计划。
 */
@Component
public class ExecutionHeartbeatLoop implements SmartLifecycle {
    private static final Logger log = LoggerFactory.getLogger(ExecutionHeartbeatLoop.class);

    private final ExecutionLedger ledger;
    private final ResourceLoadManager sessions;
    private final SnapshotStore store;
    private ScheduledExecutorService executor;
    private volatile boolean running;

    public ExecutionHeartbeatLoop(ExecutionLedger ledger, ResourceLoadManager sessions, SnapshotStore store) {
        this.ledger = ledger;
        this.sessions = sessions;
        this.store = store;
    }

    @Override
    public void start() {
        executor = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "srm-execution-heartbeat");
            thread.setDaemon(true);
            return thread;
        });
        executor.scheduleAtFixedRate(this::tick, 1, 1, TimeUnit.SECONDS);
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

    private void tick() {
        try {
            ledger.tick(System.nanoTime(), sessions.active().orElse(null), store.latest());
        } catch (RuntimeException exception) {
            log.warn("执行心跳写入失败");
        }
    }
}
