# 实施计划

PRD 是需求基线。M0 与 M1 已完成开发侧验证。M2-01 已接上运行时单会话槽位和 generation。M2-02 起以及 M3 至 M5 未开始。结论保持为：开发验证通过，目标环境待验收。

## 已锁定的选择

- 制品 `server-resource-manager`，包名 `com.szsn.srm`，Java 17，Spring Boot 3.5.16，OSHI 7.7.3。
- H2 文件库由 Spring Boot BOM 管理。自研迁移器按 `V{n}__*.sql` 执行，校验和记在 `SCHEMA_VERSION`。
- 管理员用户名来自 `srm.admin.username`。口令只通过 `java -jar ... init-admin` 写入 BCrypt 文件。
- Linux 使用 `/proc/stat` 与 `/proc/meminfo`。其他系统用 OSHI 的同类计数，CPU 公式相同。缺失字段保持缺失。
- 内存预算、CPU duty、保护窗口和执行状态迁移是纯函数，本轮没有 Worker，也不分配负载 `byte[]`。
- 控制写接口返回 `503 / CONTROL_UNAVAILABLE`。
- 前端构建进入 `src/main/resources/static`，再打进同一个 JAR。

## 需求对应

| 编号 | 本轮 |
| --- | --- |
| MON-01 / MON-02 / MON-03 | 已实现采集、质量、SSE、10 秒聚合和有界查询 |
| RES-01 至 RES-06 | 只交付状态迁移、duty 和预算纯函数 |
| SAFE-01 / SAFE-02 | 只交付保护窗口纯函数；急停接口返回 503 |
| TASK-01 至 TASK-03 | 表已建；列表为空；写操作返回 503 |
| SEC-01 | 会话、CSRF、登录限速、init-admin |
| DEP-01 / DEP-02 | 单 JAR 可构建；安装与备份脚本留到 M5 |

## 下一阶段入口

M2-01 已提供 `ResourceLoadManager` 与 generation 取消。下一步是启动前落库和幂等键，然后才是急停锁存、独立安全线程和 CPU 引擎。真实压力只在获准环境执行。
