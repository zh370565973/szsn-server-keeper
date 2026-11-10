package com.szsn.srm.resource;

import com.szsn.srm.common.ApiException;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import javax.sql.DataSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 已获准的手动启动先校验配置，再提交 STARTING 和配置快照，事务成功后才调用启动动作。
 * 时长、系统占用目标或内存字节未按 PRD 对齐时在落库前拒绝。
 * 开关关闭或急停锁存时直接拒绝，不写执行记录。事务失败、参数冲突和 24 小时内的重放都不会再次启动。
 * 本类不运行 CPU/内存引擎。公开 HTTP 由 {@link CpuControlService} 编排；非 Linux 或环境不可控时仍返回 503。
 */
@Service
public class ExecutionStartService {
    static final long IDEMPOTENCY_WINDOW_MS = Duration.ofHours(24).toMillis();
    private static final String START_FAILED = "START_FAILED";

    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactionTemplate;
    private final Clock clock;
    private final StartGate startGate;

    public ExecutionStartService(DataSource dataSource, PlatformTransactionManager transactions, Clock clock) {
        this(dataSource, transactions, clock, StartGate.OPEN);
    }

    @Autowired
    public ExecutionStartService(DataSource dataSource, PlatformTransactionManager transactions, Clock clock,
                                 StartGate startGate) {
        this.jdbc = new JdbcTemplate(dataSource);
        this.transactionTemplate = new TransactionTemplate(transactions);
        this.clock = clock;
        this.startGate = startGate == null ? StartGate.OPEN : startGate;
    }

    public StartedExecution start(String idempotencyKey, String configJson, WorkerLaunch launch) {
        if (launch == null) {
            throw new IllegalArgumentException("启动动作不能为空");
        }
        startGate.admit();
        StartConfig.Prepared prepared = StartConfig.prepare(idempotencyKey, configJson);
        PersistResult saved = admitWithRetry(prepared);
        if (!saved.fresh()) {
            return saved.view();
        }
        try {
            launch.launch(new CommittedStart(saved.executionId(), saved.snapshotJson(), saved.version()));
        } catch (RuntimeException exception) {
            try {
                markFailed(saved.executionId());
            } catch (RuntimeException persistFailure) {
                exception.addSuppressed(persistFailure);
            }
            throw exception;
        }
        return saved.view();
    }

    private PersistResult admitWithRetry(StartConfig.Prepared prepared) {
        DuplicateKeyException last = null;
        for (int attempt = 0; attempt < 3; attempt++) {
            try {
                return transactionTemplate.execute(status -> admit(prepared));
            } catch (DuplicateKeyException exception) {
                last = exception;
            }
        }
        throw last;
    }

    private PersistResult admit(StartConfig.Prepared prepared) {
        DedupRow existing = lockDedup(prepared.key());
        long now = clock.millis();
        if (existing != null && existing.expiresAt() > now) {
            if (!existing.requestHash().equals(prepared.hash())) {
                throw new ApiException(HttpStatus.CONFLICT, "IDEMPOTENCY_CONFLICT", "相同幂等键对应的请求内容不同");
            }
            return load(existing.executionId());
        }
        String executionId = UUID.randomUUID().toString();
        jdbc.update("""
                INSERT INTO TASK_EXECUTION (
                    ID, CONFIG_SNAPSHOT_JSON, STATUS, VERSION, IDEMPOTENCY_KEY, REQUEST_HASH, REQUEST_SOURCE
                ) VALUES (?, ?, ?, 1, ?, ?, 'MANUAL')
                """, executionId, prepared.snapshotJson(), ExecutionStatus.STARTING.name(), prepared.key(), prepared.hash());
        long expiresAt = now + IDEMPOTENCY_WINDOW_MS;
        if (existing == null) {
            jdbc.update("""
                    INSERT INTO REQUEST_DEDUP (IDEMPOTENCY_KEY, REQUEST_HASH, EXECUTION_ID, CREATED_AT, EXPIRES_AT)
                    VALUES (?, ?, ?, ?, ?)
                    """, prepared.key(), prepared.hash(), executionId, now, expiresAt);
        } else {
            jdbc.update("""
                    UPDATE REQUEST_DEDUP
                    SET REQUEST_HASH = ?, EXECUTION_ID = ?, CREATED_AT = ?, EXPIRES_AT = ?
                    WHERE IDEMPOTENCY_KEY = ?
                    """, prepared.hash(), executionId, now, expiresAt, prepared.key());
        }
        return new PersistResult(executionId, ExecutionStatus.STARTING, 1, prepared.snapshotJson(), true);
    }

    private PersistResult load(String executionId) {
        List<PersistResult> rows = jdbc.query("""
                SELECT STATUS, VERSION, CONFIG_SNAPSHOT_JSON
                FROM TASK_EXECUTION
                WHERE ID = ?
                """, (rs, row) -> new PersistResult(
                executionId,
                ExecutionStatus.valueOf(rs.getString(1)),
                rs.getLong(2),
                rs.getString(3),
                false), executionId);
        if (rows.isEmpty()) {
            throw new IllegalStateException("幂等记录缺少执行实例");
        }
        return rows.get(0);
    }

    private DedupRow lockDedup(String key) {
        List<DedupRow> rows = jdbc.query("""
                SELECT REQUEST_HASH, EXECUTION_ID, EXPIRES_AT
                FROM REQUEST_DEDUP
                WHERE IDEMPOTENCY_KEY = ?
                FOR UPDATE
                """, (rs, row) -> new DedupRow(rs.getString(1), rs.getString(2), rs.getLong(3)), key);
        return rows.isEmpty() ? null : rows.get(0);
    }

    private void markFailed(String executionId) {
        transactionTemplate.executeWithoutResult(status -> {
            List<String> rows = jdbc.query("SELECT STATUS FROM TASK_EXECUTION WHERE ID = ?",
                    (rs, row) -> rs.getString(1), executionId);
            if (rows.isEmpty() || ExecutionStatus.valueOf(rows.get(0)) != ExecutionStatus.STARTING) {
                return;
            }
            ExecutionStatus failed = ExecutionTransitions.startFailed(ExecutionStatus.STARTING);
            jdbc.update("""
                    UPDATE TASK_EXECUTION
                    SET STATUS = ?, REASON = ?, ENDED_AT = ?, VERSION = VERSION + 1
                    WHERE ID = ? AND STATUS = 'STARTING'
                    """, failed.name(), START_FAILED, clock.millis(), executionId);
        });
    }

    private record DedupRow(String requestHash, String executionId, long expiresAt) {
    }

    private record PersistResult(String executionId, ExecutionStatus status, long version, String snapshotJson,
            boolean fresh) {
        private StartedExecution view() {
            return new StartedExecution(executionId, status, version, snapshotJson, !fresh);
        }
    }
}
