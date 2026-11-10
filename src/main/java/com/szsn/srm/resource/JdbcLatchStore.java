package com.szsn.srm.resource;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.szsn.srm.db.DatabaseMigrator;
import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcLatchStore implements LatchStore {
    public static final String KEY = "emergency.latch";

    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    public JdbcLatchStore(JdbcTemplate jdbc, ObjectMapper objectMapper, Clock clock, DatabaseMigrator migrator) {
        if (migrator == null) {
            throw new IllegalArgumentException("数据库迁移尚未完成");
        }
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    @Override
    public Optional<StoredLatch> load() {
        List<String> rows = jdbc.query("SELECT VALUE_JSON FROM SYS_CONFIG WHERE CONFIG_KEY = ?",
                (rs, row) -> rs.getString(1), KEY);
        if (rows.isEmpty()) {
            return Optional.empty();
        }
        try {
            JsonNode node = objectMapper.readTree(rows.get(0));
            String reason = text(node.get("reason"));
            return Optional.of(new StoredLatch(
                    node.path("latched").asBoolean(false),
                    reason,
                    node.path("at").asLong(0),
                    node.path("epoch").asLong(0)));
        } catch (RuntimeException | java.io.IOException exception) {
            throw new IllegalStateException("急停锁存记录无法读取", exception);
        }
    }

    @Override
    public void save(StoredLatch latch) {
        if (latch == null) {
            throw new IllegalArgumentException("锁存记录不能为空");
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("latched", latch.latched());
        body.put("reason", latch.reason());
        body.put("at", latch.at());
        body.put("epoch", latch.epoch());
        try {
            String json = objectMapper.writeValueAsString(body);
            jdbc.update("""
                    MERGE INTO SYS_CONFIG (CONFIG_KEY, VALUE_JSON, VERSION, UPDATED_AT)
                    KEY (CONFIG_KEY)
                    VALUES (?, ?, ?, ?)
                    """, KEY, json, latch.epoch(), clock.millis());
        } catch (RuntimeException | java.io.IOException exception) {
            if (exception instanceof RuntimeException runtime) {
                throw runtime;
            }
            throw new IllegalStateException("急停锁存记录无法写入", exception);
        }
    }

    private static String text(JsonNode node) {
        if (node == null || node.isNull() || node.isMissingNode()) {
            return null;
        }
        String value = node.asText();
        return value == null || value.isBlank() ? null : value;
    }
}
