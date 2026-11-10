package com.szsn.srm.resource;

/**
 * 启动准入。关闭的开关和未解除的急停锁存都拒绝启动，并且不得因此分配负载。
 */
@FunctionalInterface
public interface StartGate {
    StartGate OPEN = () -> {
    };

    void admit();
}
