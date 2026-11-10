package com.szsn.srm.resource;

import com.szsn.srm.common.Units;
import java.util.ArrayList;
import java.util.List;

/**
 * SYSTEM_PERCENT 单步决策：used=T-A，delta=P*T/100-used，死区 ±2 pp。
 * 系统已高于目标时只释放不分配；受预算限制时标记 {@link #TARGET_LIMITED}。
 */
public final class SystemPercentPlanner {
    public static final double DEAD_ZONE_PP = 2.0;
    public static final String TARGET_LIMITED = "TARGET_LIMITED";
    public static final String SYSTEM_ABOVE_TARGET = "SYSTEM_ABOVE_TARGET";
    public static final String DEAD_ZONE = "DEAD_ZONE";
    public static final String REGROW_COOLDOWN = "REGROW_COOLDOWN";
    public static final String SAMPLE_INCOMPLETE = "SAMPLE_INCOMPLETE";

    private SystemPercentPlanner() {
    }

    public enum Action {
        HOLD,
        GROW,
        RELEASE
    }

    public record Decision(
            Action action,
            long amountBytes,
            long idealGrowBytes,
            boolean targetLimited,
            boolean systemAboveTarget,
            List<String> reasons) {
    }

    /**
     * @param targetPct 目标系统占用百分比（0–100）
     * @param memTotalBytes MemTotal
     * @param memAvailableBytes MemAvailable
     * @param heldBytes 本引擎当前持有量
     * @param additionalBudgetBytes 当前可新增预算（已 4MiB 对齐）
     * @param regrowCooldownActive 释放后冷却期内禁止再增
     */
    public static Decision decide(
            double targetPct,
            long memTotalBytes,
            long memAvailableBytes,
            long heldBytes,
            long additionalBudgetBytes,
            boolean regrowCooldownActive) {
        if (targetPct < 0 || targetPct > 100) {
            throw new IllegalArgumentException("目标百分比必须在 0 到 100 之间");
        }
        if (memTotalBytes <= 0 || memAvailableBytes < 0 || heldBytes < 0 || additionalBudgetBytes < 0) {
            return hold(SAMPLE_INCOMPLETE);
        }
        if (memAvailableBytes > memTotalBytes) {
            return hold(SAMPLE_INCOMPLETE);
        }

        long used = memTotalBytes - memAvailableBytes;
        double currentPct = used * 100.0 / memTotalBytes;
        double diff = currentPct - targetPct;
        long targetUsed = Math.round(targetPct * memTotalBytes / 100.0);

        if (Math.abs(diff) <= DEAD_ZONE_PP) {
            return new Decision(Action.HOLD, 0L, 0L, false, false, List.of(DEAD_ZONE));
        }

        if (diff > DEAD_ZONE_PP) {
            long excess = Math.max(0L, used - targetUsed);
            long release = Math.min(heldBytes, MemoryBudget.alignDown(excess));
            List<String> reasons = new ArrayList<>();
            reasons.add(SYSTEM_ABOVE_TARGET);
            if (release <= 0) {
                return new Decision(Action.HOLD, 0L, 0L, false, true, List.copyOf(reasons));
            }
            return new Decision(Action.RELEASE, release, 0L, false, true, List.copyOf(reasons));
        }

        // currentPct < targetPct - dead zone：需要增加本引擎持有量
        long deficit = Math.max(0L, targetUsed - used);
        long idealGrow = MemoryBudget.alignDown(deficit);
        List<String> reasons = new ArrayList<>();
        if (idealGrow <= 0) {
            return new Decision(Action.HOLD, 0L, 0L, false, false, List.of(DEAD_ZONE));
        }
        if (regrowCooldownActive) {
            reasons.add(REGROW_COOLDOWN);
            return new Decision(Action.HOLD, 0L, idealGrow, false, false, List.copyOf(reasons));
        }

        long capped = Math.min(idealGrow, additionalBudgetBytes);
        capped = MemoryBudget.alignDown(capped);
        boolean limited = capped < idealGrow;
        if (limited) {
            reasons.add(TARGET_LIMITED);
        }
        if (capped < Units.BLOCK) {
            if (!limited) {
                reasons.add(TARGET_LIMITED);
                limited = true;
            }
            return new Decision(Action.HOLD, 0L, idealGrow, true, false, List.copyOf(reasons));
        }
        return new Decision(Action.GROW, capped, idealGrow, limited, false, List.copyOf(reasons));
    }

    private static Decision hold(String reason) {
        return new Decision(Action.HOLD, 0L, 0L, false, false, List.of(reason));
    }
}
