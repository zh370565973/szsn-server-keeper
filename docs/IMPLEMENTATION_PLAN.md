# 实施计划

PRD 是需求基线。M0 到 M4，以及 M5-01、M5-02、M5-03 已在开发侧完成。麒麟现场对照和长跑仍待验收。制品版本保持 `1.0.0-M1`。

## 当前状态（2026-11-10 验收调整后）

公开启动入口按 PRD 在落库前拒绝：`durationSeconds` 为 10 秒–7 天，`memoryTargetPct` 为 1–70，`memoryTargetBytes` 必须是 4MiB 整数倍。不合法返回 422，不写 STARTING。

生产内存分配器是 `ByteArrayMemoryAllocator`：4MiB 分块、逐页写入非零字节、32MiB/s、三重预算；暂停/停止只丢引用，不调用 `System.gc()`。单元测试仍注入 `FakeMemoryAllocator`。生产 CPU 燃烧器是 `ChecksumCpuBurner`。CPU、内存和联合控制的 HTTP 在环境允许时返回 202，不再以 503 作为内存模式的占位。非 Linux、急停锁存或开关关闭时仍是 503 / `CONTROL_UNAVAILABLE`。模板和计划写接口已开放。计划保存定义并计算 `nextFireAt`。`srm-schedule` 每秒扫描到期项：同时到期只启动一个，冲突和暂停记 SKIPPED_CONFLICT，开关关闭或急停锁存记 SKIPPED_CONTROL_DISABLED，迟到超过 5 秒或离线记 SKIPPED_MISFIRE，多段漏执行只写一条汇总。执行状态立即落库，运行中每 30 秒心跳；重启把残留非终态标成 INTERRUPTED，不恢复负载。`srm-retention` 每小时清理：监控桶保留 7 天，终态执行记录保留 90 天且最多 10 万条，操作日志保留 30 天且最多 20 万条，过期幂等键一并删除。每批单独提交，最多 1000 行。活动实例不删除。曲线过期后执行摘要仍在。

M3-06 的假分配器边界测试已经在仓库里。2026-11-10 在这台 4 核普通 Linux 上以 64MiB 上限做了真实分配取证（见 `docs/PROGRESS.md`），因此 M3-06 改为已完成；这不是麒麟验收，也不是 AT-05 的 256MiB 全量。麒麟专项（M1-09 / AT-02、M5-04）保持待验收。M2-09 / AT-03 的 8 核、30/40/60% 五分钟观察未作为本次完成项。下一步不是 M3-07（该条已完成），也不领取 M4-01。

## 已锁定的选择

- 制品 `server-resource-manager`，包名 `com.szsn.srm`，Java 17，Spring Boot 3.5.16，OSHI 7.7.3。
- H2 文件库由 Spring Boot BOM 管理。自研迁移器按 `V{n}__*.sql` 执行，校验和记在 `SCHEMA_VERSION`。
- 管理员用户名来自 `srm.admin.username`。口令只通过 `java -jar ... init-admin` 写入 BCrypt 文件。
- Linux 使用 `/proc/stat` 与 `/proc/meminfo`。其他系统用 OSHI 的同类计数，CPU 公式相同。缺失字段保持缺失。
- 内存预算、CPU duty、保护窗口和执行状态迁移是纯函数。M2-01 只增加运行时槽位和 generation，不启动 CPU Worker，也不分配负载 `byte[]`。
- M2-02 的已获准启动先提交 STARTING 与配置快照，再调用启动动作。该条完成时公开启动仍返回 503；现已由控制服务在环境允许时受理。
- M2-03 的控制开关只存在于内存，每次进程启动为 OFF。急停先失效 Worker，再持久化锁存；解除必须人工操作。
- M2-04 的安全检查跑在独立的 `srm-safety` 线程，阈值窗口复用 `ProtectionEvaluator`；换页速率由相邻 Swap 样本推算，缺省不发明压力。
- M2-05 的 CPU 引擎复用 `CpuDutyCalculator`；Worker 数截断到逻辑核心数；停止后 2 秒内确认退出。测试注入不自旋的燃烧器。生产 Bean 是 `ChecksumCpuBurner`。该条完成时公开启动仍返回 503。
- M2-06 的暂停只停计算并保留槽位；恢复经 `ResumeGate` 重检；改目标带版本校验与变更记录。该条完成时公开控制写接口仍返回 503。
- M2-07 用可注入时钟/采集器覆盖 AT-04/09/10/13/14；不占满开发机，不分配内存。
- M2-08 开放 CPU 写控制与资源页 CPU 标签；生命周期返回 202 且 `engineCompleted=false`。该条完成时内存/联合仍 503，随后 M3 已开放。
- M3-01 假分配器接入 `MemoryBudget`；EXTRA_BYTES 超预算 422 且无分配。该条完成时预算内内存启动仍 503。
- M3-02 `ChunkedMemoryGrower`：4 MiB 分块、逐块复核停止/预算/时效、32 MiB/s 限速。生产分配改由 `ByteArrayMemoryAllocator` 触碰 `byte[]`；测试仍用假分配器。
- M3-03 `MemoryEngine` + `SystemPercentPlanner`：EXTRA_BYTES / SYSTEM_PERCENT；TARGET_LIMITED；高于目标不分配；释放后 10s 冷却。
- M3-04 暂停/停止清负载引用：2 秒内 heldBytes=0；页面分开展示 heldBytes / heapUsed / rssBytes。不把 RSS 下降当作成功。
- M3-05 `CombinedLoadStarter`：联合启动失败回收两侧并写 FAILED。CPU、内存和联合 HTTP 均已开放。
- M3-06 `M3AcceptanceTest`：假分配器覆盖 AT-05 至 AT-08。真实有限容量分配在普通 Linux 上取证，不改记成麒麟验收。
- M4-02 只接受 ONCE、DAILY、WEEKLY、FIXED_INTERVAL，拒绝 Cron。计划必须绑定不超过 24 小时的 FINITE 模板；固定间隔还要求时长不超过间隔。夏令时缺口跳过，重叠本地时间只取第一次。固定间隔按 UTC 锚点直接算下一次，不写漏执行。最多 100 条。不启动调度线程。
- M4-03 状态变更立即写入 `TASK_EXECUTION`。运行中每 30 秒保存心跳、单调时钟时长和按有效采样加权的统计；整机内存与 heldBytes 分开。启动时把残留非终态标为 INTERRUPTED，不恢复负载，不改计划的下一次触发时间。该条完成时不扫描到期项。
- M4-04 `ScheduleDispatcher` 每秒扫描。宽限 5 秒且扫描间隔不断档才尝试启动；否则记 SKIPPED_MISFIRE。离线或时钟前跳按计划写一条带 `missedCount` 的汇总，直接跳到下一次未来时间。回拨不新增执行。同一时刻按 `scheduledAt`、`scheduleId` 只启动一个。测试注入启动器，不在开发机上打满 CPU 或分配负载。
- M4-05 `RetentionCleaner` 每小时分批清理。监控桶 7 天，终态执行记录 90 天或 10 万条，操作日志 30 天或 20 万条，已过期幂等键按 `EXPIRES_AT` 删除。单批最多 1000 行并单独提交。STARTING、RUNNING、PAUSING、PAUSED、STOPPING 不删除。历史查询用 `pastRetention` 说明曲线已过保留期，执行摘要仍保留。
- M4-07 用页面替换模板、计划和执行记录的空状态。执行查询只读 `TASK_EXECUTION`。计划列表用窗口函数取每个计划最近一条 `SKIPPED`。目标变更没有单独的表，页面不把空列表说成没有人改过目标。
- 前端构建进入 `src/main/resources/static`，再打进同一个 JAR。
- M5-02 用 `deploy/assemble-release.sh` 生成离线目录：JAR、`SHA256SUMS`、`MANIFEST`、运行时依赖清单、OpenAPI、部署说明和 `deploy/` 脚本。打包脚本不启动服务。
- M5-03 `deploy/verify-offline.sh` 与 `OfflineProbe`：只使用 JDK，服务 `PATH` 不含 npm/Maven，扫描页面和依赖名，回环地址上登录，并确认没有对外套接字。Windows 证据用防火墙阻断了该 `java.exe` 的出站。不启动负载。

## 需求对应

| 编号 | 本轮 |
| --- | --- |
| MON-01 / MON-02 / MON-03 | 已实现采集、质量、SSE、10 秒聚合和有界查询 |
| RES-01 | M2-01 交付单活动槽位和 generation。急停不等待普通操作段 |
| RES-02 | 启动落库、幂等键、CPU 预检已做。M3-01 固定容量超预算 422 |
| RES-03 | M2-05/M2-06/M2-08 已交付 CPU 引擎、页面展示与 202 受理语义 |
| RES-04 至 RES-06 | 预算、分块、双模式、暂停清引用、联合失败回收、内存/联合页面已在。生产路径分配真实 `byte[]` |
| SAFE-01 | 保护窗口纯函数、独立安全线程与 CPU 引擎已在 |
| SAFE-02 | M2-03 交付内存开关、急停锁存、人工解除和页面急停入口 |
| TASK-01 至 TASK-03 | 模板、四种计划、启动快照、即时状态、30 秒心跳、重启 INTERRUPTED、冲突跳过、漏执行和历史清理已做。AT-11/12/15/20 已用文件型 H2 验收。M4-07 页面可搜索和维护模板、计划，并分页查看执行快照；计划列表带下次触发和最近跳过原因 |
| SEC-01 | 会话、CSRF、登录限速、init-admin |
| DEP-01 / DEP-02 | 单 JAR 可构建。M5-01 已提供安装、systemd、冷备、恢复和升级回滚。M5-02 已生成带 SHA-256 的离线发布目录。M5-03 已在本机阻断 java.exe 出站后完成冷启动和登录 |

## 下一阶段入口

M5-03 已完成。下一步是 M5-04：麒麟现场的 AT-17、AT-18 和 24 小时长跑，没有获准机器时保持待验收。M5-05 要等 M5-04。AT-03 五分钟曲线仍待获准环境。
