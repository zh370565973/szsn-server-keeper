package com.szsn.srm.resource;

/**
 * 急停、安全锁存和关闭开关时结束负载进程。监控进程继续运行。
 */
public interface LoadProcessKiller {
    void killLoadProcess();
}
