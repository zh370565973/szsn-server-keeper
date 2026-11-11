package com.szsn.srm.task;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.szsn.srm.auth.OperationLogRepository;
import com.szsn.srm.common.ApiException;
import com.szsn.srm.common.PageResult;
import com.szsn.srm.resource.Operator;
import com.szsn.srm.task.ScheduleSpec.Definition;
import com.szsn.srm.task.ScheduleSpec.Draft;
import com.szsn.srm.task.ScheduleSpec.EnableDraft;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Collections;
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
 * 定时计划的保存、修改、删除和启停。计算并保存下一次触发时间。
 * 查询时附上该计划最近一次 SKIPPED 的原因，不改调度。
 * 到期扫描、冲突跳过和漏执行由 {@link ScheduleDispatcher} 负责。本类不启动负载。
 */
@Service
public class ScheduleService {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String COLUMNS = """
            ID, TASK_ID, TYPE, SCHEDULE_JSON, TIMEZONE, ENABLED, NEXT_FIRE_AT, VERSION, CREATED_AT, UPDATED_AT
            """;

    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactionTemplate;
    private final OperationLogRepository operationLog;
    private final Clock clock;

    public ScheduleService(JdbcTemplate jdbc, PlatformTransactionManager transactions,
                           OperationLogRepository operationLog, Clock clock) {
        this.jdbc = jdbc;
        this.transactionTemplate = new TransactionTemplate(transactions);
        this.operationLog = operationLog;
        this.clock = clock;
    }

    public PageResult<ScheduleView> list(int page, int pageSize) {
        Long total = jdbc.queryForObject("SELECT COUNT(*) FROM TASK_SCHEDULE", Long.class);
        List<ScheduleView> items = jdbc.query("SELECT " + COLUMNS + """
                 FROM TASK_SCHEDULE
                 ORDER BY NEXT_FIRE_AT ASC, ID ASC
                 LIMIT ? OFFSET ?
                """, (rs, row) -> map(rs), pageSize, (page - 1L) * pageSize);
        return new PageResult<>(enrich(items), page, pageSize, total == null ? 0 : total);
    }

    public ScheduleView get(String id) {
        Stored stored = find(id);
        if (stored == null) {
            throw missingSchedule();
        }
        return published(stored.view());
    }

    public ScheduleView create(String json, Operator operator) {
        Draft draft = ScheduleSpec.parseCreate(json);
        long now = clock.millis();
        long nextFireAt = ScheduleCalendar.nextFire(draft.definition(), now);
        String id = UUID.randomUUID().toString();
        transactionTemplate.executeWithoutResult(status -> {
            TemplateRow template = lockTemplate(draft.taskId());
            ScheduleConstraint.assertBindable(template.runMode(), template.durationSeconds(),
                    draft.definition().type(), draft.definition().intervalSeconds());
            lockScheduleRows();
            Long count = jdbc.queryForObject("SELECT COUNT(*) FROM TASK_SCHEDULE", Long.class);
            if (count != null && count >= ScheduleConstraint.MAX_SCHEDULES) {
                throw limit();
            }
            jdbc.update("""
                    INSERT INTO TASK_SCHEDULE (
                        ID, TASK_ID, TYPE, SCHEDULE_JSON, TIMEZONE, ENABLED, NEXT_FIRE_AT,
                        VERSION, CREATED_AT, UPDATED_AT
                    ) VALUES (?, ?, ?, ?, ?, ?, ?, 1, ?, ?)
                    """, id, draft.taskId(), draft.definition().type(), ScheduleSpec.toJson(draft.definition()),
                    draft.definition().timezone(), draft.enabled(), nextFireAt, now, now);
            operationLog.insertChange(actor(operator), sourceIp(operator), "SCHEDULE_CREATE", null,
                    write(require(id)), "SUCCESS", null, requestId(operator), now);
        });
        return published(require(id));
    }

    public ScheduleView update(String id, String json, Operator operator) {
        Draft draft = ScheduleSpec.parseUpdate(json);
        long now = clock.millis();
        long nextFireAt = ScheduleCalendar.nextFire(draft.definition(), now);
        transactionTemplate.executeWithoutResult(status -> {
            TemplateRow template = lockTemplate(draft.taskId());
            ScheduleConstraint.assertBindable(template.runMode(), template.durationSeconds(),
                    draft.definition().type(), draft.definition().intervalSeconds());
            Stored current = lock(id);
            if (current == null) {
                throw missingSchedule();
            }
            if (current.view().version() != draft.expectedVersion()) {
                throw conflict(current.view().version());
            }
            int updated = jdbc.update("""
                    UPDATE TASK_SCHEDULE
                    SET TASK_ID = ?, TYPE = ?, SCHEDULE_JSON = ?, TIMEZONE = ?, ENABLED = ?,
                        NEXT_FIRE_AT = ?, VERSION = VERSION + 1, UPDATED_AT = ?
                    WHERE ID = ? AND VERSION = ?
                    """, draft.taskId(), draft.definition().type(), ScheduleSpec.toJson(draft.definition()),
                    draft.definition().timezone(), draft.enabled(), nextFireAt, now, id, draft.expectedVersion());
            if (updated != 1) {
                throw conflict(current.view().version());
            }
            operationLog.insertChange(actor(operator), sourceIp(operator), "SCHEDULE_UPDATE", write(current.view()),
                    write(require(id)), "SUCCESS", null, requestId(operator), now);
        });
        return published(require(id));
    }

    public Map<String, Object> delete(String id, Operator operator) {
        long now = clock.millis();
        transactionTemplate.executeWithoutResult(status -> {
            Stored current = lock(id);
            if (current == null) {
                throw missingSchedule();
            }
            int deleted = jdbc.update("DELETE FROM TASK_SCHEDULE WHERE ID = ?", id);
            if (deleted != 1) {
                throw missingSchedule();
            }
            Map<String, Object> after = new LinkedHashMap<>();
            after.put("id", id);
            after.put("deleted", true);
            operationLog.insertChange(actor(operator), sourceIp(operator), "SCHEDULE_DELETE", write(current.view()),
                    write(after), "SUCCESS", null, requestId(operator), now);
        });
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("id", id);
        body.put("deleted", true);
        return body;
    }

    public ScheduleView setEnabled(String id, String json, Operator operator) {
        EnableDraft draft = ScheduleSpec.parseEnable(json);
        for (int attempt = 0; attempt < 2; attempt++) {
            Stored peek = find(id);
            if (peek == null) {
                throw missingSchedule();
            }
            try {
                transactionTemplate.executeWithoutResult(status ->
                        applyEnabled(id, draft, peek.view().taskId(), operator));
                return published(require(id));
            } catch (TaskRebound rebound) {
                if (attempt == 1) {
                    throw conflict(peek.view().version());
                }
            }
        }
        throw new IllegalStateException("计划启停未能完成");
    }

    private void applyEnabled(String id, EnableDraft draft, String peekedTaskId, Operator operator) {
        long now = clock.millis();
        TemplateRow template = draft.enabled() ? lockTemplate(peekedTaskId) : null;
        Stored current = lock(id);
        if (current == null) {
            throw missingSchedule();
        }
        if (current.view().version() != draft.expectedVersion()) {
            throw conflict(current.view().version());
        }
        if (draft.enabled() && !current.view().taskId().equals(peekedTaskId)) {
            throw new TaskRebound();
        }
        if (!draft.enabled()) {
            int updated = jdbc.update("""
                    UPDATE TASK_SCHEDULE
                    SET ENABLED = FALSE, VERSION = VERSION + 1, UPDATED_AT = ?
                    WHERE ID = ? AND VERSION = ?
                    """, now, id, draft.expectedVersion());
            if (updated != 1) {
                throw conflict(current.view().version());
            }
            operationLog.insertChange(actor(operator), sourceIp(operator), "SCHEDULE_DISABLE", write(current.view()),
                    write(require(id)), "SUCCESS", null, requestId(operator), now);
            return;
        }
        Definition definition = ScheduleSpec.fromStored(current.view().type(), current.view().timezone(),
                current.scheduleJson());
        ScheduleConstraint.assertBindable(template.runMode(), template.durationSeconds(),
                definition.type(), definition.intervalSeconds());
        long nextFireAt = ScheduleCalendar.nextFire(definition, now);
        int updated = jdbc.update("""
                UPDATE TASK_SCHEDULE
                SET ENABLED = TRUE, NEXT_FIRE_AT = ?, VERSION = VERSION + 1, UPDATED_AT = ?
                WHERE ID = ? AND VERSION = ?
                """, nextFireAt, now, id, draft.expectedVersion());
        if (updated != 1) {
            throw conflict(current.view().version());
        }
        operationLog.insertChange(actor(operator), sourceIp(operator), "SCHEDULE_ENABLE", write(current.view()),
                write(require(id)), "SUCCESS", null, requestId(operator), now);
    }

    private ScheduleView published(ScheduleView view) {
        return enrich(List.of(view)).get(0);
    }

    private List<ScheduleView> enrich(List<ScheduleView> views) {
        if (views.isEmpty()) {
            return views;
        }
        Map<String, SkipMark> marks = latestSkips(views.stream().map(ScheduleView::id).toList());
        List<ScheduleView> enriched = new ArrayList<>(views.size());
        for (ScheduleView view : views) {
            SkipMark mark = marks.get(view.id());
            enriched.add(mark == null ? view : view.withLastSkip(mark.reason(), mark.at(), mark.missedCount()));
        }
        return enriched;
    }

    private Map<String, SkipMark> latestSkips(List<String> ids) {
        String placeholders = String.join(", ", Collections.nCopies(ids.size(), "?"));
        List<SkipMark> rows = jdbc.query("""
                SELECT SCHEDULE_ID, REASON, ENDED_AT, SCHEDULED_AT, STATS_JSON
                FROM (
                    SELECT SCHEDULE_ID, REASON, ENDED_AT, SCHEDULED_AT, STATS_JSON,
                           ROW_NUMBER() OVER (
                               PARTITION BY SCHEDULE_ID
                               ORDER BY COALESCE(ENDED_AT, SCHEDULED_AT) DESC, ID DESC
                           ) AS RN
                    FROM TASK_EXECUTION
                    WHERE STATUS = 'SKIPPED' AND SCHEDULE_ID IN (%s)
                ) LATEST
                WHERE RN = 1
                """.formatted(placeholders), (rs, row) -> {
            Long at = rs.getObject("ENDED_AT") == null ? null : rs.getLong("ENDED_AT");
            if (at == null && rs.getObject("SCHEDULED_AT") != null) {
                at = rs.getLong("SCHEDULED_AT");
            }
            return new SkipMark(rs.getString("SCHEDULE_ID"), rs.getString("REASON"), at,
                    missedCount(rs.getString("STATS_JSON")));
        }, ids.toArray());
        Map<String, SkipMark> first = new LinkedHashMap<>();
        for (SkipMark row : rows) {
            first.putIfAbsent(row.scheduleId(), row);
        }
        return first;
    }

    private static Long missedCount(String statsJson) {
        if (statsJson == null || statsJson.isBlank()) {
            return null;
        }
        try {
            JsonNode node = JSON.readTree(statsJson);
            JsonNode count = node.get("missedCount");
            if (count == null || !count.isIntegralNumber() || count.longValue() <= 0) {
                return null;
            }
            return count.longValue();
        } catch (Exception exception) {
            return null;
        }
    }

    private void lockScheduleRows() {
        jdbc.query("SELECT ID FROM TASK_SCHEDULE ORDER BY ID FOR UPDATE", (rs, row) -> rs.getString(1));
    }

    private TemplateRow lockTemplate(String taskId) {
        List<TemplateRow> rows = jdbc.query("""
                SELECT RUN_MODE, DURATION_SECONDS, DELETED_AT
                FROM RESOURCE_TASK
                WHERE ID = ?
                FOR UPDATE
                """, (rs, row) -> {
            Long deletedAt = rs.getObject("DELETED_AT") == null ? null : rs.getLong("DELETED_AT");
            Long duration = rs.getObject("DURATION_SECONDS") == null ? null : rs.getLong("DURATION_SECONDS");
            return new TemplateRow(rs.getString("RUN_MODE"), duration, deletedAt);
        }, taskId);
        if (rows.isEmpty() || rows.get(0).deletedAt() != null) {
            throw missingTemplate();
        }
        return rows.get(0);
    }

    private ScheduleView require(String id) {
        Stored stored = find(id);
        if (stored == null) {
            throw new IllegalStateException("计划写入后无法读取");
        }
        return stored.view();
    }

    private Stored find(String id) {
        List<Stored> rows = jdbc.query("SELECT " + COLUMNS + " FROM TASK_SCHEDULE WHERE ID = ?",
                (rs, row) -> mapStored(rs), id);
        return rows.isEmpty() ? null : rows.get(0);
    }

    private Stored lock(String id) {
        List<Stored> rows = jdbc.query("SELECT " + COLUMNS + " FROM TASK_SCHEDULE WHERE ID = ? FOR UPDATE",
                (rs, row) -> mapStored(rs), id);
        return rows.isEmpty() ? null : rows.get(0);
    }

    private static Stored mapStored(java.sql.ResultSet rs) throws java.sql.SQLException {
        String scheduleJson = rs.getString("SCHEDULE_JSON");
        JsonNode config = readLoose(scheduleJson);
        Long nextFireAt = rs.getObject("NEXT_FIRE_AT") == null ? null : rs.getLong("NEXT_FIRE_AT");
        List<Integer> weekdays = weekdays(config);
        ScheduleView view = new ScheduleView(
                rs.getString("ID"),
                rs.getString("TASK_ID"),
                rs.getString("TYPE"),
                rs.getString("TIMEZONE"),
                longOrNull(config, "startAt"),
                textOrNull(config, "localTime"),
                weekdays,
                longOrNull(config, "anchorAt"),
                longOrNull(config, "intervalSeconds"),
                rs.getBoolean("ENABLED"),
                nextFireAt,
                rs.getLong("VERSION"),
                rs.getLong("CREATED_AT"),
                rs.getLong("UPDATED_AT"),
                null,
                null,
                null);
        return new Stored(view, scheduleJson);
    }

    private static ScheduleView map(java.sql.ResultSet rs) throws java.sql.SQLException {
        return mapStored(rs).view();
    }

    private static JsonNode readLoose(String json) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            JsonNode node = JSON.readTree(json);
            return node != null && node.isObject() ? node : null;
        } catch (Exception exception) {
            return null;
        }
    }

    private static Long longOrNull(JsonNode node, String field) {
        if (node == null || !node.has(field) || !node.get(field).isIntegralNumber()) {
            return null;
        }
        return node.get(field).longValue();
    }

    private static String textOrNull(JsonNode node, String field) {
        if (node == null || !node.has(field) || !node.get(field).isTextual()) {
            return null;
        }
        return node.get(field).asText();
    }

    private static List<Integer> weekdays(JsonNode node) {
        if (node == null || !node.has("weekdays") || !node.get("weekdays").isArray()) {
            return null;
        }
        List<Integer> days = new ArrayList<>();
        for (JsonNode item : node.get("weekdays")) {
            if (item.isIntegralNumber()) {
                days.add(item.intValue());
            }
        }
        return days.isEmpty() ? null : List.copyOf(days);
    }

    private static String write(Object value) {
        try {
            return JSON.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException(exception);
        }
    }

    private static ApiException missingSchedule() {
        return new ApiException(HttpStatus.NOT_FOUND, "NOT_FOUND", "定时计划不存在");
    }

    private static ApiException missingTemplate() {
        return new ApiException(HttpStatus.NOT_FOUND, "NOT_FOUND", "任务模板不存在");
    }

    private static ApiException conflict(long version) {
        return new ApiException(HttpStatus.CONFLICT, "VERSION_CONFLICT", "计划版本已变化",
                Map.of("version", version));
    }

    private static ApiException limit() {
        return new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "SCHEDULE_LIMIT", "计划数量不能超过 100",
                Map.of("limit", ScheduleConstraint.MAX_SCHEDULES));
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

    private record Stored(ScheduleView view, String scheduleJson) {
    }

    private record SkipMark(String scheduleId, String reason, Long at, Long missedCount) {
    }

    private record TemplateRow(String runMode, Long durationSeconds, Long deletedAt) {
    }

    private static final class TaskRebound extends RuntimeException {
    }
}
