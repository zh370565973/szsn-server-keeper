package com.szsn.srm.system;

import java.util.List;

public record EnvironmentReport(
        String hostname,
        String osName,
        String osVersion,
        String kernel,
        String cpuModel,
        int logicalCpus,
        String jdkVersion,
        Long memTotalBytes,
        String cgroupVersion,
        boolean controlCapable,
        List<String> controlDisabledReasons,
        Long dataDiskFreeBytes,
        Double dataDiskFreeRatio) {
}
