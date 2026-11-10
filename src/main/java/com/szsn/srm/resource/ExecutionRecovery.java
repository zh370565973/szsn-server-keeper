package com.szsn.srm.resource;

import com.szsn.srm.db.DatabaseMigrator;
import java.time.Clock;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * 进程启动时把残留的非终态执行标成 INTERRUPTED。
 * 不重放启动动作，不恢复负载，也不改计划的下一次触发时间。
 * 保留最后一次心跳和已写入的时长，不把发现时间减开始时间写成运行时长。
 */
@Component
public class ExecutionRecovery implements InitializingBean {
    public static final String REASON = "ACTUAL_STOP_TIME_UNKNOWN";

    private final JdbcTemplate jdbc;
    private final Clock clock;

    public ExecutionRecovery(JdbcTemplate jdbc, Clock clock, DatabaseMigrator migrator) {
        if (migrator == null) {
            throw new IllegalArgumentException("数据库迁移尚未完成");
        }
        this.jdbc = jdbc;
        this.clock = clock;
    }

    @Override
    public void afterPropertiesSet() {
        recover();
    }

    public int recover() {
        return jdbc.update("""
                UPDATE TASK_EXECUTION
                SET STATUS = 'INTERRUPTED',
                    REASON = ?,
                    ENDED_AT = ?,
                    VERSION = VERSION + 1
                WHERE STATUS IN ('STARTING', 'RUNNING', 'PAUSING', 'PAUSED', 'STOPPING')
                """, REASON, clock.millis());
    }
}
