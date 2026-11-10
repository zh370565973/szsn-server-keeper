package com.szsn.srm.resource;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 会话 generation 与原子停止标志。急停只改内存中的标志，不获取普通操作锁，也不访问数据库。
 */
public final class SessionGeneration {
    private final AtomicReference<Epoch> current = new AtomicReference<>(new Epoch(1));

    public Epoch current() {
        return current.get();
    }

    /**
     * 停止当前 generation，并发布下一个。旧 Worker 持有的 Epoch 会立即失效。
     */
    public Epoch invalidate() {
        while (true) {
            Epoch epoch = current.get();
            epoch.stop();
            Epoch next = new Epoch(epoch.generation + 1);
            if (current.compareAndSet(epoch, next)) {
                return next;
            }
        }
    }

    public static final class Epoch {
        private final long generation;
        private final AtomicBoolean stopped = new AtomicBoolean(false);

        private Epoch(long generation) {
            this.generation = generation;
        }

        public long generation() {
            return generation;
        }

        public boolean live() {
            return !stopped.get();
        }

        void stop() {
            stopped.set(true);
        }
    }
}
