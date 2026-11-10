# Server Resource Manager 产品需求文档

版本：1.1 · 开发基线版  
日期：2026-11-10  
交付对象：产品负责人、Cursor 开发代理、测试人员  
原始需求：[共享方案](https://chatgpt.com/s/t_6af1b70e54fc8191b15d22b4e76ab829)

> 本文是独立、完整的开发依据，替代原方案中的模糊规则。默认值、阈值及性能指标是本次设计确定的要求，尚未经过目标服务器实测。实现完成必须提供验收证据，不能将本文视为已通过测试的结论。

## 1. 产品目标与设计决策

SRM 是一个运行在单台 Linux 服务器上的资源监控与受控负载工具。用户通过浏览器查看真实系统指标，并在安全预算内主动增加 CPU 或内存占用，支持手动运行、限时运行和定时运行。

核心使用闭环：查看服务器状态 → 配置目标 → 预算预检 → 启动 → 观察调节结果 → 自动或手动停止 → 查询记录。

本工具只能减少自己产生的负载，不能降低其他业务的资源消耗。CPU 目标和系统内存目标均为尽力达到的目标，不能作为操作系统资源配额或业务性能保障。

### 1.1 相对原方案的关键优化

| 原方案缺口 | 最终决策 |
| --- | --- |
| 单 JAR 与内存隔离需求容易引发架构扩张 | V1 使用单 JVM、堆内有界分配；明确可用容量与释放边界，暂不引入子进程或 native allocator |
| 系统内存目标可能超过 Java 可分配容量 | 同时计算系统、堆、任务三类预算；拒绝不合法固定容量，百分比目标标记受限原因 |
| 暂停含义不清楚 | 暂停 CPU 计算并清除负载内存引用；恢复必须重新预检和渐进分配 |
| 任务、计划、执行记录混用 | 分离任务模板、定时计划、执行实例；每次执行保存不可变配置快照 |
| 停止、紧急停止、全局开关重复 | 普通停止结束当前实例；关闭开关停止并禁止新启动；急停增加持久化锁存，必须人工解除 |
| 只在任务结束时保存记录 | 启动前持久化实例，运行中保存状态与心跳，启动恢复时标记异常中断 |
| 保护阈值缺少时间窗口 | 明确连续样本数、滞回、过期数据处理与解除条件 |
| 缺少可执行验收 | 增加需求编号、状态机、API 契约、故障测试及阶段门禁 |

### 1.2 目标环境与待实测事实

来源方案提供：银河麒麟高级服务器操作系统 V10 SP3、x86_64、AMD EPYC、8 个逻辑 CPU、KVM、JDK 17、内网离线。以上是需求环境描述，本次未连接目标机器核验。

服务器总内存、内核版本、JDK 发行版及补丁、磁盘容量、cgroup 限制、可用证书和最终访问地址尚未知。不得写死为开发机器配置。安装预检输出上述信息；不满足控制条件时提供只读监控，禁用资源控制并说明原因。

### 1.3 范围

V1 必须交付：登录、实时监控、CPU 控制、两种内存控制、联合控制、暂停恢复、限时与持续任务、一次性及周期计划、安全保护、历史查询、单 JAR 离线部署。

不做：多机管理、多角色体系、云端服务、自动发现、业务进程控制、任意 Shell 执行、磁盘或网络压力生成、容器编排、第三方告警平台、信创认证、多架构适配。

## 2. 架构与技术约束

### 2.1 选型

| 层次 | 约束 |
| --- | --- |
| 运行时 | JDK 17，构建设置 release=17 |
| 后端 | Spring Boot 3.5.x 稳定补丁版、Spring MVC、Spring Security；初始化时核验并锁定确切版本 |
| 持久化 | Spring JDBC + H2 Embedded 文件模式，不引入 ORM、数据库服务 |
| 采集 | OSHI + 必要的 procfs 只读采集；JVM 使用 ManagementFactory/MXBean |
| 实时传输 | 单采集器 + SSE 广播；REST 查询与控制 |
| 调度 | Spring Scheduling + 数据库计划，不引入 Quartz |
| 前端 | Vue 3 + TypeScript + Vite + Ant Design Vue + ECharts |
| 构建 | Maven + npm lockfile；前端编译产物内嵌 JAR |
| 部署 | 普通 Linux 用户 + systemd；生产机只需 JDK、JAR、外置配置和数据目录 |

Spring Boot 3.5 系列支持 Java 17；确切依赖版本须在初始化时验证并锁定，不使用动态版本或 SNAPSHOT。兼容依据见 [Spring Boot 系统要求](https://docs.spring.io/spring-boot/3.5/system-requirements.html)。

不引入 Redis、MQ、独立 Web 服务、Prometheus 服务或额外微服务。无需为少量 JVM 指标新增独立监控基础设施。

### 2.2 模块职责

```text
浏览器 ── REST / SSE ── Spring Boot
                         ├─ auth       登录、会话、CSRF
                         ├─ monitor    单次采集、快照、广播
                         ├─ resource   会话串行管理、CPU/内存引擎
                         ├─ safety     独立检查、停止信号、急停锁存
                         ├─ task       模板、执行实例、计划触发
                         ├─ history    聚合、查询、清理
                         └─ H2         配置、任务、计划、记录
```

安全检查线程不得与 CPU Worker、历史写入、SSE 发送共享执行队列。采集、CPU 调节、调度各自使用有界且数量固定的执行器。CPU Worker 上限为可用逻辑核心数；禁止每周期新建线程。负载调节不得持有数据库事务锁。

默认 V1 控制运行在不受额外 CPU/memory cgroup 配额限制的完整虚拟机内。必须检测 cgroup v1/v2、cpuset/CPU affinity 与内存限制；发现相对于 VM 的额外资源约束，或无法确定约束时，禁用负载控制并解释原因。V1 不尝试把宿主口径指标用于受限进程控制。

## 3. 统一定义与默认配置

容量内部及接口统一 bytes，显示使用 MiB/GiB，1 MiB=1,048,576 bytes。百分比统一 0–100 数值；百分点使用 pp。时间存 UTC epoch milliseconds，显示按配置 IANA 时区，默认 Asia/Shanghai。

所有参数必须后端校验；禁止 NaN、无穷、负容量、溢出、未知枚举。配置入口不得绕过硬上限。

| 项目 | 默认值 | V1 范围或约束 |
| --- | --- | --- |
| 监听 | 127.0.0.1:8080 | 远程使用内置 HTTPS 并配置访问网段 |
| 全局控制开关 | OFF | 每次服务启动均 OFF，人工开启 |
| 采集/SSE 间隔 | 1 秒 | V1 固定 |
| CPU 控制周期 | 2 秒 | V1 固定 |
| CPU 目标 | 30% | 1–60%；部署配置可将上限提高至 70%，硬上限 70% |
| CPU 调节死区 | ±3 pp | V1 固定 |
| 内存分配块 | 4 MiB | 固定；输入为 4 MiB 整数倍 |
| 内存增长速率 | 32 MiB/s | 安全检查逐块执行 |
| 内存任务上限 | 512 MiB | 外置配置可调整，但不能突破实时预算 |
| 系统内存目标 | 50% | 1–70% |
| 系统保留量 | max(总内存×20%, 1 GiB) | V1 不允许 Web 调低 |
| 堆负载硬上限 | Xmx×50% | 不允许 Web 提高 |
| JVM 运行保留 | max(Xmx×25%, 256 MiB) | 负载不能侵占 |
| 默认限时 | 1 小时 | 10 秒–7 天；持续运行显式选择 |
| 历史聚合周期/保留 | 10 秒/7 天 | 固定 |
| 执行记录/操作日志 | 90 天/30 天 | 分批清理；分别最多 10万/20万条 |
| 实时缓存 | 最近 15 分钟 | 每秒一个共享快照，容量有界 |
| 最大 SSE 连接 | 5 | 超出返回 429 |
| 最大计划数 | 100 | 超出拒绝创建 |

默认推荐启动参数为 Xms=128 MiB、Xmx=1 GiB；仅在预检满足系统可用内存保留条件时启用内存负载。不要自动设置 Xms=Xmx，不启用 AlwaysPreTouch。改变 Xmx 必须停服务、修改部署配置并重启。需要更大内存负载时，先测量管理程序开销并重新进行预算验收。

## 4. 监控需求

### MON-01 指标与口径

| 指标 | 采集/显示规则 |
| --- | --- |
| 系统信息 | 主机名、OS、内核、CPU 型号/核心、JDK、系统及进程运行时间 |
| CPU | overallBusyPct、userPct、systemPct、idlePct、iowaitPct、stealPct；Load Average 1/5/15 |
| 内存 | MemTotal、MemAvailable、used=total-available、缓存参考值、Swap 总量和占用 |
| JVM | heap used/committed/max、non-heap、GC 次数/耗时、线程数、进程 RSS、进程 CPU |
| 磁盘 | 按挂载点的容量与使用率；按块设备的读写 bytes/s |
| 网络 | 按网卡收发 bytes/s、累计计数、接口状态 |

CPU 主控制口径：使用 /proc/stat 相邻快照的计数差；total 为 user、nice、system、idle、iowait、irq、softirq、steal 之和，不重复加入 guest/guest_nice；busy=(total-idle-iowait)/total。steal 单独显示。进程 CPU 换算为整机归一化的 0–100%，不得与单核 100% 口径混用。

MemAvailable 表示估算的可用内存，不等于 MemFree；指标缺失不允许伪造 0。Linux 指标口径参考 [procfs 官方说明](https://docs.kernel.org/filesystems/proc.html)。

磁盘设备吞吐与挂载点容量分开展示，避免逻辑卷和物理盘重复累加；总览选定明确设备集合。网络默认排除 loopback，显示当前汇总的接口，避免桥接与从属接口重复计算。计数器重置或首次采样时速率为 null，不能出现负值。

### MON-02 数据质量

每个快照带 sequence、observedAt、ageMs、quality 和缺失字段列表。quality=VALID/PARTIAL/STALE/UNAVAILABLE。安全逻辑根据所需字段判断，不因可选温度缺失停机。

CPU/内存任一关键指标最后成功采集距今 >3 秒，立即禁止增加负载；>10 秒终止当前执行并锁定控制。界面显示“数据过期”和最后采集时间，不继续把旧值标为实时。

### MON-03 实时与历史

采集器只有一个，多个页面共享快照。SSE 每秒推送最新快照，15 秒心跳，慢客户端只保留最新待发送快照。断线指数退避重连，最长 30 秒；重连先获取 current，不承诺补发所有事件。浏览器断开不停止后台任务。

每 10 秒写入一次聚合数据：有效样本均值、峰值、最小可用内存、有效样本数及覆盖时长。无效区间显示断线。查询最多返回 2,000 个桶，可自动聚合到分钟或小时；禁止直接加载全部历史到浏览器。

## 5. 资源控制

### RES-01 单活动会话

同一时间只有一个执行实例占用控制权，CPU/内存/联合控制是同一会话的三种配置。STARTING、RUNNING、PAUSING、PAUSED、STOPPING 均占用该槽位。暂停不允许其他任务插队。

所有普通变更通过 ResourceLoadManager 串行执行。急停通过独立原子停止标志/会话 generation 使旧 Worker 失效；不得等待数据库、SSE 或普通操作队列。新任务只有在旧 Worker 确认退出且负载引用归零后才能启动。

### RES-02 启动预检

必须检查：已登录、全局 ON、急停未锁存、无活动会话、有效监控连续 10 秒、支持的运行环境、数据库可写、CPU/内存/Swap 未处于保护状态、目标与预算有效。

先持久化 STARTING 和完整配置快照，再启动引擎。启动失败应停止两个引擎并写 FAILED；联合控制不允许静默退化为单项运行。固定内存超预算直接拒绝；系统百分比模式可启动受预算限制的目标，并显示预计可达范围。

预检接口仅是预览，不预留资源；正式启动必须重算预算并重新争用槽位。

### RES-03 CPU 调节

设目标 T、当前整机 CPU 使用率的 EWMA 值 C（alpha=0.4）、Worker duty 为 d，0≤d≤1。每 2 秒执行：误差 e=T-C；|e|≤3 pp 时保持；否则 d'=clamp(d+0.3×e/100,0,1)，单次增加最多 0.05、减少最多 0.15。初始化 d=0，默认 Worker 数 N=可用逻辑核心数。

Worker 使用约 100 ms 时间片交替计算/等待；计算需产生可观察校验值，避免被 JIT 消除；每不超过 5 ms 检查停止/暂停/generation。等待需可唤醒，不使用无退出条件的忙循环。安全规则可直接把 duty 置零，不受下降斜率限制。

原始 CPU 已高于目标+3 pp 连续两次控制采样时，本程序 duty 直接归零；低于目标-3 pp 且通过安全检查后再渐进补充。状态标记 EXTERNAL_LOAD_HIGH，不宣称能够将整机降至目标。

显示目标、实际、duty、Worker 数、受限原因。允许运行中更新目标；更新带版本校验，并记录变更。暂停时可修改配置，恢复时生效。

### RES-04 内存引擎与预算

V1 使用固定大小 byte[] 分块持有，逐页写入非零数据，记录已完成触碰的 payload bytes。对象开销另由 JVM used 体现。禁止无限分配、Unsafe、自定义 native 分配、依赖 DirectByteBuffer Cleaner 强制释放。

定义：

```text
T = MemTotal
A = MemAvailable
H = JVM max heap
U = JVM heap used（含当前负载及尚未 GC 的垃圾）
L = 当前持有且触碰完成的负载 payload bytes
Rsys = max(0.20*T, 1 GiB)
Rheap = max(0.25*H, 256 MiB)
Lcap = min(配置任务上限, 0.50*H)
Guard = 64 MiB
新增预算 B = 向下对齐4MiB(max(0, min(A-Rsys-Guard,
                                      H-U-Rheap-Guard,
                                      Lcap-L)))
```

每分配一块前复核停止标志、最新预算和关键样本时效；没有一块预算就停止增加并显示原因。预算只用于保守准入，不保证外部进程突然抢占时绝对不会 OOM。

模式 A：EXTRA_BYTES。目标为本引擎持有的 payload 容量，不是系统 RSS 的精确增量。新启动时 target≤B；运行中可维持原有持有量，但安全线触发时必须释放。增大目标需满足增量≤B；减少目标立即清除多余块引用。

模式 B：SYSTEM_PERCENT。每 2 秒依据 used=T-A 和目标百分比 P 计算 delta=P*T/100-used；死区 ±2 pp。delta>0 时按预算与速率增加，delta<0 时减少自身持有量，最少减到零。系统本身已高于目标时不分配。受堆或系统预算限制时显示 TARGET_LIMITED，不无限追逐目标。因 GC 滞后清除引用后 10 秒内禁止重新增加内存，以减少反复分配。

### RES-05 内存释放的真实边界

停止/暂停必须在正常调度条件下 2 秒内清除所有负载引用并将 heldBytes 归零；这代表程序不再持有这些块，不代表操作系统 RSS 已下降。页面同时显示 heldBytes、heapUsed、rssBytes，文案分别为“负载持有”“JVM 堆使用”“进程驻留内存”。

不依赖 System.gc() 达成释放，不把 RSS 回落作为单进程堆分配的承诺。Java 对显式 GC 也只给出尽力执行语义，见 [Java 17 System.gc 文档](https://docs.oracle.com/en/java/javase/17/docs/api/java.base/java/lang/System.html#gc())。

如果“停止后必须迅速把物理内存归还系统”成为硬性需求，应另立版本采用同 JAR 的隔离负载子进程；本版不暗中引入该架构。检测到 JVM OOM 或无法停止 Worker 时，锁定控制并进入故障处置，不能继续自动尝试分配。单 JVM 无法对致命错误提供绝对隔离。

### RES-06 生命周期

| 操作/事件 | 当前状态 | 结果 |
| --- | --- | --- |
| 启动 | 无活动实例 | STARTING → RUNNING；失败 → FAILED |
| 暂停 | RUNNING | PAUSING → PAUSED；CPU 停止计算，清除内存引用 |
| 恢复 | PAUSED | 重新预检，通过后 STARTING → RUNNING；未通过保持 PAUSED |
| 普通停止 | 任意非终态 | STOPPING → STOPPED |
| 到期 | 任意非终态 | STOPPING → COMPLETED，reason=DURATION_ELAPSED |
| 安全停止/急停 | 任意非终态 | STOPPING → SAFETY_STOPPED |
| 服务重启发现未完成 | 持久化非终态 | INTERRUPTED，禁止恢复负载 |
| 冲突或漏执行 | 尚未启动 | SKIPPED，附原因 |

结束状态不可恢复。重新运行创建新 executionId。停止同一 executionId 为幂等操作。普通停止不会禁止未来计划。

durationSeconds 是启动成功后的最大墙钟持续时间，包含暂停，暂停不延长到期点；运行中用单调时钟计算，避免系统时间回拨改变时长。记录 activeDurationMs 和 elapsedDurationMs 两个字段。只有显式 CONTINUOUS 允许无时长；定时计划必须有限时长。

## 6. 安全保护与故障处理

### SAFE-01 保护优先级

优先级：急停/严重故障 > 安全停止 > 到期停止 > 用户调整 > 闭环目标。硬停止影响整个联合会话，不允许另一引擎继续运行。

| 条件 | 判定窗口 | 动作 |
| --- | --- | --- |
| CPU ≥85% | 连续 3 个 1秒有效样本 | duty=0，禁止增长；CPU<75% 连续10秒后可渐进恢复 |
| CPU ≥95% | 连续 3 个样本 | 整个会话安全停止并锁存 |
| steal 或 iowait ≥20% | 连续10秒 | 禁止 CPU 增长并归零；<10%连续10秒后恢复 |
| A<Rsys+Guard | 任一有效样本 | 禁止新增内存 |
| A<Rsys | 连续2个样本 | 清除负载引用、停止整个会话并锁存 |
| 堆 U/H ≥80% | 任一有效样本 | 禁止新增内存 |
| 堆 U/H ≥85% | 连续2个样本 | 停止整个会话并锁存 |
| Swap 换入+换出速率 ≥16 MiB/s | 连续5秒 | 停止整个会话并锁存；无 Swap 则不适用 |
| CPU/内存关键指标过期 | >3秒 / >10秒 | 禁止增长 / 停止并锁存 |
| 数据库写入失败或数据盘余量不足 | 一次写入失败；或剩余<256MiB或<5% | 禁止启动、停止当前会话、暂停历史写入、显示故障 |
| Worker 异常 | 首次捕获 | 停止整个会话，记录 FAILED，锁存 |
| 停止后 Worker 2秒仍未响应 | 一次 | 显示 STOP_TIMEOUT、保持锁定，触发服务故障退出流程 |

Swap 使用率高但无活跃换页不直接等同于压力。以上阈值是本版默认产品规则，需要目标环境验收；不声称适用于所有硬件。不能通过 Web 关闭安全线程或移除硬上限。

### SAFE-02 开关和急停

控制开关 OFF：停止当前会话；所有计划触发记 SKIPPED_CONTROL_DISABLED。ON 只允许未来启动，不立即执行曾错过的计划。

急停：立即设置停止标志、OFF 和锁存；持久化锁存原因；停止当前会话；保留计划配置但禁止触发。没有排队任务，因此无需实现隐藏队列。数据库故障不能阻塞停止动作；锁存写入失败时，依靠下次启动默认 OFF，避免意外恢复。

解除锁存：管理员人工操作，必须无活动会话、连续30秒关键指标健康、数据库可写。解除后仍 OFF，用户单独打开开关。后台不得自行清除锁存。

页面所有主视图固定显示急停入口，点击直接执行，不先弹确认框。启动持续任务需要确认目标与停止方式。紧急停止仍需合法会话和 CSRF；另提供 systemctl stop 的运维兜底。

### SAFE-03 异常边界

安全采样与软件阈值存在反应延迟，不能绝对防止宿主压力、内核 OOM Kill 或长时间 GC。服务发生致命错误时允许失败退出；systemd 可重启监控服务，但控制开关保持 OFF。禁止在 OOM 后无限重试负载。

有序退出给引擎 2 秒停止、持久化最多3秒，总关闭窗口10秒。systemd 最终终止未退出的进程。意外断电或 SIGKILL 不能依赖 shutdown hook 写结束记录，启动恢复必须处理。

## 7. 任务、计划与历史

### TASK-01 任务模板

字段：名称（1–64字符）、说明（0–500字符）、resourceMode=CPU/MEMORY/COMBINED、cpuTargetPct、memoryMode、memoryTargetBytes 或 memoryTargetPct、runMode=FINITE/CONTINUOUS、durationSeconds、version。

CPU 模式内存参数必须为空；MEMORY 模式 CPU 参数为空；COMBINED 两者必填。内存两种目标互斥。模板编辑不改变已经启动的实例。直接从控制面板启动也创建执行记录，taskId 可为空。

模板被计划引用时删除返回409，并列出引用计划；无引用时软删除，历史快照保留。

### TASK-02 计划

支持 ONCE、DAILY、WEEKLY、FIXED_INTERVAL；V1 不提供任意 Cron 输入。

| 类型 | 配置 |
| --- | --- |
| ONCE | startAt，必须晚于当前服务器时间 |
| DAILY | localTime + timezone |
| WEEKLY | weekdays（ISO 1–7）+ localTime + timezone |
| FIXED_INTERVAL | anchorAt + intervalSeconds，间隔≥60秒 |

计划关联任务模板，每次到期读取当时模板版本并生成不可变执行快照。关联模板必须 FINITE，执行时长≤24小时；固定间隔还要求时长≤间隔。修改关联模板也要验证该约束。

调度器每秒扫描到期项；使用数据库唯一约束(scheduleId, scheduledAt)去重。到期迟到≤5秒且服务一直在线，可尝试启动；超过5秒记录 SKIPPED_MISFIRE。进程离线期间不补跑；长时间离线按计划记录一条漏执行汇总和遗漏数，直接计算下一未来时间，禁止无限逐条补写。

冲突策略固定 SKIP，无排队、无抢占、无失败自动重试。多个计划同时到期按 scheduledAt、scheduleId 升序竞争槽位。暂停会话同样导致 SKIPPED_CONFLICT。

关闭或删除计划不影响已启动实例；停止当前实例不关闭后续计划。修改计划只影响未来触发。时区夏令时不存在的本地时间跳过，重复的本地时间只执行第一次；固定间隔按 UTC 锚点计算。时钟回拨不重复触发，前跳按漏执行规则处理。

### TASK-03 记录

启动前保存 STARTING；状态变更即时落库；运行中每30秒保存心跳和统计摘要。均值按有效采样时长加权，排除空值；保存有效覆盖时长。区分整机内存使用均值/峰值与负载 heldBytes 均值/峰值，不混称“内存占用”。

启动时将残留非终态标为 INTERRUPTED，lastHeartbeatAt 为最后已知存活点，endedAt 为发现中断时间，注明实际停止时间未知。不得伪造准确运行时长。

## 8. 页面与交互

统一中文界面，暗色为默认主题，优先可读性。桌面宽度1280px及以上完整布局；窄屏允许表格滚动。禁用按钮必须说明原因，不用只有颜色的状态提示。

| 页面 | 核心内容与操作 |
| --- | --- |
| 登录 | 用户名/密码、失败提示、会话过期重新登录 |
| 总览 | 主机信息、四类指标卡、CPU/内存/网络/磁盘趋势、当前会话、最近执行、安全状态 |
| 资源控制 | CPU/内存/联合三个Tab共享同一会话；目标、预算、预检、时长、启动/暂停/恢复/停止 |
| 任务模板 | 搜索、创建、编辑、删除、立即运行 |
| 定时计划 | 创建、编辑、启停、下次触发时间、最近跳过原因 |
| 执行记录 | 状态、开始/结束、快照、目标变更、安全事件、有效统计、分页 |
| 历史监控 | 时间范围与指标、采样覆盖率、自动聚合、数据缺口 |
| 系统设置 | 控制开关、锁存解除、阈值只读说明、保留策略信息、部署配置指引 |

资源面板需同时呈现“请求目标”“实际值”“当前允许新增量”“限制原因”。对于超过预算的固定容量，显示可输入最大值并禁止提交，后端仍复核。不要把输入目标改小后静默启动。

启动、暂停、恢复等异步操作显示服务端状态；成功响应不直接假定引擎已经完成动作。停止后内存文案为“负载引用已释放，系统内存回收由 JVM/OS 决定”。无数据、加载失败、过期、无权限、请求冲突必须有独立状态。

## 9. API 契约

### 9.1 通用规范

前缀 /api/v1；JSON camelCase。成功返回 data、requestId、serverTime；失败返回 error.code、error.message、error.details、requestId。列表为 items、page、pageSize、total，page从1开始，pageSize默认20最大100。

HTTP：400格式错误，401未登录，403无权限/CSRF，404不存在，409状态冲突或版本冲突，422目标或预算不合法，429限流，503采集或存储不可用。任何拒绝都不得产生负载。

启动类接口必填 Idempotency-Key(UUID)，24小时内相同键+相同请求返回同一 executionId；相同键不同参数返回409。记录持久化，对启动重试有效。异步生命周期变更返回202及当前实例，前端通过SSE或GET确认完成。静态文件与API同源，关闭跨域。

### 9.2 路由

| 方法 | 路由 | 说明 |
| --- | --- | --- |
| GET | /auth/csrf | 获取CSRF token，未登录也可用于登录 |
| POST | /auth/login | 登录；使用CSRF |
| POST | /auth/logout | 注销 |
| GET | /auth/me | 当前会话 |
| GET | /system/info | 环境、能力、禁用原因 |
| GET | /monitor/current | 最新快照 |
| GET | /monitor/stream | SSE，事件metric/resource/safety |
| GET | /monitor/history | from、to、metrics、bucketSeconds；自动限制桶数 |
| POST | /resource/preflight | 无副作用预检和预算 |
| GET | /resource/status | 开关、锁存、当前实例、预算 |
| POST | /executions | 手动启动，请求配置见示例 |
| GET | /executions | 历史分页、状态/时间/taskId过滤 |
| GET | /executions/{id} | 快照、统计、事件 |
| PATCH | /executions/{id}/targets | cpuTargetPct及内存对应目标、expectedVersion |
| POST | /executions/{id}/pause | 暂停 |
| POST | /executions/{id}/resume | 恢复 |
| POST | /executions/{id}/stop | 普通停止；重复调用幂等 |
| POST | /resource/emergency-stop | 急停锁存；重复调用幂等 |
| POST | /resource/reset-latch | 健康检查后解除锁存 |
| PUT | /resource/control-switch | enabled:boolean；开启不解除锁存 |
| GET/POST | /tasks | 查询/创建模板 |
| GET/PUT/DELETE | /tasks/{id} | 查询/修改/软删除；修改使用expectedVersion |
| POST | /tasks/{id}/run | 按当前模板创建实例，必需幂等键 |
| GET/POST | /schedules | 查询/创建计划 |
| GET/PUT/DELETE | /schedules/{id} | 详情/修改/删除；修改使用expectedVersion |
| PUT | /schedules/{id}/enabled | enabled、expectedVersion |
| GET | /settings | 运行时可见配置，不返回秘密 |
| GET | /logs | 操作日志分页 |

V1 部署级安全阈值、监听地址、Xmx、认证参数通过外置配置维护，Web 不提供万能配置写入接口。

### 9.3 启动请求示例

```json
{
  "name": "CPU40与内存256MiB测试",
  "resourceMode": "COMBINED",
  "cpuTargetPct": 40,
  "memoryMode": "EXTRA_BYTES",
  "memoryTargetBytes": 268435456,
  "runMode": "FINITE",
  "durationSeconds": 3600
}
```

SYSTEM_PERCENT 模式改用 memoryTargetPct，并删除 memoryTargetBytes。CPU 模式删除全部内存字段。CONTINUOUS 删除 durationSeconds。

```json
{
  "data": {
    "executionId": "3ac238a3-958c-4cf5-8216-e11ce3b59242",
    "status": "STARTING",
    "version": 1
  },
  "requestId": "7b1791a7-e4b5-49a1-9eb8-ef62d4095509",
  "serverTime": 1794240000000
}
```

固定容量超预算：422 / MEMORY_BUDGET_EXCEEDED；details 返回 requestedBytes、allowedAdditionalBytes、limitingFactors，不分配内存。

SSE id使用 bootId:sequence，跨重启不重复；payload必须包括observedAt与quality。会话登出、过期时主动关闭相关SSE，不允许连接绕过认证持续读数据。

## 10. 数据模型

H2 文件位于 /var/lib/server-resource-manager/data；关闭 H2 Web Console、TCP server、AUTO_SERVER。小型连接池，最大4连接。使用带版本号的 SQL 迁移和校验和记录，禁止启动时覆盖已有数据。

允许为明确实体增加表，不强求原稿的“五张表”。数据库采用下列七张业务表，加 schema_version 技术表：

| 表 | 必需字段与约束 |
| --- | --- |
| sys_config | config_key唯一、value_json、version、updated_at；保存急停锁存等，不保存明文密码 |
| resource_task | id、name、description、mode、cpu_target_pct、memory_mode、memory_target_bytes、memory_target_pct、run_mode、duration_seconds、version、deleted_at、created_at、updated_at |
| task_schedule | id、task_id、type、schedule_json、timezone、enabled、next_fire_at、version、created_at、updated_at |
| task_execution | id、task_id可空、schedule_id可空、scheduled_at可空、config_snapshot_json、status、reason、version、started_at、ended_at、last_heartbeat_at、elapsed_duration_ms、active_duration_ms、stats_json、idempotency_key、request_hash、request_source |
| resource_metric | bucket_start主键、sample_count、coverage_ms、metrics_json；包括系统及负载均值/峰值，设备维度保持可辨识 |
| operation_log | id、execution_id可空、actor、source_ip、action、before_json、after_json、result、reason、request_id、created_at |
| request_dedup | idempotency_key主键、request_hash、execution_id、created_at、expires_at；拒绝请求也可保存一致结果 |

task_execution(schedule_id,scheduled_at) 唯一约束仅用于非空计划触发；具体H2 DDL必须验证null语义。索引：执行 started_at/status/task_id；计划 enabled/next_fire_at；日志 created_at/execution_id。

启动事务内写实例及幂等记录，再提交和启动引擎。事务失败不启动。提交后进程崩溃由恢复逻辑将STARTING转INTERRUPTED，不重放启动动作。运行目标修改保存版本与日志后应用，应用失败停止会话，不谎报更新成功。

历史及日志清理每小时批量执行，单批最多1000行，避开长事务；不删除活跃实例。达到条数上限清理最旧记录。详细监控过期后保留执行摘要，页面说明曲线已过保留期。

## 11. 认证与离线部署

### SEC-01 单管理员

全路径默认认证保护，包括 SSE、历史、控制及系统详情。仅登录页、必要静态资源、CSRF 获取入口可匿名。使用服务端 Session Cookie（HttpOnly、SameSite=Strict；HTTPS时Secure），默认空闲30分钟失效；SSE流量不得无限延长登录有效期。

密码使用 BCrypt 哈希，首次密码由运维通过本地初始化命令设置，不内置默认密码、不打印密码、不通过网页执行系统命令。登录失败按账户和来源限速：5分钟5次失败后冷却5分钟；敏感信息不进入日志。修改操作启用CSRF，读取接口不得有副作用。

默认仅回环地址HTTP访问。远程访问必须配置内置HTTPS证书和访问CIDR；非回环监听但缺失上述配置时启动拒绝。反向代理不是必需依赖；V1不自动信任X-Forwarded-For。

### DEP-01 文件和打包

```text
server-resource-manager/
  pom.xml
  frontend/                    # Vue源码与锁文件
  src/main/java/.../           # auth/monitor/resource/safety/task/history
  src/main/resources/
    static/                    # 构建时生成
    db/migration/
  deploy/                     # install/upgrade/backup/restore脚本与service
  docs/                       # PRD、OpenAPI、部署、验收记录
```

安装位置：JAR /opt/server-resource-manager；配置 /etc/server-resource-manager；数据库 /var/lib/server-resource-manager；日志 /var/log/server-resource-manager。以srm普通用户运行，数据与秘密仅服务用户可读写。只由安装脚本完成必要的用户及目录配置。

离线发布包包含 JAR、脚本、示例配置、systemd unit、SHA256校验、依赖版本清单、部署文档。开发环境下载依赖并构建；生产运行不得下载 npm/Maven 依赖、字体、CDN资源或发送遥测。必须在无互联网环境验证完整流程。

OSHI/JNA 的目标系统兼容性属于安装预检；本地库加载失败提供明确诊断，不能把指标写成0。Windows开发环境仅用于UI及单元测试，不假装通过麒麟兼容验收。

### DEP-02 启停、备份和升级

systemd配置 Restart=on-failure，重启间隔5秒，并设置启动频率限制。服务重启恢复监控和计划定义，但控制OFF，所有负载不自动恢复。

V1采用停服务冷备：确认服务已停止 → 复制完整数据目录及必要配置 → 计算校验和 → 重新启动。不得复制正在写入的H2文件。H2也提供数据库备份命令，但本版优先保持脚本简单，参见 [H2 BACKUP](https://h2database.com/html/commands.html#backup)。

升级脚本先关闭控制并停止服务，备份旧JAR及数据，部署新JAR，执行迁移并验证健康。失败时停止新服务，恢复同一备份对应的旧JAR与数据库，禁止旧JAR直接打开已升级数据库。恢复脚本需显式指定备份并提醒覆盖现有数据。

## 12. 验收标准

### 12.1 功能与故障用例

| 编号 | 场景 | 通过条件 |
| --- | --- | --- |
| AT-01 | 离线冷启动 | 无外网、无Node/Maven仍能启动并登录，静态资源无外部请求 |
| AT-02 | 真实监控 | 与相同采样口径的procfs对照；缺失值明确标记，不出现模拟值 |
| AT-03 | CPU目标30/40/60% | 8核、基线CPU<10%、无明显steal/iowait；预热60秒后观察5分钟，≥90%的10秒均值在目标±5pp内 |
| AT-04 | 外部CPU突然升高 | 超过目标后两次控制采样内本程序duty归零；外部降载后渐进恢复，无占满冲刺 |
| AT-05 | 内存256MiB | 预算充足时按速率达到heldBytes目标，页面区分堆/RSS/负载 |
| AT-06 | 超预算及恶意输入 | 负值、溢出、互斥字段、超限全部拒绝且无资源副作用 |
| AT-07 | 系统内存目标不可达 | 达预算后停止增加，显示受限原因，持续运行不会突破硬限 |
| AT-08 | 暂停和恢复 | CPU停止、heldBytes归零；槽位保留；恢复重检，不瞬间满负载；暂停不延长时限 |
| AT-09 | 普通停止/到期 | 正常调度下2秒内Worker确认退出且heldBytes=0，生成正确终态；不要求RSS同步下降 |
| AT-10 | 急停并发 | 并发启动/调整与急停竞态下旧generation不再分配，控制OFF锁存，新启动拒绝 |
| AT-11 | 计划冲突与幂等 | 同时触发只有一个实例运行；重复HTTP请求、时钟回拨不重复启动 |
| AT-12 | 进程异常退出 | 重启后原非终态标INTERRUPTED，控制OFF，过去计划不补跑 |
| AT-13 | 监控过期 | 超3秒禁止增长、超10秒安全停止；可选温度缺失不触发 |
| AT-14 | 压力保护 | 注入阈值样本验证连续窗口、滞回、锁存；未经人工解除不恢复 |
| AT-15 | 数据库故障/磁盘满 | 拒绝新任务、停止当前负载、UI展示故障；急停不等待写库 |
| AT-16 | 会话/CSRF/SSE | 未认证拒绝、伪造变更拒绝、过期/注销关闭SSE、连接上限有效 |
| AT-17 | 备份恢复/升级回滚 | 在空目录恢复数据一致，失败升级可回到匹配旧JAR和数据库 |
| AT-18 | 长时间运行 | 连续24小时完成采集、任务轮转及清理，线程/队列不持续增长，无无法停止会话 |
| AT-19 | 慢SSE客户端 | 慢连接不阻塞采集/安全线程，无无界积压，其余客户端正常 |
| AT-20 | 模板与实例隔离 | 编辑模板不改变已有实例；删除引用模板返回明确冲突 |

阈值、时钟与故障测试优先使用可注入采集器和时钟；生产默认不能启用模拟数据。不得为测试把真实业务服务器推至95% CPU或耗尽内存。实际负载验收在获准的测试环境进行。

### 12.2 性能目标与测量方法

基准：目标8逻辑CPU虚拟机，至少4GiB内存，固定JDK17发行版和参数，1个浏览器。预热5分钟、记录15分钟。

纯监控模式：进程CPU均值≤整机2%，RSS≤350MiB为优化目标；如果超标必须分析并记录，不能删除功能或伪造数据。负载运行后的JVM可能保留已提交内存，不套用冷启动RSS门槛。

负载在允许范围内：状态查询API P95≤500ms，停止请求接收P95≤500ms，Worker退出与引用清除≤2秒。严重系统停顿、内核OOM或不可调度时无法保证软件时限，测试记录必须注明条件。

10秒聚合每7天约60,480个系统桶，默认设备数量下数据库与应用日志合计目标≤500MiB。容量超目标先检查设备维度和日志频率，不允许静默停止审计。

## 13. Cursor 实施阶段与交付

每阶段先实现后验证，通过才进入下一阶段。不得一次生成完整项目后仅以“编译成功”宣告完成。

| 阶段 | 内容 | 门禁 |
| --- | --- | --- |
| M0 设计落地 | 核对需求、锁定依赖、OpenAPI、DDL、状态枚举、预算纯函数、环境诊断方案 | 契约和字段一致，无未解释的架构扩张 |
| M1 基础与只读监控 | 工程、认证、真实采集、SSE、页面、H2迁移、离线打包 | AT-01/02/16/19；禁止先上线负载引擎 |
| M2 安全骨架与CPU | 会话管理、急停、状态机、CPU引擎、阈值保护 | AT-03/04/09/10/13/14，自动测试先于真实压力验收 |
| M3 内存与联合控制 | 预算、分块、两模式、暂停恢复、联合失败回收 | AT-05/06/07/08 |
| M4 任务计划与历史 | 模板、计划、去重、恢复、聚合查询、清理 | AT-11/12/15/20 |
| M5 部署验收 | 脚本、冷备、升级回滚、目标机兼容、长跑 | AT-17/18及性能报告 |

最终交付：源代码、锁文件、可执行JAR及SHA256、OpenAPI、DDL迁移、安装/启停/备份/恢复/升级说明、目标环境诊断、测试结果、已知限制。

单元测试重点：预算边界、状态转换、并发取消、阈值窗口、时钟变化、计划去重；集成测试重点：真实H2持久化和恢复、权限、API冲突、SSE背压。测试引擎必须可替换真实负载，禁止测试套件默认占满开发机。

在目标麒麟机器完成验收之前，只能标记“开发验证通过，目标环境待验收”。不得假称兼容性已证实。

## 14. 实施优先级与变更规则

本文件中的默认值用于直接开发；未知部署信息通过预检处理，不阻塞普通工程建设。首要顺序是正确指标、安全停止、有限内存预算，再做图表和视觉完善。

如果后续要求严格RSS回收、超过堆预算的大容量负载、容器内配额控制或多机管理，应单独提交需求变更和架构决策，不能通过放宽安全阈值解决。所有需求变更同步更新PRD、OpenAPI、测试和部署说明。
