package com.szsn.srm.history;

import com.szsn.srm.common.ApiException;
import com.szsn.srm.monitor.MetricSnapshot;
import com.szsn.srm.monitor.Quality;
import com.szsn.srm.system.StorageHealth;
import java.time.Clock;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

@Service
public class HistoryService {
    static final long RAW_BUCKET_MS = 10_000;

    private final HistoryRepository repository;
    private final StorageHealth storageHealth;
    private final Clock clock;
    private final Accumulator accumulator = new Accumulator();
    private boolean writesPaused;

    public HistoryService(HistoryRepository repository, StorageHealth storageHealth, Clock clock) {
        this.repository = repository;
        this.storageHealth = storageHealth;
        this.clock = clock;
        this.writesPaused = !storageHealth.healthy();
    }

    public synchronized void accept(MetricSnapshot snapshot) {
        if (writesPaused || !storageHealth.healthy()) {
            writesPaused = true;
            return;
        }
        try {
            Long closed = accumulator.add(snapshot);
            if (closed != null) {
                repository.insert(closed, accumulator.closedCount, accumulator.closedCoverage, accumulator.closedMetrics);
            }
        } catch (RuntimeException exception) {
            writesPaused = true;
            storageHealth.markFault("历史写入失败");
        }
    }

    public synchronized void flush() {
        if (writesPaused || !storageHealth.healthy() || accumulator.bucketStart == Long.MIN_VALUE || accumulator.count == 0) {
            return;
        }
        try {
            repository.insert(accumulator.bucketStart, accumulator.count, accumulator.coverage, accumulator.metrics());
            accumulator.reset();
        } catch (RuntimeException exception) {
            writesPaused = true;
            storageHealth.markFault("历史写入失败");
        }
    }

    public HistoryView query(long from, long to, Long bucketSeconds) {
        if (to <= from) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", "时间范围不合法");
        }
        long bucketMs = chooseBucket(to - from, bucketSeconds);
        List<HistoryRepository.RawBucket> rows = repository.find(from, to);
        List<HistoryPoint> points = rebucket(rows, bucketMs);
        long now = clock.millis();
        long retainedFrom = RetentionPolicy.metricCutoff(now);
        return new HistoryView(from, to, bucketMs / 1000, points, retainedFrom,
                RetentionPolicy.pastMetricRetention(from, now));
    }

    public static long chooseBucket(long spanMs, Long requestedSeconds) {
        long bucketMs = requestedSeconds == null ? RAW_BUCKET_MS : Math.max(RAW_BUCKET_MS, requestedSeconds * 1000);
        if (bucketCount(spanMs, bucketMs) <= 2000) {
            return bucketMs;
        }
        if (bucketCount(spanMs, 60_000) <= 2000) {
            return 60_000;
        }
        long hour = 3_600_000;
        while (bucketCount(spanMs, hour) > 2000) {
            hour += 3_600_000;
        }
        return hour;
    }

    private static long bucketCount(long spanMs, long bucketMs) {
        return (spanMs + bucketMs - 1) / bucketMs;
    }

    private List<HistoryPoint> rebucket(List<HistoryRepository.RawBucket> rows, long bucketMs) {
        List<HistoryPoint> points = new ArrayList<>();
        Long current = null;
        Accumulator group = new Accumulator();
        for (HistoryRepository.RawBucket row : rows) {
            long start = row.bucketStart() - Math.floorMod(row.bucketStart(), bucketMs);
            if (current != null && start != current) {
                points.add(new HistoryPoint(current, group.count, group.coverage, group.metrics()));
                group = new Accumulator();
            }
            current = start;
            group.addRaw(row);
        }
        if (current != null && group.count > 0) {
            points.add(new HistoryPoint(current, group.count, group.coverage, group.metrics()));
        }
        return points;
    }

    public record HistoryPoint(long bucketStart, int sampleCount, long coverageMs, Map<String, Object> metrics) {
    }

    public record HistoryView(
            long from,
            long to,
            long bucketSeconds,
            List<HistoryPoint> buckets,
            long retainedFrom,
            boolean pastRetention) {
    }

    static final class Accumulator {
        long bucketStart = Long.MIN_VALUE;
        int count;
        long coverage;
        double cpuWeighted;
        long cpuWeight;
        double cpuPeak = -1;
        long memAvailableMin = Long.MAX_VALUE;
        double memUsedWeighted;
        long memUsedWeight;
        double memUsedPeak = -1;
        double rxWeighted;
        long rxWeight;
        double txWeighted;
        long txWeight;
        int closedCount;
        long closedCoverage;
        Map<String, Object> closedMetrics = Map.of();

        Long add(MetricSnapshot snapshot) {
            if (snapshot.quality() != Quality.VALID && snapshot.quality() != Quality.PARTIAL) {
                return null;
            }
            long start = snapshot.observedAt() - Math.floorMod(snapshot.observedAt(), RAW_BUCKET_MS);
            Long closed = null;
            if (bucketStart != Long.MIN_VALUE && start != bucketStart && count > 0) {
                closedCount = count;
                closedCoverage = coverage;
                closedMetrics = metrics();
                closed = bucketStart;
                reset();
            }
            bucketStart = start;
            count++;
            coverage += 1000;
            Double cpu = snapshot.cpu().overallBusyPct();
            if (cpu != null) {
                cpuWeighted += cpu * 1000;
                cpuWeight += 1000;
                cpuPeak = Math.max(cpuPeak, cpu);
            }
            if (snapshot.memory().availableBytes() != null) {
                memAvailableMin = Math.min(memAvailableMin, snapshot.memory().availableBytes());
            }
            if (snapshot.memory().usedBytes() != null) {
                double used = snapshot.memory().usedBytes();
                memUsedWeighted += used * 1000;
                memUsedWeight += 1000;
                memUsedPeak = Math.max(memUsedPeak, used);
            }
            if (snapshot.network().rxBytesPerSec() != null) {
                rxWeighted += snapshot.network().rxBytesPerSec() * 1000;
                rxWeight += 1000;
            }
            if (snapshot.network().txBytesPerSec() != null) {
                txWeighted += snapshot.network().txBytesPerSec() * 1000;
                txWeight += 1000;
            }
            return closed;
        }

        void addRaw(HistoryRepository.RawBucket row) {
            count += row.sampleCount();
            coverage += row.coverageMs();
            Map<String, Object> metrics = row.metrics();
            cpuWeighted += number(metrics.get("cpuBusyAvg")) * row.coverageMs();
            if (metrics.get("cpuBusyAvg") != null) {
                cpuWeight += row.coverageMs();
            }
            if (metrics.get("cpuBusyPeak") != null) {
                cpuPeak = Math.max(cpuPeak, number(metrics.get("cpuBusyPeak")));
            }
            if (metrics.get("memAvailableMinBytes") != null) {
                memAvailableMin = Math.min(memAvailableMin, (long) number(metrics.get("memAvailableMinBytes")));
            }
            memUsedWeighted += number(metrics.get("memUsedAvgBytes")) * row.coverageMs();
            if (metrics.get("memUsedAvgBytes") != null) {
                memUsedWeight += row.coverageMs();
            }
            if (metrics.get("memUsedPeakBytes") != null) {
                memUsedPeak = Math.max(memUsedPeak, number(metrics.get("memUsedPeakBytes")));
            }
            rxWeighted += number(metrics.get("netRxBytesPerSecAvg")) * row.coverageMs();
            if (metrics.get("netRxBytesPerSecAvg") != null) {
                rxWeight += row.coverageMs();
            }
            txWeighted += number(metrics.get("netTxBytesPerSecAvg")) * row.coverageMs();
            if (metrics.get("netTxBytesPerSecAvg") != null) {
                txWeight += row.coverageMs();
            }
        }

        Map<String, Object> metrics() {
            Map<String, Object> values = new LinkedHashMap<>();
            putAvg(values, "cpuBusyAvg", cpuWeighted, cpuWeight);
            if (cpuPeak >= 0) {
                values.put("cpuBusyPeak", cpuPeak);
            }
            if (memAvailableMin != Long.MAX_VALUE) {
                values.put("memAvailableMinBytes", memAvailableMin);
            }
            putAvg(values, "memUsedAvgBytes", memUsedWeighted, memUsedWeight);
            if (memUsedPeak >= 0) {
                values.put("memUsedPeakBytes", memUsedPeak);
            }
            putAvg(values, "netRxBytesPerSecAvg", rxWeighted, rxWeight);
            putAvg(values, "netTxBytesPerSecAvg", txWeighted, txWeight);
            return values;
        }

        void reset() {
            bucketStart = Long.MIN_VALUE;
            count = 0;
            coverage = 0;
            cpuWeighted = 0;
            cpuWeight = 0;
            cpuPeak = -1;
            memAvailableMin = Long.MAX_VALUE;
            memUsedWeighted = 0;
            memUsedWeight = 0;
            memUsedPeak = -1;
            rxWeighted = 0;
            txWeighted = 0;
            rxWeight = 0;
            txWeight = 0;
        }

        private static void putAvg(Map<String, Object> values, String key, double weighted, long weight) {
            if (weight > 0) {
                values.put(key, weighted / weight);
            }
        }

        private static double number(Object value) {
            return value instanceof Number number ? number.doubleValue() : 0;
        }
    }
}
