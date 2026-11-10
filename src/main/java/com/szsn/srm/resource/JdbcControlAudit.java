package com.szsn.srm.resource;

import com.szsn.srm.auth.OperationLogRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

@Component
public class JdbcControlAudit implements ControlAudit {
    private static final Logger log = LoggerFactory.getLogger(JdbcControlAudit.class);
    private final OperationLogRepository logs;

    public JdbcControlAudit(OperationLogRepository logs) {
        this.logs = logs;
    }

    @Override
    public void record(String actor, String sourceIp, String action, String result, String reason, String requestId) {
        try {
            logs.insert(actor == null || actor.isBlank() ? "system" : actor, sourceIp, action, result, reason, requestId);
        } catch (RuntimeException exception) {
            log.warn("控制操作日志写入失败");
        }
    }
}
