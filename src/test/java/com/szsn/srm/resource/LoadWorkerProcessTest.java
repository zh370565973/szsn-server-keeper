package com.szsn.srm.resource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * 用 4MiB 真实块启动负载子进程，停止后进程退出、驻留内存为 0。
 * 不把开发机内存打满，也不启动 CPU 燃烧。
 */
class LoadWorkerProcessTest {
    @Test
    @Timeout(60)
    void stopExitsChildAndRssReturnsToZero() throws Exception {
        ResourceLoadManager sessions = new ResourceLoadManager();
        LoadWorkerSample sample = new LoadWorkerSample(1D, 8L << 30, 6L << 30, false, false, false, false);
        JvmLoadWorkerHost host = new JvmLoadWorkerHost(sessions, () -> sample);
        try {
            SessionClaim claim = sessions.claim("load-rss");
            host.start(claim, new LoadWorkerRequest(
                    "load-rss", "MEMORY", MemoryEngine.MODE_EXTRA_BYTES, 4L * 1024 * 1024, null, 0D, 1),
                    512L * 1024 * 1024);
            long held = 0;
            long rss = 0;
            long deadline = System.nanoTime() + 20_000_000_000L;
            while (System.nanoTime() < deadline) {
                LoadWorkerSnapshot snapshot = host.snapshot();
                held = snapshot.heldBytes();
                rss = snapshot.loadRssBytes();
                if (held >= 4L * 1024 * 1024 && rss > 0 && snapshot.alive()) {
                    break;
                }
                Thread.sleep(200);
            }
            assertTrue(held >= 4L * 1024 * 1024, "子进程应持有 4MiB，实际 " + held);
            assertTrue(rss > 0, "运行中的负载进程应有驻留内存");
            assertTrue(host.releaseStopped(10_000L));
            LoadWorkerSnapshot stopped = host.snapshot();
            assertFalse(stopped.alive());
            assertEquals(0L, stopped.loadRssBytes());
            assertEquals(0L, stopped.heldBytes());
            assertTrue(sessions.active().isEmpty());
            assertEquals(ExecutionStatus.STOPPED, sessions.lastFinished().orElseThrow().status());
        } finally {
            host.close();
        }
    }
}
