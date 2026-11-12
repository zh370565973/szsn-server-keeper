package com.szsn.srm.resource;

/**
 * 常驻监控进程只用小堆。大堆只算给负载子进程，并且不超过整机内存的 90%。
 */
public final class LoadHeapPlan {
    public static final long PARENT_HEAP_BYTES = 512L * 1024 * 1024;
    public static final long MIN_CHILD_HEAP_BYTES = 256L * 1024 * 1024;

    private LoadHeapPlan() {
    }

    /**
     * @param targetBytes 固定容量目标；百分比模式或未知目标传 null，此时内存负载按整机 90% 封顶
     */
    public static long childMaxBytes(long memTotalBytes, boolean memoryLoad, Long targetBytes) {
        long cap = memTotalBytes > 0 ? (memTotalBytes * 9) / 10 : MIN_CHILD_HEAP_BYTES;
        cap = Math.max(MIN_CHILD_HEAP_BYTES, cap);
        if (!memoryLoad) {
            return MIN_CHILD_HEAP_BYTES;
        }
        if (targetBytes == null || targetBytes <= 0) {
            return cap;
        }
        long needed = (long) Math.ceil(targetBytes / 0.9d) + (64L * 1024 * 1024);
        needed = Math.max(MIN_CHILD_HEAP_BYTES, needed);
        return Math.min(needed, cap);
    }

    public static String megabyteFlag(String name, long bytes) {
        long mb = Math.max(1, bytes / (1024 * 1024));
        return name + mb + "m";
    }
}
