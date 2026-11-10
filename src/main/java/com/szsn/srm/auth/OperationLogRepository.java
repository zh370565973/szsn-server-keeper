package com.szsn.srm.auth;

import com.szsn.srm.common.PageResult;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class OperationLogRepository {
    private final JdbcTemplate jdbc;

    public OperationLogRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void insert(String actor, String sourceIp, String action, String result, String reason, String requestId) {
        jdbc.update("""
                INSERT INTO OPERATION_LOG
                (ID, ACTOR, SOURCE_IP, ACTION, RESULT, REASON, REQUEST_ID, CREATED_AT)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                """, UUID.randomUUID().toString(), actor, sourceIp, action, result, reason, requestId, System.currentTimeMillis());
    }

    public void insertChange(String actor, String sourceIp, String action, String beforeJson, String afterJson,
                             String result, String reason, String requestId, long createdAt) {
        jdbc.update("""
                INSERT INTO OPERATION_LOG
                (ID, ACTOR, SOURCE_IP, ACTION, BEFORE_JSON, AFTER_JSON, RESULT, REASON, REQUEST_ID, CREATED_AT)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, UUID.randomUUID().toString(), actor, sourceIp, action, beforeJson, afterJson, result, reason,
                requestId, createdAt);
    }

    public PageResult<LogRow> page(int page, int pageSize) {
        Long total = jdbc.queryForObject("SELECT COUNT(*) FROM OPERATION_LOG", Long.class);
        List<LogRow> items = jdbc.query("""
                SELECT ID, ACTOR, SOURCE_IP, ACTION, RESULT, REASON, REQUEST_ID, CREATED_AT
                FROM OPERATION_LOG
                ORDER BY CREATED_AT DESC
                LIMIT ? OFFSET ?
                """, (rs, row) -> new LogRow(
                rs.getString(1),
                rs.getString(2),
                rs.getString(3),
                rs.getString(4),
                rs.getString(5),
                rs.getString(6),
                rs.getString(7),
                rs.getLong(8)), pageSize, (page - 1L) * pageSize);
        return new PageResult<>(items, page, pageSize, total == null ? 0 : total);
    }

    public record LogRow(
            String id,
            String actor,
            String sourceIp,
            String action,
            String result,
            String reason,
            String requestId,
            long createdAt) {
    }
}
