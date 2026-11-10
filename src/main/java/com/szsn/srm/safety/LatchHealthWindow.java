package com.szsn.srm.safety;

import java.util.List;
import org.springframework.stereotype.Component;

/**
 * 人工解除锁存前的连续健康计时。样本间隔超过 {@link #MAX_GAP_MS} 就重新计算。
 * 窗口自己不会清除锁存。
 */
@Component
public class LatchHealthWindow {
    public static final long REQUIRED_MS = 30_000;
    public static final long MAX_GAP_MS = 2_500;

    private long streakStart = Long.MIN_VALUE;
    private long lastAt = Long.MIN_VALUE;
    private long healthyForMs;
    private List<String> reasons = List.of("NO_SAMPLE");

    public synchronized void observe(KeyMetricSample sample) {
        if (sample == null) {
            throw new IllegalArgumentException("样本不能为空");
        }
        long at = sample.observedAt();
        if (lastAt != Long.MIN_VALUE && at < lastAt) {
            breakStreak(List.of("CLOCK_STEP"));
            lastAt = at;
            return;
        }
        KeyMetricHealth.Verdict verdict = KeyMetricHealth.assess(sample);
        if (!verdict.healthy()) {
            breakStreak(verdict.reasons());
            lastAt = at;
            return;
        }
        boolean gap = lastAt != Long.MIN_VALUE && at - lastAt > MAX_GAP_MS;
        if (streakStart == Long.MIN_VALUE || gap) {
            streakStart = at;
        }
        lastAt = at;
        healthyForMs = at - streakStart;
        reasons = List.of();
    }

    public synchronized View view() {
        boolean ready = streakStart != Long.MIN_VALUE && reasons.isEmpty() && healthyForMs >= REQUIRED_MS;
        return new View(healthyForMs, ready, reasons);
    }

    private void breakStreak(List<String> nextReasons) {
        streakStart = Long.MIN_VALUE;
        healthyForMs = 0;
        reasons = nextReasons == null || nextReasons.isEmpty() ? List.of("UNHEALTHY") : List.copyOf(nextReasons);
    }

    public record View(long healthyForMs, boolean ready, List<String> reasons) {
    }
}
