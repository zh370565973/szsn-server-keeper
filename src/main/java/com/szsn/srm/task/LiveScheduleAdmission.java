package com.szsn.srm.task;

import com.szsn.srm.resource.ControlState;
import com.szsn.srm.resource.ControlSwitch;
import com.szsn.srm.resource.ResourceLoadManager;
import org.springframework.stereotype.Component;

@Component
public class LiveScheduleAdmission implements ScheduleAdmission {
    private final ControlSwitch controlSwitch;
    private final ResourceLoadManager sessions;

    public LiveScheduleAdmission(ControlSwitch controlSwitch, ResourceLoadManager sessions) {
        this.controlSwitch = controlSwitch;
        this.sessions = sessions;
    }

    @Override
    public boolean controlOpen() {
        ControlState state = controlSwitch.state();
        return "ON".equals(state.controlSwitch()) && !state.emergencyLatched();
    }

    @Override
    public boolean sessionOccupied() {
        return sessions.active().isPresent();
    }
}
