# 部署说明

版本 `1.0.0-M1`。这个目录是离线发布包：JAR、安装脚本、示例配置、systemd unit、SHA-256、依赖清单和本说明。生产机使用已经构建好的 JAR，不在目标机下载 npm、Maven、字体或 CDN 资源。断网启动本身仍是 M5-03，本文不把它记成已验收。

## 包里有什么

| 文件 | 作用 |
| --- | --- |
| `server-resource-manager.jar` | 可执行 JAR，页面已经打进包内 |
| `SHA256SUMS` | 除本文件和 `MANIFEST` 以外每个文件的 SHA-256 |
| `MANIFEST` | 版本，以及 JAR、`SHA256SUMS` 的 SHA-256 |
| `DEPENDENCIES.txt` | 构建机解析出的运行时 Java 依赖，含传递依赖 |
| `openapi.yaml` | 当前 HTTP 接口 |
| `DEPLOY.md` | 本说明 |
| `deploy/` | 安装、冷备、恢复、升级脚本，systemd unit，示例配置 |

前端版本锁在源码的 `frontend/package-lock.json`。构建之后页面在 JAR 里，发布包不再携带 `node_modules`。

## 核对校验和

在发布目录执行：

```text
sha256sum -c SHA256SUMS
```

再核对清单里的 JAR：

```text
sha256sum server-resource-manager.jar
```

输出必须与 `MANIFEST` 的 `jar_sha256` 相同。不一致就不要安装。

## 安装和初始化

需要 Linux、root，以及 JDK 17。脚本只创建 `srm` 用户和目录，不写管理员口令，也不启动服务。

```text
sudo bash deploy/install.sh --jar "$PWD/server-resource-manager.jar"
sudo -u srm java -jar /opt/server-resource-manager/server-resource-manager.jar \
  --spring.config.additional-location=file:/etc/server-resource-manager/application.yml \
  init-admin
sudo systemctl start server-resource-manager
```

口令只从终端读取，不写进命令行。默认只监听 `127.0.0.1:8080`。改成非回环地址时，必须同时配置内置 HTTPS 证书和 `srm.remote.cidr`，否则进程拒绝启动。

安装路径：

| 用途 | 路径 |
| --- | --- |
| JAR 与脚本 | `/opt/server-resource-manager` |
| 配置 | `/etc/server-resource-manager` |
| 数据库与口令 | `/var/lib/server-resource-manager` |
| 日志 | `/var/log/server-resource-manager` |
| 冷备 | `/var/backups/server-resource-manager` |

数据目录权限保持仅 `srm` 可读写。更细的停服务冷备、恢复和失败回滚见包内 `deploy/README.md`。

## 启停

```text
sudo systemctl start server-resource-manager
sudo systemctl stop server-resource-manager
sudo systemctl status server-resource-manager
```

`Restart=on-failure`，间隔 5 秒，120 秒内最多启动 5 次。`systemctl stop` 是干净停止，不会被立刻拉起。进程每次启动时控制开关都是 OFF，未完成的负载不会恢复。

## 备份、恢复和升级

冷备前先停止服务。不要复制仍在写入的 H2 文件。

```text
sudo systemctl stop server-resource-manager
sudo bash /opt/server-resource-manager/backup.sh
```

恢复会覆盖现有 JAR、数据库和配置，必须指定备份并确认：

```text
sudo bash /opt/server-resource-manager/restore.sh --backup <备份编号> --confirm
```

升级使用新的 JAR。健康检查失败时，脚本只用这一次升级前的备份恢复旧 JAR 和数据库：

```text
sudo bash /opt/server-resource-manager/upgrade.sh --jar /path/new.jar
```

旧 JAR 不能直接打开已经迁移过的数据库。`srm-run` 发现 JAR 校验和与数据目录里的 `release-pair` 不一致时拒绝启动。

## 依赖清单

`DEPENDENCIES.txt` 在构建机用下面的命令生成，范围是运行时（含编译期传递依赖，不含测试）：

```text
mvn -B -Dskip.frontend=true dependency:list -DincludeScope=runtime -Dsort=true -DappendOutput=false -DoutputFile=target/DEPENDENCIES.txt
```

生产运行不根据这份清单再次下载。

## 断网检查

在已经装有 JDK 17 的目录里执行。脚本只调用 `javac` 和 `java`，启动前把 `PATH` 换成空目录：

```text
bash deploy/verify-offline.sh --jar server-resource-manager.jar --work /tmp/srm-offline
```

它会扫描包内页面和依赖名，用临时口令完成 `init-admin`，在 `127.0.0.1` 上登录，并确认这个进程没有对外套接字。工作目录必须为空。Windows 上可以追加 `--block-outbound`，短暂阻断这份 `java.exe` 的出站；检查结束后规则会删掉。Linux 上不要加这个参数，套接字检查改为读 `/proc`。

## 本次没有验收的部分

- 麒麟现场的备份恢复、升级回滚和 24 小时长跑仍是 M5-04，目标环境待验收。
- 8 核、基线 CPU 低于 10% 的 30/40/60% 五分钟观察仍是 M2-09 / AT-03。
