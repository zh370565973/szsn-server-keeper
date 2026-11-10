package com.szsn.srm.safety;

import java.util.List;

public record ProtectionDecision(
        boolean forbidCpuGrowth,
        boolean zeroDuty,
        boolean forbidMemoryGrowth,
        boolean safetyStop,
        boolean latch,
        List<String> reasons) {
}
