package com.szsn.srm.task;

import com.szsn.srm.resource.CpuControlService;
import org.springframework.stereotype.Component;

/**
 * 生产路径把已落库的计划启动交给现有控制服务。环境不可控时由控制服务拒绝，不在这里另写负载。
 */
@Component
public class EngineScheduleLauncher implements ScheduleLauncher {
    private final CpuControlService control;

    public EngineScheduleLauncher(CpuControlService control) {
        this.control = control;
    }

    @Override
    public void launch(String executionId, String snapshotJson) {
        control.launchScheduled(executionId, snapshotJson);
    }
}
