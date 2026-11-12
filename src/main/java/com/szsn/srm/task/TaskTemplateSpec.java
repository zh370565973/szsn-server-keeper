package com.szsn.srm.task;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.szsn.srm.common.ApiException;
import com.szsn.srm.common.Units;
import com.szsn.srm.resource.LoadBounds;
import java.math.BigDecimal;
import java.util.Iterator;
import java.util.Set;
import org.springframework.http.HttpStatus;

/**
 * 把模板请求收成可落库的字段。CPU 和内存目标都是 1–90。
 * 不读取本机容量，也不创建执行实例。
 */
final class TaskTemplateSpec {
    private static final ObjectMapper JSON = new ObjectMapper()
            .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS);
    private static final BigDecimal CPU_MIN = new BigDecimal("1");
    private static final BigDecimal CPU_MAX = BigDecimal.valueOf(LoadBounds.CPU_TARGET_MAX);
    private static final BigDecimal MEMORY_PCT_MIN = BigDecimal.valueOf(LoadBounds.MEMORY_TARGET_PCT_MIN);
    private static final BigDecimal MEMORY_PCT_MAX = BigDecimal.valueOf(LoadBounds.MEMORY_TARGET_PCT_MAX);
    private static final Set<String> FIELDS = Set.of(
            "name",
            "description",
            "resourceMode",
            "cpuTargetPct",
            "memoryMode",
            "memoryTargetBytes",
            "memoryTargetPct",
            "runMode",
            "durationSeconds");

    private TaskTemplateSpec() {
    }

    static Draft parseCreate(String json) {
        return parse(json, false);
    }

    static Draft parseUpdate(String json) {
        return parse(json, true);
    }

    static String toStartJson(TaskTemplateView view) {
        ObjectNode snapshot = JSON.createObjectNode();
        snapshot.put("name", view.name());
        snapshot.put("resourceMode", view.resourceMode());
        if (view.cpuTargetPct() != null) {
            snapshot.put("cpuTargetPct", view.cpuTargetPct());
        }
        if (view.memoryMode() != null) {
            snapshot.put("memoryMode", view.memoryMode());
        }
        if (view.memoryTargetBytes() != null) {
            snapshot.put("memoryTargetBytes", view.memoryTargetBytes());
        }
        if (view.memoryTargetPct() != null) {
            snapshot.put("memoryTargetPct", view.memoryTargetPct());
        }
        snapshot.put("runMode", view.runMode());
        if (view.durationSeconds() != null) {
            snapshot.put("durationSeconds", view.durationSeconds());
        }
        return write(snapshot);
    }

    private static Draft parse(String json, boolean update) {
        JsonNode raw = readObject(json);
        Iterator<String> names = raw.fieldNames();
        while (names.hasNext()) {
            String name = names.next();
            boolean allowed = FIELDS.contains(name) || (update && "expectedVersion".equals(name));
            if (!allowed || raw.get(name).isNull()) {
                throw invalid(allowed ? "配置字段不能为空" : "配置包含无法识别的字段");
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
        String name = text(raw, "name");
        int nameChars = name.codePointCount(0, name.length());
        if (name.isBlank() || nameChars < 1 || nameChars > 64 || name.length() > 64) {
            throw invalid("名称长度必须是 1 到 64 个字符");
        }
        String description = "";
        if (raw.has("description")) {
            JsonNode node = raw.get("description");
            if (!node.isTextual()) {
                throw invalid("description 不合法");
            }
            description = node.asText();
            if (description.codePointCount(0, description.length()) > 500 || description.length() > 500) {
                throw invalid("说明不能超过 500 个字符");
            }
        }
        boolean usesCpu = !resourceMode.equals("MEMORY");
        boolean usesMemory = !resourceMode.equals("CPU");
        Double cpuTargetPct = null;
        if (usesCpu) {
            cpuTargetPct = cpuPercent(raw, "cpuTargetPct");
        } else {
            requireAbsent(raw, "cpuTargetPct");
        }
        String memoryMode = null;
        Long memoryTargetBytes = null;
        Double memoryTargetPct = null;
        if (usesMemory) {
            memoryMode = text(raw, "memoryMode");
            if (memoryMode.equals("EXTRA_BYTES")) {
                requireAbsent(raw, "memoryTargetPct");
                memoryTargetBytes = exactLong(raw, "memoryTargetBytes", Units.BLOCK, Long.MAX_VALUE);
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
        Long expectedVersion = null;
        if (update) {
            if (!raw.has("expectedVersion")) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", "修改模板必须提供 expectedVersion");
            }
            expectedVersion = exactLong(raw, "expectedVersion", 1, Long.MAX_VALUE);
        }
        return new Draft(name, description, resourceMode, cpuTargetPct, memoryMode, memoryTargetBytes,
                memoryTargetPct, runMode, durationSeconds, expectedVersion);
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
            if ("memoryTargetBytes".equals(field)) {
                throw invalid("memoryTargetBytes 必须是 4MiB 的整数倍");
            }
            throw invalid(field + " 不合法");
        }
        return parsed;
    }

    private static Double cpuPercent(JsonNode raw, String field) {
        BigDecimal value = decimal(raw, field);
        if (value.compareTo(CPU_MIN) < 0 || value.compareTo(CPU_MAX) > 0) {
            throw invalid("cpuTargetPct 必须在 1 到 " + CPU_MAX.intValue() + " 之间");
        }
        double parsed = value.doubleValue();
        if (Double.isNaN(parsed) || Double.isInfinite(parsed) || parsed < 1D || parsed > LoadBounds.CPU_TARGET_MAX) {
            throw invalid("cpuTargetPct 必须在 1 到 " + CPU_MAX.intValue() + " 之间");
        }
        return parsed;
    }

    private static Double memoryPercent(JsonNode raw, String field) {
        BigDecimal value = decimal(raw, field);
        if (value.compareTo(MEMORY_PCT_MIN) < 0 || value.compareTo(MEMORY_PCT_MAX) > 0) {
            throw invalid(field + " 必须在 1 到 " + (int) LoadBounds.MEMORY_TARGET_PCT_MAX + " 之间");
        }
        double parsed = value.doubleValue();
        if (Double.isNaN(parsed) || Double.isInfinite(parsed)
                || parsed < LoadBounds.MEMORY_TARGET_PCT_MIN
                || parsed > LoadBounds.MEMORY_TARGET_PCT_MAX) {
            throw invalid(field + " 必须在 1 到 " + (int) LoadBounds.MEMORY_TARGET_PCT_MAX + " 之间");
        }
        return parsed;
    }

    private static BigDecimal decimal(JsonNode raw, String field) {
        JsonNode node = raw.get(field);
        if (node == null || !node.isNumber()) {
            throw invalid(field + " 不合法");
        }
        BigDecimal value = node.decimalValue().stripTrailingZeros();
        if (value.scale() < 0) {
            value = value.setScale(0);
        }
        return value;
    }

    private static JsonNode readObject(String json) {
        if (json == null || json.isBlank()) {
            throw malformed();
        }
        try (JsonParser parser = JSON.createParser(json)) {
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

    private static String write(ObjectNode snapshot) {
        try {
            return JSON.writeValueAsString(snapshot);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException(exception);
        }
    }

    private static ApiException malformed() {
        return new ApiException(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", "请求格式不合法");
    }

    private static ApiException invalid(String message) {
        return new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "TARGET_INVALID", message);
    }

    record Draft(
            String name,
            String description,
            String resourceMode,
            Double cpuTargetPct,
            String memoryMode,
            Long memoryTargetBytes,
            Double memoryTargetPct,
            String runMode,
            Long durationSeconds,
            Long expectedVersion) {
    }
}
