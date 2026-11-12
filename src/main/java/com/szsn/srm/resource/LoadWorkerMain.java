package com.szsn.srm.resource;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.szsn.srm.monitor.Quality;
import com.szsn.srm.safety.SafetySignal;
import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.lang.management.ManagementFactory;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 负载子进程入口。只燃烧 CPU 和分配 {@code byte[]}，不启动 Spring，也不打开 H2。
 * 父进程写入 STOP 或关掉管道后本进程退出，操作系统收回它占的物理内存。
 * 不调用 {@code System.gc()}。
 */
public final class LoadWorkerMain {
    private static final ObjectMapper JSON = new ObjectMapper();

    private LoadWorkerMain() {
    }

    public static void main(String[] args) {
        System.exit(run(System.in, System.out));
    }

    static int run(InputStream input, OutputStream output) {
        PrintWriter writer = new PrintWriter(new OutputStreamWriter(output, StandardCharsets.UTF_8), true);
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(input, StandardCharsets.UTF_8))) {
            String startLine = reader.readLine();
            if (startLine == null || !startLine.startsWith("START ")) {
                writer.println("FAIL 缺少 START");
                return 1;
            }
            JsonNode start = JSON.readTree(startLine.substring("START ".length()));
            EngineBundle bundle = new EngineBundle();
            if (start.has("memTotal") || start.has("memAvailable") || start.has("cpuBusy")) {
                bundle.applySample(start);
            }
            bundle.launch(start);
            writer.println("READY");
            AtomicBoolean reporting = new AtomicBoolean(true);
            Thread reports = new Thread(() -> {
                while (reporting.get()) {
                    writer.println("REPORT " + bundle.reportJson());
                    try {
                        Thread.sleep(200);
                    } catch (InterruptedException exception) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                }
            }, "srm-load-report");
            reports.setDaemon(true);
            reports.start();
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.startsWith("STOP")) {
                    break;
                }
                if (line.startsWith("SAMPLE ")) {
                    bundle.applySample(JSON.readTree(line.substring("SAMPLE ".length())));
                    if (bundle.safetyStop()) {
                        break;
                    }
                } else if (line.startsWith("RETARGET ")) {
                    try {
                        bundle.retarget(JSON.readTree(line.substring("RETARGET ".length())));
                    } catch (RuntimeException exception) {
                        writer.println("FAIL " + exception.getClass().getSimpleName());
                    }
                }
            }
            reporting.set(false);
            bundle.shutdown();
            writer.println("EXIT");
            writer.flush();
            return 0;
        } catch (Exception exception) {
            writer.println("FAIL " + exception.getClass().getSimpleName());
            writer.flush();
            return 1;
        }
    }

    /**
     * 子进程内的引擎。样本和安全标志只来自父进程写入的 SAMPLE。
     */
    private static final class EngineBundle {
        private final ResourceLoadManager sessions = new ResourceLoadManager();
        private final AtomicReference<SampleState> sample = new AtomicReference<>(SampleState.waiting());
        private final ByteArrayMemoryAllocator allocator = new ByteArrayMemoryAllocator();
        private CpuEngine cpu;
        private MemoryEngine memory;

        static EngineBundle start(JsonNode spec) {
            EngineBundle bundle = new EngineBundle();
            bundle.launch(spec);
            return bundle;
        }

        void launch(JsonNode spec) {
            String executionId = text(spec, "executionId");
            if (executionId == null || executionId.isBlank()) {
                executionId = "load";
            }
            SessionClaim claim = sessions.claim(executionId);
            String mode = text(spec, "resourceMode");
            boolean withCpu = "CPU".equals(mode) || "COMBINED".equals(mode);
            boolean withMemory = "MEMORY".equals(mode) || "COMBINED".equals(mode);
            if (!withCpu && !withMemory) {
                throw new IllegalArgumentException("resourceMode 不合法");
            }
            if (withCpu) {
                int workers = spec.path("workerCount").asInt(1);
                double target = spec.path("cpuTargetPct").asDouble();
                cpu = new CpuEngine(sessions, this::cpuBusy, this::safety, new ChecksumCpuBurner());
                cpu.start(claim, target, Math.max(1, workers), true);
            }
            if (withMemory) {
                MemorySampleSource samples = this::memorySample;
                MemoryBudgetGate gate = MemoryBudgetGate.withDefaults(allocator, samples);
                ChunkedMemoryGrower grower = new ChunkedMemoryGrower(allocator, gate, samples);
                memory = new MemoryEngine(sessions, allocator, gate, samples, grower, this::safety);
                String memoryMode = text(spec, "memoryMode");
                boolean manage = !withCpu;
                if (MemoryEngine.MODE_EXTRA_BYTES.equals(memoryMode)) {
                    long target = spec.path("memoryTargetBytes").asLong();
                    memory.startExtraBytes(claim, target, manage, true);
                } else if (MemoryEngine.MODE_SYSTEM_PERCENT.equals(memoryMode)) {
                    double target = spec.path("memoryTargetPct").asDouble();
                    memory.startSystemPercent(claim, target, manage, true);
                } else {
                    throw new IllegalArgumentException("memoryMode 不合法");
                }
            }
        }

        void applySample(JsonNode node) {
            sample.set(new SampleState(
                    node.path("cpuBusy").isNumber() ? node.path("cpuBusy").asDouble() : null,
                    node.path("memTotal").isNumber() ? node.path("memTotal").asLong() : null,
                    node.path("memAvailable").isNumber() ? node.path("memAvailable").asLong() : null,
                    node.path("forbidCpu").asBoolean(false),
                    node.path("zeroDuty").asBoolean(false),
                    node.path("forbidMemory").asBoolean(false),
                    node.path("safetyStop").asBoolean(false)));
        }

        boolean safetyStop() {
            return sample.get().safetyStop;
        }

        void retarget(JsonNode node) {
            if (cpu != null && node.has("cpuTargetPct")) {
                CpuEngineView view = cpu.view();
                cpu.updateTarget(view.configVersion(), node.path("cpuTargetPct").asDouble());
            }
            if (memory != null && (node.has("memoryTargetBytes") || node.has("memoryTargetPct"))) {
                MemoryEngineView view = memory.view();
                Long bytes = node.has("memoryTargetBytes") ? node.path("memoryTargetBytes").asLong() : null;
                Double pct = node.has("memoryTargetPct") ? node.path("memoryTargetPct").asDouble() : null;
                memory.updateTarget(view.configVersion(), bytes, pct);
            }
        }

        void shutdown() {
            String executionId = cpu != null ? cpu.view().executionId() : memory == null ? null : memory.view().executionId();
            if (executionId != null) {
                try {
                    sessions.requestStop(executionId, StopKind.USER);
                } catch (RuntimeException ignored) {
                    // 已经在停止中
                }
            }
            try {
                if (cpu != null) {
                    cpu.awaitStop(CpuEngine.STOP_TIMEOUT_MS);
                }
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
            } catch (RuntimeException ignored) {
                // 进程即将退出
            }
            try {
                if (memory != null) {
                    memory.stopAndClear(MemoryEngine.CLEAR_TIMEOUT_MS, cpu == null);
                }
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
            } catch (RuntimeException ignored) {
                if (memory != null) {
                    memory.clearLoadReferences();
                }
            }
        }

        String reportJson() {
            Map<String, Object> body = new LinkedHashMap<>();
            if (memory != null) {
                MemoryEngineView view = memory.view();
                body.put("heldBytes", view.heldBytes());
                body.put("memoryMode", view.memoryMode());
                body.put("memoryTargetBytes", view.memoryTargetBytes());
                body.put("memoryTargetPct", view.memoryTargetPct());
                body.put("targetLimited", view.targetLimited());
                body.put("systemUsedPct", view.systemUsedPct());
                body.put("memoryVersion", view.configVersion());
                body.put("limitedReasons", view.limitedReasons());
                body.put("paused", view.paused());
            } else {
                body.put("heldBytes", 0);
            }
            if (cpu != null) {
                CpuEngineView view = cpu.view();
                body.put("cpuTargetPct", view.targetPct());
                body.put("actualPct", view.actualPct());
                body.put("duty", view.duty());
                body.put("workerCount", view.workerCount());
                body.put("ewma", view.ewma());
                body.put("cpuVersion", view.configVersion());
                body.put("externalLoadHigh", view.externalLoadHigh());
                body.put("safetyZeroed", view.safetyZeroed());
                body.put("stopTimeout", view.stopTimeout());
                if (!body.containsKey("limitedReasons")) {
                    body.put("limitedReasons", view.limitedReasons());
                }
            }
            var heap = ManagementFactory.getMemoryMXBean().getHeapMemoryUsage();
            body.put("heapUsed", heap.getUsed());
            body.put("heapMax", heap.getMax());
            try {
                return JSON.writeValueAsString(body);
            } catch (Exception exception) {
                return "{\"heldBytes\":0}";
            }
        }

        private Double cpuBusy() {
            return sample.get().cpuBusy;
        }

        private MemorySample memorySample() {
            SampleState current = sample.get();
            var heap = ManagementFactory.getMemoryMXBean().getHeapMemoryUsage();
            if (current.memTotal == null || current.memAvailable == null) {
                return new MemorySample(null, null, heap.getMax(), heap.getUsed(), null);
            }
            return new MemorySample(current.memTotal, current.memAvailable, heap.getMax(), heap.getUsed(), 0L);
        }

        private SafetySignal safety() {
            SampleState current = sample.get();
            return new SafetySignal(
                    0,
                    null,
                    0,
                    Quality.VALID,
                    true,
                    null,
                    current.forbidCpu,
                    current.zeroDuty,
                    current.forbidMemory,
                    current.safetyStop,
                    false,
                    List.of());
        }

        private static String text(JsonNode node, String field) {
            JsonNode value = node.get(field);
            if (value == null || value.isNull()) {
                return null;
            }
            String text = value.asText();
            return text == null || text.isBlank() ? null : text;
        }
    }

    private record SampleState(
            Double cpuBusy,
            Long memTotal,
            Long memAvailable,
            boolean forbidCpu,
            boolean zeroDuty,
            boolean forbidMemory,
            boolean safetyStop) {
        static SampleState waiting() {
            return new SampleState(null, null, null, false, false, false, false);
        }
    }

}
