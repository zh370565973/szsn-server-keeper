# 部署目录

本目录在 M5 放置安装、升级、备份、恢复脚本和 systemd unit。

M1 只交付可执行 JAR。开发机启动前需要本机 JDK 17，并用初始化命令写入管理员口令：

```text
java -jar server-resource-manager.jar init-admin
```

生产布局（脚本尚未提供）：

- JAR：`/opt/server-resource-manager`
- 配置：`/etc/server-resource-manager`
- 数据：`/var/lib/server-resource-manager`
- 日志：`/var/log/server-resource-manager`

默认只监听 `127.0.0.1:8080`。改成非回环地址时，必须同时配置内置 HTTPS 证书和 `srm.remote.cidr`，否则进程拒绝启动。服务不读取 `X-Forwarded-For`。

目标麒麟环境尚未验收。
