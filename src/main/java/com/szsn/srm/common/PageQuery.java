package com.szsn.srm.common;

public final class PageQuery {
    private PageQuery() {
    }

    public static int page(Integer page) {
        if (page == null) {
            return 1;
        }
        if (page < 1) {
            throw new ApiException(org.springframework.http.HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", "page 从 1 开始");
        }
        return page;
    }

    public static int pageSize(Integer pageSize) {
        if (pageSize == null) {
            return 20;
        }
        if (pageSize < 1 || pageSize > 100) {
            throw new ApiException(org.springframework.http.HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", "pageSize 范围是 1 到 100");
        }
        return pageSize;
    }
}
