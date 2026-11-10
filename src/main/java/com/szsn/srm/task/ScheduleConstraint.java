package com.szsn.srm.task;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.szsn.srm.common.ApiException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;

/**
 * 计划只能绑定有限时长模板，时长不超过 24 小时；固定间隔还要求时长不超过间隔。
 */
final class ScheduleConstraint {
    static final long MAX_DURATION_SECONDS = 24L * 60 * 60;
    static final long MIN_INTERVAL_SECONDS = 60L;
    static final int MAX_SCHEDULES = 100;
    static final String NOT_FINITE = "NOT_FINITE";
    static final String DURATION_EXCEEDS_24H = "DURATION_EXCEEDS_24H";
    static final String DURATION_EXCEEDS_INTERVAL = "DURATION_EXCEEDS_INTERVAL";

    private static final ObjectMapper JSON = new ObjectMapper();

    private ScheduleConstraint() {
    }

    static void assertBindable(String runMode, Long durationSeconds, String type, Long intervalSeconds) {
        String reason = reason(runMode, durationSeconds, type, intervalSeconds);
        if (reason == null) {
            return;
        }
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("reason", reason);
        details.put("maxDurationSeconds", MAX_DURATION_SECONDS);
        if (intervalSeconds != null) {
            details.put("intervalSeconds", intervalSeconds);
        }
        throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "TEMPLATE_NOT_SCHEDULABLE", message(reason), details);
    }

    static void assertReferenced(String runMode, Long durationSeconds, List<Ref> schedules) {
        if (schedules == null || schedules.isEmpty()) {
            return;
        }
        List<Map<String, Object>> problems = new ArrayList<>();
        for (Ref schedule : schedules) {
            Long interval = intervalSeconds(schedule.type(), schedule.scheduleJson());
            String reason = reason(runMode, durationSeconds, schedule.type(), interval);
            if (reason == null) {
                continue;
            }
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id", schedule.id());
            item.put("type", schedule.type());
            item.put("reason", reason);
            problems.add(item);
        }
        if (problems.isEmpty()) {
            return;
        }
        String message = problems.size() == 1
                ? message(String.valueOf(problems.get(0).get("reason")))
                : "关联模板不满足计划时长约束";
        throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "TEMPLATE_NOT_SCHEDULABLE", message,
                Map.of("schedules", List.copyOf(problems), "maxDurationSeconds", MAX_DURATION_SECONDS));
    }

    static Long intervalSeconds(String type, String scheduleJson) {
        if (!"FIXED_INTERVAL".equals(type) || scheduleJson == null || scheduleJson.isBlank()) {
            return null;
        }
        try {
            JsonNode node = JSON.readTree(scheduleJson);
            JsonNode value = node.get("intervalSeconds");
            if (value == null || !value.isIntegralNumber()) {
                return null;
            }
            return value.longValue();
        } catch (Exception exception) {
            return null;
        }
    }

    private static String reason(String runMode, Long durationSeconds, String type, Long intervalSeconds) {
        if (!"FINITE".equals(runMode) || durationSeconds == null) {
            return NOT_FINITE;
        }
        if (durationSeconds > MAX_DURATION_SECONDS) {
            return DURATION_EXCEEDS_24H;
        }
        if ("FIXED_INTERVAL".equals(type) && (intervalSeconds == null || durationSeconds > intervalSeconds)) {
            return DURATION_EXCEEDS_INTERVAL;
        }
        return null;
    }

    private static String message(String reason) {
        return switch (reason) {
            case NOT_FINITE -> "计划只能绑定有限时长模板";
            case DURATION_EXCEEDS_24H -> "计划执行时长不能超过 24 小时";
            case DURATION_EXCEEDS_INTERVAL -> "固定间隔计划的执行时长不能超过间隔";
            default -> "关联模板不满足计划时长约束";
        };
    }

    record Ref(String id, String type, String scheduleJson) {
    }
}
