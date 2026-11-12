package com.szsn.srm.resource;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.szsn.srm.common.ApiException;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import org.springframework.http.HttpStatus;
import oshi.SystemInfo;
import oshi.software.os.OSProcess;

/**
 * 用同一个 JAR 拉起负载子进程。停止、暂停、到期和急停都结束该进程，
 * 物理内存随进程退出交还。监控进程不分配负载数组。
 */
public final class JvmLoadWorkerHost implements LoadProcessKiller {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final long READY_MS = 20_000L;
    private static final long STOP_MS = 5_000L;

    private final ResourceLoadManager sessions;
    private final Supplier<LoadWorkerSample> samples;
    private final Object lock = new Object();
    private final StringBuilder errors = new StringBuilder();
    private SessionClaim claim;
    private LoadWorkerRequest request;
    private long heapBytes;
    private Process process;
    private PrintWriter childIn;
    private Intent intent = Intent.IDLE;
    private boolean ready;
    private long version = 1;
    private volatile LoadWorkerSnapshot snapshot = LoadWorkerSnapshot.idle();
    private volatile boolean pump;
    private volatile boolean acceptHeld;
    private SystemInfo systemInfo;

    public JvmLoadWorkerHost(ResourceLoadManager sessions, Supplier<LoadWorkerSample> samples) {
        this.sessions = Objects.requireNonNull(sessions, "sessions");
        this.samples = samples == null ? () -> null : samples;
    }

    public boolean isolatesProcess() {
        return true;
    }

    public void start(SessionClaim claim, LoadWorkerRequest request, long heapBytes) {
        Objects.requireNonNull(claim, "claim");
        Objects.requireNonNull(request, "request");
        synchronized (lock) {
            if (process != null && process.isAlive()) {
                throw new IllegalStateException("负载进程已在运行");
            }
            this.claim = claim;
            this.request = request;
            this.heapBytes = heapBytes;
            this.intent = Intent.RUN;
            this.ready = false;
            this.version = 1;
            this.acceptHeld = false;
            errors.setLength(0);
        }
        Process started;
        try {
            started = launch(request, heapBytes);
        } catch (IOException exception) {
            failStart(new IllegalStateException("负载进程没有启动", exception));
            return;
        }
        PrintWriter writer = new PrintWriter(new OutputStreamWriter(started.getOutputStream(), StandardCharsets.UTF_8), true);
        BlockingQueue<String> control = new LinkedBlockingQueue<>();
        synchronized (lock) {
            this.process = started;
            this.childIn = writer;
        }
        Thread reader = new Thread(() -> readLoop(started, control), "srm-load-parent");
        reader.setDaemon(true);
        reader.start();
        writer.println("START " + startJson(request));
        String line = poll(control, READY_MS);
        if (line == null || line.startsWith("FAIL") || !"READY".equals(line)) {
            destroy(started);
            failStart(new IllegalStateException("负载进程没有就绪：" + line + suffix()));
            return;
        }
        synchronized (lock) {
            ready = true;
        }
        raiseOomScore(started.pid());
        acceptHeld = true;
        pump = true;
        Thread samplesThread = new Thread(this::pumpSamples, "srm-load-sample");
        samplesThread.setDaemon(true);
        samplesThread.start();
        try {
            sessions.markRunning(claim.executionId());
        } catch (RuntimeException exception) {
            killLoadProcess();
            throw exception;
        }
    }

    public String resourceMode() {
        synchronized (lock) {
            return request == null ? null : request.resourceMode();
        }
    }

    public boolean owns(String executionId) {
        synchronized (lock) {
            return claim != null && claim.executionId().equals(executionId)
                    && (intent == Intent.RUN || intent == Intent.PAUSE);
        }
    }

    public LoadWorkerSnapshot snapshot() {
        Process current;
        synchronized (lock) {
            current = process;
        }
        long rss = current != null && current.isAlive() ? resident(current.pid()) : 0L;
        LoadWorkerSnapshot currentSnapshot = snapshot;
        if (rss == currentSnapshot.loadRssBytes()) {
            return currentSnapshot;
        }
        return copyRss(currentSnapshot, rss, current != null && current.isAlive());
    }

    public void pause(String executionId) {
        requireOwned(executionId);
        sessions.beginPause(executionId);
        sessions.completePause(executionId);
        stopProcess(Intent.PAUSE, STOP_MS);
        try {
            sessions.observeHeldBytes(executionId, 0);
        } catch (RuntimeException ignored) {
            // 槽位已不在暂停态时忽略
        }
    }

    public void resume(String executionId, ResumeGate gate) {
        requireOwned(executionId);
        ResumeGate check = gate == null ? ResumeGate.OPEN : gate;
        LoadWorkerRequest stored;
        long heap;
        SessionClaim storedClaim;
        synchronized (lock) {
            stored = request;
            heap = heapBytes;
            storedClaim = claim;
        }
        try {
            check.admit();
        } catch (RuntimeException exception) {
            sessions.rejectResume(executionId);
            throw exception;
        }
        sessions.acceptResume(executionId);
        try {
            start(storedClaim, stored, heap);
        } catch (RuntimeException exception) {
            try {
                sessions.requestStop(executionId, StopKind.FAILURE);
            } catch (RuntimeException ignored) {
                // 继续确认退出
            }
            releaseStopped(STOP_MS);
            throw exception;
        }
    }

    public long updateTargets(String executionId, long expected, Double cpuTarget, Long memoryBytes, Double memoryPct) {
        requireOwned(executionId);
        LoadWorkerSnapshot current = snapshot();
        long actual = current.version() == 0 ? version : current.version();
        if (actual != expected) {
            throw new ApiException(HttpStatus.CONFLICT, "VERSION_MISMATCH", "目标版本不匹配",
                    Map.of("expectedVersion", expected, "actualVersion", actual));
        }
        LoadWorkerRequest updated = retargetRequest(request, cpuTarget, memoryBytes, memoryPct);
        synchronized (lock) {
            request = updated;
            version = expected + 1;
        }
        Process child = process;
        if (child != null && child.isAlive() && intent == Intent.RUN) {
            Map<String, Object> body = new LinkedHashMap<>();
            if (cpuTarget != null) {
                body.put("cpuTargetPct", cpuTarget);
            }
            if (memoryBytes != null) {
                body.put("memoryTargetBytes", memoryBytes);
            }
            if (memoryPct != null) {
                body.put("memoryTargetPct", memoryPct);
            }
            send("RETARGET " + write(body));
        }
        publish(updated, expected + 1, intent == Intent.PAUSE, 0);
        return expected + 1;
    }

    public boolean releaseStopped(long timeoutMs) {
        return stopProcess(Intent.STOP, timeoutMs);
    }

    @Override
    public void killLoadProcess() {
        stopProcess(Intent.STOP, STOP_MS);
    }

    public void close() {
        killLoadProcess();
    }

    private boolean stopProcess(Intent why, long timeoutMs) {
        acceptHeld = false;
        Process current;
        synchronized (lock) {
            intent = why;
            current = process;
        }
        pump = false;
        send("STOP");
        closeChildIn();
        if (current != null) {
            try {
                if (!current.waitFor(Math.max(1, timeoutMs), TimeUnit.MILLISECONDS)) {
                    current.destroyForcibly();
                    current.waitFor(2, TimeUnit.SECONDS);
                }
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                current.destroyForcibly();
            }
        }
        onProcessEnded();
        return current == null || !current.isAlive();
    }

    private void onProcessEnded() {
        Intent why;
        SessionClaim current;
        boolean wasReady;
        synchronized (lock) {
            why = intent;
            current = claim;
            wasReady = ready;
            process = null;
            pump = false;
            if (why != Intent.PAUSE) {
                intent = Intent.IDLE;
                claim = null;
                request = null;
            }
        }
        if (current == null) {
            snapshot = LoadWorkerSnapshot.idle();
            return;
        }
        if (why == Intent.PAUSE) {
            try {
                sessions.observeHeldBytes(current.executionId(), 0);
            } catch (RuntimeException ignored) {
                // 暂停槽位可能已结束
            }
            publish(request, version, true, 0);
            return;
        }
        StopKind kind = wasReady && why == Intent.STOP ? StopKind.USER : StopKind.FAILURE;
        try {
            sessions.requestStop(current.executionId(), kind);
        } catch (RuntimeException ignored) {
            // 已经处于停止中或终态
        }
        try {
            sessions.observeHeldBytes(current.executionId(), 0);
        } catch (RuntimeException ignored) {
            // 槽位已经结束
        }
        try {
            sessions.confirmWorkerExit(current.permit());
        } catch (RuntimeException ignored) {
            // 许可已确认
        }
        try {
            sessions.observeHeldBytes(current.executionId(), 0);
            sessions.confirmWorkerExit(current.permit());
        } catch (RuntimeException ignored) {
            // 第二次确认覆盖停止过程中迟到的持有量
        }
        snapshot = LoadWorkerSnapshot.idle();
    }

    private void failStart(RuntimeException exception) {
        synchronized (lock) {
            intent = Intent.STOP;
            ready = false;
        }
        onProcessEnded();
        throw exception;
    }

    private Process launch(LoadWorkerRequest request, long heapBytes) throws IOException {
        String classpath = System.getProperty("java.class.path", "");
        List<String> command = new ArrayList<>();
        command.add(javaBinary());
        command.add("-Xms128m");
        command.add(LoadHeapPlan.megabyteFlag("-Xmx", heapBytes));
        if (!classpath.contains(java.io.File.pathSeparator) && classpath.endsWith(".jar")) {
            command.add("-jar");
            command.add(classpath);
            command.add("--load-worker");
        } else {
            command.add("-cp");
            command.add(classpath);
            command.add(LoadWorkerMain.class.getName());
        }
        ProcessBuilder builder = new ProcessBuilder(command);
        builder.redirectError(ProcessBuilder.Redirect.PIPE);
        return builder.start();
    }

    private void readLoop(Process started, BlockingQueue<String> control) {
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(started.getInputStream(), StandardCharsets.UTF_8));
             BufferedReader error = new BufferedReader(new InputStreamReader(started.getErrorStream(), StandardCharsets.UTF_8))) {
            Thread stderr = new Thread(() -> drain(error), "srm-load-err");
            stderr.setDaemon(true);
            stderr.start();
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.startsWith("REPORT ")) {
                    applyReport(line.substring("REPORT ".length()));
                } else {
                    control.offer(line);
                }
            }
        } catch (IOException ignored) {
            // 进程退出时管道关闭
        }
        control.offer("EXIT");
        onProcessEnded();
    }

    private void drain(BufferedReader error) {
        try {
            String line;
            while ((line = error.readLine()) != null) {
                synchronized (errors) {
                    if (errors.length() < 2000) {
                        errors.append(line).append('\n');
                    }
                }
            }
        } catch (IOException ignored) {
            // 进程退出
        }
    }

    private void pumpSamples() {
        while (pump) {
            LoadWorkerSample sample = samples.get();
            Map<String, Object> body = new LinkedHashMap<>();
            if (sample != null) {
                body.put("cpuBusy", sample.cpuBusy());
                body.put("memTotal", sample.memTotal());
                body.put("memAvailable", sample.memAvailable());
                body.put("forbidCpu", sample.forbidCpu());
                body.put("zeroDuty", sample.zeroDuty());
                body.put("forbidMemory", sample.forbidMemory());
                body.put("safetyStop", sample.safetyStop());
            }
            send("SAMPLE " + write(body));
            try {
                Thread.sleep(500);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    private void applyReport(String json) {
        try {
            JsonNode node = JSON.readTree(json);
            long held = node.path("heldBytes").asLong(0);
            long reportedVersion = Math.max(node.path("cpuVersion").asLong(0), node.path("memoryVersion").asLong(0));
            if (reportedVersion <= 0) {
                reportedVersion = version;
            }
            List<String> reasons = new ArrayList<>();
            JsonNode limited = node.get("limitedReasons");
            if (limited != null && limited.isArray()) {
                limited.forEach(item -> reasons.add(item.asText()));
            }
            Process current;
            synchronized (lock) {
                current = process;
                version = reportedVersion;
            }
            boolean alive = current != null && current.isAlive();
            long rss = alive ? resident(current.pid()) : 0L;
            String executionId = claim == null ? null : claim.executionId();
            snapshot = new LoadWorkerSnapshot(
                    executionId,
                    false,
                    alive,
                    held,
                    rss,
                    node.path("heapUsed").asLong(0),
                    node.has("cpuTargetPct") ? node.path("cpuTargetPct").asDouble() : request == null ? null : request.cpuTargetPct(),
                    node.has("actualPct") && !node.path("actualPct").isNull() ? node.path("actualPct").asDouble() : null,
                    node.has("duty") ? node.path("duty").asDouble() : null,
                    node.has("workerCount") ? node.path("workerCount").asInt() : null,
                    node.has("ewma") ? node.path("ewma").asDouble() : null,
                    reportedVersion,
                    node.path("externalLoadHigh").asBoolean(false),
                    node.path("safetyZeroed").asBoolean(false),
                    node.path("stopTimeout").asBoolean(false),
                    node.path("memoryMode").isMissingNode() || node.path("memoryMode").isNull() ? null : node.path("memoryMode").asText(),
                    node.has("memoryTargetBytes") && !node.path("memoryTargetBytes").isNull() ? node.path("memoryTargetBytes").asLong() : null,
                    node.has("memoryTargetPct") && !node.path("memoryTargetPct").isNull() ? node.path("memoryTargetPct").asDouble() : null,
                    node.has("systemUsedPct") && !node.path("systemUsedPct").isNull() ? node.path("systemUsedPct").asDouble() : null,
                    node.path("targetLimited").asBoolean(false),
                    List.copyOf(reasons));
            if (acceptHeld && intent == Intent.RUN && claim != null) {
                try {
                    sessions.observeHeldBytes(claim.executionId(), held);
                } catch (RuntimeException ignored) {
                    // 停止过程中不再回写持有量
                }
            }
        } catch (Exception ignored) {
            // 半行汇报忽略
        }
    }

    private void publish(LoadWorkerRequest stored, long reportedVersion, boolean paused, long held) {
        snapshot = new LoadWorkerSnapshot(
                stored == null ? null : stored.executionId(),
                paused,
                false,
                held,
                0,
                0,
                stored == null ? null : stored.cpuTargetPct(),
                null,
                paused ? 0D : null,
                stored == null ? null : stored.workerCount(),
                null,
                reportedVersion,
                false,
                false,
                false,
                stored == null ? null : stored.memoryMode(),
                stored == null ? null : stored.memoryTargetBytes(),
                stored == null ? null : stored.memoryTargetPct(),
                null,
                false,
                List.of());
    }

    private static LoadWorkerRequest retargetRequest(LoadWorkerRequest current, Double cpuTarget, Long memoryBytes, Double memoryPct) {
        if (current == null) {
            throw new IllegalStateException("没有可修改的负载");
        }
        return new LoadWorkerRequest(
                current.executionId(),
                current.resourceMode(),
                current.memoryMode(),
                memoryBytes != null ? memoryBytes : current.memoryTargetBytes(),
                memoryPct != null ? memoryPct : current.memoryTargetPct(),
                cpuTarget != null ? cpuTarget : current.cpuTargetPct(),
                current.workerCount());
    }

    private void requireOwned(String executionId) {
        if (!owns(executionId)) {
            throw new SessionRejectedException(SessionRejectedException.Reason.NOT_ACTIVE, "引擎没有该会话");
        }
    }

    private void send(String line) {
        PrintWriter writer;
        synchronized (lock) {
            writer = childIn;
        }
        if (writer != null) {
            writer.println(line);
        }
    }

    private void closeChildIn() {
        PrintWriter writer;
        synchronized (lock) {
            writer = childIn;
            childIn = null;
        }
        if (writer != null) {
            writer.close();
        }
    }

    private static void destroy(Process started) {
        started.destroy();
        try {
            if (!started.waitFor(2, TimeUnit.SECONDS)) {
                started.destroyForcibly();
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            started.destroyForcibly();
        }
    }

    private static void raiseOomScore(long pid) {
        Path path = Path.of("/proc", Long.toString(pid), "oom_score_adj");
        try {
            if (Files.exists(path)) {
                Files.writeString(path, "500\n");
            }
        } catch (IOException ignored) {
            // 没有权限时保持继承的分数
        }
    }

    private long resident(long pid) {
        if (pid <= 0) {
            return 0;
        }
        try {
            if (systemInfo == null) {
                systemInfo = new SystemInfo();
            }
            OSProcess processInfo = systemInfo.getOperatingSystem().getProcess((int) pid);
            if (processInfo == null) {
                return 0;
            }
            long rss = processInfo.getResidentMemory();
            return Math.max(0, rss);
        } catch (Throwable throwable) {
            return 0;
        }
    }

    private static LoadWorkerSnapshot copyRss(LoadWorkerSnapshot source, long rss, boolean alive) {
        return new LoadWorkerSnapshot(
                source.executionId(), source.paused(), alive, source.heldBytes(), rss, source.loadHeapUsedBytes(),
                source.cpuTargetPct(), source.actualPct(), source.duty(), source.workerCount(), source.ewma(),
                source.version(), source.externalLoadHigh(), source.safetyZeroed(), source.stopTimeout(),
                source.memoryMode(), source.memoryTargetBytes(), source.memoryTargetPct(), source.systemUsedPct(),
                source.targetLimited(), source.limitedReasons());
    }

    private String startJson(LoadWorkerRequest request) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("executionId", request.executionId());
        body.put("resourceMode", request.resourceMode());
        body.put("memoryMode", request.memoryMode());
        body.put("memoryTargetBytes", request.memoryTargetBytes());
        body.put("memoryTargetPct", request.memoryTargetPct());
        body.put("cpuTargetPct", request.cpuTargetPct());
        body.put("workerCount", request.workerCount());
        LoadWorkerSample sample = samples.get();
        if (sample != null) {
            body.put("cpuBusy", sample.cpuBusy());
            body.put("memTotal", sample.memTotal());
            body.put("memAvailable", sample.memAvailable());
            body.put("forbidCpu", sample.forbidCpu());
            body.put("zeroDuty", sample.zeroDuty());
            body.put("forbidMemory", sample.forbidMemory());
            body.put("safetyStop", sample.safetyStop());
        }
        return write(body);
    }

    private static String write(Map<String, Object> body) {
        try {
            return JSON.writeValueAsString(body);
        } catch (IOException exception) {
            throw new IllegalStateException("负载参数无法编码", exception);
        }
    }

    private static String poll(BlockingQueue<String> control, long timeoutMs) {
        try {
            return control.poll(timeoutMs, TimeUnit.MILLISECONDS);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            return null;
        }
    }

    private String suffix() {
        synchronized (errors) {
            if (errors.isEmpty()) {
                return "";
            }
            return "：" + errors;
        }
    }

    private static String javaBinary() {
        String name = System.getProperty("os.name", "").toLowerCase().contains("win") ? "java.exe" : "java";
        Path binary = Path.of(System.getProperty("java.home"), "bin", name);
        if (Files.exists(binary)) {
            return binary.toString();
        }
        return "java";
    }

    private enum Intent {
        IDLE, RUN, PAUSE, STOP
    }
}
