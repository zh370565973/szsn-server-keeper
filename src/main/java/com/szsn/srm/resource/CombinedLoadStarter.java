package com.szsn.srm.resource;

import java.util.Objects;

/**
 * 联合控制启动：同一会话同时拉起 CPU 与内存引擎。
 * EXTRA_BYTES 超预算在启动任一引擎前拒绝；任一引擎启动失败时停止两侧并释放槽位，
 * 不允许静默退化为单项运行。内存是否触碰真实 {@code byte[]} 由注入的分配器决定。
 */
public final class CombinedLoadStarter {
    private final ResourceLoadManager sessions;
    private final CpuEngine cpu;
    private final MemoryEngine memory;
    private final MemoryBudgetGate budget;

    public CombinedLoadStarter(
            ResourceLoadManager sessions, CpuEngine cpu, MemoryEngine memory, MemoryBudgetGate budget) {
        this.sessions = Objects.requireNonNull(sessions, "sessions");
        this.cpu = Objects.requireNonNull(cpu, "cpu");
        this.memory = Objects.requireNonNull(memory, "memory");
        this.budget = Objects.requireNonNull(budget, "budget");
    }

    /**
     * @param autoControl 传给 {@link CpuEngine#start(SessionClaim, double, int, boolean)}；测试可关自动调节
     */
    public void start(
            SessionClaim claim,
            double cpuTargetPct,
            int workerCount,
            String memoryMode,
            Long memoryTargetBytes,
            Double memoryTargetPct,
            boolean autoControl) {
        if (claim == null || claim.permit() == null) {
            throw new IllegalArgumentException("会话许可不能为空");
        }
        if (memoryMode == null || memoryMode.isBlank()) {
            throw new IllegalArgumentException("memoryMode 不能为空");
        }
        if (MemoryEngine.MODE_EXTRA_BYTES.equals(memoryMode)) {
            if (memoryTargetBytes == null) {
                throw new IllegalArgumentException("memoryTargetBytes 不能为空");
            }
        } else if (MemoryEngine.MODE_SYSTEM_PERCENT.equals(memoryMode)) {
            if (memoryTargetPct == null) {
                throw new IllegalArgumentException("memoryTargetPct 不能为空");
            }
        } else {
            throw new IllegalArgumentException("memoryMode 不合法");
        }
        boolean cpuStarted = false;
        boolean memoryStarted = false;
        try {
            // 固定容量超预算：在拉起任一引擎前拒绝，避免静默只跑 CPU
            if (MemoryEngine.MODE_EXTRA_BYTES.equals(memoryMode)) {
                budget.requireFixedCapacity(memoryTargetBytes);
            }
            cpu.start(claim, cpuTargetPct, workerCount, autoControl);
            cpuStarted = true;
            if (MemoryEngine.MODE_EXTRA_BYTES.equals(memoryMode)) {
                memory.startExtraBytes(claim, memoryTargetBytes, false, autoControl);
            } else {
                memory.startSystemPercent(claim, memoryTargetPct, false, autoControl);
            }
            memoryStarted = true;
        } catch (RuntimeException exception) {
            recover(claim, cpuStarted || memoryStarted);
            throw exception;
        }
    }

    public void start(
            SessionClaim claim,
            double cpuTargetPct,
            int workerCount,
            String memoryMode,
            Long memoryTargetBytes,
            Double memoryTargetPct) {
        start(claim, cpuTargetPct, workerCount, memoryMode, memoryTargetBytes, memoryTargetPct, true);
    }

    /**
     * 失败回收：两侧引擎都必须停下；不允许只留下 CPU 或只留下内存。
     */
    void recover(SessionClaim claim, boolean eitherStarted) {
        String executionId = claim.executionId();
        try {
            sessions.requestStop(executionId, StopKind.FAILURE);
        } catch (RuntimeException ignored) {
            // 槽位可能尚未 RUNNING，或已在停止中
        }
        try {
            if (cpu.view().executionId() != null) {
                cpu.awaitStop(CpuEngine.STOP_TIMEOUT_MS);
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
        } catch (RuntimeException ignored) {
            // 继续清内存侧
        }
        try {
            memory.stopAndClear(MemoryEngine.CLEAR_TIMEOUT_MS, false);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            memory.clearLoadReferences();
        } catch (RuntimeException ignored) {
            memory.clearLoadReferences();
        }
        releaseSlot(claim, eitherStarted);
    }

    private void releaseSlot(SessionClaim claim, boolean eitherStarted) {
        if (sessions.active().isEmpty()) {
            return;
        }
        SessionView active = sessions.active().orElse(null);
        if (active == null || !active.executionId().equals(claim.executionId())) {
            return;
        }
        if (!eitherStarted && active.status() == ExecutionStatus.STARTING) {
            try {
                sessions.requestStop(claim.executionId(), StopKind.FAILURE);
            } catch (RuntimeException ignored) {
                // 继续确认退出
            }
        }
        try {
            sessions.confirmWorkerExit(claim.permit());
        } catch (RuntimeException ignored) {
            // 已确认或已终态
        }
    }
}
