package com.szsn.srm.common;

public record ApiBody<T>(T data, ApiError error, String requestId, long serverTime) {
    public static <T> ApiBody<T> ok(T data, String requestId, long serverTime) {
        return new ApiBody<>(data, null, requestId, serverTime);
    }

    public static ApiBody<Void> fail(ApiError error, String requestId, long serverTime) {
        return new ApiBody<>(null, error, requestId, serverTime);
    }
}
