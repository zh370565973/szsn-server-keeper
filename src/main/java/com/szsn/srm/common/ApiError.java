package com.szsn.srm.common;

import java.util.Map;

public record ApiError(String code, String message, Map<String, Object> details) {
}
