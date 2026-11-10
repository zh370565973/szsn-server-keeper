# 进度

阶段结论：M0 与 M1 在 Windows 开发机上验证通过。M2-01 至 M2-08、M3-01 至 M3-05、M3-07、M4-01 至 M4-06 已在开发侧完成。生产路径现使用真实 `byte[]` 内存分配和 `ChecksumCpuBurner`。公开启动按 PRD 在落库前校验时长、系统占用和 4MiB 对齐。任务模板可以创建、修改和软删除。计划可以保存 ONCE、DAILY、WEEKLY、FIXED_INTERVAL，并算出下一次触发时间。执行实例在启动前保存 STARTING 和配置快照，状态变更立即落库，运行中每 30 秒写心跳；重启只把残留非终态标成 INTERRUPTED，不恢复负载。到期扫描每秒运行：同时到期只启动一个，迟到超过 5 秒记 SKIPPED_MISFIRE，离线或时钟前跳不补跑，回拨不重复触发。历史与日志按小时分批清理：监控曲线 7 天，终态执行记录 90 天或 10 万条，操作日志 30 天或 20 万条，活动实例不删除。AT-11、AT-12、AT-15、AT-20 已用文件型 H2 验收并发、重启恢复、迁移校验和、写入失败与磁盘空间不足。麒麟现场对照（M1-09 / AT-02）和长跑（M5-04）仍待验收，本次不要求麒麟机器。M2-09 / AT-03 的 8 核五分钟曲线仍待验收。下文前半是各里程碑当时的记录，不以「未验证」一节里的旧句作为当前实现状态。

## 已完成

| 编号 | 可观察行为 | 代码 |
| --- | --- | --- |
| MON-01 | 总览显示主机、CPU、内存、JVM、磁盘、网络。Linux 读 `/proc/stat` 与 `/proc/meminfo`；本机用 OSHI 的同类计数。缺失项显示“无数据” | `OshiMetricSampler`、`ProcStatMath`、`MeminfoParser` |
| MON-02 | 快照带 sequence、observedAt、ageMs、quality、missingFields。超过 3 秒为 STALE，超过 10 秒为 UNAVAILABLE | `SnapshotQuality` |
| MON-03 | 单采集线程每秒采样，SSE 只保留最新帧，15 秒心跳，最多 5 个连接。10 秒聚合写入 H2，查询最多 2000 桶 | `CollectorLoop`、`SseHub`、`HistoryService` |
| SEC-01 | 登录、CSRF、30 分钟活动超时、5 次失败后冷却 5 分钟。SSE 不刷新活动时间。注销关闭该会话的 SSE | `AuthController`、`SecurityConfig` |
| RES / SAFE | 预算、duty、保护窗口和状态迁移可单测。M1 当时写控制接口返回 503；现已由 CPU/内存控制服务受理，非 Linux 仍 503 | `MemoryBudget`、`CpuDutyCalculator`、`ProtectionEvaluator`、`CpuControlService` |
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

这一节在 M1 结束时写过「负载引擎未实现」。该句已过时：CPU 与内存引擎已在后续任务落地。当前仍未验证的是：

- 银河麒麟 V10 SP3、与麒麟 `/proc` 的现场对照（M1-09 / AT-02）、以及 M5-04 长跑。用户同意本次不强制麒麟机，这些条目保持待验收。
- M2-09 / AT-03：8 核、基线 CPU 低于 10% 时的 30/40/60% 五分钟观察。短时燃烧不能把该条改成已完成。
- 非回环 HTTPS 与 CIDR 只做了启动拒绝判断，没有证书环境。
- 24 小时运行仍未做。磁盘空间不足和数据库写入失败已在 M4-06 用注入的低空间报告和真实 H2 写入失败验收，没有把开发机磁盘写满。备份升级脚本仍未做。

## 已知限制

- Windows 上的 CPU 百分比来自 OSHI 计数，不是 `/proc/stat`。控制能力被明确禁用。
- 停止后的 RSS 回落不属于本阶段，也没有调用 `System.gc()`。
- 前端生产包约 2.6 MB，已全部打进 JAR。Vite 8 的传递依赖提示需要更新的 Node，当前 Node 20.19.5 可以完成构建。
- 任务模板、定时计划和执行记录页面仍是空状态，留给 M4-07。模板和计划的 HTTP 接口已经开放；计划页面还没有接到这些接口。

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

## M3-01 假分配器接入三重预算

`FakeMemoryAllocator` 只维护 heldBytes 计数，不分配 `byte[]`。`MemoryBudgetGate` 复用已有 `MemoryBudget`：EXTRA_BYTES 固定容量超过 `held+B` 时抛 422 / `MEMORY_BUDGET_EXCEEDED`（details 含 requestedBytes、allowedAdditionalBytes、limitingFactors），持有量不变。预算内增长只改假计数。公开启动对超预算 MEMORY 先 422；预算内仍 503（分块引擎未开放）。预检与 `/resource/status` 返回预算预览，无副作用。

2026-11-10，工作目录 `D:\SZSN\szsn-server-keeper`。测试运行时是 JDK 17.0.19。

- `mvn "-Dskip.frontend=true" clean test`：BUILD SUCCESS。90 个测试，0 失败。其中 `MemoryBudgetGateTest` 5 个，覆盖超预算 422 无增长、预算内假持有、增量超限、减目标清计数、缺样本零预算；`CpuControlServiceTest` 增补超预算 422、预算内仍 503、内存预检暴露预算。

## M3-02 4MiB 分块、逐块检查和 32MiB/s 限速

`ChunkedMemoryGrower` 按 `Units.BLOCK`（4 MiB）增长假持有量，不分配真实 `byte[]`。每块分配前复核：Worker 停止标志/generation、`forbidMemoryGrowth`、样本时效（`ageMs > 3s` 或缺失视为过期）、最新三重预算是否至少够一块。块间按 32 MiB/s 限速（块间隔 125ms），可注入时钟与 sleeper。减少目标立即释放，不限速。目标必须是 4 MiB 整数倍。公开 MEMORY 启动仍 503（模式与联合控制留给后续任务）。

2026-11-10，工作目录 `D:\SZSN\szsn-server-keeper`。测试运行时是 JDK 17.0.19。

- `mvn "-Dskip.frontend=true" clean test`：BUILD SUCCESS。98 个测试，0 失败。其中 `ChunkedMemoryGrowerTest` 8 个，覆盖分块限速、停止标志中断、预算耗尽停增、样本过期、缺 age、禁止增长、减目标立即释放、非对齐拒绝。

## M3-03 EXTRA_BYTES 与 SYSTEM_PERCENT

`SystemPercentPlanner` 实现 used=T-A、死区 ±2 pp、高于目标只释放不分配、预算不足标 `TARGET_LIMITED`、释放后 10s 冷却禁止再增。`MemoryEngine` 支持 EXTRA_BYTES（启动前固定容量门闸，超限 422）与 SYSTEM_PERCENT（可受限启动），经 `ChunkedMemoryGrower` 假增长。仍不分配真实 `byte[]`；暂停/停止清引用与 MEMORY HTTP 启动留给后续任务（仍 503）。

2026-11-10，工作目录 `D:\SZSN\szsn-server-keeper`。测试运行时是 JDK 17.0.19。

- `mvn "-Dskip.frontend=true" clean test`：BUILD SUCCESS。110 个测试，0 失败。其中 `SystemPercentPlannerTest` 6 个、`MemoryEngineTest` 6 个，覆盖死区、系统已高于目标不分配、TARGET_LIMITED、冷却、EXTRA_BYTES 超预算 422。

## M3-04 暂停和停止清除负载引用

`MemoryEngine.pause()` / `stopAndClear()` 在正常调度下于 2 秒内 `allocator.clear()` 并使 `heldBytes=0`，暂停保留会话槽位；恢复经 `ResumeGate` 重检后从零再增长。CPU 编排的暂停/停止路径调用 `clearLoadReferences()`，不把 RSS 下降当作成功，也不调用 `System.gc()`。`/resource/status` 增加 `memory`（heldBytes / heapUsedBytes / rssBytes）；资源页分开展示「负载持有」「JVM 堆使用」「进程驻留内存」。MEMORY HTTP 启动仍 503（留给 M3-07）。

2026-11-10，工作目录 `D:\SZSN\szsn-server-keeper`。测试运行时是 JDK 17.0.19。

- `mvn "-Dskip.frontend=true" clean test`：BUILD SUCCESS。113 个测试，0 失败。其中 `MemoryEngineTest` 9 个（新增暂停清引用保留槽位、恢复重检后再增长、停止 2 秒内 heldBytes=0）。
- `npm run build`（frontend）：成功。资源页与总览 JVM 卡文案对齐三项口径。

## M3-05 联合控制失败回收

`CombinedLoadStarter` 在同一会话内先校验 EXTRA_BYTES 预算，再启动 CPU，再以 `manageSession=false` 挂接内存引擎。任一引擎失败时：`requestStop(FAILURE)`、等待 CPU 退出、清除内存引用（不提前 `confirmWorkerExit`）、释放槽位；`ExecutionStartService` 将行写为 FAILED / `START_FAILED`。不允许静默只跑一侧。`CpuControlService` 开放 COMBINED 启动与预检；MEMORY 单独仍 503。暂停时内存侧不接管会话状态机。仍不分配真实 `byte[]`。

2026-11-10，工作目录 `D:\SZSN\szsn-server-keeper`。测试运行时是 JDK 17.0.19。

- `mvn "-Dskip.frontend=true" clean test`：BUILD SUCCESS。121 个测试，0 失败。其中 `CombinedLoadStarterTest` 6 个（双引擎成功、内存冲突回收、CPU 许可失效回收、超预算两侧不启动、落库 FAILED、SYSTEM_PERCENT 冲突回收），`CpuControlServiceTest` 增补 COMBINED 超预算 422 与受理成功。

## M3-06 AT-05 至 AT-08

新增 `M3AcceptanceTest`，假分配器与可注入时钟覆盖：

- AT-05：预算充足时按 4MiB 块 / 32MiB/s 达到 256MiB `heldBytes`；`memoryLoadView` 分开展示 heldBytes / heapUsedBytes / rssBytes
- AT-06：负值、溢出、互斥字段、超预算全部 `TARGET_INVALID` / `MEMORY_BUDGET_EXCEEDED`，持有量与两侧引擎无副作用
- AT-07：SYSTEM_PERCENT 达预算后标 `TARGET_LIMITED`，持续 tick 不突破任务硬限
- AT-08：暂停使 CPU duty=0 且 heldBytes=0、槽位保留；恢复重检失败保持 PAUSED；恢复后不瞬间满负载；FINITE 时限含暂停、不延长到期点

仍不分配真实 `byte[]`。有限容量真实分配：目标环境待验收。

2026-11-10，工作目录 `D:\SZSN\szsn-server-keeper`。测试运行时是 JDK 17.0.19。

- `mvn "-Dskip.frontend=true" clean test`：BUILD SUCCESS。126 个测试，0 失败。其中 `M3AcceptanceTest` 5 个全部通过。

## M3-07 资源页内存和联合标签

开放 MEMORY / COMBINED 资源页与 HTTP 启动。`budget.maxAllowedBytes` 为固定容量可输入最大值；超预算时页面展示该值并禁止提交，后端仍返回 422 / `MEMORY_BUDGET_EXCEEDED`（含 maxAllowedBytes），不静默改小目标后启动。内存引擎增加自动控制循环与运行中改目标；MEMORY-only 暂停/停止自行确认槽位退出。`loadEngine=CPU_MEMORY`。仍不分配真实 `byte[]`。

2026-11-10，工作目录 `D:\SZSN\szsn-server-keeper`。测试运行时是 JDK 17.0.19。

- `mvn "-Dskip.frontend=true" clean test`：BUILD SUCCESS。127 个测试，0 失败。其中 `CpuControlServiceTest` 9 个（新增 MEMORY 预算内启动、超预算预检展示最大值、改目标超限不改小）。
- `npm run build`（frontend）：成功。资源页内存/联合标签可用；超预算红条显示可输入最大值并禁用启动。

## 下一阶段

M4-04 未开始：冲突跳过、漏执行和时钟去重。M2-09 / AT-03 仍待 8 核环境下的 30/40/60% 五分钟观察。麒麟专项仍待验收。

## M4-01 任务模板

模板可以创建、整份修改和软删除。CPU 模式不能带内存字段，MEMORY 模式不能带 CPU 字段，两种内存目标互斥。CPU 目标按公开启动边界限制在 1 到 60。修改使用 `expectedVersion`，版本不符返回 409 / `VERSION_CONFLICT`，并且只更新 `RESOURCE_TASK`。已有 `TASK_EXECUTION` 的配置快照、状态和版本保持不变。被 `TASK_SCHEDULE` 引用时删除返回 409 / `TEMPLATE_REFERENCED`，`details.schedules` 按计划编号列出引用；没有引用时写入 `DELETED_AT`，历史快照仍在。列表支持名称包含搜索，`%` 和 `_` 按字面量匹配。

`POST /tasks/{id}/run` 读取当前模板生成启动配置。环境不可控时返回 503，不新增执行记录。启动成功后只在 `TASK_ID` 为空时补上模板编号，不改快照。本机是 Windows，这条路径实际返回 503。计划的创建、修改、调度和页面都没有做。

2026-11-10，工作目录 `D:\SZSN\szsn-server-keeper`。测试运行时是 JDK 17.0.19。

- `mvn "-Dskip.frontend=true" test`：BUILD SUCCESS。140 个测试，0 失败，0 错误，0 跳过。其中 `TaskTemplateServiceTest` 5 个，覆盖字段互斥、版本冲突不改已启动实例、引用删除 409、软删除后快照仍在、名称搜索。`ApiIntegrationTest` 增补登录后的创建、修改、引用冲突、立即运行 503 不落库，以及计划写接口仍为 503。

## 2026-11-10 验收调整

用户同意本次不强制麒麟机。公开启动在落库前校验时长 10 秒–7 天、`memoryTargetPct` 1–70、内存字节 4MiB 对齐。生产分配器改为 `ByteArrayMemoryAllocator`，生产燃烧器确认为 `ChecksumCpuBurner`。单元测试仍注入假分配器和瞬时燃烧器。当时 M4 尚未开始；模板能力见上方 M4-01。

工作目录 `/agent/repos/szsn-server-keeper`。JDK 17.0.21（`JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64`）。

- `mvn -B -Dskip.frontend=true test`：BUILD SUCCESS。134 个测试，0 失败，0 错误，0 跳过。其中 `ExecutionStartServiceTest` 11 个、`CpuControlServiceTest` 11 个，覆盖时长、系统占用和未对齐内存字节在落库前 422 且 `TASK_EXECUTION` 不增加；`ByteArrayMemoryAllocatorTest` 3 个，覆盖触碰页、清除引用，以及生产 Bean 是真实分配器和 `ChecksumCpuBurner`。`M3AcceptanceTest` 的到期用例改为 PRD 下限 10 秒。

有限容量自测（不在默认测试集里，类 `LimitedRealLoadProbe`）。主机 4 核，`MemTotal` 16387328 kB，探测前 `MemAvailable` 约 5098216 kB。1 秒 `/proc/stat` 差值 `[user,nice,system,idle,iowait,irq,softirq,steal]=[0,0,0,399,0,0,0,0]`，忙占比 0.0%，低于 10%，但只有 4 核，不符合 AT-03 的 8 核和五分钟 30/40/60% 协议，因此没有强跑该曲线，M2-09 保持待验收。

```text
export JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64
mvn -q -Dskip.frontend=true -DincludeScope=test dependency:build-classpath -Dmdep.outputFile=/tmp/srm-cp.txt
java -cp "target/classes:target/test-classes:$(cat /tmp/srm-cp.txt)" com.szsn.srm.resource.LimitedRealLoadProbe
```

结果：`PROBE_OK capBytes=67108864`。`ChunkedMemoryGrower` 加 `ByteArrayMemoryAllocator` 在 1905 ms 内达到 held=67108864（16 个 4MiB 块，页样本字节=1），与 32MiB/s 的块间隔相符。堆从探测前的 MXBean used=0 增到峰值 102682624；`VmRSS` 从 40872 kB 增到 111040 kB。`clear()` 后 held=0、块数=0，堆 used 仍为 104779776，RSS 仍为 111408 kB。未调用 `System.gc()`，RSS 未下降，不把它当成释放成功。随后 `ChecksumCpuBurner` 燃烧 1500 ms，校验值非 0，进程 utime+stime 增加 152 tick（约一核）。这只证明生产燃烧器会占用 CPU，不把 M2-09 记成已完成。

据此把 M3-06 从待验收改为已完成，并注明：用户同意本次不强制麒麟机，改为普通 Linux 测试环境取证；麒麟专项仍待验收。

## M4-02 计划形态

计划支持 ONCE、DAILY、WEEKLY、FIXED_INTERVAL，拒绝 Cron 和无法识别的字段。ONCE 的 `startAt` 必须晚于当前时间。DAILY / WEEKLY 使用 IANA 时区和 `HH:mm:ss`；WEEKLY 的星期是 ISO 1–7，保存时去重并排序。夏令时不存在的本地时间跳到下一天，重复的本地时间只取较早的一次。FIXED_INTERVAL 从 UTC 锚点按间隔直接算下一次，间隔至少 60 秒，不逐条补写漏执行。

计划必须绑定未删除的 FINITE 模板，执行时长不超过 24 小时。固定间隔还要求时长不超过间隔。修改仍被引用的模板时重新检查这些约束，不满足返回 422 / `TEMPLATE_NOT_SCHEDULABLE`，模板保持原样。计划最多 100 条，超出返回 422 / `SCHEDULE_LIMIT`。

创建、修改和启用都会写入 `nextFireAt`。关闭计划保留原来的下一次时间。修改或删除计划不改已有执行实例的快照、状态和版本。本任务不启动调度线程，也不写执行记录。定时计划页面仍是空状态，留给 M4-07。

2026-11-10，工作目录 `D:\SZSN\szsn-server-keeper`。测试运行时是 JDK 17.0.19。

- `mvn "-Dskip.frontend=true" test`：BUILD SUCCESS。147 个测试，0 失败，0 错误，0 跳过。其中 `ScheduleCalendarTest` 2 个，覆盖夏令时缺口、重叠只取第一次、ISO 星期和 UTC 间隔。`ScheduleServiceTest` 4 个，覆盖四种形态、拒绝 Cron、24 小时和间隔约束、版本冲突、删除不改执行实例、过期一次性计划不能重新启用，以及第 101 条被拒绝。`TaskTemplateServiceTest` 新增被引用模板不能改成持续任务或超过 24 小时。`ApiIntegrationTest` 中空计划请求返回 422 / `SCHEDULE_INVALID`，不新增计划和执行记录。

## M4-03 执行快照、心跳和重启恢复

启动前仍先提交 STARTING 和不可变配置快照，本任务不改这条路径。普通状态变更立即写入 `TASK_EXECUTION`；重复的 STARTING 不抬高版本。运行中的时长用单调时钟计算：暂停计入 elapsed，不计入 active。墙钟回拨不改变已累计时长。每 30 秒写一次心跳和统计摘要。均值按有效采样时长加权，空值不计入该项；VALID / PARTIAL 之外的样本不计入覆盖。整机内存使用和负载 heldBytes 分列均值、峰值和覆盖时长。配置快照在心跳和状态变更后保持原样。

进程启动时，残留的 STARTING、RUNNING、PAUSING、PAUSED、STOPPING 改为 INTERRUPTED，原因 `ACTUAL_STOP_TIME_UNKNOWN`。`endedAt` 是发现时间，`lastHeartbeatAt` 和已写入的时长保持原值；没有心跳或时长时保持空，不用发现时间减开始时间伪造运行时长。不重放启动，不恢复负载，控制开关仍是每次进程启动为 OFF，计划的 `nextFireAt` 不变。急停返回前不等待数据库。本任务不启动到期扫描。

2026-11-10，工作目录 `D:\SZSN\szsn-server-keeper`。测试运行时是 JDK 17.0.19。

- `mvn "-Dskip.frontend=true" test`：BUILD SUCCESS。152 个测试，0 失败，0 错误，0 跳过。其中 `ExecutionLedgerTest` 5 个，覆盖空值加权、整机内存与 heldBytes 分离、状态即时落库且快照不变、未满 30 秒不写心跳、单调时钟时长不受墙钟回拨影响、暂停不计入 active，以及重启把残留非终态标成 INTERRUPTED 且不恢复负载、不改计划触发时间。

## M4-04 冲突跳过、漏执行和时钟去重

`srm-schedule` 每秒扫描已启用且 `nextFireAt` 已到的计划，按 `scheduledAt`、`scheduleId` 升序处理。相邻两次扫描间隔不超过 5 秒，并且触发点不早于本进程开始观察的时间，才尝试启动；只启动这一次扫描里的一个。其余准时触发记 `SKIPPED_CONFLICT`。暂停中的会话同样占槽，记 `SKIPPED_CONFLICT`。控制开关关闭或急停锁存记 `SKIPPED_CONTROL_DISABLED`，不启动负载。

迟到超过 5 秒、扫描出现超过 5 秒的空档，或触发点早于本进程上线，都记 `SKIPPED_MISFIRE`，不补跑。固定间隔用除法算出遗漏次数；多段漏执行只插入一条执行记录，`STATS_JSON` 里写 `missedCount`，然后把 `nextFireAt` 直接拨到下一次未来时间。时钟回拨本拍不触发。同一个 `(scheduleId, scheduledAt)` 已有记录时不再启动。一次性计划处理完后关闭。启动失败把仍处于 STARTING 的记录改为 FAILED 或 SKIPPED，不自动重试，也不挡住同一次扫描里的下一个计划。

调度测试注入启动器，不调用生产燃烧器，也不分配 `byte[]`。生产启动仍走 `CpuControlService.launchScheduled`：预检不通过时抛出，不领取槽位。本机是 Windows，环境不可控时这条路径不会打满 CPU。历史清理和计划页面仍留给后续任务。AT-11、AT-12、AT-15、AT-20 仍是 M4-06。

2026-11-10，工作目录 `D:\SZSN\szsn-server-keeper`。测试运行时是 JDK 17.0.19。

- `mvn "-Dskip.frontend=true" test`：BUILD SUCCESS。163 个测试，0 失败，0 错误，0 跳过。其中 `ScheduleDueTest` 4 个，覆盖间隔跳变的遗漏计数、5 秒宽限、未连续扫描、上线前的触发点，以及日计划只留一条汇总后的下一次时间。`ScheduleDispatcherTest` 7 个，覆盖离线 11 次只写 1 条 `SKIPPED_MISFIRE`、在线但迟到超过 5 秒不启动、同时到期只启动较早的一个、相同时间只启动编号较小的一个、暂停和开关关闭不启动、时钟回拨和重复触发点不第二次启动、启动失败不留下 STARTING 也不挡住下一个计划。

## M4-05 历史与日志清理

`srm-retention` 启动后立即清理一次，之后每小时一次。监控桶保留 7 天。终态执行记录保留 90 天，超过 10 万条时再删最旧的终态记录。操作日志保留 30 天，超过 20 万条时删最旧记录。已过期的幂等键按 `EXPIRES_AT` 删除。每一批单独提交，最多 1000 行。STARTING、RUNNING、PAUSING、PAUSED、STOPPING 不删除。监控桶过期后，执行记录里的 `STATS_JSON` 摘要仍然保留。

历史查询增加 `retainedFrom` 和 `pastRetention`。起点早于 7 天保留期时，历史页提示曲线已清理、执行摘要仍在。最近 1 小时不显示这条警告。设置页写明 7 天、90 天、30 天和条数上限。

2026-11-10，工作目录 `D:\SZSN\szsn-server-keeper`。测试运行时是 JDK 17.0.19。

- `mvn "-Dskip.frontend=true" test`：BUILD SUCCESS。168 个测试，0 失败，0 错误，0 跳过。其中 `RetentionCleanerTest` 5 个，覆盖 7/90/30 天边界、活动实例和执行摘要保留、单批不超过设定行数且外层事务回滚后删除仍然生效、条数上限只删最旧终态记录和最旧日志，以及查询起点越过保留期时 `pastRetention=true`。
- 本机临时库启动后，用 Vite 打开历史页：选择「含过期」出现“查询起点已超过 7 天曲线保留期”；改回「1 小时」后警告消失，桶宽回到 10 秒。设置页能看到保留策略说明。未做 CPU 或内存压力。

## M4-06 真实 H2 验收

`M4AcceptanceTest` 使用 `target` 下的文件型 H2，覆盖 AT-11、AT-12、AT-15、AT-20。不调用生产燃烧器，也不分配负载 `byte[]`。

- AT-11：8 个线程抢同一个 `(scheduleId, scheduledAt)` 只插入一行；8 个线程用同一个幂等键启动只产生一个 executionId，重放不再启动；8 个线程同时扫描两个到期计划只运行一个，另一个记 `SKIPPED_CONFLICT`；时钟回拨不新增执行记录。
- AT-12：写入 RUNNING 和早已到期的计划后关闭数据库再打开。重复迁移不新增 `SCHEMA_VERSION`。残留执行变为 `INTERRUPTED`，原因 `ACTUAL_STOP_TIME_UNKNOWN`，心跳和已记录时长保持原值。新进程控制开关为 OFF。过期计划记一条 `SKIPPED_MISFIRE`，不补跑。篡改校验和后迁移拒绝启动，已恢复的记录不变。
- AT-15：历史桶写入失败后标记「历史写入失败」，后续桶不再写。安全检查停止当前会话并锁存，新启动返回 `CONTROL_LATCHED` 且不增加执行记录。`/system/info` 的 `storageHealthy=false`，设置页已绑定 `storageFault`。数据盘剩余不足的环境报告同样锁存并出现在系统信息里。急停先使 Worker 许可失效，此时 `SYS_CONFIG` 还没有锁存行；放行后才写入 H2。
- AT-20：修改模板的名称、目标和时长后，已启动实例的配置快照、版本和状态不变。被计划引用时删除返回 409 / `TEMPLATE_REFERENCED`，并列出计划编号。

2026-11-10，工作目录 `D:\SZSN\szsn-server-keeper`。测试运行时是 JDK 17.0.19。

- `mvn "-Dskip.frontend=true" "-Dtest=M4AcceptanceTest" test`：BUILD SUCCESS。5 个测试，0 失败。
- `mvn "-Dskip.frontend=true" test`：BUILD SUCCESS。173 个测试，0 失败，0 错误，0 跳过。其中 `M4AcceptanceTest` 5 个。未做 CPU 或内存压力，也没有把磁盘写满。

## 下一阶段

M4-07 未开始：模板、计划和执行记录页面。M2-09 / AT-03 仍待 8 核环境下的 30/40/60% 五分钟观察。麒麟专项仍待验收。安装、备份和升级脚本留到 M5。
