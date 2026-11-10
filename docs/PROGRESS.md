# 进度

阶段结论：M0 与 M1 在 Windows 开发机上验证通过。目标麒麟环境待验收。本轮没有实现或启动 CPU/内存负载。

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

## 下一阶段

M2：实现会话状态机的运行时、独立安全检查、generation 取消、急停锁存，然后接入 CPU 引擎。先用可注入时钟和采集器测试，不在开发机上做真实满载。
