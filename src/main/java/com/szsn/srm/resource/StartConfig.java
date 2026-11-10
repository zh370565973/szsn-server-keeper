package com.szsn.srm.resource;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.szsn.srm.common.ApiException;
import com.szsn.srm.common.Units;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Iterator;
import java.util.Set;
import java.util.UUID;
import org.springframework.http.HttpStatus;

/**
 * 把手动启动请求收成稳定的配置快照。不计算内存预算，也不读取本机容量。
 */
final class StartConfig {
    private static final ObjectMapper JSON = new ObjectMapper()
            .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS);
    private static final BigDecimal HUNDRED = new BigDecimal("100");
    private static final BigDecimal MEMORY_PCT_MIN = BigDecimal.valueOf(LoadBounds.MEMORY_TARGET_PCT_MIN);
    private static final BigDecimal MEMORY_PCT_MAX = BigDecimal.valueOf(LoadBounds.MEMORY_TARGET_PCT_MAX);
    private static final Set<String> FIELDS = Set.of(
            "name",
            "resourceMode",
            "cpuTargetPct",
            "memoryMode",
            "memoryTargetBytes",
            "memoryTargetPct",
            "runMode",
            "durationSeconds");

    private StartConfig() {
    }

    static Prepared prepare(String idempotencyKey, String configJson) {
        String key = canonicalKey(idempotencyKey);
        JsonNode raw = readObject(configJson);
        String snapshot = write(canonicalize(raw));
        return new Prepared(key, snapshot, sha256(snapshot));
    }

    private static String canonicalKey(String idempotencyKey) {
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "IDEMPOTENCY_KEY_REQUIRED", "幂等键不能为空");
        }
        try {
            return UUID.fromString(idempotencyKey).toString();
        } catch (IllegalArgumentException exception) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "IDEMPOTENCY_KEY_INVALID", "幂等键必须是 UUID");
        }
    }

    private static JsonNode readObject(String configJson) {
        if (configJson == null || configJson.isBlank()) {
            throw malformed();
        }
        try (JsonParser parser = JSON.createParser(configJson)) {
            JsonNode node = JSON.readTree(parser);
            if (node == null || !node.isObject() || parser.nextToken() != null) {
                throw malformed();
            }
            return node;
        } catch (ApiException exception) {
            throw exception;
        } catch (Exception exception) {
            throw malformed();
        }
    }

    private static ObjectNode canonicalize(JsonNode raw) {
        Iterator<String> names = raw.fieldNames();
        while (names.hasNext()) {
            String name = names.next();
            if (!FIELDS.contains(name) || raw.get(name).isNull()) {
                throw invalid(FIELDS.contains(name) ? "配置字段不能为空" : "配置包含无法识别的字段");
            }
        }
        String resourceMode = text(raw, "resourceMode");
        if (!resourceMode.equals("CPU") && !resourceMode.equals("MEMORY") && !resourceMode.equals("COMBINED")) {
            throw invalid("resourceMode 不合法");
        }
        String runMode = text(raw, "runMode");
        if (!runMode.equals("FINITE") && !runMode.equals("CONTINUOUS")) {
            throw invalid("runMode 不合法");
        }
        boolean usesCpu = !resourceMode.equals("MEMORY");
        boolean usesMemory = !resourceMode.equals("CPU");
        String name = raw.has("name") ? text(raw, "name") : null;
        if (name != null) {
            int chars = name.codePointCount(0, name.length());
            if (chars < 1 || chars > 64) {
                throw invalid("名称长度必须是 1 到 64 个字符");
            }
        }
        BigDecimal cpuTargetPct = null;
        if (usesCpu) {
            cpuTargetPct = percent(raw, "cpuTargetPct");
        } else {
            requireAbsent(raw, "cpuTargetPct");
        }
        String memoryMode = null;
        Long memoryTargetBytes = null;
        BigDecimal memoryTargetPct = null;
        if (usesMemory) {
            memoryMode = text(raw, "memoryMode");
            if (memoryMode.equals("EXTRA_BYTES")) {
                requireAbsent(raw, "memoryTargetPct");
                memoryTargetBytes = exactLong(raw, "memoryTargetBytes", 1, Long.MAX_VALUE);
                if (memoryTargetBytes % Units.BLOCK != 0) {
                    throw invalid("memoryTargetBytes 必须是 4MiB 的整数倍");
                }
            } else if (memoryMode.equals("SYSTEM_PERCENT")) {
                requireAbsent(raw, "memoryTargetBytes");
                memoryTargetPct = memoryPercent(raw, "memoryTargetPct");
            } else {
                throw invalid("memoryMode 不合法");
            }
        } else {
            requireAbsent(raw, "memoryMode", "memoryTargetBytes", "memoryTargetPct");
        }
        Long durationSeconds = null;
        if (runMode.equals("FINITE")) {
            durationSeconds = exactLong(raw, "durationSeconds",
                    LoadBounds.DURATION_MIN_SECONDS, LoadBounds.DURATION_MAX_SECONDS);
        } else {
            requireAbsent(raw, "durationSeconds");
        }
        ObjectNode snapshot = JSON.createObjectNode();
        if (name != null) {
            snapshot.put("name", name);
        }
        snapshot.put("resourceMode", resourceMode);
        if (cpuTargetPct != null) {
            snapshot.set("cpuTargetPct", JSON.getNodeFactory().numberNode(cpuTargetPct));
        }
        if (memoryMode != null) {
            snapshot.put("memoryMode", memoryMode);
        }
        if (memoryTargetBytes != null) {
            snapshot.put("memoryTargetBytes", memoryTargetBytes);
        }
        if (memoryTargetPct != null) {
            snapshot.set("memoryTargetPct", JSON.getNodeFactory().numberNode(memoryTargetPct));
        }
        snapshot.put("runMode", runMode);
        if (durationSeconds != null) {
            snapshot.put("durationSeconds", durationSeconds);
        }
        return snapshot;
    }

    private static void requireAbsent(JsonNode raw, String... fields) {
        for (String field : fields) {
            if (raw.has(field)) {
                throw invalid(field + " 不能出现在当前模式中");
            }
        }
    }

    private static String text(JsonNode raw, String field) {
        JsonNode node = raw.get(field);
        if (node == null || !node.isTextual() || node.asText().isEmpty()) {
            throw invalid(field + " 不合法");
        }
        return node.asText();
    }

    private static long exactLong(JsonNode raw, String field, long minimum, long maximum) {
        JsonNode node = raw.get(field);
        if (node == null || !node.isNumber()) {
            throw invalid(field + " 不合法");
        }
        BigDecimal value = node.decimalValue().stripTrailingZeros();
        if (value.scale() > 0) {
            throw invalid(field + " 必须是整数");
        }
        final long parsed;
        try {
            parsed = value.longValueExact();
        } catch (ArithmeticException exception) {
            throw invalid(field + " 超出范围");
        }
        if (parsed < minimum || parsed > maximum) {
            if ("durationSeconds".equals(field)) {
                throw invalid("durationSeconds 必须在 10 秒到 7 天之间");
            }
            throw invalid(field + " 不合法");
        }
        return parsed;
    }

    private static BigDecimal memoryPercent(JsonNode raw, String field) {
        JsonNode node = raw.get(field);
        if (node == null || !node.isNumber()) {
            throw invalid(field + " 不合法");
        }
        BigDecimal value = node.decimalValue().stripTrailingZeros();
        if (value.scale() < 0) {
            value = value.setScale(0);
        }
        if (value.compareTo(MEMORY_PCT_MIN) < 0 || value.compareTo(MEMORY_PCT_MAX) > 0) {
            throw invalid(field + " 必须在 1 到 70 之间");
        }
        return value;
    }

    private static BigDecimal percent(JsonNode raw, String field) {
        JsonNode node = raw.get(field);
        if (node == null || !node.isNumber()) {
            throw invalid(field + " 不合法");
        }
        BigDecimal value = node.decimalValue().stripTrailingZeros();
        if (value.scale() < 0) {
            value = value.setScale(0);
        }
        if (value.compareTo(BigDecimal.ZERO) < 0 || value.compareTo(HUNDRED) > 0) {
            throw invalid(field + " 必须在 0 到 100 之间");
        }
        return value;
    }

    private static String write(ObjectNode snapshot) {
        try {
            return JSON.writeValueAsString(snapshot);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException(exception);
        }
    }

    private static String sha256(String text) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
    }

    private static ApiException malformed() {
        return new ApiException(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", "请求格式不合法");
    }

    private static ApiException invalid(String message) {
        return new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "TARGET_INVALID", message);
    }

    record Prepared(String key, String snapshotJson, String hash) {
    }
}
