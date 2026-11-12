# Server Resource Manager

单机服务器资源监控与受控负载工具。一个 Spring Boot / Java 17 进程内嵌 Vue 控制页，打成单个可执行 JAR。默认监听所有网卡，使用普通 HTTP，端口见 `server.port`。

它用来在本机上查看 CPU、内存和 JVM，并在明确打开控制开关后，按预算短时占用 CPU 或内存。占用受三重内存预算、CPU duty、急停和独立安全线程约束。非 Linux 或存在额外 cgroup/cpuset 限制时，控制写接口返回 503，不产生负载。

## 构建与启动

需要 JDK 17。完整打包还需要 Node，用来把前端打进 JAR。只跑单元测试时不要装 Node，Maven 会跳过前端：

```text
mvn -Dskip.frontend=true test
mvn package
java -jar target/server-resource-manager-1.0.0-M1.jar
```

服务起来后用浏览器打开控制页。数据目录默认是 `./data/server-resource-manager`。登录口令已经写在配置里，一般不用再执行 `init-admin`。

## 访问地址与登录

| 项目 | 值 |
| --- | --- |
| 控制页 | `http://<服务器地址>:<server.port>` |
| 监听 | `0.0.0.0`，普通 HTTP（`application.yml` 的 `server.address` / `server.port`） |
| 用户名 | `admin`（`srm.admin.username`） |
| 口令 | `Adm#K8pQ3mN7vR2xL5`（`srm.admin.password`） |

只有这一个管理员，页面不能再加用户，也不能在网页上改口令。用户名和口令都在 `application.yml`，一般不改。`srm.admin.password` 非空时直接用它登录，不读 `./data/server-resource-manager/admin.password`。

把 `srm.admin.password` 留空后，才需要 `init-admin`：终端输入 12–200 位口令，程序只把 BCrypt 哈希写到 `password-file`。明文不进日志。文件已存在时要加 `--force` 才会覆盖。口令和口令文件都没有时，登录返回 503。登录失败连续 5 次后冷却 5 分钟。

H2 账号也在同一份配置里，只给本进程连库，不能用来打开控制页。用户名 `srm_db_k4n8`，密码 `Hk7mQ2vL9pR4wX8nB3cF6`。这两项一般不改；已经建好的库文件仍认当初的账号。

不需要 HTTPS 证书。云安全组或主机防火墙放行 `server.port` 后，用公网 IP 访问。

## 资源占用怎么实现

控制页上的 CPU、内存开关只增加本进程自己的负载。它不能压低其他进程已经占用的资源，目标也是尽力达到，不是操作系统配额。非 Linux，或检测到额外 cgroup / cpuset 限制时，写接口返回 503，不产生负载。同一时间只有一个执行实例。每次进程启动后控制开关都是关的。

### CPU

生产路径是 `ChecksumCpuBurner`，由 `CpuEngine` 按 duty 调度。

1. Worker 数等于当前可用逻辑核心数，不超过这个数，也不会每个调节周期新建线程。
2. 每 2 秒读一次整机 CPU 使用率，用 EWMA（alpha=0.4）和目标比较。误差在 ±3 个百分点内保持当前 duty；否则按 `d' = clamp(d + 0.3 × 误差 / 100, 0, 1)` 调整。单次最多增加 0.05、减少 0.15。duty 从 0 开始。
3. 每个 Worker 以约 100 毫秒为一个时间片：计算 `100ms × duty`，其余时间可唤醒地等待。duty 为 0 时只等待，不做计算。
4. 计算段跑一段可观察的校验和循环（旋转、异或、累加），结果记入引擎，避免被 JIT 当成死代码删掉。每不超过 5 毫秒检查一次停止、暂停和会话是否仍有效。
5. 外部 CPU 已经高于目标 +3 个百分点并连续两次采样时，duty 直接归零，并标记外部负载偏高。安全线程也可以直接把 duty 置零。

页面上看到的是目标、实际使用率、duty、Worker 数和受限原因。暂停只停止计算并保留槽位；恢复要重新预检，duty 再从 0 往上走。

### 内存

生产路径是 `ByteArrayMemoryAllocator`，由 `ChunkedMemoryGrower` 按块增长。只使用普通 Java 堆上的 `byte[]`，不用 Unsafe，也不分配堆外内存。

1. 目标字节数必须是 4MiB 的整数倍。增长时每次申请一块 4MiB，速率不超过 32MiB/s。每一块分配前都会再查停止标志、最新预算和监控样本是否过期。
2. 每块按 4KiB 一页写入非零字节（页首和块尾各写 `1`）。这样操作系统会真正提交这些页，而不是只保留一块未触碰的虚拟地址。
3. 这些数组放在队列里，`heldBytes` 就是当前仍被引用的字节数。这是本引擎持有的堆载荷，不是系统 RSS 的精确增量。
4. 两种目标：
   - `EXTRA_BYTES`：直接指定要持有的字节数。
   - `SYSTEM_PERCENT`：每 2 秒按「目标占用百分比 × 总内存 − 当前已用」计算差额。系统本身已经高于目标时不再分配；受预算卡住时显示目标受限，不会一直追。
5. 能再分配多少由三重预算里最小的那一档决定，并向下对齐到 4MiB：
   - 系统：可用内存减去 `max(总内存 × 20%, 1GiB)` 再减去保护量。
   - 堆：`Xmx` 减去已用堆，再减去 `max(Xmx × 25%, 256MiB)` 和保护量。负载上限还不超过 `Xmx` 的一半。
   - 任务：本次任务上限减去已经持有的字节。
6. 暂停或停止只丢掉这些数组的引用，使 `heldBytes` 归零。不调用 `System.gc()`。RSS 稍后下降只说明收集器或操作系统稍后回收了页，不能当成已经释放成功。清除引用后有一段冷却，避免马上再分配。

页面把 `heldBytes`、JVM `heapUsed` 和进程 RSS 分开显示。超预算时给出还能输入的最大值并禁止提交，不会把目标悄悄改小再启动。

## 当前进度

制品版本仍是 `1.0.0-M1`，没有另立发版号。M0 到 M3 的开发项已经在 main 上：监控、登录、CPU 引擎、内存分块、联合控制、资源页和安全停机都已接入。

- 公开启动在写入执行记录之前校验：时长 10 秒–7 天，系统占用目标 1–70%，内存字节必须是 4MiB 的整数倍。不合法返回 422，不落库。
- 生产内存路径使用按 4MiB 分块、逐页写入非零字节的 `byte[]` 分配器，增长不超过 32MiB/s，并受三重预算限制。暂停或停止只清除引用。单元测试仍注入假分配器。M3-06 已用普通 Linux 上 64MiB 上限的真实分配取证记为完成，不是麒麟上的 256MiB 全量。
- 生产 CPU 路径使用 `ChecksumCpuBurner` 做真实计算。测试注入不自旋的燃烧器。
- 不调用 `System.gc()`，也不把 RSS 下降当成内存已经还给系统。

还没做的部分不要当成已完成：

- M4（模板、计划、心跳）未开工。
- 麒麟现场对照（M1-09 / AT-02）和长跑（M5-04）仍待验收。本次不要求麒麟机器。
- M2-09 / AT-03 要求 8 核、基线 CPU 低于 10% 时对 30/40/60% 做五分钟观察。短时燃烧不能代替这项。

更细的范围、进度和接口见 [PRD.md](PRD.md)、[TASKS.md](TASKS.md)、[docs/PROGRESS.md](docs/PROGRESS.md)、[docs/IMPLEMENTATION_PLAN.md](docs/IMPLEMENTATION_PLAN.md)、[docs/openapi.yaml](docs/openapi.yaml) 和 [deploy/README.md](deploy/README.md)。
