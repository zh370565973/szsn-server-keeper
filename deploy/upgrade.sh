#!/bin/bash
# 关闭控制并停止服务，备份旧 JAR 与数据库，再部署新 JAR。健康检查失败时只回滚这一份备份。
set -euo pipefail
HERE=$(cd "$(dirname "$0")" && pwd)
# shellcheck source=srm-common.sh
. "$HERE/srm-common.sh"

new_jar=""
while [ $# -gt 0 ]; do
  case "$1" in
    --jar)
      new_jar=${2:-}
      shift 2
      ;;
    *)
      echo "未知参数：$1" >&2
      echo "用法：upgrade.sh --jar /path/server-resource-manager.jar" >&2
      exit 2
      ;;
  esac
done

if [ -z "$new_jar" ] || [ ! -f "$new_jar" ]; then
  echo "需要新 JAR：upgrade.sh --jar <文件>" >&2
  exit 2
fi

assert_sandbox_paths
stop_for_maintenance || exit 1
id=$(create_backup)
printf 'backup_id=%s\nresult=in-progress\n' "$id" > "$SRM_BACKUP_ROOT/upgrade-in-progress"
install_new_jar "$new_jar"
if start_and_health; then
  printf 'backup_id=%s\nresult=upgraded\n' "$id" > "$SRM_BACKUP_ROOT/upgrade-in-progress"
  echo "升级完成。失败时只能恢复这一份备份：${id}"
  exit 0
fi

echo "升级后的健康检查失败，停止新服务并恢复备份 ${id} 的旧 JAR 和数据库。" >&2
stop_for_maintenance || exit 1
if ! restore_tree "$id"; then
  echo "回滚失败。不要用旧 JAR 启动当前数据目录。目标备份：${id}" >&2
  exit 1
fi
if ! start_and_health; then
  echo "回滚后的服务未通过健康检查。磁盘上的 JAR 和数据库仍是备份 ${id}，未改用其他备份。" >&2
  printf 'backup_id=%s\nresult=rolled-back-unhealthy\n' "$id" > "$SRM_BACKUP_ROOT/upgrade-in-progress"
  exit 1
fi
printf 'backup_id=%s\nresult=rolled-back\n' "$id" > "$SRM_BACKUP_ROOT/upgrade-in-progress"
echo "已回滚到备份 ${id}" >&2
exit 1
