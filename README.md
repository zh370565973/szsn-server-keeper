# Server Resource Manager

单机服务器资源监控与受控负载工具。一个 Spring Boot / Java 17 进程内嵌 Vue 控制页，打成单个可执行 JAR。默认只监听 `127.0.0.1:8080`。

它用来在本机上查看 CPU、内存和 JVM，并在明确打开控制开关后，按预算短时占用 CPU 或内存。占用受三重内存预算、CPU duty、急停和独立安全线程约束。非 Linux 或存在额外 cgroup/cpuset 限制时，控制写接口返回 503，不产生负载。

## 构建与启动

需要 JDK 17。完整打包还需要 Node，用来把前端打进 JAR。只跑单元测试时不要装 Node，Maven 会跳过前端：

```text
mvn -Dskip.frontend=true test
mvn package
java -jar target/server-resource-manager-1.0.0-M1.jar init-admin
java -jar target/server-resource-manager-1.0.0-M1.jar
```

`init-admin` 把管理员口令写成 BCrypt 文件。服务起来后用浏览器打开 `http://127.0.0.1:8080`。数据目录默认是 `./data`。

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
