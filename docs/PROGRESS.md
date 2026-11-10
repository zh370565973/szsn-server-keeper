# 进度

阶段结论：M0 与 M1 在 Windows 开发机上验证通过。目标麒麟环境待验收。M2-01 至 M2-08 已在开发侧完成（含资源页 CPU 标签）。M2-09 / AT-03 真实 CPU 目标待获准环境验收。本轮测试不占满开发机 CPU，也不分配内存。

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
- AT-03 至 AT-15、AT-17、AT-18、AT-20。负载引擎、计划调度和备份升级都未实现。急停锁存与独立安全线程已在开发侧完成，目标环境待验收。
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

## M2-03 控制开关与急停锁存

控制开关只留在内存里，每个进程启动都是 OFF，重启不会沿用上一次的 ON。急停先使当前 Worker 许可失效，并把开关打到 OFF、写入内存锁存，然后才尝试把锁存原因写入 `SYS_CONFIG`。写库失败不撤销停止；本进程保持锁存，下次启动仍是 OFF。重复急停不新增锁存行。锁存记录读不出来时按已锁存处理，拒绝启动。

解除锁存只能由人工调用。当时必须没有活动会话、数据库可写，并且关键指标连续健康 30 秒。样本间隔超过 2.5 秒、时钟回退，或出现锁存级条件，健康计时就重新开始。解除成功后开关仍是 OFF，后台不会自己清除锁存。打开开关只允许以后的启动，不会补跑计划，也不会启动引擎。

公开的 `POST /api/v1/executions` 仍返回 503 / `CONTROL_UNAVAILABLE`。已获准的启动服务在写执行记录之前检查开关和锁存，关闭或锁存时不增加 `TASK_EXECUTION`。各主视图顶栏有急停按钮，点击直接执行，不先弹确认框。资源页的启动、暂停、恢复和停止仍然禁用。单次快照没有换页速率时，健康判断不把缺省值当成零压力；速率窗口留给后续安全线程。

2026-11-10，工作目录 `D:\SZSN\szsn-server-keeper`。测试运行时是 JDK 17.0.19。

- `mvn "-Dskip.frontend=true" test`：BUILD SUCCESS。51 个测试，0 失败，0 错误，0 跳过。其中 `ControlSwitchTest` 9 个，覆盖每次启动为 OFF、锁存读失败时拒绝启动、急停在锁存写库返回前使 Worker 失效、写库失败后重试、关闭开关停止会话且打开开关不启动、解除条件、重启后锁存仍在、关闭或锁存时不写执行记录，以及 8 线程并发只保留一行锁存。`LatchHealthWindowTest` 4 个。`ApiIntegrationTest` 覆盖未登录或缺少 CSRF 时急停被拒绝，带会话和 CSRF 时锁存且不增加执行记录。
- `npm run build`（frontend）：成功。页面打进 `src/main/resources/static`，没有 CDN 外链。Node 20.19.5 对 Vite 8 的传递依赖有引擎提示，构建本身完成。
- 临时进程监听 `127.0.0.1:18081`：登录后顶栏可直接急停，状态变为 OFF 且已锁存；锁存期间打开开关被拒绝；指标连续健康超过 30 秒后可人工解除，解除后仍为 OFF；再打开开关不产生活动会话。资源页启动按钮保持禁用。该进程使用 `target/m2-03-ui`，核对后已停止。

## M2-04 独立安全线程

安全检查跑在独立的 `srm-safety` 单线程调度器上，不与采集、历史写入、SSE 发送共用执行器，也不启动 CPU Worker。每秒读取最新快照，用已有 `ProtectionEvaluator` 判定 forbid/zeroDuty/stop/latch；解除锁存用的健康窗口也改由该线程喂入。换页速率由相邻 Swap 已用字节推算，缺上一样本时保持 null，不把缺省当成零压力。达到锁存级条件或存储故障时，先使 Worker 许可失效并关闭开关，再尝试持久化锁存。SSE 的 safety 帧附带当前保护标志，由采集线程只读最新信号后发送。

本任务不实现 CPU 引擎，也不分配内存。公开启动接口仍返回 503。

2026-11-10，工作目录 `D:\SZSN\szsn-server-keeper`。测试运行时是 JDK 17.0.19。

- `mvn "-Dskip.frontend=true" clean test`：BUILD SUCCESS。61 个测试，0 失败，0 错误，0 跳过。其中 `SafetyLoopTest` 7 个，覆盖独立线程名、CPU 85% 归零与 95% 锁存、指标过期先禁止后停止、缺省换页速率不发明压力、存储故障不等待历史队列、健康窗口由安全线程推进，以及锁存写库阻塞前 Worker 已失效。`SwapRateWindowTest` 3 个。原有测试仍通过。

## M2-05 CPU 引擎

CPU 引擎复用已有 `CpuDutyCalculator`（含安全归零、禁止增长、外部高负载）。默认 Worker 数等于可用逻辑核心数，显式请求时也不超过该上限；Worker 使用约 100ms 时间片，等待可唤醒，计算路径每不超过 5ms 检查许可。急停或普通停止后，在 2 秒内 join Worker 并 `confirmWorkerExit`。可注入采集与燃烧器；单元测试使用瞬时燃烧器，不自旋占满开发机。生产用的 `ChecksumCpuBurner` 仅在显式调用时产生可观察校验值。

公开启动接口仍返回 503。暂停、恢复和运行中改目标留到 M2-06；不实现内存分配。

2026-11-10，工作目录 `D:\SZSN\szsn-server-keeper`。测试运行时是 JDK 17.0.19。

- `mvn "-Dskip.frontend=true" clean test`：BUILD SUCCESS。69 个测试，0 失败，0 错误，0 跳过。其中 `CpuEngineTest` 8 个，覆盖 Worker 上限、duty 渐进与安全归零、外部高负载归零、禁止增长不抬高 duty、急停 2 秒内退出、自动控制线程名、拒绝重复启动，以及校验燃烧器尊重许可。原有测试仍通过。

## M2-06 CPU 暂停、恢复和运行中改目标

暂停将会话迁到 PAUSED，duty 归零，Worker 停止计算但保留槽位与许可，其他任务不能插队。恢复通过可注入 `ResumeGate` 重新预检：失败保持 PAUSED；通过后 STARTING → RUNNING，duty 从 0 渐进。运行中或暂停时可按 `expectedVersion` 改目标并记变更；记录失败不抬版本。外部高负载仍归零 duty。燃烧器每段检查暂停标志。公开控制写接口仍返回 503。不实现内存分配。

2026-11-10，工作目录 `D:\SZSN\szsn-server-keeper`。测试运行时是 JDK 17.0.19。

- `mvn "-Dskip.frontend=true" clean test`：BUILD SUCCESS。74 个测试，0 失败，0 错误，0 跳过。其中 `CpuEngineTest` 12 个（新增暂停保留槽位与恢复重检、版本改目标、日志失败不抬版本、恢复后外部高负载归零），`RuleTest` 增补 `resetDuty`。原有测试仍通过。

## M2-07 M2 自动测试

新增 `M2AcceptanceTest`，用可注入时钟、采集器和瞬时燃烧器覆盖：

- AT-04：外部 CPU 抬高后两次控制采样内 duty 归零；降载后按斜率渐进，无占满冲刺
- AT-09：普通停止与到期停止均在 2 秒内确认退出，heldBytes=0，终态分别为 STOPPED / COMPLETED
- AT-10：调整与急停竞态下旧 generation 失效，开关 OFF 且锁存，新启动被拒绝；人工解除后仍 OFF
- AT-13：指标超 3 秒禁止增长、超 10 秒安全停止并锁存；可选温度缺失仅使质量 PARTIAL，不触发保护
- AT-14：CPU 85% / steal 连续窗口与滞回；CPU 95% 锁存后健康样本不会自动解除，须人工 reset

不实现内存分配，不打开资源页写控制，不跑真实压力。

2026-11-10，工作目录 `D:\SZSN\szsn-server-keeper`。测试运行时是 JDK 17.0.19。

- `mvn "-Dskip.frontend=true" clean test`：BUILD SUCCESS。79 个测试，0 失败，0 错误，0 跳过。其中 `M2AcceptanceTest` 5 个全部通过。原有测试仍通过。

## M2-08 资源页 CPU 标签

开放 CPU 控制写接口与资源页 CPU 标签。`/resource/status` 返回目标、实际、duty、Worker 数、受限原因与配置版本。启动/暂停/恢复/停止/改目标返回 202，`engineCompleted=false`，前端以状态轮询确认，不假定引擎已调到目标。非 Linux 或环境不可控时仍返回 503 / `CONTROL_UNAVAILABLE`，不写执行记录、不启动燃烧器。内存与联合标签保持不可用。

2026-11-10，工作目录 `D:\SZSN\szsn-server-keeper`。测试运行时是 JDK 17.0.19。

- `mvn "-Dskip.frontend=true" test`：BUILD SUCCESS。83 个测试，0 失败。其中 `CpuControlServiceTest` 4 个，覆盖受理响应不假定完成、状态字段、内存模式拒绝、环境不可控拒绝、预检无副作用。
- `npm run build`（frontend）：成功。资源页 CPU 标签展示目标/实际/duty/Worker/受限原因；内存与联合仍提示引擎未开放。

## 下一阶段

M3-01：假分配器接入三重预算。M2-09 / AT-03 仅在获准测试环境、基线 CPU 低于 10% 时执行。
