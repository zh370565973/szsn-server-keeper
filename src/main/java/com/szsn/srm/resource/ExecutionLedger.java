package com.szsn.srm.resource;

import com.szsn.srm.monitor.MetricSnapshot;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Types;
import java.time.Clock;
import java.util.concurrent.TimeUnit;
import javax.sql.DataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * 执行实例落库。状态变更立即写入；运行中每 30 秒写心跳、单调时钟时长和统计摘要。
 * 配置快照只在启动事务里写入，这里不改它。不扫描计划，也不启动负载。
 */
@Component
public class ExecutionLedger implements OrdinaryJournal {
    public static final long HEARTBEAT_MS = 30_000L;

    private static final Logger log = LoggerFactory.getLogger(ExecutionLedger.class);
    private static final String OPEN_STATUSES = "'STOPPED', 'COMPLETED', 'SAFETY_STOPPED', 'FAILED', 'INTERRUPTED', 'SKIPPED'";

    private final JdbcTemplate jdbc;
    private final Clock clock;
    private final NanoSource nanos;
    private final Object monitor = new Object();
    private Live live;

    @Autowired
    public ExecutionLedger(DataSource dataSource, Clock clock) {
        this(dataSource, clock, System::nanoTime);
    }

    public ExecutionLedger(DataSource dataSource, Clock clock, NanoSource nanos) {
        this.jdbc = new JdbcTemplate(dataSource);
        this.clock = clock;
        this.nanos = nanos == null ? System::nanoTime : nanos;
    }

    @Override
    public void record(String executionId, ExecutionStatus status) {
        long now = nanos.nanoTime();
        synchronized (monitor) {
            if (status == ExecutionStatus.STARTING) {
                return;
            }
            if (status == ExecutionStatus.RUNNING) {
                openRunning(executionId, now);
            } else if (live != null && live.executionId.equals(executionId)) {
                syncPause(live, status, now);
            }
            if (status.terminal()) {
                seal(executionId, status, now);
                return;
            }
            writeOpenStatus(executionId, status);
        }
    }

    @Override
    public void recordLater(String executionId, ExecutionStatus status) {
        Thread thread = new Thread(() -> {
            try {
                record(executionId, status);
            } catch (RuntimeException exception) {
                log.warn("执行终态写入失败");
            }
        }, "srm-execution-seal");
        thread.setDaemon(true);
        thread.start();
    }

    /**
     * 用调用方给出的单调时间推进采样和心跳。没有活动会话或尚未进入 RUNNING 时不写心跳。
     */
    public void tick(long nanoTime, SessionView session, MetricSnapshot snapshot) {
        synchronized (monitor) {
            if (session == null || session.status().terminal() || live == null
                    || !live.executionId.equals(session.executionId())) {
                return;
            }
            syncPause(live, session.status(), nanoTime);
            long gapMs = TimeUnit.NANOSECONDS.toMillis(Math.max(0, nanoTime - live.lastSampleNanos));
            live.lastSampleNanos = nanoTime;
            if (snapshot != null && gapMs > 0) {
                Double cpu = snapshot.cpu() == null ? null : snapshot.cpu().overallBusyPct();
                Long systemUsed = snapshot.memory() == null ? null : snapshot.memory().usedBytes();
                live.stats.add(gapMs, snapshot.quality(), cpu, systemUsed, session.heldBytes());
            }
            if (nanoTime - live.lastHeartbeatNanos < TimeUnit.MILLISECONDS.toNanos(HEARTBEAT_MS)) {
                return;
            }
            Durations durations = durations(live, nanoTime);
            writeHeartbeat(live.executionId, clock.millis(), durations, live.stats.toJson());
            live.lastHeartbeatNanos = nanoTime;
        }
    }

    private void openRunning(String executionId, long nanoTime) {
        if (live == null || !live.executionId.equals(executionId)) {
            live = Live.start(executionId, nanoTime);
            return;
        }
        syncPause(live, ExecutionStatus.RUNNING, nanoTime);
    }

    private void seal(String executionId, ExecutionStatus status, long nanoTime) {
        long wall = clock.millis();
        final boolean tracked = live != null && live.executionId.equals(executionId);
        final Durations trackedDurations = tracked ? durations(live, nanoTime) : null;
        final String statsJson = tracked ? live.stats.toJson() : null;
        final Long heartbeatAt = tracked ? wall : null;
        final Long elapsed = trackedDurations == null ? null : trackedDurations.elapsedMs;
        final Long active = trackedDurations == null ? null : trackedDurations.activeMs;
        jdbc.update("""
                UPDATE TASK_EXECUTION
                SET STATUS = ?,
                    ENDED_AT = COALESCE(ENDED_AT, ?),
                    ELAPSED_DURATION_MS = COALESCE(?, ELAPSED_DURATION_MS),
                    ACTIVE_DURATION_MS = COALESCE(?, ACTIVE_DURATION_MS),
                    STATS_JSON = COALESCE(?, STATS_JSON),
                    LAST_HEARTBEAT_AT = COALESCE(?, LAST_HEARTBEAT_AT),
                    REASON = COALESCE(REASON, ?),
                    VERSION = VERSION + 1
                WHERE ID = ?
                  AND STATUS NOT IN (%s)
                """.formatted(OPEN_STATUSES), (PreparedStatement statement) -> {
            statement.setString(1, status.name());
            statement.setLong(2, wall);
            setLong(statement, 3, elapsed);
            setLong(statement, 4, active);
            if (statsJson == null) {
                statement.setNull(5, Types.CLOB);
            } else {
                statement.setString(5, statsJson);
            }
            setLong(statement, 6, heartbeatAt);
            statement.setString(7, reasonFor(status));
            statement.setString(8, executionId);
        });
        if (tracked) {
            live = null;
        }
    }

    private void writeOpenStatus(String executionId, ExecutionStatus status) {
        Long startedAt = status == ExecutionStatus.RUNNING ? clock.millis() : null;
        jdbc.update("""
                UPDATE TASK_EXECUTION
                SET STATUS = ?,
                    STARTED_AT = COALESCE(STARTED_AT, ?),
                    VERSION = VERSION + 1
                WHERE ID = ?
                  AND STATUS NOT IN (%s)
                """.formatted(OPEN_STATUSES), (PreparedStatement statement) -> {
            statement.setString(1, status.name());
            setLong(statement, 2, startedAt);
            statement.setString(3, executionId);
        });
    }

    private void writeHeartbeat(String executionId, long wallMillis, Durations durations, String statsJson) {
        jdbc.update("""
                UPDATE TASK_EXECUTION
                SET LAST_HEARTBEAT_AT = ?,
                    ELAPSED_DURATION_MS = ?,
                    ACTIVE_DURATION_MS = ?,
                    STATS_JSON = ?,
                    VERSION = VERSION + 1
                WHERE ID = ?
                  AND STATUS NOT IN (%s)
                """.formatted(OPEN_STATUSES), wallMillis, durations.elapsedMs, durations.activeMs, statsJson, executionId);
    }

    private static void syncPause(Live live, ExecutionStatus status, long nanoTime) {
        boolean pausing = status == ExecutionStatus.PAUSED || status == ExecutionStatus.PAUSING;
        if (pausing && !live.paused) {
            live.paused = true;
            live.pauseMarkNanos = nanoTime;
            return;
        }
        if (!pausing && live.paused) {
            live.pausedNanos += Math.max(0, nanoTime - live.pauseMarkNanos);
            live.paused = false;
            live.pauseMarkNanos = null;
        }
    }

    private static Durations durations(Live live, long nanoTime) {
        long paused = live.pausedNanos;
        if (live.paused && live.pauseMarkNanos != null) {
            paused += Math.max(0, nanoTime - live.pauseMarkNanos);
        }
        long elapsedNanos = Math.max(0, nanoTime - live.originNanos);
        long activeNanos = Math.max(0, elapsedNanos - paused);
        return new Durations(
                TimeUnit.NANOSECONDS.toMillis(elapsedNanos),
                TimeUnit.NANOSECONDS.toMillis(activeNanos));
    }

    private static String reasonFor(ExecutionStatus status) {
        return switch (status) {
            case STOPPED -> "USER_STOP";
            case COMPLETED -> "DURATION_ELAPSED";
            case SAFETY_STOPPED -> "SAFETY_STOP";
            case FAILED -> "FAILED";
            case INTERRUPTED -> ExecutionRecovery.REASON;
            case SKIPPED -> "SKIPPED";
            default -> null;
        };
    }

    private static void setLong(PreparedStatement statement, int index, Long value) throws SQLException {
        if (value == null) {
            statement.setNull(index, Types.BIGINT);
        } else {
            statement.setLong(index, value);
        }
    }

    public interface NanoSource {
        long nanoTime();
    }

    private static final class Live {
        private final String executionId;
        private final long originNanos;
        private final ExecutionStats stats = new ExecutionStats();
        private long lastHeartbeatNanos;
        private long lastSampleNanos;
        private long pausedNanos;
        private Long pauseMarkNanos;
        private boolean paused;

        private Live(String executionId, long originNanos) {
            this.executionId = executionId;
            this.originNanos = originNanos;
            this.lastHeartbeatNanos = originNanos;
            this.lastSampleNanos = originNanos;
        }

        private static Live start(String executionId, long originNanos) {
            return new Live(executionId, originNanos);
        }
    }

    private record Durations(long elapsedMs, long activeMs) {
    }
}
