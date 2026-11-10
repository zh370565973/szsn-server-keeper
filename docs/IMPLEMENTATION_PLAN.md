# 实施计划

PRD 是需求基线。M0 与 M1 已完成。M2-01 已交付运行时会话槽位，M2-02 已交付启动落库和幂等键，M2-03 已交付控制开关和急停锁存。结论保持为：开发验证通过，目标环境待验收。

## 已锁定的选择

- 制品 `server-resource-manager`，包名 `com.szsn.srm`，Java 17，Spring Boot 3.5.16，OSHI 7.7.3。
- H2 文件库由 Spring Boot BOM 管理。自研迁移器按 `V{n}__*.sql` 执行，校验和记在 `SCHEMA_VERSION`。
- 管理员用户名来自 `srm.admin.username`。口令只通过 `java -jar ... init-admin` 写入 BCrypt 文件。
- Linux 使用 `/proc/stat` 与 `/proc/meminfo`。其他系统用 OSHI 的同类计数，CPU 公式相同。缺失字段保持缺失。
- 内存预算、CPU duty、保护窗口和执行状态迁移是纯函数。M2-01 只增加运行时槽位和 generation，不启动 CPU Worker，也不分配负载 `byte[]`。
- M2-02 的已获准启动先提交 STARTING 与配置快照，再调用启动动作。公开的执行启动接口仍返回 `503 / CONTROL_UNAVAILABLE`。
- M2-03 的控制开关只存在于内存，每次进程启动为 OFF。急停先失效 Worker，再持久化锁存；解除必须人工操作。
- 前端构建进入 `src/main/resources/static`，再打进同一个 JAR。

## 需求对应

| 编号 | 本轮 |
| --- | --- |
| MON-01 / MON-02 / MON-03 | 已实现采集、质量、SSE、10 秒聚合和有界查询 |
| RES-01 | M2-01 交付单活动槽位和 generation。急停不等待普通操作段 |
| RES-02 | 启动落库和幂等键已做。预检、预算拒绝和引擎未做 |
| RES-03 至 RES-06 | 状态迁移、duty 和预算纯函数仍在；引擎未做 |
| SAFE-01 | 保护窗口纯函数已在；独立安全线程未做 |
| SAFE-02 | M2-03 交付内存开关、急停锁存、人工解除和页面急停入口 |
| TASK-01 至 TASK-03 | 表已建；手动启动快照可落库；列表和写接口仍按后续任务保留 |
| SEC-01 | 会话、CSRF、登录限速、init-admin |
| DEP-01 / DEP-02 | 单 JAR 可构建；安装与备份脚本留到 M5 |

## 下一阶段入口

M2-03 已接上控制开关和急停锁存。下一步是 M2-04 的独立安全线程，然后才是 CPU 引擎。真实压力只在获准环境执行。
