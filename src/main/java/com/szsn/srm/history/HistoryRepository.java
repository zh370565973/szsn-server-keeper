package com.szsn.srm.history;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class HistoryRepository {
    private static final TypeReference<Map<String, Object>> MAP = new TypeReference<>() {
    };

    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;

    public HistoryRepository(JdbcTemplate jdbc, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
    }

    public void insert(long bucketStart, int sampleCount, long coverageMs, Map<String, Object> metrics) {
        jdbc.update("""
                MERGE INTO RESOURCE_METRIC (BUCKET_START, SAMPLE_COUNT, COVERAGE_MS, METRICS_JSON)
                KEY (BUCKET_START)
                VALUES (?, ?, ?, ?)
                """, bucketStart, sampleCount, coverageMs, write(metrics));
    }

    public List<RawBucket> find(long from, long to) {
        return jdbc.query("""
                SELECT BUCKET_START, SAMPLE_COUNT, COVERAGE_MS, METRICS_JSON
                FROM RESOURCE_METRIC
                WHERE BUCKET_START >= ? AND BUCKET_START < ?
                ORDER BY BUCKET_START
                """, (rs, row) -> new RawBucket(
                rs.getLong(1),
                rs.getInt(2),
                rs.getLong(3),
                read(rs.getString(4))), from, to);
    }

    public int deleteOlderThan(long cutoff, int limit) {
        return jdbc.update("DELETE FROM RESOURCE_METRIC WHERE BUCKET_START < ? LIMIT ?", cutoff, limit);
    }

    private String write(Map<String, Object> metrics) {
        try {
            return objectMapper.writeValueAsString(metrics);
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
    }

    private Map<String, Object> read(String json) {
        try {
            return objectMapper.readValue(json, MAP);
        } catch (Exception exception) {
            return Map.of();
        }
    }

    public record RawBucket(long bucketStart, int sampleCount, long coverageMs, Map<String, Object> metrics) {
    }
}
