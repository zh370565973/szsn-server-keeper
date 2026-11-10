package com.szsn.srm.resource;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.szsn.srm.common.ApiException;
import com.szsn.srm.monitor.MetricSnapshot;
import com.szsn.srm.monitor.Quality;
import com.szsn.srm.monitor.SnapshotStore;
import com.szsn.srm.safety.SafetyLoop;
import com.szsn.srm.safety.SafetySignal;
import com.szsn.srm.system.ControlAvailability;
import com.szsn.srm.system.EnvironmentReport;
import com.szsn.srm.system.StorageHealth;
import java.time.Clock;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

/**
 * CPU / 联合控制编排。成功响应只反映已受理的会话状态，不假定 duty 已调到目标。
 * MEMORY 单独启动仍 503；COMBINED 任一引擎失败时两侧都停止并由启动服务写 FAILED。
 * 暂停/停止会清除内存负载引用（heldBytes→0），不调用 {@code System.gc()}，不把 RSS 下降当作成功。
 * 本类不分配真实 {@code byte[]}。
 */
@Service
public class CpuControlService implements DisposableBean {
    static final double CPU_TARGET_MIN = 1D;
    static final double CPU_TARGET_MAX = 60D;
    static final long MONITOR_READY_MS = 10_000L;
    static final long MONITOR_FRESH_MS = 3_000L;

    private static final ObjectMapper JSON = new ObjectMapper();

    private final ControlAvailability availability;
    private final ControlSwitch controlSwitch;
    private final ResourceLoadManager sessions;
    private final ExecutionStartService starts;
    private final CpuEngine engine;
    private final MemoryEngine memoryEngine;
    private final CombinedLoadStarter combined;
    private final MemoryBudgetGate memoryBudget;
    private final SnapshotStore snapshots;
    private final SafetyLoop safetyLoop;
    private final StorageHealth storageHealth;
    private final Clock clock;
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(runnable -> {
        Thread thread = new Thread(runnable, "srm-cpu-lifecycle");
        thread.setDaemon(true);
        return thread;
    });
    private final AtomicReference<ScheduledFuture<?>> durationStop = new AtomicReference<>();

    public CpuControlService(ControlAvailability availability, ControlSwitch controlSwitch,
                             ResourceLoadManager sessions, ExecutionStartService starts, CpuEngine engine,
                             MemoryEngine memoryEngine, MemoryBudgetGate memoryBudget, SnapshotStore snapshots,
                             SafetyLoop safetyLoop, StorageHealth storageHealth, Clock clock) {
        this.availability = availability;
        this.controlSwitch = controlSwitch;
        this.sessions = sessions;
        this.starts = starts;
        this.engine = engine;
        this.memoryEngine = memoryEngine;
        this.combined = new CombinedLoadStarter(sessions, engine, memoryEngine, memoryBudget);
        this.memoryBudget = memoryBudget;
        this.snapshots = snapshots;
        this.safetyLoop = safetyLoop;
        this.storageHealth = storageHealth;
        this.clock = clock;
    }

    public Map<String, Object> preflight(String configJson) {
        JsonNode config = readConfig(configJson);
        String mode = text(config, "resourceMode");
        List<String> reasons = new ArrayList<>();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("resourceMode", mode);
        body.put("sideEffect", false);
        if ("MEMORY".equals(mode)) {
            return memoryPreflight(config, body, reasons);
        }
        if ("COMBINED".equals(mode)) {
            return combinedPreflight(config, body, reasons);
        }
        if (!"CPU".equals(mode)) {
            reasons.add("resourceMode 不合法");
            body.put("ok", false);
            body.put("reasons", List.copyOf(reasons));
            body.put("budget", null);
            return body;
        }
        double target = requireCpuTarget(config);
        body.put("cpuTargetPct", target);
        collectStartReasons(reasons);
        EnvironmentReport env = availability.environment();
        int workers = CpuEngine.cappedWorkerCount(0, Math.max(1, env.logicalCpus()));
        body.put("workerCount", workers);
        body.put("ok", reasons.isEmpty());
        body.put("reasons", List.copyOf(reasons));
        body.put("budget", null);
        return body;
    }

    public Map<String, Object> start(String idempotencyKey, String configJson) {
        JsonNode preview = readConfig(configJson);
        String mode = text(preview, "resourceMode");
        if ("MEMORY".equals(mode)) {
            rejectMemoryOnlyStart(preview);
        }
        if (!"CPU".equals(mode) && !"COMBINED".equals(mode)) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "TARGET_INVALID", "resourceMode 不合法");
        }
        double target = requireCpuTarget(preview);
        String memoryMode = null;
        Long memoryTargetBytes = null;
        Double memoryTargetPct = null;
        if ("COMBINED".equals(mode)) {
            memoryMode = text(preview, "memoryMode");
            if (MemoryEngine.MODE_EXTRA_BYTES.equals(memoryMode)) {
                memoryTargetBytes = requireMemoryTargetBytes(preview);
                memoryBudget.requireFixedCapacity(memoryTargetBytes);
            } else if (MemoryEngine.MODE_SYSTEM_PERCENT.equals(memoryMode)) {
                memoryTargetPct = requireMemoryTargetPct(preview);
            } else {
                throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "TARGET_INVALID", "memoryMode 不合法");
            }
        }
        List<String> reasons = new ArrayList<>();
        collectStartReasons(reasons);
        if (!reasons.isEmpty()) {
            throw ControlAvailability.unavailable(reasons);
        }
        EnvironmentReport env = availability.environment();
        int workers = CpuEngine.cappedWorkerCount(0, Math.max(1, env.logicalCpus()));
        Long durationSeconds = finiteDuration(preview);
        final String resolvedMemoryMode = memoryMode;
        final Long resolvedMemoryBytes = memoryTargetBytes;
        final Double resolvedMemoryPct = memoryTargetPct;
        final boolean combinedMode = "COMBINED".equals(mode);
        StartedExecution started = starts.start(idempotencyKey, configJson, committed -> {
            SessionClaim claim = sessions.claim(committed.executionId());
            try {
                if (combinedMode) {
                    combined.start(claim, target, workers, resolvedMemoryMode, resolvedMemoryBytes, resolvedMemoryPct);
                } else {
                    engine.start(claim, target, workers);
                }
            } catch (RuntimeException exception) {
                if (!combinedMode) {
                    try {
                        sessions.requestStop(committed.executionId(), StopKind.FAILURE);
                    } catch (RuntimeException ignored) {
                        // 启动失败路径以原始异常为准
                    }
                    scheduleAwaitStop();
                }
                // COMBINED 的回收已在 CombinedLoadStarter 内完成；ExecutionStartService 写 FAILED
                throw exception;
            }
            if (durationSeconds != null) {
                scheduleDurationStop(committed.executionId(), durationSeconds);
            }
        });
        return executionAccepted(started.executionId(), started.status().name(), started.version(), started.replay());
    }

    public Map<String, Object> pause(String executionId) {
        requireActive(executionId);
        CpuEngineView view = engine.pause();
        if (memoryEngine.view().executionId() != null) {
            memoryEngine.pause(false);
        } else {
            memoryEngine.clearLoadReferences();
        }
        return executionAccepted(executionId, sessions.active().map(session -> session.status().name()).orElse("PAUSED"),
                view.configVersion(), false);
    }

    public Map<String, Object> resume(String executionId) {
        requireActive(executionId);
        ResumeGate gate = () -> {
            List<String> reasons = new ArrayList<>();
            collectResumeReasons(reasons);
            if (!reasons.isEmpty()) {
                throw ControlAvailability.unavailable(reasons);
            }
        };
        CpuEngineView view = engine.resume(gate);
        if (memoryEngine.view().executionId() != null) {
            memoryEngine.resume(ResumeGate.OPEN, false);
        }
        return executionAccepted(executionId, sessions.active().map(session -> session.status().name()).orElse("RUNNING"),
                view.configVersion(), false);
    }

    public Map<String, Object> stop(String executionId) {
        cancelDurationStop();
        sessions.requestStop(executionId, StopKind.USER);
        scheduleAwaitStop();
        String status = sessions.active()
                .filter(session -> session.executionId().equals(executionId))
                .map(session -> session.status().name())
                .orElseGet(() -> sessions.lastFinished()
                        .filter(session -> session.executionId().equals(executionId))
                        .map(session -> session.status().name())
                        .orElse("STOPPING"));
        return executionAccepted(executionId, status, engine.view().configVersion(), false);
    }

    public Map<String, Object> updateTargets(String executionId, String bodyJson) {
        requireActive(executionId);
        JsonNode body = readConfig(bodyJson);
        if (body.has("memoryMode") || body.has("memoryTargetBytes") || body.has("memoryTargetPct")) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "TARGET_INVALID", "CPU 模式不能修改内存目标");
        }
        if (!body.has("cpuTargetPct") || !body.has("expectedVersion")) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", "cpuTargetPct 与 expectedVersion 必填");
        }
        double target = requireCpuTarget(body);
        long expected = body.get("expectedVersion").asLong();
        CpuEngineView view = engine.updateTarget(expected, target);
        return executionAccepted(executionId,
                sessions.active().map(session -> session.status().name()).orElse("RUNNING"),
                view.configVersion(), false);
    }

    public Map<String, Object> cpuStatus() {
        CpuEngineView view = engine.view();
        Map<String, Object> cpu = new LinkedHashMap<>();
        if (view.executionId() == null) {
            cpu.put("targetPct", null);
            cpu.put("actualPct", null);
            cpu.put("duty", null);
            cpu.put("workerCount", null);
            cpu.put("ewma", null);
            cpu.put("configVersion", null);
            cpu.put("externalLoadHigh", false);
            cpu.put("safetyZeroed", false);
            cpu.put("stopTimeout", false);
            cpu.put("limitedReasons", List.of());
            return cpu;
        }
        cpu.put("targetPct", view.targetPct());
        cpu.put("actualPct", view.actualPct());
        cpu.put("duty", view.duty());
        cpu.put("workerCount", view.workerCount());
        cpu.put("ewma", view.ewma());
        cpu.put("configVersion", view.configVersion());
        cpu.put("externalLoadHigh", view.externalLoadHigh());
        cpu.put("safetyZeroed", view.safetyZeroed());
        cpu.put("stopTimeout", view.stopTimeout());
        cpu.put("limitedReasons", view.limitedReasons());
        return cpu;
    }

    public Map<String, Object> memoryBudgetView() {
        return memoryBudget.budgetView();
    }

    /**
     * 页面分开展示：负载持有、JVM 堆使用、进程驻留内存。
     */
    public Map<String, Object> memoryLoadView() {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("heldBytes", memoryEngine.heldBytes());
        MetricSnapshot raw = snapshots.latest();
        MetricSnapshot.JvmView jvm = raw == null ? null : raw.jvm();
        view.put("heapUsedBytes", jvm == null ? null : jvm.heapUsedBytes());
        view.put("rssBytes", jvm == null ? null : jvm.rssBytes());
        MemoryEngineView engineView = memoryEngine.view();
        view.put("paused", engineView.paused());
        view.put("memoryMode", engineView.memoryMode());
        view.put("limitedReasons", engineView.limitedReasons());
        return view;
    }

    @Override
    public void destroy() {
        cancelDurationStop();
        scheduler.shutdownNow();
    }

    private Map<String, Object> memoryPreflight(JsonNode config, Map<String, Object> body, List<String> reasons) {
        String memoryMode = text(config, "memoryMode");
        body.put("memoryMode", memoryMode);
        Map<String, Object> budget = memoryBudget.budgetView();
        body.put("budget", budget);
        if ("EXTRA_BYTES".equals(memoryMode)) {
            long requested = requireMemoryTargetBytes(config);
            body.put("memoryTargetBytes", requested);
            long allowed = ((Number) budget.get("additionalBytes")).longValue()
                    + ((Number) budget.get("heldBytes")).longValue();
            if (requested > allowed) {
                reasons.add("固定容量超过当前预算");
            }
        } else if ("SYSTEM_PERCENT".equals(memoryMode)) {
            body.put("memoryTargetPct", requireMemoryTargetPct(config));
        } else {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "TARGET_INVALID", "memoryMode 不合法");
        }
        reasons.add(ControlAvailability.MEMORY_ENGINE_REASON);
        body.put("ok", false);
        body.put("reasons", List.copyOf(reasons));
        return body;
    }

    private Map<String, Object> combinedPreflight(JsonNode config, Map<String, Object> body, List<String> reasons) {
        double target = requireCpuTarget(config);
        body.put("cpuTargetPct", target);
        String memoryMode = text(config, "memoryMode");
        body.put("memoryMode", memoryMode);
        Map<String, Object> budget = memoryBudget.budgetView();
        body.put("budget", budget);
        if (MemoryEngine.MODE_EXTRA_BYTES.equals(memoryMode)) {
            long requested = requireMemoryTargetBytes(config);
            body.put("memoryTargetBytes", requested);
            long allowed = ((Number) budget.get("additionalBytes")).longValue()
                    + ((Number) budget.get("heldBytes")).longValue();
            if (requested > allowed) {
                reasons.add("固定容量超过当前预算");
            }
        } else if (MemoryEngine.MODE_SYSTEM_PERCENT.equals(memoryMode)) {
            body.put("memoryTargetPct", requireMemoryTargetPct(config));
        } else {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "TARGET_INVALID", "memoryMode 不合法");
        }
        collectStartReasons(reasons);
        EnvironmentReport env = availability.environment();
        int workers = CpuEngine.cappedWorkerCount(0, Math.max(1, env.logicalCpus()));
        body.put("workerCount", workers);
        body.put("ok", reasons.isEmpty());
        body.put("reasons", List.copyOf(reasons));
        return body;
    }

    private void rejectMemoryOnlyStart(JsonNode preview) {
        if (MemoryEngine.MODE_EXTRA_BYTES.equals(text(preview, "memoryMode"))) {
            memoryBudget.requireFixedCapacity(requireMemoryTargetBytes(preview));
        }
        availability.rejectMemoryOnly();
    }

    private void collectStartReasons(List<String> reasons) {
        reasons.addAll(availability.reasons());
        ControlState state = controlSwitch.state();
        if (state.emergencyLatched()) {
            reasons.add("急停已锁存");
        }
        if ("OFF".equals(state.controlSwitch())) {
            reasons.add("控制开关已关闭");
        }
        if (sessions.active().isPresent()) {
            reasons.add("已有活动会话");
        }
        if (!storageHealth.healthy()) {
            String fault = storageHealth.fault();
            if (fault != null && !reasons.contains(fault)) {
                reasons.add(fault);
            }
        }
        appendMonitorReasons(reasons);
        appendSafetyReasons(reasons);
    }

    private void collectResumeReasons(List<String> reasons) {
        reasons.addAll(availability.reasons());
        ControlState state = controlSwitch.state();
        if (state.emergencyLatched()) {
            reasons.add("急停已锁存");
        }
        if ("OFF".equals(state.controlSwitch())) {
            reasons.add("控制开关已关闭");
        }
        appendMonitorReasons(reasons);
        appendSafetyReasons(reasons);
    }

    private void appendMonitorReasons(List<String> reasons) {
        MetricSnapshot raw = snapshots.latest();
        if (raw == null) {
            reasons.add("尚无有效监控样本");
            return;
        }
        MetricSnapshot latest = raw.freshen(clock.millis());
        if (!latest.criticalComplete()
                || latest.quality() == Quality.UNAVAILABLE
                || latest.ageMs() > MONITOR_FRESH_MS) {
            reasons.add("监控数据过期或关键指标不完整");
            return;
        }
        if (!monitorReady(latest)) {
            reasons.add("有效监控尚未连续满 10 秒");
        }
    }

    private boolean monitorReady(MetricSnapshot latest) {
        List<MetricSnapshot> copy = snapshots.copy();
        if (copy.isEmpty()) {
            return false;
        }
        long newest = latest.observedAt();
        long oldestOk = newest;
        for (int index = copy.size() - 1; index >= 0; index--) {
            MetricSnapshot sample = copy.get(index);
            if (!sample.criticalComplete() || sample.quality() == Quality.UNAVAILABLE) {
                break;
            }
            oldestOk = sample.observedAt();
        }
        return newest - oldestOk >= MONITOR_READY_MS;
    }

    private void appendSafetyReasons(List<String> reasons) {
        SafetySignal signal = safetyLoop.current();
        if (signal == null) {
            return;
        }
        if (signal.latch() || signal.safetyStop()) {
            reasons.add("安全保护已触发停止");
        } else if (signal.forbidCpuGrowth() || signal.zeroDuty()) {
            reasons.add("CPU 处于保护窗口，禁止增长");
        }
    }

    private void requireActive(String executionId) {
        if (executionId == null || executionId.isBlank()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", "executionId 不合法");
        }
        SessionView active = sessions.active().orElseThrow(() ->
                new SessionRejectedException(SessionRejectedException.Reason.NOT_ACTIVE, "没有可变更的活动会话"));
        if (!active.executionId().equals(executionId)) {
            throw new SessionRejectedException(SessionRejectedException.Reason.NOT_ACTIVE, "没有可变更的活动会话");
        }
        CpuEngineView view = engine.view();
        if (view.executionId() == null || !view.executionId().equals(executionId)) {
            throw new SessionRejectedException(SessionRejectedException.Reason.NOT_ACTIVE, "CPU 引擎没有该会话");
        }
    }

    private void scheduleDurationStop(String executionId, long durationSeconds) {
        cancelDurationStop();
        ScheduledFuture<?> future = scheduler.schedule(() -> {
            try {
                sessions.active()
                        .filter(session -> session.executionId().equals(executionId))
                        .ifPresent(session -> {
                            sessions.requestStop(executionId, StopKind.DURATION);
                            scheduleAwaitStop();
                        });
            } catch (RuntimeException ignored) {
                // 到期停止失败不抛到调度器
            }
        }, durationSeconds, TimeUnit.SECONDS);
        durationStop.set(future);
    }

    private void scheduleAwaitStop() {
        scheduler.execute(() -> {
            try {
                engine.awaitStop(CpuEngine.STOP_TIMEOUT_MS);
                if (memoryEngine.view().executionId() != null) {
                    memoryEngine.stopAndClear(MemoryEngine.CLEAR_TIMEOUT_MS, false);
                } else {
                    memoryEngine.clearLoadReferences();
                }
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
            } catch (RuntimeException ignored) {
                // 等待停止失败不向上抛
            }
        });
    }

    private void cancelDurationStop() {
        ScheduledFuture<?> future = durationStop.getAndSet(null);
        if (future != null) {
            future.cancel(false);
        }
    }

    private static Map<String, Object> executionAccepted(String executionId, String status, long version, boolean replay) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("executionId", executionId);
        body.put("status", status);
        body.put("version", version);
        body.put("replay", replay);
        body.put("engineCompleted", false);
        return body;
    }

    private static JsonNode readConfig(String configJson) {
        if (configJson == null || configJson.isBlank()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", "请求格式不合法");
        }
        try {
            JsonNode node = JSON.readTree(configJson);
            if (node == null || !node.isObject()) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", "请求格式不合法");
            }
            return node;
        } catch (ApiException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", "请求格式不合法");
        }
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || !value.isTextual() || value.asText().isEmpty()) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "TARGET_INVALID", field + " 不合法");
        }
        return value.asText();
    }

    private static double requireCpuTarget(JsonNode node) {
        JsonNode value = node.get("cpuTargetPct");
        if (value == null || !value.isNumber()) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "TARGET_INVALID", "cpuTargetPct 不合法");
        }
        double target = value.asDouble();
        if (Double.isNaN(target) || Double.isInfinite(target) || target < CPU_TARGET_MIN || target > CPU_TARGET_MAX) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "TARGET_INVALID",
                    "cpuTargetPct 必须在 " + (int) CPU_TARGET_MIN + " 到 " + (int) CPU_TARGET_MAX + " 之间");
        }
        return target;
    }

    private static Long finiteDuration(JsonNode node) {
        String runMode = text(node, "runMode");
        if (!"FINITE".equals(runMode)) {
            return null;
        }
        JsonNode value = node.get("durationSeconds");
        if (value == null || !value.isNumber() || value.asLong() < 1) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "TARGET_INVALID", "durationSeconds 不合法");
        }
        return value.asLong();
    }

    private static long requireMemoryTargetBytes(JsonNode node) {
        JsonNode value = node.get("memoryTargetBytes");
        if (value == null || !value.isNumber() || value.asLong() < 1) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "TARGET_INVALID", "memoryTargetBytes 不合法");
        }
        return value.asLong();
    }

    private static double requireMemoryTargetPct(JsonNode node) {
        JsonNode value = node.get("memoryTargetPct");
        if (value == null || !value.isNumber()) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "TARGET_INVALID", "memoryTargetPct 不合法");
        }
        double target = value.asDouble();
        if (Double.isNaN(target) || Double.isInfinite(target) || target < 0 || target > 100) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "TARGET_INVALID", "memoryTargetPct 必须在 0 到 100 之间");
        }
        return target;
    }
}
