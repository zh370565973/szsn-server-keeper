package com.szsn.srm.task;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.szsn.srm.common.ApiException;
import com.szsn.srm.resource.SessionRejectedException;
import java.time.Clock;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 每秒扫描到期计划。同一时刻只启动一个；迟到超过 5 秒记 SKIPPED_MISFIRE。
 * 离线或时钟前跳不补跑，多段漏执行只写一条汇总。时钟回拨不重复触发。
 * 冲突、暂停会话、开关关闭和急停锁存都不启动负载。
 */
@Service
public class ScheduleDispatcher {
    static final String MISFIRE = "SKIPPED_MISFIRE";
    static final String CONFLICT = "SKIPPED_CONFLICT";
    static final String CONTROL_DISABLED = "SKIPPED_CONTROL_DISABLED";

    private static final Logger log = LoggerFactory.getLogger(ScheduleDispatcher.class);
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String OPEN_STATUSES =
            "'STARTING', 'RUNNING', 'PAUSING', 'PAUSED', 'STOPPING'";

    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactionTemplate;
    private final TaskTemplateService templates;
    private final ScheduleAdmission admission;
    private final ScheduleLauncher launcher;
    private final Clock clock;
    private final Object scanLock = new Object();

    private Long processStartedAt;
    private Long lastScanAt;

    public ScheduleDispatcher(JdbcTemplate jdbc, PlatformTransactionManager transactions,
                              TaskTemplateService templates, ScheduleAdmission admission,
                              ScheduleLauncher launcher, Clock clock) {
        this.jdbc = jdbc;
        this.transactionTemplate = new TransactionTemplate(transactions);
        this.templates = templates;
        this.admission = admission;
        this.launcher = launcher;
        this.clock = clock;
    }

    public void scan() {
        synchronized (scanLock) {
            long now = clock.millis();
            if (processStartedAt == null) {
                processStartedAt = now;
            }
            if (lastScanAt != null && now < lastScanAt) {
                lastScanAt = now;
                return;
            }
            boolean watching = lastScanAt == null
                    ? now - processStartedAt <= ScheduleDue.GRACE_MS
                    : now - lastScanAt <= ScheduleDue.GRACE_MS;
            dispatch(now, watching, processStartedAt);
            lastScanAt = now;
        }
    }

    private void dispatch(long now, boolean watching, long onlineSince) {
        List<String> ids = jdbc.query("""
                SELECT ID FROM TASK_SCHEDULE
                WHERE ENABLED = TRUE AND NEXT_FIRE_AT IS NOT NULL AND NEXT_FIRE_AT <= ?
                ORDER BY NEXT_FIRE_AT ASC, ID ASC
                """, (rs, row) -> rs.getString(1), now);
        for (String id : ids) {
            Launch launch;
            try {
                launch = handle(id, now, watching, onlineSince);
            } catch (RuntimeException exception) {
                log.warn("计划 {} 处理失败", id, exception);
                continue;
            }
            if (launch == null) {
                continue;
            }
            try {
                launcher.launch(launch.executionId(), launch.snapshotJson());
            } catch (RuntimeException exception) {
                log.warn("计划启动失败 {}", launch.executionId(), exception);
                finishUnstarted(launch.executionId(), exception, now);
            }
        }
    }

    private Launch handle(String id, long now, boolean watching, long onlineSince) {
        return transactionTemplate.execute(status -> {
            DueSchedule schedule = lock(id);
            if (schedule == null || !schedule.enabled() || schedule.nextFireAt() == null
                    || schedule.nextFireAt() > now) {
                return null;
            }
            ScheduleSpec.Definition definition;
            ScheduleDue.Plan plan;
            try {
                definition = ScheduleSpec.fromStored(schedule.type(), schedule.timezone(), schedule.scheduleJson());
                plan = ScheduleDue.plan(definition, schedule.nextFireAt(), now, watching, onlineSince);
            } catch (RuntimeException exception) {
                insertSkip(schedule, schedule.nextFireAt(), "{}", "SCHEDULE_INVALID", null, now);
                advance(schedule, null, true, now);
                return null;
            }
            if (plan == null) {
                return null;
            }
            PreparedTemplate template = prepareTemplate(schedule.taskId(), definition);
            if (plan.missedCount() > 0) {
                insertSkip(schedule, plan.firstAt(), template.snapshot(), MISFIRE, missedStats(plan.missedCount()), now);
            }
            String executionId = null;
            if (plan.startLatest()) {
                executionId = admitStart(schedule, plan.lastAt(), template, now);
            }
            boolean disable = plan.disable() || plan.nextAt() == null || plan.nextAt() <= now;
            advance(schedule, plan.nextAt(), disable, now);
            return executionId == null ? null : new Launch(executionId, template.snapshot());
        });
    }

    private String admitStart(DueSchedule schedule, long scheduledAt, PreparedTemplate template, long now) {
        if (!template.bindable()) {
            insertSkip(schedule, scheduledAt, template.snapshot(), template.reason(), null, now);
            return null;
        }
        if (!admission.controlOpen()) {
            insertSkip(schedule, scheduledAt, template.snapshot(), CONTROL_DISABLED, null, now);
            return null;
        }
        if (admission.sessionOccupied() || nonTerminalExists()) {
            insertSkip(schedule, scheduledAt, template.snapshot(), CONFLICT, null, now);
            return null;
        }
        if (alreadyRecorded(schedule.id(), scheduledAt)) {
            return null;
        }
        String executionId = UUID.randomUUID().toString();
        jdbc.update("""
                INSERT INTO TASK_EXECUTION (
                    ID, TASK_ID, SCHEDULE_ID, SCHEDULED_AT, CONFIG_SNAPSHOT_JSON,
                    STATUS, VERSION, REQUEST_SOURCE
                ) VALUES (?, ?, ?, ?, ?, 'STARTING', 1, 'SCHEDULE')
                """, executionId, schedule.taskId(), schedule.id(), scheduledAt, template.snapshot());
        return executionId;
    }

    private void insertSkip(DueSchedule schedule, long scheduledAt, String snapshot, String reason, String stats,
                            long now) {
        if (alreadyRecorded(schedule.id(), scheduledAt)) {
            return;
        }
        try {
            jdbc.update("""
                    INSERT INTO TASK_EXECUTION (
                        ID, TASK_ID, SCHEDULE_ID, SCHEDULED_AT, CONFIG_SNAPSHOT_JSON,
                        STATUS, REASON, VERSION, ENDED_AT, STATS_JSON, REQUEST_SOURCE
                    ) VALUES (?, ?, ?, ?, ?, 'SKIPPED', ?, 1, ?, ?, 'SCHEDULE')
                    """, UUID.randomUUID().toString(), schedule.taskId(), schedule.id(), scheduledAt, snapshot,
                    reason, now, stats);
        } catch (DuplicateKeyException ignored) {
            // 同一触发点已经写过，时钟回拨或重复扫描都不再写第二条。
        }
    }

    private void advance(DueSchedule schedule, Long nextAt, boolean disable, long now) {
        boolean enabled = schedule.enabled() && !disable && nextAt != null;
        int updated = jdbc.update("""
                UPDATE TASK_SCHEDULE
                SET ENABLED = ?, NEXT_FIRE_AT = ?, VERSION = VERSION + 1, UPDATED_AT = ?
                WHERE ID = ? AND VERSION = ?
                """, enabled, enabled ? nextAt : null, now, schedule.id(), schedule.version());
        if (updated != 1) {
            throw new IllegalStateException("计划触发时间未能更新");
        }
    }

    private void finishUnstarted(String executionId, RuntimeException exception, long now) {
        String reason = reasonOf(exception);
        String status = reason.startsWith("SKIPPED_") ? "SKIPPED" : "FAILED";
        transactionTemplate.executeWithoutResult(ignored -> jdbc.update("""
                UPDATE TASK_EXECUTION
                SET STATUS = ?, REASON = ?, ENDED_AT = ?, VERSION = VERSION + 1
                WHERE ID = ? AND STATUS = 'STARTING'
                """, status, reason, now, executionId));
    }

    private PreparedTemplate prepareTemplate(String taskId, ScheduleSpec.Definition definition) {
        try {
            TaskTemplateView view = templates.get(taskId);
            ScheduleConstraint.assertBindable(view.runMode(), view.durationSeconds(), definition.type(),
                    definition.intervalSeconds());
            return new PreparedTemplate(TaskTemplateSpec.toStartJson(view), true, null);
        } catch (ApiException exception) {
            String reason = "NOT_FOUND".equals(exception.code()) ? "TEMPLATE_UNAVAILABLE" : "TEMPLATE_NOT_SCHEDULABLE";
            return new PreparedTemplate("{}", false, reason);
        }
    }

    private boolean nonTerminalExists() {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM TASK_EXECUTION WHERE STATUS IN (" + OPEN_STATUSES + ")", Integer.class);
        return count != null && count > 0;
    }

    private boolean alreadyRecorded(String scheduleId, long scheduledAt) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM TASK_EXECUTION WHERE SCHEDULE_ID = ? AND SCHEDULED_AT = ?",
                Integer.class, scheduleId, scheduledAt);
        return count != null && count > 0;
    }

    private DueSchedule lock(String id) {
        List<DueSchedule> rows = jdbc.query("""
                SELECT ID, TASK_ID, TYPE, TIMEZONE, SCHEDULE_JSON, ENABLED, NEXT_FIRE_AT, VERSION
                FROM TASK_SCHEDULE
                WHERE ID = ?
                FOR UPDATE
                """, (rs, row) -> {
            Long nextFireAt = rs.getObject("NEXT_FIRE_AT") == null ? null : rs.getLong("NEXT_FIRE_AT");
            return new DueSchedule(
                    rs.getString("ID"),
                    rs.getString("TASK_ID"),
                    rs.getString("TYPE"),
                    rs.getString("TIMEZONE"),
                    rs.getString("SCHEDULE_JSON"),
                    rs.getBoolean("ENABLED"),
                    nextFireAt,
                    rs.getLong("VERSION"));
        }, id);
        return rows.isEmpty() ? null : rows.get(0);
    }

    private static String missedStats(long missedCount) {
        ObjectNode node = JSON.createObjectNode();
        node.put("missedCount", missedCount);
        try {
            return JSON.writeValueAsString(node);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException(exception);
        }
    }

    private static String reasonOf(RuntimeException exception) {
        if (exception instanceof SessionRejectedException rejected
                && rejected.reason() == SessionRejectedException.Reason.SLOT_OCCUPIED) {
            return CONFLICT;
        }
        if (exception instanceof ApiException api) {
            if ("CONTROL_DISABLED".equals(api.code()) || "CONTROL_LATCHED".equals(api.code())) {
                return CONTROL_DISABLED;
            }
            if (api.code() != null && !api.code().isBlank() && api.code().length() <= 64) {
                return api.code();
            }
        }
        return "START_FAILED";
    }

    private record DueSchedule(String id, String taskId, String type, String timezone, String scheduleJson,
                               boolean enabled, Long nextFireAt, long version) {
    }

    private record PreparedTemplate(String snapshot, boolean bindable, String reason) {
    }

    private record Launch(String executionId, String snapshotJson) {
    }
}
