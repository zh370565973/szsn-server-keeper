package com.szsn.srm.task;

import com.szsn.srm.common.ApiException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import org.springframework.http.HttpStatus;

/**
 * 只计算下一次触发时间。不扫描到期项，不补写漏执行，也不启动负载。
 * 夏令时缺口跳过；重叠的本地时间只取较早的那一次。固定间隔从 UTC 锚点直接跳到下一次。
 */
final class ScheduleCalendar {
    private ScheduleCalendar() {
    }

    static long nextFire(ScheduleSpec.Definition definition, long now) {
        try {
            return switch (definition.type()) {
                case "ONCE" -> nextOnce(definition.startAt(), now);
                case "DAILY" -> nextLocal(zone(definition.timezone()), localTime(definition.localTime()), null, now);
                case "WEEKLY" -> nextLocal(zone(definition.timezone()), localTime(definition.localTime()),
                        definition.weekdays(), now);
                case "FIXED_INTERVAL" -> nextInterval(definition.anchorAt(), definition.intervalSeconds(), now);
                default -> throw invalid("type 不合法");
            };
        } catch (ApiException exception) {
            throw exception;
        } catch (ArithmeticException exception) {
            throw invalid("下一次触发时间超出可表示范围");
        }
    }

    private static long nextOnce(long startAt, long now) {
        if (startAt <= now) {
            throw invalid("一次性计划的开始时间必须晚于当前时间");
        }
        return startAt;
    }

    private static long nextInterval(long anchorAt, long intervalSeconds, long now) {
        long intervalMs = Math.multiplyExact(intervalSeconds, 1000L);
        if (intervalMs <= 0) {
            throw invalid("intervalSeconds 不合法");
        }
        if (anchorAt >= now) {
            return anchorAt;
        }
        long elapsed = Math.subtractExact(now, anchorAt);
        long steps = elapsed / intervalMs;
        if (elapsed % intervalMs != 0) {
            steps = Math.incrementExact(steps);
        }
        return Math.addExact(anchorAt, Math.multiplyExact(steps, intervalMs));
    }

    private static long nextLocal(ZoneId zone, LocalTime time, List<Integer> weekdays, long now) {
        Instant nowInstant = Instant.ofEpochMilli(now);
        LocalDate start = nowInstant.atZone(zone).toLocalDate();
        for (int day = 0; day < 14; day++) {
            LocalDate date = start.plusDays(day);
            if (weekdays != null && !weekdays.contains(date.getDayOfWeek().getValue())) {
                continue;
            }
            Instant candidate = firstInstant(date, time, zone);
            if (candidate != null && !candidate.isBefore(nowInstant)) {
                return candidate.toEpochMilli();
            }
        }
        throw invalid("在可预见范围内没有合法触发时间");
    }

    /**
     * 缺口返回 null。重叠时取较早的瞬间，也就是该本地时间的第一次。
     */
    private static Instant firstInstant(LocalDate date, LocalTime time, ZoneId zone) {
        LocalDateTime local = LocalDateTime.of(date, time);
        List<ZoneOffset> offsets = zone.getRules().getValidOffsets(local);
        Instant earliest = null;
        for (ZoneOffset offset : offsets) {
            Instant candidate = local.atOffset(offset).toInstant();
            if (earliest == null || candidate.isBefore(earliest)) {
                earliest = candidate;
            }
        }
        return earliest;
    }

    private static ZoneId zone(String timezone) {
        try {
            if (timezone == null || !ZoneId.getAvailableZoneIds().contains(timezone)) {
                throw invalid("timezone 不合法");
            }
            return ZoneId.of(timezone);
        } catch (ApiException exception) {
            throw exception;
        } catch (Exception exception) {
            throw invalid("timezone 不合法");
        }
    }

    private static LocalTime localTime(String value) {
        try {
            return LocalTime.parse(value);
        } catch (Exception exception) {
            throw invalid("localTime 不合法");
        }
    }

    private static ApiException invalid(String message) {
        return new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "SCHEDULE_INVALID", message);
    }
}
