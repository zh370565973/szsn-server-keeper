package com.szsn.srm.monitor;

import java.util.LinkedHashMap;
import java.util.Map;

public final class MeminfoParser {
    private MeminfoParser() {
    }

    public record MemoryReading(Long totalBytes, Long availableBytes, Long cacheBytes, Long swapTotalBytes, Long swapUsedBytes) {
    }

    public static MemoryReading parse(String text) {
        Map<String, Long> values = new LinkedHashMap<>();
        if (text != null) {
            for (String line : text.split("\\R")) {
                int colon = line.indexOf(':');
                if (colon < 0) {
                    continue;
                }
                String key = line.substring(0, colon).trim();
                String[] tokens = line.substring(colon + 1).trim().split("\\s+");
                if (tokens.length == 0 || tokens[0].isBlank()) {
                    continue;
                }
                long amount = Long.parseLong(tokens[0]);
                if (tokens.length > 1 && "kB".equals(tokens[1])) {
                    amount *= 1024L;
                }
                values.put(key, amount);
            }
        }
        Long total = values.get("MemTotal");
        Long available = values.get("MemAvailable");
        Long cached = values.get("Cached");
        Long buffers = values.get("Buffers");
        Long cache = null;
        if (cached != null && buffers != null) {
            cache = cached + buffers;
        } else if (cached != null) {
            cache = cached;
        }
        Long swapTotal = values.get("SwapTotal");
        Long swapFree = values.get("SwapFree");
        Long swapUsed = null;
        if (swapTotal != null && swapFree != null) {
            swapUsed = Math.max(0, swapTotal - swapFree);
        }
        return new MemoryReading(total, available, cache, swapTotal, swapUsed);
    }
}
