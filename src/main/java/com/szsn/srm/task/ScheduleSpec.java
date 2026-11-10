package com.szsn.srm.task;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.szsn.srm.common.ApiException;
import java.math.BigDecimal;
import java.time.ZoneId;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;
import org.springframework.http.HttpStatus;

/**
 * 把计划请求收成四种固定形态。不接受 Cron 表达式。
 */
final class ScheduleSpec {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Pattern LOCAL_TIME = Pattern.compile("^(?:[01]\\d|2[0-3]):[0-5]\\d:[0-5]\\d$");
    private static final long MAX_INTERVAL_SECONDS = Long.MAX_VALUE / 1000L;
    private static final Set<String> ONCE = Set.of("taskId", "type", "startAt", "enabled");
    private static final Set<String> DAILY = Set.of("taskId", "type", "timezone", "localTime", "enabled");
    private static final Set<String> WEEKLY = Set.of("taskId", "type", "timezone", "localTime", "weekdays", "enabled");
    private static final Set<String> FIXED = Set.of("taskId", "type", "anchorAt", "intervalSeconds", "enabled");

    private ScheduleSpec() {
    }

    static Draft parseCreate(String json) {
        return parse(json, false);
    }

    static Draft parseUpdate(String json) {
        return parse(json, true);
    }

    static EnableDraft parseEnable(String json) {
        JsonNode raw = readObject(json);
        Iterator<String> names = raw.fieldNames();
        while (names.hasNext()) {
            String name = names.next();
            if (!name.equals("enabled") && !name.equals("expectedVersion")) {
                throw invalid(name.equals("cron") || name.equals("cronExpression")
                        ? "不提供 Cron 表达式"
                        : "配置包含无法识别的字段");
            }
            if (raw.get(name).isNull()) {
                throw invalid("配置字段不能为空");
            }
        }
        if (!raw.has("expectedVersion")) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", "修改计划必须提供 expectedVersion");
        }
        return new EnableDraft(enabled(raw), exactLong(raw, "expectedVersion", 1, Long.MAX_VALUE));
    }

    static Definition fromStored(String type, String timezone, String scheduleJson) {
        JsonNode raw = readObject(scheduleJson);
        return switch (type) {
            case "ONCE" -> new Definition("ONCE", "UTC", exactLong(raw, "startAt", Long.MIN_VALUE, Long.MAX_VALUE),
                    null, null, null, null);
            case "DAILY" -> dailyOrWeekly("DAILY", timezone, raw);
            case "WEEKLY" -> dailyOrWeekly("WEEKLY", timezone, raw);
            case "FIXED_INTERVAL" -> new Definition("FIXED_INTERVAL", "UTC", null, null, null,
                    exactLong(raw, "anchorAt", Long.MIN_VALUE, Long.MAX_VALUE),
                    exactLong(raw, "intervalSeconds", ScheduleConstraint.MIN_INTERVAL_SECONDS, MAX_INTERVAL_SECONDS));
            default -> throw invalid("计划配置无法计算下一次触发时间");
        };
    }

    static String toJson(Definition definition) {
        ObjectNode node = JSON.createObjectNode();
        switch (definition.type()) {
            case "ONCE" -> node.put("startAt", definition.startAt());
            case "DAILY" -> {
                node.put("localTime", definition.localTime());
                node.put("timezone", definition.timezone());
            }
            case "WEEKLY" -> {
                node.put("localTime", definition.localTime());
                node.put("timezone", definition.timezone());
                ArrayNode days = node.putArray("weekdays");
                for (Integer day : definition.weekdays()) {
                    days.add(day);
                }
            }
            case "FIXED_INTERVAL" -> {
                node.put("anchorAt", definition.anchorAt());
                node.put("intervalSeconds", definition.intervalSeconds());
            }
            default -> throw new IllegalStateException(definition.type());
        }
        try {
            return JSON.writeValueAsString(node);
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
    }

    private static Draft parse(String json, boolean update) {
        JsonNode raw = readObject(json);
        String type = text(raw, "type");
        if ("CRON".equals(type)) {
            throw invalid("不提供 Cron 表达式");
        }
        Set<String> allowed = allowed(type);
        if (allowed == null) {
            throw invalid("type 不合法");
        }
        Iterator<String> names = raw.fieldNames();
        while (names.hasNext()) {
            String name = names.next();
            if ("cron".equals(name) || "cronExpression".equals(name)) {
                throw invalid("不提供 Cron 表达式");
            }
            boolean known = allowed.contains(name) || (update && "expectedVersion".equals(name));
            if (!known || raw.get(name).isNull()) {
                throw invalid(known ? "配置字段不能为空" : "配置包含无法识别的字段");
            }
        }
        for (String field : allowed) {
            if (!raw.has(field)) {
                throw invalid(field + " 不合法");
            }
        }
        Long expectedVersion = null;
        if (update) {
            if (!raw.has("expectedVersion")) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", "修改计划必须提供 expectedVersion");
            }
            expectedVersion = exactLong(raw, "expectedVersion", 1, Long.MAX_VALUE);
        }
        Definition definition = switch (type) {
            case "ONCE" -> new Definition("ONCE", "UTC",
                    exactLong(raw, "startAt", Long.MIN_VALUE, Long.MAX_VALUE), null, null, null, null);
            case "DAILY" -> new Definition("DAILY", zone(text(raw, "timezone")), null, localTime(raw), null, null, null);
            case "WEEKLY" -> new Definition("WEEKLY", zone(text(raw, "timezone")), null, localTime(raw),
                    weekdays(raw), null, null);
            case "FIXED_INTERVAL" -> new Definition("FIXED_INTERVAL", "UTC", null, null, null,
                    exactLong(raw, "anchorAt", Long.MIN_VALUE, Long.MAX_VALUE),
                    exactLong(raw, "intervalSeconds", ScheduleConstraint.MIN_INTERVAL_SECONDS, MAX_INTERVAL_SECONDS));
            default -> throw invalid("type 不合法");
        };
        return new Draft(text(raw, "taskId"), enabled(raw), definition, expectedVersion);
    }

    private static Definition dailyOrWeekly(String type, String columnTimezone, JsonNode raw) {
        String timezone = zone(text(raw, "timezone"));
        if (columnTimezone != null && !columnTimezone.equals(timezone)) {
            throw invalid("计划配置无法计算下一次触发时间");
        }
        List<Integer> days = "WEEKLY".equals(type) ? weekdays(raw) : null;
        return new Definition(type, timezone, null, localTime(raw), days, null, null);
    }

    private static Set<String> allowed(String type) {
        return switch (type) {
            case "ONCE" -> ONCE;
            case "DAILY" -> DAILY;
            case "WEEKLY" -> WEEKLY;
            case "FIXED_INTERVAL" -> FIXED;
            default -> null;
        };
    }

    private static boolean enabled(JsonNode raw) {
        JsonNode node = raw.get("enabled");
        if (node == null || !node.isBoolean()) {
            throw invalid("enabled 不合法");
        }
        return node.booleanValue();
    }

    private static String localTime(JsonNode raw) {
        String value = text(raw, "localTime");
        if (!LOCAL_TIME.matcher(value).matches()) {
            throw invalid("localTime 必须是 HH:mm:ss");
        }
        return value;
    }

    private static String zone(String timezone) {
        if (!ZoneId.getAvailableZoneIds().contains(timezone)) {
            throw invalid("timezone 不合法");
        }
        try {
            ZoneId.of(timezone);
        } catch (Exception exception) {
            throw invalid("timezone 不合法");
        }
        return timezone;
    }

    private static List<Integer> weekdays(JsonNode raw) {
        JsonNode node = raw.get("weekdays");
        if (node == null || !node.isArray() || node.isEmpty() || node.size() > 7) {
            throw invalid("weekdays 必须是 1 到 7 的 ISO 星期");
        }
        TreeSet<Integer> days = new TreeSet<>();
        for (JsonNode item : node) {
            if (item == null || !item.isIntegralNumber() || item.decimalValue().stripTrailingZeros().scale() > 0) {
                throw invalid("weekdays 必须是 1 到 7 的 ISO 星期");
            }
            int day;
            try {
                day = item.decimalValue().intValueExact();
            } catch (ArithmeticException exception) {
                throw invalid("weekdays 必须是 1 到 7 的 ISO 星期");
            }
            if (day < 1 || day > 7 || !days.add(day)) {
                throw invalid(day < 1 || day > 7 ? "weekdays 必须是 1 到 7 的 ISO 星期" : "weekdays 不能重复");
            }
        }
        return List.copyOf(days);
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
            if ("intervalSeconds".equals(field) && parsed < ScheduleConstraint.MIN_INTERVAL_SECONDS) {
                throw invalid("intervalSeconds 必须大于等于 60");
            }
            throw invalid(field + " 不合法");
        }
        return parsed;
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

    private static ApiException malformed() {
        return new ApiException(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", "请求格式不合法");
    }

    private static ApiException invalid(String message) {
        return new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "SCHEDULE_INVALID", message);
    }

    record Definition(
            String type,
            String timezone,
            Long startAt,
            String localTime,
            List<Integer> weekdays,
            Long anchorAt,
            Long intervalSeconds) {
    }

    record Draft(String taskId, boolean enabled, Definition definition, Long expectedVersion) {
    }

    record EnableDraft(boolean enabled, long expectedVersion) {
    }
}
