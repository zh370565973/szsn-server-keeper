package com.szsn.srm.resource;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 绑定到某个 generation 的 Worker 许可。只记录持有字节，不分配 {@code byte[]}，也不做计算。
 */
public final class LoadWorker {
    private final SessionGeneration.Epoch epoch;
    private final ActiveSession session;
    private final Runnable settled;
    private final Object lock = new Object();
    private final AtomicLong held = new AtomicLong();
    private final AtomicBoolean exited = new AtomicBoolean(false);

    private LoadWorker(SessionGeneration.Epoch epoch, ActiveSession session, Runnable settled, boolean alreadyExited) {
        this.epoch = epoch;
        this.session = session;
        this.settled = settled;
        if (alreadyExited) {
            this.exited.set(true);
        }
    }

    static LoadWorker registered(SessionGeneration.Epoch epoch, ActiveSession session, Runnable settled) {
        return new LoadWorker(epoch, session, settled, false);
    }

    static LoadWorker inactive(SessionGeneration.Epoch epoch) {
        return new LoadWorker(epoch, null, () -> {
        }, true);
    }

    public long generation() {
        return epoch.generation();
    }

    public boolean live() {
        return !exited.get() && epoch.live() && session != null && session.acceptsLoad();
    }

    public boolean exited() {
        return exited.get();
    }

    public long heldBytes() {
        return held.get();
    }

    /**
     * 记账式占用。generation 已失效、会话不再接受负载或 Worker 已退出时返回 false，且不增加持有量。
     */
    public boolean claim(long bytes) {
        if (bytes <= 0) {
            throw new IllegalArgumentException("负载字节必须为正数");
        }
        synchronized (lock) {
            if (!canHold()) {
                return false;
            }
            long current = held.get();
            if (current > Long.MAX_VALUE - bytes) {
                throw new IllegalArgumentException("负载字节溢出");
            }
            held.addAndGet(bytes);
            if (!canHold()) {
                held.addAndGet(-bytes);
                return false;
            }
            return true;
        }
    }

    public void dropReferences() {
        synchronized (lock) {
            held.set(0);
        }
        settled.run();
    }

    public void acknowledgeExit() {
        boolean changed;
        synchronized (lock) {
            changed = exited.compareAndSet(false, true);
        }
        if (changed) {
            settled.run();
        }
    }

    private boolean canHold() {
        return !exited.get() && epoch.live() && session != null && session.acceptsLoad();
    }
}
