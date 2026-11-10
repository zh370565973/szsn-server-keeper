# 实施计划

PRD 是需求基线。M0 与 M1 已完成。M2-01 至 M2-08 已在开发侧交付（含 AT-04/09/10/13/14 自动测试与资源页 CPU 标签）。结论保持为：开发验证通过，目标环境待验收。

## 已锁定的选择

- 制品 `server-resource-manager`，包名 `com.szsn.srm`，Java 17，Spring Boot 3.5.16，OSHI 7.7.3。
- H2 文件库由 Spring Boot BOM 管理。自研迁移器按 `V{n}__*.sql` 执行，校验和记在 `SCHEMA_VERSION`。
- 管理员用户名来自 `srm.admin.username`。口令只通过 `java -jar ... init-admin` 写入 BCrypt 文件。
- Linux 使用 `/proc/stat` 与 `/proc/meminfo`。其他系统用 OSHI 的同类计数，CPU 公式相同。缺失字段保持缺失。
- 内存预算、CPU duty、保护窗口和执行状态迁移是纯函数。M2-01 只增加运行时槽位和 generation，不启动 CPU Worker，也不分配负载 `byte[]`。
- M2-02 的已获准启动先提交 STARTING 与配置快照，再调用启动动作。公开的执行启动接口仍返回 `503 / CONTROL_UNAVAILABLE`。
- M2-03 的控制开关只存在于内存，每次进程启动为 OFF。急停先失效 Worker，再持久化锁存；解除必须人工操作。
- M2-04 的安全检查跑在独立的 `srm-safety` 线程，阈值窗口复用 `ProtectionEvaluator`；换页速率由相邻 Swap 样本推算，缺省不发明压力。
- M2-05 的 CPU 引擎复用 `CpuDutyCalculator`；Worker 数截断到逻辑核心数；停止后 2 秒内确认退出。测试注入不自旋的燃烧器；公开启动接口仍返回 503。
- M2-06 的暂停只停计算并保留槽位；恢复经 `ResumeGate` 重检；改目标带版本校验与变更记录。公开控制写接口仍返回 503。
- M2-07 用可注入时钟/采集器覆盖 AT-04/09/10/13/14；不占满开发机，不分配内存。
- M2-08 开放 CPU 写控制与资源页 CPU 标签；生命周期返回 202 且 `engineCompleted=false`；内存/联合仍 503。
- M3-01 假分配器接入 `MemoryBudget`；EXTRA_BYTES 超预算 422 且无分配；预算内内存启动仍 503。
- 前端构建进入 `src/main/resources/static`，再打进同一个 JAR。

## 需求对应

| 编号 | 本轮 |
| --- | --- |
| MON-01 / MON-02 / MON-03 | 已实现采集、质量、SSE、10 秒聚合和有界查询 |
| RES-01 | M2-01 交付单活动槽位和 generation。急停不等待普通操作段 |
| RES-02 | 启动落库、幂等键、CPU 预检已做。M3-01 固定容量超预算 422 |
| RES-03 | M2-05/M2-06/M2-08 已交付 CPU 引擎、页面展示与 202 受理语义 |
| RES-04 至 RES-06 | 预算纯函数与假分配器门闸已在；分块/双模式/释放仍未做 |
| SAFE-01 | 保护窗口纯函数、独立安全线程与 CPU 引擎已在 |
| SAFE-02 | M2-03 交付内存开关、急停锁存、人工解除和页面急停入口 |
| TASK-01 至 TASK-03 | 表已建；手动启动快照可落库；列表和写接口仍按后续任务保留 |
| SEC-01 | 会话、CSRF、登录限速、init-admin |
| DEP-01 / DEP-02 | 单 JAR 可构建；安装与备份脚本留到 M5 |

## 下一阶段入口

M3-01 已完成。下一步是 M3-02：4MiB 分块、逐块检查和 32MiB/s 限速。真实压力（AT-03 / M2-09）只在获准环境执行。
