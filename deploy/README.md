# 安装、备份、恢复和升级

这些脚本只在 Linux 上改动默认系统目录。开发机上的自动化测试会把目录指到临时路径。目标麒麟机器上的备份恢复验收仍是 AT-17，留在 M5-04。

生产布局：

| 用途 | 路径 |
| --- | --- |
| JAR 与启动脚本 | `/opt/server-resource-manager` |
| 配置 | `/etc/server-resource-manager` |
| 数据库与口令 | `/var/lib/server-resource-manager` |
| 日志 | `/var/log/server-resource-manager` |
| 冷备 | `/var/backups/server-resource-manager` |
| systemd | `/etc/systemd/system/server-resource-manager.service` |

服务用户是 `srm`。数据目录权限 `700`，口令文件只放在该目录。安装脚本在 root 下创建用户和目录；不要手工加宽权限。

## 安装

在目标机上用已经构建好的 JAR，不在生产机下载 Maven 或 npm：

```text
sudo bash install.sh --jar /path/server-resource-manager.jar
```

安装会打印内核、内存、JDK、磁盘和 cgroup。控制能力由服务启动时诊断，脚本不会把它写成已通过。配置文件已存在时不覆盖。

安装不会启动服务。配置里的 `srm.admin.password` 就是唯一管理员口令，非空时直接登录，不必再执行 `init-admin`。只有把这项留空时才需要：

```text
sudo -u srm java -jar /opt/server-resource-manager/server-resource-manager.jar \
  --spring.config.additional-location=file:/etc/server-resource-manager/application.yml \
  init-admin
sudo systemctl start server-resource-manager
```

`init-admin` 的口令只从终端读取。默认配置已经有口令，直接 `systemctl start` 即可。默认只监听 `127.0.0.1:8080`。改成非回环地址时，必须同时配置内置 HTTPS 证书和 `srm.remote.cidr`，否则进程拒绝启动。

## systemd

`Restart=on-failure`，`RestartSec=5`。120 秒内最多启动 5 次（`StartLimitInterval=120` 与 `StartLimitBurst=5`）。停止窗口 `TimeoutStopSec=10`。JVM 正常处理 SIGTERM 后可能以 143 退出，`SuccessExitStatus=143` 避免一次计划内的停止被当成失败又拉起来。

`systemctl stop` 是干净停止，不会因为 `on-failure` 自动重启。进程每次启动时控制开关都是 OFF，负载和未完成的执行不会恢复。

## 冷备

不要在服务运行时复制 H2 文件。`backup.sh` 发现服务仍是 active、activating 或 deactivating，或者数据目录里还有 `*.lock.db`，就直接拒绝。

```text
sudo systemctl stop server-resource-manager
sudo bash /opt/server-resource-manager/backup.sh
```

脚本确认已经停止，复制数据目录、配置和当前 JAR，写入 `CHECKSUMS` 和 `MANIFEST`，然后重新启动。每个备份里的 JAR 必须和数据目录中的 `release-pair` 是同一对。

## 恢复

恢复会覆盖现有 JAR、数据库和配置。必须写明备份编号，并追加 `--confirm`：

```text
sudo bash /opt/server-resource-manager/restore.sh --backup 20261111T020000Z-12345 --confirm
```

没有 `--confirm` 时只打印覆盖警告，不改文件。校验和或 JAR 与 `release-pair` 对不上时，不覆盖现有数据。

## 升级和回滚

```text
sudo bash /opt/server-resource-manager/upgrade.sh --jar /path/new.jar
```

顺序是：关闭控制并停止服务、备份旧 JAR 和数据库、放入新 JAR、启动并请求本机 `/api/v1/auth/csrf`。启动前会把 `release-pair` 改成新 JAR 的 SHA-256。健康检查失败时，停止新服务，只用这一份备份恢复旧 JAR 和数据库，然后再次启动。不会挑选别的备份，也不会让旧 JAR 直接打开已经迁移过的数据库。

`srm-run` 是 systemd 的启动命令。当前 JAR 的校验和与 `release-pair` 不一致时，它拒绝执行 Java。

## 发布包

离线目录由 `assemble-release.sh` 生成，说明在 `docs/DEPLOY.md`。断网冷启动用同目录的 `verify-offline.sh`。麒麟现场的备份恢复和 24 小时长跑仍属于 M5-04。
