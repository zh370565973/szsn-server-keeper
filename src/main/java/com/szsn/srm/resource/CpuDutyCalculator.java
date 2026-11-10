package com.szsn.srm.resource;

import com.szsn.srm.common.Units;

/**
 * CPU duty 纯计算。初始化 duty 为 0。安全归零不受下降斜率限制。
 */
public final class CpuDutyCalculator {
    private static final double ALPHA = 0.4;
    private static final double DEADBAND_PP = 3.0;
    private static final double MAX_INCREASE = 0.05;
    private static final double MAX_DECREASE = 0.15;

    private double duty;
    private double ewma;
    private boolean initialized;
    private int highStreak;
    private boolean externalLoadHigh;

    public DutyStep step(double rawCpuPct, double targetPct, boolean safetyZero) {
        return step(rawCpuPct, targetPct, safetyZero, false);
    }

    /**
     * @param forbidGrowth 为 true 时只允许下降或保持，不抬高 duty；安全归零仍立即生效
     */
    public DutyStep step(double rawCpuPct, double targetPct, boolean safetyZero, boolean forbidGrowth) {
        if (Double.isNaN(rawCpuPct) || Double.isInfinite(rawCpuPct) || Double.isNaN(targetPct) || Double.isInfinite(targetPct)) {
            throw new IllegalArgumentException("CPU 百分比不能是 NaN 或无穷");
        }
        if (!initialized) {
            ewma = rawCpuPct;
            initialized = true;
        } else {
            ewma = ALPHA * rawCpuPct + (1 - ALPHA) * ewma;
        }
        if (safetyZero) {
            duty = 0;
            return new DutyStep(duty, ewma, false, externalLoadHigh, true);
        }
        if (rawCpuPct > targetPct + DEADBAND_PP) {
            highStreak++;
            if (highStreak >= 2) {
                externalLoadHigh = true;
            }
        } else {
            highStreak = 0;
        }
        if (externalLoadHigh) {
            duty = 0;
            if (rawCpuPct < targetPct - DEADBAND_PP) {
                externalLoadHigh = false;
            } else {
                return new DutyStep(0, ewma, false, true, false);
            }
        }
        double error = targetPct - ewma;
        if (Math.abs(error) <= DEADBAND_PP) {
            return new DutyStep(duty, ewma, true, false, false);
        }
        double delta = 0.3 * error / 100.0;
        if (delta > MAX_INCREASE) {
            delta = MAX_INCREASE;
        } else if (delta < -MAX_DECREASE) {
            delta = -MAX_DECREASE;
        }
        if (forbidGrowth && delta > 0) {
            delta = 0;
        }
        duty = Units.clampDouble(duty + delta, 0, 1);
        return new DutyStep(duty, ewma, false, false, false);
    }

    public double duty() {
        return duty;
    }

    public double ewma() {
        return ewma;
    }

    public record DutyStep(double duty, double ewma, boolean hold, boolean externalLoadHigh, boolean safetyZeroed) {
    }
}
