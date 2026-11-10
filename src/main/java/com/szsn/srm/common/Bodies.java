package com.szsn.srm.common;

public final class Bodies {
    private Bodies() {
    }

    public static <T> ApiBody<T> ok(T data) {
        return ApiBody.ok(data, RequestIds.current(), System.currentTimeMillis());
    }
}
