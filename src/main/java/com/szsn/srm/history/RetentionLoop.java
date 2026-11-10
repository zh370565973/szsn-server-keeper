package com.szsn.srm.history;

import java.time.Clock;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;

/**
 * 独立线程每小时跑一次 {@link RetentionCleaner}。不与采集、安全检查、CPU Worker 或计划扫描共用队列。
 */
@Component
public class RetentionLoop implements SmartLifecycle {
    public static final String THREAD_NAME = "srm-retention";

    private static final Logger log = LoggerFactory.getLogger(RetentionLoop.class);

    private final RetentionCleaner cleaner;
    private final Clock clock;
    private ScheduledExecutorService executor;
    private volatile boolean running;

    public RetentionLoop(RetentionCleaner cleaner, Clock clock) {
        this.cleaner = cleaner;
        this.clock = clock;
    }

    @Override
    public void start() {
        executor = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, THREAD_NAME);
            thread.setDaemon(true);
            return thread;
        });
        executor.scheduleAtFixedRate(this::tick, 0, 1, TimeUnit.HOURS);
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
            cleaner.purge(clock.millis());
        } catch (RuntimeException exception) {
            log.warn("历史与日志清理失败", exception);
        }
    }
}
