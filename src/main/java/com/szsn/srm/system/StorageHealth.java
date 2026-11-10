package com.szsn.srm.system;

import java.util.concurrent.atomic.AtomicReference;
import org.springframework.stereotype.Component;

@Component
public class StorageHealth {
    private final AtomicReference<String> fault = new AtomicReference<>();

    public boolean healthy() {
        return fault.get() == null;
    }

    public String fault() {
        return fault.get();
    }

    public void markFault(String reason) {
        fault.compareAndSet(null, reason);
    }
}
