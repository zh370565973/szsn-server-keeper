package com.szsn.srm.monitor;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import org.springframework.stereotype.Component;

@Component
public class SnapshotStore {
    static final int CAPACITY = 15 * 60;
    private final Deque<MetricSnapshot> ring = new ArrayDeque<>();
    private MetricSnapshot latest;

    public synchronized void add(MetricSnapshot snapshot) {
        latest = snapshot;
        ring.addLast(snapshot);
        while (ring.size() > CAPACITY) {
            ring.removeFirst();
        }
    }

    public synchronized MetricSnapshot latest() {
        return latest;
    }

    public synchronized int size() {
        return ring.size();
    }

    public synchronized List<MetricSnapshot> copy() {
        return List.copyOf(ring);
    }
}
