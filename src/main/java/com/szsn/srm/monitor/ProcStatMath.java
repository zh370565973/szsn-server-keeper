package com.szsn.srm.monitor;

public final class ProcStatMath {
    private ProcStatMath() {
    }

    public record Counters(long user, long nice, long system, long idle, long iowait, long irq, long softirq, long steal) {
        public long[] asArray() {
            return new long[] {user, nice, system, idle, iowait, irq, softirq, steal};
        }
    }

    public record Percents(
            double overallBusyPct,
            double userPct,
            double systemPct,
            double idlePct,
            double iowaitPct,
            double stealPct) {
    }

    public static Counters parseAggregate(String statText) {
        if (statText == null) {
            return null;
        }
        for (String line : statText.split("\\R")) {
            if (line.startsWith("cpu ")) {
                String[] parts = line.trim().split("\\s+");
                if (parts.length < 9) {
                    return null;
                }
                return new Counters(
                        parse(parts[1]),
                        parse(parts[2]),
                        parse(parts[3]),
                        parse(parts[4]),
                        parse(parts[5]),
                        parse(parts[6]),
                        parse(parts[7]),
                        parse(parts[8]));
            }
        }
        return null;
    }

    public static Percents between(Counters previous, Counters current) {
        if (previous == null || current == null) {
            return null;
        }
        long[] before = previous.asArray();
        long[] after = current.asArray();
        long[] delta = new long[8];
        long total = 0;
        for (int i = 0; i < 8; i++) {
            delta[i] = after[i] - before[i];
            if (delta[i] < 0) {
                return null;
            }
            total += delta[i];
        }
        if (total <= 0) {
            return null;
        }
        double user = percent(delta[0] + delta[1], total);
        double system = percent(delta[2] + delta[5] + delta[6], total);
        double idle = percent(delta[3], total);
        double iowait = percent(delta[4], total);
        double steal = percent(delta[7], total);
        double busy = percent(total - delta[3] - delta[4], total);
        return new Percents(busy, user, system, idle, iowait, steal);
    }

    private static long parse(String token) {
        return Long.parseLong(token);
    }

    private static double percent(long part, long total) {
        return part * 100.0 / total;
    }
}
