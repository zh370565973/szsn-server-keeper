package com.szsn.srm.task;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.szsn.srm.common.ApiException;
import com.szsn.srm.common.PageResult;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * 读取已落库的执行记录。不启动负载，也不补写跳过或心跳。
 * 目标变更没有单独的表，详情里的 targetChanges 因此为空。
 */
@Service
public class ExecutionQueryService {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Set<String> STATUSES = Set.of(
            "STARTING", "RUNNING", "PAUSING", "PAUSED", "STOPPING",
            "STOPPED", "COMPLETED", "SAFETY_STOPPED", "FAILED", "INTERRUPTED", "SKIPPED");
    private static final String COLUMNS = """
            ID, TASK_ID, SCHEDULE_ID, SCHEDULED_AT, CONFIG_SNAPSHOT_JSON, STATUS, REASON, VERSION,
            STARTED_AT, ENDED_AT, LAST_HEARTBEAT_AT, ELAPSED_DURATION_MS, ACTIVE_DURATION_MS,
            STATS_JSON, REQUEST_SOURCE
            """;

    private final JdbcTemplate jdbc;

    public ExecutionQueryService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public PageResult<ExecutionSummary> list(int page, int pageSize, String status, String taskId, Long from, Long to) {
        Filter filter = filter(status, taskId, from, to);
        Long total = jdbc.queryForObject(
                "SELECT COUNT(*) FROM TASK_EXECUTION " + filter.where(), Long.class, filter.args().toArray());
        List<Object> pageArgs = new ArrayList<>(filter.args());
        pageArgs.add(pageSize);
        pageArgs.add((page - 1L) * pageSize);
        List<ExecutionSummary> items = jdbc.query("""
                SELECT %s
                FROM TASK_EXECUTION %s
                ORDER BY COALESCE(STARTED_AT, ENDED_AT, SCHEDULED_AT) DESC, ID DESC
                LIMIT ? OFFSET ?
                """.formatted(COLUMNS, filter.where()), (rs, row) -> map(rs).summary(), pageArgs.toArray());
        return new PageResult<>(items, page, pageSize, total == null ? 0 : total);
    }

    public ExecutionDetail get(String id) {
        List<Stored> rows = jdbc.query("SELECT " + COLUMNS + " FROM TASK_EXECUTION WHERE ID = ?",
                (rs, row) -> map(rs), id);
        if (rows.isEmpty()) {
            throw new ApiException(HttpStatus.NOT_FOUND, "NOT_FOUND", "执行记录不存在");
        }
        Stored stored = rows.get(0);
        Parsed snapshot = parsed(stored.configSnapshotJson());
        Parsed stats = parsed(stored.statsJson());
        return new ExecutionDetail(
                stored.summary(),
                stored.configSnapshotJson() == null ? "" : stored.configSnapshotJson(),
                snapshot.values(),
                snapshot.readable(),
                stats.values(),
                stats.readable(),
                safetyEvents(stored.summary()),
                List.of());
    }

    private static Filter filter(String status, String taskId, Long from, Long to) {
        String normalizedStatus = blankToNull(status);
        if (normalizedStatus != null && !STATUSES.contains(normalizedStatus)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", "status 不合法");
        }
        String normalizedTask = blankToNull(taskId);
        if (normalizedTask != null && normalizedTask.length() > 36) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", "taskId 不合法");
        }
        if (from != null && to != null && from > to) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", "from 不能晚于 to");
        }
        List<String> clauses = new ArrayList<>();
        List<Object> args = new ArrayList<>();
        if (normalizedStatus != null) {
            clauses.add("STATUS = ?");
            args.add(normalizedStatus);
        }
        if (normalizedTask != null) {
            clauses.add("TASK_ID = ?");
            args.add(normalizedTask);
        }
        if (from != null) {
            clauses.add("COALESCE(STARTED_AT, ENDED_AT, SCHEDULED_AT) >= ?");
            args.add(from);
        }
        if (to != null) {
            clauses.add("COALESCE(STARTED_AT, ENDED_AT, SCHEDULED_AT) <= ?");
            args.add(to);
        }
        String where = clauses.isEmpty() ? "" : "WHERE " + String.join(" AND ", clauses);
        return new Filter(where, args);
    }

    private static Stored map(ResultSet rs) throws SQLException {
        String snapshot = rs.getString("CONFIG_SNAPSHOT_JSON");
        String stats = rs.getString("STATS_JSON");
        ExecutionSummary summary = new ExecutionSummary(
                rs.getString("ID"),
                rs.getString("TASK_ID"),
                rs.getString("SCHEDULE_ID"),
                nullableLong(rs, "SCHEDULED_AT"),
                snapshotName(snapshot),
                rs.getString("STATUS"),
                rs.getString("REASON"),
                rs.getLong("VERSION"),
                nullableLong(rs, "STARTED_AT"),
                nullableLong(rs, "ENDED_AT"),
                nullableLong(rs, "LAST_HEARTBEAT_AT"),
                nullableLong(rs, "ELAPSED_DURATION_MS"),
                nullableLong(rs, "ACTIVE_DURATION_MS"),
                rs.getString("REQUEST_SOURCE"),
                missedCount(stats));
        return new Stored(summary, snapshot, stats);
    }

    private static List<SafetyEvent> safetyEvents(ExecutionSummary summary) {
        boolean safety = "SAFETY_STOPPED".equals(summary.status())
                || (summary.reason() != null && summary.reason().startsWith("SAFETY"));
        if (!safety) {
            return List.of();
        }
        Long at = summary.endedAt() != null ? summary.endedAt() : summary.lastHeartbeatAt();
        return List.of(new SafetyEvent(summary.reason(), at));
    }

    private static String snapshotName(String json) {
        Parsed parsed = parsed(json);
        if (!parsed.readable() || parsed.values() == null) {
            return null;
        }
        Object name = parsed.values().get("name");
        if (name instanceof String text && !text.isBlank()) {
            return text;
        }
        return null;
    }

    private static Long missedCount(String statsJson) {
        Parsed parsed = parsed(statsJson);
        if (!parsed.readable() || parsed.values() == null) {
            return null;
        }
        Object count = parsed.values().get("missedCount");
        if (count instanceof Number number && number.longValue() > 0) {
            return number.longValue();
        }
        return null;
    }

    private static Parsed parsed(String json) {
        if (json == null || json.isBlank()) {
            return new Parsed(Map.of(), true);
        }
        try {
            JsonNode node = JSON.readTree(json);
            if (!node.isObject()) {
                return new Parsed(null, false);
            }
            return new Parsed(toMap(node), true);
        } catch (Exception exception) {
            return new Parsed(null, false);
        }
    }

    private static Map<String, Object> toMap(JsonNode node) {
        Map<String, Object> values = new LinkedHashMap<>();
        Iterator<String> names = node.fieldNames();
        while (names.hasNext()) {
            String name = names.next();
            Object value = scalar(node.get(name));
            if (value != null) {
                values.put(name, value);
            }
        }
        return values;
    }

    private static Object scalar(JsonNode node) {
        if (node == null || node.isNull()) {
            return null;
        }
        if (node.isIntegralNumber()) {
            return node.longValue();
        }
        if (node.isFloatingPointNumber()) {
            return node.doubleValue();
        }
        if (node.isBoolean()) {
            return node.booleanValue();
        }
        if (node.isTextual()) {
            return node.asText();
        }
        if (node.isObject()) {
            return toMap(node);
        }
        if (node.isArray()) {
            List<Object> items = new ArrayList<>();
            node.forEach(item -> items.add(scalar(item)));
            return items;
        }
        return node.asText();
    }

    private static Long nullableLong(ResultSet rs, String column) throws SQLException {
        Object value = rs.getObject(column);
        if (value == null) {
            return null;
        }
        if (value instanceof Number number) {
            return number.longValue();
        }
        return rs.getLong(column);
    }

    private static String blankToNull(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim();
    }

    private record Filter(String where, List<Object> args) {
    }

    private record Stored(ExecutionSummary summary, String configSnapshotJson, String statsJson) {
    }

    private record Parsed(Map<String, Object> values, boolean readable) {
    }

    public record ExecutionSummary(
            String id,
            String taskId,
            String scheduleId,
            Long scheduledAt,
            String name,
            String status,
            String reason,
            long version,
            Long startedAt,
            Long endedAt,
            Long lastHeartbeatAt,
            Long elapsedDurationMs,
            Long activeDurationMs,
            String requestSource,
            Long missedCount) {
    }

    public record ExecutionDetail(
            ExecutionSummary execution,
            String configSnapshotJson,
            Map<String, Object> configSnapshot,
            boolean configSnapshotReadable,
            Map<String, Object> stats,
            boolean statsReadable,
            List<SafetyEvent> safetyEvents,
            List<TargetChange> targetChanges) {
    }

    public record SafetyEvent(String reason, Long at) {
    }

    public record TargetChange(Long at, Long fromVersion, Long toVersion, String field, Double value) {
    }
}
