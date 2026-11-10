package com.szsn.srm.common;

import java.util.List;

public record PageResult<T>(List<T> items, int page, int pageSize, long total) {
    public static <T> PageResult<T> empty(int page, int pageSize) {
        return new PageResult<>(List.of(), page, pageSize, 0);
    }
}
