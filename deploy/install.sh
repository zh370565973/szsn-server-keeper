#!/bin/bash
# 创建 srm 用户、目录、示例配置和 systemd unit。不设置管理员口令，也不启动负载。
set -euo pipefail
HERE=$(cd "$(dirname "$0")" && pwd)
# shellcheck source=srm-common.sh
. "$HERE/srm-common.sh"

jar=""
while [ $# -gt 0 ]; do
  case "$1" in
    --jar)
      jar=${2:-}
      shift 2
      ;;
    *)
      echo "未知参数：$1" >&2
      echo "用法：install.sh --jar /path/server-resource-manager.jar" >&2
      exit 2
      ;;
  esac
done

if [ -z "$jar" ] || [ ! -f "$jar" ]; then
  echo "需要已构建的 JAR：install.sh --jar <文件>" >&2
  exit 2
fi

assert_sandbox_paths
preflight_report

if [ "$(id -u)" -eq 0 ] && [ "${SRM_SKIP_USER:-0}" != 1 ]; then
  if ! getent group "$SRM_GROUP" >/dev/null 2>&1; then
    groupadd --system "$SRM_GROUP"
  fi
  if ! id "$SRM_USER" >/dev/null 2>&1; then
    useradd --system --gid "$SRM_GROUP" --home-dir "$SRM_DATA" --shell /usr/sbin/nologin --no-create-home "$SRM_USER"
  fi
else
  echo "当前不是 root，跳过 srm 用户和属主。正式安装请用 root 运行。" >&2
fi

mkdir -p "$SRM_PREFIX" "$SRM_ETC" "$SRM_DATA" "$SRM_LOG" "$SRM_BACKUP_ROOT" "$SRM_UNIT_DIR"

cp -a "$jar" "$SRM_PREFIX/$SRM_JAR_NAME"
chmod 644 "$SRM_PREFIX/$SRM_JAR_NAME" || true
for name in srm-common.sh srm-run install.sh backup.sh restore.sh upgrade.sh; do
  cp -a "$HERE/$name" "$SRM_PREFIX/$name"
  chmod 755 "$SRM_PREFIX/$name" || true
done

if [ ! -f "$SRM_ETC/application.yml" ]; then
  sed -e "s|__PREFIX__|${SRM_PREFIX}|g" -e "s|__ETC__|${SRM_ETC}|g" \
    -e "s|__DATA__|${SRM_DATA}|g" -e "s|__LOG__|${SRM_LOG}|g" \
    "$HERE/application.yml.example" > "$SRM_ETC/application.yml"
fi

cat > "$SRM_ETC/layout.env" <<EOF
SRM_PREFIX=${SRM_PREFIX}
SRM_ETC=${SRM_ETC}
SRM_DATA=${SRM_DATA}
SRM_LOG=${SRM_LOG}
SRM_USER=${SRM_USER}
SRM_GROUP=${SRM_GROUP}
SRM_JAVA=${SRM_JAVA}
SRM_SERVICE=${SRM_SERVICE}
SRM_JAR_NAME=${SRM_JAR_NAME}
SRM_HEALTH_URL=${SRM_HEALTH_URL}
SRM_BACKUP_ROOT=${SRM_BACKUP_ROOT}
SRM_UNIT_DIR=${SRM_UNIT_DIR}
EOF

write_release_pair

sed -e "s|^User=.*|User=${SRM_USER}|" \
    -e "s|^Group=.*|Group=${SRM_GROUP}|" \
    -e "s|^WorkingDirectory=.*|WorkingDirectory=${SRM_PREFIX}|" \
    -e "s|^EnvironmentFile=.*|EnvironmentFile=${SRM_ETC}/layout.env|" \
    -e "s|^ExecStart=.*|ExecStart=${SRM_PREFIX}/srm-run|" \
    "$HERE/server-resource-manager.service" > "$SRM_UNIT_DIR/${SRM_SERVICE}.service"

if [ "$(id -u)" -eq 0 ] && [ "${SRM_SKIP_USER:-0}" != 1 ]; then
  chown root:root "$SRM_PREFIX" "$SRM_PREFIX/$SRM_JAR_NAME" "$SRM_UNIT_DIR/${SRM_SERVICE}.service" || true
  chown -R "root:${SRM_GROUP}" "$SRM_ETC" || true
  chmod 750 "$SRM_ETC" || true
  chmod 640 "$SRM_ETC/application.yml" "$SRM_ETC/layout.env" || true
  chown -R "${SRM_USER}:${SRM_GROUP}" "$SRM_DATA" "$SRM_LOG" || true
  chmod 700 "$SRM_DATA" || true
  chmod 750 "$SRM_LOG" || true
  chmod 600 "$SRM_DATA/release-pair" || true
else
  chmod 700 "$SRM_DATA" || true
  chmod 600 "$SRM_DATA/release-pair" || true
fi

"$SRM_SYSTEMCTL" daemon-reload
"$SRM_SYSTEMCTL" enable "$SRM_SERVICE"

echo "安装完成。尚未启动服务，也没有写入管理员口令。"
echo "下一步："
echo "  ${SRM_JAVA} -jar ${SRM_PREFIX}/${SRM_JAR_NAME} --spring.config.additional-location=file:${SRM_ETC}/application.yml init-admin"
echo "口令只从终端读取。然后执行：${SRM_SYSTEMCTL} start ${SRM_SERVICE}"
