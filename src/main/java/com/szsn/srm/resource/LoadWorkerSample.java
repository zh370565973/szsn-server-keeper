package com.szsn.srm.resource;

/**
 * 父进程每秒写给负载子进程的机器样本和安全标志。
 */
public record LoadWorkerSample(
        Double cpuBusy,
        Long memTotal,
        Long memAvailable,
        boolean forbidCpu,
        boolean zeroDuty,
        boolean forbidMemory,
        boolean safetyStop) {
}
