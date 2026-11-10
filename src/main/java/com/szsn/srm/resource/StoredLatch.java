package com.szsn.srm.resource;

public record StoredLatch(boolean latched, String reason, long at, long epoch) {
}
