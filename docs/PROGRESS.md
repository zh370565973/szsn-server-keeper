# 进度

阶段结论：M0 与 M1 在 Windows 开发机上验证通过。目标麒麟环境待验收。M2-01 已在开发侧完成运行时会话槽位。M2-02 已在开发侧完成启动落库和幂等键。本轮没有实现或启动 CPU/内存负载。

## 已完成

| 编号 | 可观察行为 | 代码 |
| --- | --- | --- |
| MON-01 | 总览显示主机、CPU、内存、JVM、磁盘、网络。Linux 读 `/proc/stat` 与 `/proc/meminfo`；本机用 OSHI 的同类计数。缺失项显示“无数据” | `OshiMetricSampler`、`ProcStatMath`、`MeminfoParser` |
| MON-02 | 快照带 sequence、observedAt、ageMs、quality、missingFields。超过 3 秒为 STALE，超过 10 秒为 UNAVAILABLE | `SnapshotQuality` |
| MON-03 | 单采集线程每秒采样，SSE 只保留最新帧，15 秒心跳，最多 5 个连接。10 秒聚合写入 H2，查询最多 2000 桶 | `CollectorLoop`、`SseHub`、`HistoryService` |
| SEC-01 | 登录、CSRF、30 分钟活动超时、5 次失败后冷却 5 分钟。SSE 不刷新活动时间。注销关闭该会话的 SSE | `AuthController`、`SecurityConfig` |
| RES / SAFE | 预算、duty、保护窗口和状态迁移可单测。写控制接口返回 503，不写执行记录 | `MemoryBudget`、`CpuDutyCalculator`、`ProtectionEvaluator`、`UnavailableControlController` |
| DEP-01 | `mvn package` 产出内嵌前端的可执行 JAR | `pom.xml`、`frontend/` |

H2 对 `(SCHEDULE_ID, SCHEDULED_AT)` 的唯一索引允许多个全空组合，相同的非空计划触发会被拒绝。`SchemaMigrationTest` 固定了这个行为。

## 验证命令

2026-11-10，工作目录 `D:\SZSN\szsn-server-keeper`。

- `mvn clean "-Dskip.frontend=true" test`：21 个测试，0 失败。覆盖预算、duty、保护窗口、`/proc/stat` 夹具、H2 迁移与唯一约束、认证/CSRF/未登录、控制接口无副作用、SSE 上限与慢客户端合并。
- `mvn -DskipTests package`：成功。H2 解析为 2.3.232。
- `java -jar target/server-resource-manager-1.0.0-M1.jar init-admin`：退出码 0，只写入 BCrypt 哈希。
- 同一 JAR 监听 `127.0.0.1:18080`：`/api/v1/auth/csrf` 返回 200，`/` 返回内嵌页面 200。

对应验收：

- AT-01：构建侧通过。JAR 含 `BOOT-INF/classes/static/index.html`，页面没有 CDN 外链。无网络安装包和断网机器启动仍待 M5。
- AT-02：开发侧通过。夹具对照 busy 公式；本机快照内存总量大于 0，且不是写死的模拟值。与麒麟 `/proc` 的现场对照待验收。
- AT-16：开发侧通过。未登录返回 401，缺少 CSRF 返回 403，登录后可以读取监控，注销关闭已登记的 SSE。
- AT-19：开发侧通过。慢订阅者发送阻塞时，发布仍能返回，连接里只保留最新帧。

## 未验证

- 银河麒麟 V10 SP3、真实 `/proc` 对照、cgroup 判定和 OSHI/JNA 在目标内核上的加载。
- AT-03 至 AT-15、AT-17、AT-18、AT-20。负载引擎、计划调度、急停锁存、备份升级都未实现。
- 非回环 HTTPS 与 CIDR 只做了启动拒绝判断，没有证书环境。
- 24 小时运行、磁盘满和数据库故障注入。

## 已知限制

- Windows 上的 CPU 百分比来自 OSHI 计数，不是 `/proc/stat`。控制能力被明确禁用。
- 停止后的 RSS 回落不属于本阶段，也没有调用 `System.gc()`。
- 前端生产包约 2.6 MB，已全部打进 JAR。Vite 8 的传递依赖提示需要更新的 Node，当前 Node 20.19.5 可以完成构建。
- 任务、计划和执行页面只有空状态。列表接口返回空页，写接口返回 503。

## M2-01 运行时会话槽位

同一进程只有一个 STARTING、RUNNING、PAUSING、PAUSED 或 STOPPING 会话。暂停不释放槽位。急停只递增 generation 并置停止标志，旧 Worker 许可立即失效；该路径不进入普通操作锁，也不调用日志记录点。新会话要等旧 Worker 确认退出且持有量为 0。持有量只是计数，没有分配内存。写控制接口仍返回 503。启动落库、幂等键、急停锁存和 CPU 引擎留到后续任务。

2026-11-10，工作目录 `/workspace`。测试运行时是 JDK 21.0.12.1；`pom.xml` 的编译目标仍是 17。

- `mvn -Dskip.frontend=true test`：BUILD SUCCESS。27 个测试，0 失败。其中 `ResourceLoadManagerTest` 6 个，覆盖五种占用状态互斥、暂停保留槽位、急停在日志记录点仍阻塞时返回且旧许可失效、Worker 未退出或持有量非 0 时拒绝新会话、普通停止幂等，以及 4 个线程同时争用只有一个成功。原有 21 个测试仍通过，控制写接口仍是 503 且不增加 `TASK_EXECUTION`。

## M2-02 启动前落库和幂等键

已获准的手动启动先在一个事务里写入 `TASK_EXECUTION`（状态 STARTING、完整配置快照）和 `REQUEST_DEDUP`，提交成功后才调用 `WorkerLaunch`。事务中的第二次写入失败会回滚，启动动作不会执行。启动动作抛错时，用已有状态迁移把该行写成 FAILED，原因 `START_FAILED`；同一幂等键再次进入只返回这条记录，不再启动。

幂等键必须是 UUID，大小写视为同一键。请求先收成字段顺序固定的配置快照再计算 SHA-256。24 小时内相同键和相同快照返回同一个 executionId，不重复启动。相同键、不同快照返回 409 / `IDEMPOTENCY_CONFLICT`，不改已有快照。窗口按 `expires_at = created_at + 24h` 保存，`now < expires_at` 仍算有效；满 24 小时后同一键新建 executionId，旧行保留。本任务不计算内存预算，不跑 CPU，也不分配 `byte[]`。

公开的 `POST /api/v1/executions` 仍返回 503 / `CONTROL_UNAVAILABLE`，也不写执行记录。控制开关、急停锁存、预检和 CPU 引擎属于后续任务；在那些检查通过之前，不能从 HTTP 占用会话槽位。

2026-11-10，工作目录 `/agent/repos/szsn-server-keeper`。测试运行时是 JDK 21.0.12.1；`pom.xml` 的编译目标仍是 17。

- `mvn -Dskip.frontend=true package`：BUILD SUCCESS。37 个测试，0 失败，0 错误，0 跳过。其中 `ExecutionStartServiceTest` 10 个，覆盖提交后才启动、事务失败不启动、24 小时内重放、大小写与数字写法相同、参数冲突 409、满 24 小时新建、启动失败后重试不再启动、提交后才能签发槽位、8 线程并发只有一次启动，以及非法请求不落库。原有测试仍通过，控制写接口仍是 503 且不增加 `TASK_EXECUTION`。

## 下一阶段

M2-03：控制开关与急停锁存。每次启动开关为 OFF；急停持久化锁存；解除锁存需人工操作、无活动会话、连续 30 秒指标健康。仍不在开发机上做真实满载，也不实现内存分配或 CPU 引擎。
