#!/bin/bash
# 停服务冷备：调用前服务必须已经停止。复制数据、配置和 JAR，写校验和，然后重新启动。
set -euo pipefail
HERE=$(cd "$(dirname "$0")" && pwd)
# shellcheck source=srm-common.sh
. "$HERE/srm-common.sh"

if [ $# -gt 0 ]; then
  echo "backup.sh 不接受参数。请先 systemctl stop ${SRM_SERVICE}，再运行本脚本。" >&2
  exit 2
fi

assert_sandbox_paths
assert_stopped || exit 1
assert_no_lock || exit 1
id=$(create_backup)
echo "备份完成：${id}"
"$SRM_SYSTEMCTL" start "$SRM_SERVICE"
echo "服务已重新启动"
