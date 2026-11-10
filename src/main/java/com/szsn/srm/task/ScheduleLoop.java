package com.szsn.srm.task;

import com.szsn.srm.resource.ExecutionRecovery;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;

/**
 * 独立的计划扫描线程，每秒调用一次 {@link ScheduleDispatcher}。
 * 不与安全检查、采集或 CPU Worker 共用执行器。启动前依赖重启恢复，避免残留非终态被当成新的槽位。
 */
@Component
public class ScheduleLoop implements SmartLifecycle {
    public static final String THREAD_NAME = "srm-schedule";

    private static final Logger log = LoggerFactory.getLogger(ScheduleLoop.class);

    private final ScheduleDispatcher dispatcher;
    private ScheduledExecutorService executor;
    private volatile boolean running;

    public ScheduleLoop(ScheduleDispatcher dispatcher, ExecutionRecovery recovery) {
        if (recovery == null) {
            throw new IllegalArgumentException("重启恢复尚未完成");
        }
        this.dispatcher = dispatcher;
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

    private void tick() {
        try {
            dispatcher.scan();
        } catch (RuntimeException exception) {
            log.warn("计划扫描失败", exception);
        }
    }
}
