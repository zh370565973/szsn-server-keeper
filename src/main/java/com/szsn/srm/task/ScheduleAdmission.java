package com.szsn.srm.task;

/**
 * 调度器看开关、急停锁存和活动槽位。不启动负载。
 */
public interface ScheduleAdmission {
    /** 开关为 ON 且没有急停锁存。 */
    boolean controlOpen();

    /** 已有活动会话，含暂停。暂停同样占着槽位。 */
    boolean sessionOccupied();
}
