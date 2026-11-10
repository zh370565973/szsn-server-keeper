package com.szsn.srm.task;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.szsn.srm.auth.OperationLogRepository;
import com.szsn.srm.common.ApiException;
import com.szsn.srm.common.PageResult;
import com.szsn.srm.resource.Operator;
import java.time.Clock;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 任务模板的创建、修改和软删除。修改只写 {@code RESOURCE_TASK}，不更新已有执行实例的配置快照。
 * 被 {@code TASK_SCHEDULE} 引用时拒绝删除并列出计划；修改后的时长仍须满足这些计划的 24 小时和间隔约束。
 * 本类不扫描到期计划，也不启动负载。
 */
@Service
public class TaskTemplateService {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String COLUMNS = """
            ID, NAME, DESCRIPTION, MODE, CPU_TARGET_PCT, MEMORY_MODE, MEMORY_TARGET_BYTES,
            MEMORY_TARGET_PCT, RUN_MODE, DURATION_SECONDS, VERSION, DELETED_AT, CREATED_AT, UPDATED_AT
            """;

    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactionTemplate;
    private final OperationLogRepository operationLog;
    private final Clock clock;

    public TaskTemplateService(JdbcTemplate jdbc, PlatformTransactionManager transactions,
                               OperationLogRepository operationLog, Clock clock) {
        this.jdbc = jdbc;
        this.transactionTemplate = new TransactionTemplate(transactions);
        this.operationLog = operationLog;
        this.clock = clock;
    }

    public PageResult<TaskTemplateView> list(int page, int pageSize, String query) {
        String keyword = query == null ? "" : query.trim();
        boolean filter = !keyword.isEmpty();
        String where = filter ? "WHERE DELETED_AT IS NULL AND NAME LIKE ? ESCAPE '~'" : "WHERE DELETED_AT IS NULL";
        Long total = filter
                ? jdbc.queryForObject("SELECT COUNT(*) FROM RESOURCE_TASK " + where, Long.class, likeContains(keyword))
                : jdbc.queryForObject("SELECT COUNT(*) FROM RESOURCE_TASK " + where, Long.class);
        String sql = "SELECT " + COLUMNS + " FROM RESOURCE_TASK " + where
                + " ORDER BY UPDATED_AT DESC, ID ASC LIMIT ? OFFSET ?";
        List<Object> args = new ArrayList<>();
        if (filter) {
            args.add(likeContains(keyword));
        }
        args.add(pageSize);
        args.add((page - 1L) * pageSize);
        List<TaskTemplateView> items = jdbc.query(sql, (rs, row) -> mapActive(rs), args.toArray());
        return new PageResult<>(items, page, pageSize, total == null ? 0 : total);
    }

    public TaskTemplateView get(String id) {
        Stored stored = find(id);
        if (stored == null || stored.deletedAt() != null) {
            throw missing();
        }
        return stored.view();
    }

    public String runConfig(String id) {
        return TaskTemplateSpec.toStartJson(get(id));
    }

    public TaskTemplateView create(String json, Operator operator) {
        TaskTemplateSpec.Draft draft = TaskTemplateSpec.parseCreate(json);
        String id = UUID.randomUUID().toString();
        long now = clock.millis();
        transactionTemplate.executeWithoutResult(status -> {
            jdbc.update("""
                    INSERT INTO RESOURCE_TASK (
                        ID, NAME, DESCRIPTION, MODE, CPU_TARGET_PCT, MEMORY_MODE, MEMORY_TARGET_BYTES,
                        MEMORY_TARGET_PCT, RUN_MODE, DURATION_SECONDS, VERSION, CREATED_AT, UPDATED_AT
                    ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 1, ?, ?)
                    """, id, draft.name(), draft.description(), draft.resourceMode(), draft.cpuTargetPct(),
                    draft.memoryMode(), draft.memoryTargetBytes(), draft.memoryTargetPct(), draft.runMode(),
                    draft.durationSeconds(), now, now);
            TaskTemplateView created = requireActive(id);
            operationLog.insertChange(actor(operator), sourceIp(operator), "TASK_CREATE", null, write(created),
                    "SUCCESS", null, requestId(operator), now);
        });
        return requireActive(id);
    }

    public TaskTemplateView update(String id, String json, Operator operator) {
        TaskTemplateSpec.Draft draft = TaskTemplateSpec.parseUpdate(json);
        long now = clock.millis();
        transactionTemplate.executeWithoutResult(status -> {
            Stored current = lock(id);
            if (current == null || current.deletedAt() != null) {
                throw missing();
            }
            if (current.version() != draft.expectedVersion()) {
                throw new ApiException(HttpStatus.CONFLICT, "VERSION_CONFLICT", "模板版本已变化",
                        Map.of("version", current.version()));
            }
            ScheduleConstraint.assertReferenced(draft.runMode(), draft.durationSeconds(), referencingScheduleRefs(id));
            int updated = jdbc.update("""
                    UPDATE RESOURCE_TASK
                    SET NAME = ?, DESCRIPTION = ?, MODE = ?, CPU_TARGET_PCT = ?, MEMORY_MODE = ?,
                        MEMORY_TARGET_BYTES = ?, MEMORY_TARGET_PCT = ?, RUN_MODE = ?, DURATION_SECONDS = ?,
                        VERSION = VERSION + 1, UPDATED_AT = ?
                    WHERE ID = ? AND VERSION = ? AND DELETED_AT IS NULL
                    """, draft.name(), draft.description(), draft.resourceMode(), draft.cpuTargetPct(),
                    draft.memoryMode(), draft.memoryTargetBytes(), draft.memoryTargetPct(), draft.runMode(),
                    draft.durationSeconds(), now, id, draft.expectedVersion());
            if (updated != 1) {
                throw new ApiException(HttpStatus.CONFLICT, "VERSION_CONFLICT", "模板版本已变化");
            }
            operationLog.insertChange(actor(operator), sourceIp(operator), "TASK_UPDATE", write(current.view()),
                    write(requireActive(id)), "SUCCESS", null, requestId(operator), now);
        });
        return requireActive(id);
    }

    public Map<String, Object> delete(String id, Operator operator) {
        long now = clock.millis();
        transactionTemplate.executeWithoutResult(status -> {
            Stored current = lock(id);
            if (current == null || current.deletedAt() != null) {
                throw missing();
            }
            List<Map<String, Object>> schedules = referencingSchedules(id);
            if (!schedules.isEmpty()) {
                throw new ApiException(HttpStatus.CONFLICT, "TEMPLATE_REFERENCED", "模板仍被计划引用",
                        Map.of("schedules", List.copyOf(schedules)));
            }
            int updated = jdbc.update("""
                    UPDATE RESOURCE_TASK
                    SET DELETED_AT = ?, UPDATED_AT = ?, VERSION = VERSION + 1
                    WHERE ID = ? AND DELETED_AT IS NULL
                    """, now, now, id);
            if (updated != 1) {
                throw missing();
            }
            Map<String, Object> after = new LinkedHashMap<>();
            after.put("id", id);
            after.put("deleted", true);
            operationLog.insertChange(actor(operator), sourceIp(operator), "TASK_DELETE", write(current.view()),
                    write(after), "SUCCESS", null, requestId(operator), now);
        });
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("id", id);
        body.put("deleted", true);
        return body;
    }

    /**
     * 启动事务已经写好快照之后，只补模板编号。不改配置快照、状态或版本。
     * 幂等重放若已有别的模板编号，保持原值。
     */
    public void bindExecution(String taskId, String executionId) {
        if (taskId == null || executionId == null) {
            return;
        }
        jdbc.update("""
                UPDATE TASK_EXECUTION
                SET TASK_ID = ?
                WHERE ID = ? AND TASK_ID IS NULL
                """, taskId, executionId);
    }

    private TaskTemplateView requireActive(String id) {
        Stored stored = find(id);
        if (stored == null || stored.deletedAt() != null) {
            throw new IllegalStateException("模板写入后无法读取");
        }
        return stored.view();
    }

    private Stored find(String id) {
        List<Stored> rows = jdbc.query("SELECT " + COLUMNS + " FROM RESOURCE_TASK WHERE ID = ?",
                (rs, row) -> mapStored(rs), id);
        return rows.isEmpty() ? null : rows.get(0);
    }

    private Stored lock(String id) {
        List<Stored> rows = jdbc.query("SELECT " + COLUMNS + " FROM RESOURCE_TASK WHERE ID = ? FOR UPDATE",
                (rs, row) -> mapStored(rs), id);
        return rows.isEmpty() ? null : rows.get(0);
    }

    private List<ScheduleConstraint.Ref> referencingScheduleRefs(String taskId) {
        return jdbc.query("""
                SELECT ID, TYPE, SCHEDULE_JSON
                FROM TASK_SCHEDULE
                WHERE TASK_ID = ?
                ORDER BY ID
                """, (rs, row) -> new ScheduleConstraint.Ref(rs.getString(1), rs.getString(2), rs.getString(3)),
                taskId);
    }

    private List<Map<String, Object>> referencingSchedules(String taskId) {
        return jdbc.query("""
                SELECT ID, TYPE
                FROM TASK_SCHEDULE
                WHERE TASK_ID = ?
                ORDER BY ID
                """, (rs, row) -> {
            Map<String, Object> schedule = new LinkedHashMap<>();
            schedule.put("id", rs.getString(1));
            schedule.put("type", rs.getString(2));
            return schedule;
        }, taskId);
    }

    private static TaskTemplateView mapActive(java.sql.ResultSet rs) throws java.sql.SQLException {
        Stored stored = mapStored(rs);
        return stored.deletedAt() == null ? stored.view() : null;
    }

    private static Stored mapStored(java.sql.ResultSet rs) throws java.sql.SQLException {
        Double cpu = rs.getObject("CPU_TARGET_PCT") == null ? null : rs.getDouble("CPU_TARGET_PCT");
        Long memoryBytes = rs.getObject("MEMORY_TARGET_BYTES") == null ? null : rs.getLong("MEMORY_TARGET_BYTES");
        Double memoryPct = rs.getObject("MEMORY_TARGET_PCT") == null ? null : rs.getDouble("MEMORY_TARGET_PCT");
        Long duration = rs.getObject("DURATION_SECONDS") == null ? null : rs.getLong("DURATION_SECONDS");
        Long deletedAt = rs.getObject("DELETED_AT") == null ? null : rs.getLong("DELETED_AT");
        TaskTemplateView view = new TaskTemplateView(
                rs.getString("ID"),
                rs.getString("NAME"),
                rs.getString("DESCRIPTION"),
                rs.getString("MODE"),
                cpu,
                rs.getString("MEMORY_MODE"),
                memoryBytes,
                memoryPct,
                rs.getString("RUN_MODE"),
                duration,
                rs.getLong("VERSION"),
                rs.getLong("CREATED_AT"),
                rs.getLong("UPDATED_AT"));
        return new Stored(view, deletedAt);
    }

    private static String likeContains(String keyword) {
        String escaped = keyword.replace("~", "~~").replace("%", "~%").replace("_", "~_");
        return "%" + escaped + "%";
    }

    private static String write(Object value) {
        try {
            return JSON.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException(exception);
        }
    }

    private static ApiException missing() {
        return new ApiException(HttpStatus.NOT_FOUND, "NOT_FOUND", "任务模板不存在");
    }

    private static String actor(Operator operator) {
        if (operator == null || operator.name() == null || operator.name().isBlank()) {
            return "system";
        }
        return operator.name();
    }

    private static String sourceIp(Operator operator) {
        return operator == null ? null : operator.sourceIp();
    }

    private static String requestId(Operator operator) {
        return operator == null ? null : operator.requestId();
    }

    private record Stored(TaskTemplateView view, Long deletedAt) {
        private long version() {
            return view.version();
        }
    }
}
