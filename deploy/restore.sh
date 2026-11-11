#!/bin/bash
# 用指定备份覆盖当前 JAR、数据库和配置。必须同时给出 --backup 和 --confirm。
set -euo pipefail
HERE=$(cd "$(dirname "$0")" && pwd)
# shellcheck source=srm-common.sh
. "$HERE/srm-common.sh"

backup_id=""
confirm=0
while [ $# -gt 0 ]; do
  case "$1" in
    --backup)
      backup_id=${2:-}
      shift 2
      ;;
    --confirm)
      confirm=1
      shift
      ;;
    *)
      echo "未知参数：$1" >&2
      exit 2
      ;;
  esac
done

if [ -z "$backup_id" ]; then
  echo "必须用 --backup 指定备份编号。恢复会覆盖现有 JAR、数据库和配置。" >&2
  exit 2
fi

echo "恢复将覆盖现有 JAR、数据库和配置。未纳入该备份的当前数据会丢失。备份：${backup_id}" >&2
if [ "$confirm" -ne 1 ]; then
  echo "未执行恢复。确认覆盖请追加 --confirm。" >&2
  exit 2
fi

assert_sandbox_paths
verify_backup "$SRM_BACKUP_ROOT/$backup_id" || exit 1
stop_for_maintenance || exit 1
restore_tree "$backup_id" || exit 1
if ! start_and_health; then
  echo "文件已恢复为备份 ${backup_id}，但服务健康检查未通过。没有改用其他备份。" >&2
  exit 1
fi
echo "已从备份 ${backup_id} 恢复同一对 JAR 和数据库，并重新启动。"
