package com.szsn.srm.resource;

/**
 * 恢复前重新预检。失败时抛出业务异常，会话保持 {@code PAUSED}，不得瞬间满负载。
 */
@FunctionalInterface
public interface ResumeGate {
    ResumeGate OPEN = () -> {
    };

    void admit();
}
