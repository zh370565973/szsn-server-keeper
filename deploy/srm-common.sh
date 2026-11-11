#!/bin/bash
# 安装、冷备、恢复和升级共用的路径、校验和与成对回滚。
# 控制开关只存在于进程内存；复制 H2 文件之前，服务必须已经停止。

if [ -n "${SRM_COMMON_LOADED:-}" ]; then
  return 0 2>/dev/null || exit 0
fi
SRM_COMMON_LOADED=1

set -euo pipefail

load_layout_if_present() {
  local file="${SRM_LAYOUT_FILE:-}"
  if [ -z "$file" ]; then
    if [ -n "${SRM_ETC:-}" ]; then
      file="$SRM_ETC/layout.env"
    else
      file="/etc/server-resource-manager/layout.env"
    fi
  fi
  [ -f "$file" ] || return 0
  local line key value
  while IFS= read -r line || [ -n "$line" ]; do
    line=${line%$'\r'}
    case "$line" in
      ''|'#'*) continue ;;
    esac
    key=${line%%=*}
    value=${line#*=}
    case "$key" in
      SRM_PREFIX|SRM_ETC|SRM_DATA|SRM_LOG|SRM_USER|SRM_GROUP|SRM_JAVA|SRM_SERVICE|SRM_HEALTH_URL|SRM_BACKUP_ROOT|SRM_UNIT_DIR|SRM_JAR_NAME)
        if [ -z "${!key:-}" ]; then
          printf -v "$key" '%s' "$value"
          export "$key"
        fi
        ;;
    esac
  done < "$file"
}

load_layout_if_present

SRM_PREFIX=${SRM_PREFIX:-/opt/server-resource-manager}
SRM_ETC=${SRM_ETC:-/etc/server-resource-manager}
SRM_DATA=${SRM_DATA:-/var/lib/server-resource-manager}
SRM_LOG=${SRM_LOG:-/var/log/server-resource-manager}
SRM_BACKUP_ROOT=${SRM_BACKUP_ROOT:-/var/backups/server-resource-manager}
SRM_UNIT_DIR=${SRM_UNIT_DIR:-/etc/systemd/system}
SRM_USER=${SRM_USER:-srm}
SRM_GROUP=${SRM_GROUP:-srm}
SRM_SERVICE=${SRM_SERVICE:-server-resource-manager}
SRM_JAR_NAME=${SRM_JAR_NAME:-server-resource-manager.jar}
SRM_JAVA=${SRM_JAVA:-/usr/bin/java}
SRM_SYSTEMCTL=${SRM_SYSTEMCTL:-systemctl}
SRM_CURL=${SRM_CURL:-curl}
SRM_HEALTH_URL=${SRM_HEALTH_URL:-http://127.0.0.1:8080/api/v1/auth/csrf}
SRM_HEALTH_ATTEMPTS=${SRM_HEALTH_ATTEMPTS:-60}
SRM_HEALTH_INTERVAL=${SRM_HEALTH_INTERVAL:-1}
SRM_STOP_ATTEMPTS=${SRM_STOP_ATTEMPTS:-15}
SRM_STOP_INTERVAL=${SRM_STOP_INTERVAL:-1}

SRM_PREFIX=${SRM_PREFIX%/}
SRM_ETC=${SRM_ETC%/}
SRM_DATA=${SRM_DATA%/}
SRM_LOG=${SRM_LOG%/}
SRM_BACKUP_ROOT=${SRM_BACKUP_ROOT%/}
SRM_UNIT_DIR=${SRM_UNIT_DIR%/}

export SRM_PREFIX SRM_ETC SRM_DATA SRM_LOG SRM_BACKUP_ROOT SRM_UNIT_DIR
export SRM_USER SRM_GROUP SRM_SERVICE SRM_JAR_NAME SRM_JAVA SRM_SYSTEMCTL SRM_CURL
export SRM_HEALTH_URL SRM_HEALTH_ATTEMPTS SRM_HEALTH_INTERVAL SRM_STOP_ATTEMPTS SRM_STOP_INTERVAL

if ! command -v sha256sum >/dev/null 2>&1; then
  echo "需要 sha256sum 才能计算备份校验和。" >&2
  exit 1
fi

assert_sandbox_paths() {
  local os
  os=$(uname -s)
  case "$os" in
    Linux) return 0 ;;
  esac
  if [ "${SRM_ALLOW_NON_LINUX:-0}" != 1 ]; then
    echo "当前系统是 ${os}。安装、备份、恢复和升级只在 Linux 上改动服务目录。" >&2
    exit 1
  fi
  local path
  for path in "$SRM_PREFIX" "$SRM_ETC" "$SRM_DATA" "$SRM_LOG" "$SRM_BACKUP_ROOT" "$SRM_UNIT_DIR"; do
    case "$path" in
      /opt|/opt/*|/etc|/etc/*|/var|/var/*|/usr|/usr/*)
        echo "非 Linux 环境拒绝操作系统目录：${path}" >&2
        exit 1
        ;;
    esac
  done
}

checksum_file() {
  local out
  out=$(sha256sum -- "$1") || return 1
  printf '%s\n' "${out%% *}"
}

manifest_value() {
  local file=$1 key=$2 line
  line=$(grep -E "^${key}=" "$file" 2>/dev/null | head -n 1 || true)
  line=${line%$'\r'}
  printf '%s\n' "${line#*=}"
}

service_state() {
  "$SRM_SYSTEMCTL" is-active "$SRM_SERVICE" 2>/dev/null || true
}

service_busy() {
  case "$(service_state)" in
    active|activating|deactivating) return 0 ;;
    *) return 1 ;;
  esac
}

assert_stopped() {
  if service_busy; then
    echo "服务仍在运行。冷备必须在服务停止后复制数据目录，不能复制正在写入的 H2 文件。" >&2
    return 1
  fi
}

assert_no_lock() {
  local locks
  locks=$(find "$SRM_DATA" -name '*.lock.db' -print 2>/dev/null || true)
  if [ -n "$locks" ]; then
    echo "数据目录仍有 H2 锁文件，拒绝复制可能正在写入的数据库：" >&2
    printf '%s\n' "$locks" >&2
    return 1
  fi
}

stop_for_maintenance() {
  echo "关闭控制并停止服务。控制开关只在进程内存中，停止后下次启动为 OFF，不自动恢复负载。" >&2
  "$SRM_SYSTEMCTL" stop "$SRM_SERVICE" || true
  "$SRM_SYSTEMCTL" reset-failed "$SRM_SERVICE" >/dev/null 2>&1 || true
  local attempt=1
  while [ "$attempt" -le "$SRM_STOP_ATTEMPTS" ]; do
    if ! service_busy; then
      assert_no_lock || return 1
      return 0
    fi
    attempt=$((attempt + 1))
    if [ "$SRM_STOP_INTERVAL" -gt 0 ]; then
      sleep "$SRM_STOP_INTERVAL"
    fi
  done
  echo "服务仍未停止，拒绝复制数据库。" >&2
  return 1
}

start_and_health() {
  if ! "$SRM_SYSTEMCTL" start "$SRM_SERVICE"; then
    echo "服务未能启动。" >&2
    return 1
  fi
  local attempt=1 state
  while [ "$attempt" -le "$SRM_HEALTH_ATTEMPTS" ]; do
    state=$(service_state)
    if [ "$state" = "failed" ]; then
      echo "服务进入 failed，健康检查未通过。" >&2
      return 1
    fi
    if [ "$state" = "active" ] && "$SRM_CURL" -fsS --max-time 2 "$SRM_HEALTH_URL" >/dev/null 2>&1; then
      return 0
    fi
    attempt=$((attempt + 1))
    if [ "$SRM_HEALTH_INTERVAL" -gt 0 ]; then
      sleep "$SRM_HEALTH_INTERVAL"
    fi
  done
  echo "健康检查未通过：${SRM_HEALTH_URL}" >&2
  return 1
}

reject_backup_path() {
  case "$1" in
    data/*|config/*|jar/*) ;;
    *) echo "备份路径不在 data、config 或 jar 下：$1" >&2; return 1 ;;
  esac
  case "$1" in
    *..*|/*|*\\*) echo "备份路径不合法：$1" >&2; return 1 ;;
  esac
}

live_path() {
  case "$1" in
    data/*) printf '%s\n' "$SRM_DATA/${1#data/}" ;;
    config/*) printf '%s\n' "$SRM_ETC/${1#config/}" ;;
    jar/*) printf '%s\n' "$SRM_PREFIX/${1#jar/}" ;;
    *) echo "无法映射备份路径：$1" >&2; return 1 ;;
  esac
}

check_checksum_list() {
  local root=$1 line sum rel got
  [ -f "$root/CHECKSUMS" ] || { echo "备份缺少 CHECKSUMS：${root}" >&2; return 1; }
  while IFS= read -r line || [ -n "$line" ]; do
    line=${line%$'\r'}
    [ -z "$line" ] && continue
    sum=${line%% *}
    rel=${line#* }
    rel=${rel# }
    reject_backup_path "$rel" || return 1
    [ -f "$root/$rel" ] || { echo "备份缺少文件：${rel}" >&2; return 1; }
    got=$(checksum_file "$root/$rel") || return 1
    if [ "$got" != "$sum" ]; then
      echo "校验和不匹配：${rel}" >&2
      return 1
    fi
  done < "$root/CHECKSUMS"
}

verify_backup() {
  local src=$1 id jar_sum manifest_jar pair_sum
  [ -d "$src" ] || { echo "备份不存在：${src}" >&2; return 1; }
  [ -f "$src/MANIFEST" ] || { echo "备份缺少 MANIFEST：${src}" >&2; return 1; }
  check_checksum_list "$src" || return 1
  id=$(manifest_value "$src/MANIFEST" backup_id)
  if [ "$id" != "$(basename "$src")" ]; then
    echo "备份编号与目录不一致。" >&2
    return 1
  fi
  jar_sum=$(checksum_file "$src/jar/$SRM_JAR_NAME") || return 1
  manifest_jar=$(manifest_value "$src/MANIFEST" jar_sha256)
  pair_sum=$(manifest_value "$src/data/release-pair" jar_sha256)
  if [ -z "$jar_sum" ] || [ "$jar_sum" != "$manifest_jar" ] || [ "$jar_sum" != "$pair_sum" ]; then
    echo "备份中的 JAR 与 release-pair 不是同一对，拒绝使用。旧 JAR 不能打开另一份数据库。" >&2
    return 1
  fi
}

verify_live_against() {
  local src=$1 line sum rel live got
  while IFS= read -r line || [ -n "$line" ]; do
    line=${line%$'\r'}
    [ -z "$line" ] && continue
    sum=${line%% *}
    rel=${line#* }
    rel=${rel# }
    live=$(live_path "$rel") || return 1
    [ -f "$live" ] || { echo "恢复后缺少文件：${rel}" >&2; return 1; }
    got=$(checksum_file "$live") || return 1
    if [ "$got" != "$sum" ]; then
      echo "恢复后的文件与备份不一致：${rel}" >&2
      return 1
    fi
  done < "$src/CHECKSUMS"
}

installed_pair_matches() {
  local pair actual
  [ -f "$SRM_DATA/release-pair" ] || return 1
  pair=$(manifest_value "$SRM_DATA/release-pair" jar_sha256)
  actual=$(checksum_file "$SRM_PREFIX/$SRM_JAR_NAME") || return 1
  [ -n "$pair" ] && [ "$pair" = "$actual" ]
}

write_checksums() {
  local root=$1 out=$2 file rel sum
  : > "$out"
  find "$root" -type f ! -name CHECKSUMS ! -name MANIFEST -print | LC_ALL=C sort | while IFS= read -r file; do
    [ -n "$file" ] || continue
    rel=${file#"$root"/}
    sum=$(checksum_file "$file") || return 1
    printf '%s  %s\n' "$sum" "$rel"
  done > "$out"
}

create_backup() {
  assert_stopped || return 1
  assert_no_lock || return 1
  [ -f "$SRM_PREFIX/$SRM_JAR_NAME" ] || { echo "缺少正在使用的 JAR。" >&2; return 1; }
  [ -f "$SRM_DATA/release-pair" ] || { echo "数据目录缺少 release-pair，拒绝备份不成对的 JAR 和数据库。" >&2; return 1; }
  local id dest
  id=$(date -u +%Y%m%dT%H%M%SZ)-${RANDOM}
  dest="$SRM_BACKUP_ROOT/$id"
  if [ -e "$dest" ]; then
    id="${id}-${RANDOM}"
    dest="$SRM_BACKUP_ROOT/$id"
  fi
  mkdir -p "$dest/data" "$dest/config" "$dest/jar"
  cp -a "$SRM_DATA"/. "$dest/data"/
  cp -a "$SRM_ETC"/. "$dest/config"/
  cp -a "$SRM_PREFIX/$SRM_JAR_NAME" "$dest/jar/$SRM_JAR_NAME"
  if ! write_checksums "$dest" "$dest/CHECKSUMS" || [ ! -s "$dest/CHECKSUMS" ]; then
    echo "备份里没有文件。" >&2
    rm -rf "$dest"
    return 1
  fi
  local jar_sum sums_sum
  jar_sum=$(checksum_file "$dest/jar/$SRM_JAR_NAME") || return 1
  sums_sum=$(checksum_file "$dest/CHECKSUMS") || return 1
  cat > "$dest/MANIFEST" <<EOF
backup_id=${id}
created_at=$(date -u +%Y-%m-%dT%H:%M:%SZ)
jar_sha256=${jar_sum}
checksums_sha256=${sums_sum}
EOF
  if ! verify_backup "$dest"; then
    rm -rf "$dest"
    return 1
  fi
  printf '%s\n' "$id"
}

copy_backup_into_place() {
  local src=$1
  mkdir -p "$SRM_DATA" "$SRM_ETC" "$SRM_PREFIX"
  cp -a "$src/data"/. "$SRM_DATA"/
  cp -a "$src/config"/. "$SRM_ETC"/
  cp -a "$src/jar/$SRM_JAR_NAME" "$SRM_PREFIX/$SRM_JAR_NAME"
}

undo_restore() {
  local aside=$1
  rm -rf "$SRM_DATA" "$SRM_ETC"
  if [ -f "$SRM_PREFIX/$SRM_JAR_NAME" ]; then
    rm -f "$SRM_PREFIX/$SRM_JAR_NAME"
  fi
  mv "$aside/data" "$SRM_DATA"
  mv "$aside/config" "$SRM_ETC"
  if [ -f "$aside/$SRM_JAR_NAME" ]; then
    mv "$aside/$SRM_JAR_NAME" "$SRM_PREFIX/$SRM_JAR_NAME"
  fi
}

restore_tree() {
  local id=$1
  local src="$SRM_BACKUP_ROOT/$id"
  verify_backup "$src" || return 1
  assert_stopped || return 1
  assert_no_lock || return 1
  local aside="$SRM_BACKUP_ROOT/.aside-${id}-$$"
  rm -rf "$aside"
  mkdir -p "$aside"
  mv "$SRM_DATA" "$aside/data"
  mv "$SRM_ETC" "$aside/config"
  if [ -f "$SRM_PREFIX/$SRM_JAR_NAME" ]; then
    mv "$SRM_PREFIX/$SRM_JAR_NAME" "$aside/$SRM_JAR_NAME"
  fi
  if ! copy_backup_into_place "$src"; then
    echo "恢复复制失败，正在退回覆盖前的文件。" >&2
    undo_restore "$aside"
    return 1
  fi
  if ! verify_live_against "$src" || ! installed_pair_matches; then
    echo "恢复后的 JAR 与数据库不是这份备份中的一对，正在退回覆盖前的文件。" >&2
    undo_restore "$aside"
    return 1
  fi
  rm -rf "$aside"
}

exec_service() {
  if ! installed_pair_matches; then
    echo "拒绝启动：当前 JAR 与数据目录 release-pair 不一致。旧 JAR 不能直接打开已升级的数据库，请用 restore.sh 恢复同一备份。" >&2
    exit 1
  fi
  exec "$SRM_JAVA" -jar "$SRM_PREFIX/$SRM_JAR_NAME" \
    --spring.config.additional-location="file:${SRM_ETC}/application.yml"
}

preflight_report() {
  local kernel=未知 mem=未知 jdk=未知 disk=未知 cgroup=未知
  kernel=$(uname -r 2>/dev/null || printf '%s' "未知")
  if [ -r /proc/meminfo ]; then
    mem=$(awk '/^MemTotal:/{print $2 " " $3; exit}' /proc/meminfo)
    [ -n "$mem" ] || mem=未知
  fi
  if [ -x "$SRM_JAVA" ] || [ -f "$SRM_JAVA" ]; then
    jdk=$("$SRM_JAVA" -version 2>&1 || true)
    jdk=${jdk%%$'\n'*}
    [ -n "$jdk" ] || jdk=未知
  fi
  if command -v df >/dev/null 2>&1 && [ -d "$SRM_DATA" ]; then
    disk=$(df -Pk "$SRM_DATA" 2>/dev/null | awk 'NR==2 {print $4 " KiB free"}' || true)
    [ -n "$disk" ] || disk=未知
  elif command -v df >/dev/null 2>&1; then
    disk=$(df -Pk . 2>/dev/null | awk 'NR==2 {print $4 " KiB free"}' || true)
    [ -n "$disk" ] || disk=未知
  fi
  if [ -d /sys/fs/cgroup ]; then
    if [ -f /sys/fs/cgroup/cgroup.controllers ] || [ -f /sys/fs/cgroup/unified/cgroup.controllers ]; then
      cgroup=v2
    else
      cgroup=v1或混合
    fi
    if [ -r /sys/fs/cgroup/memory.max ]; then
      cgroup="${cgroup} memory.max=$(cat /sys/fs/cgroup/memory.max)"
    fi
  fi
  printf 'kernel=%s\n' "$kernel"
  printf 'memTotal=%s\n' "$mem"
  printf 'jdk=%s\n' "$jdk"
  printf 'disk=%s\n' "$disk"
  printf 'cgroup=%s\n' "$cgroup"
  printf 'listen=%s\n' "127.0.0.1:8080"
  printf 'controlCapable=%s\n' "安装脚本不判断为已通过，由服务启动时诊断"
}

write_release_pair() {
  local sum
  sum=$(checksum_file "$SRM_PREFIX/$SRM_JAR_NAME") || return 1
  printf 'jar_sha256=%s\n' "$sum" > "$SRM_DATA/release-pair"
}

install_new_jar() {
  local source=$1
  [ -f "$source" ] || { echo "新 JAR 不存在：${source}" >&2; return 1; }
  cp -a "$source" "$SRM_PREFIX/$SRM_JAR_NAME"
  chmod 644 "$SRM_PREFIX/$SRM_JAR_NAME" || true
  write_release_pair
}
