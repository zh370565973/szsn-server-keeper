#!/bin/bash
# 用已经构建好的 JAR 做断网冷启动检查。
# 只调用 JDK 的 javac 和 java。启动前把 PATH 改成空目录，因此找不到 npm 或 Maven。
# 不下载字体或 CDN，不发送遥测，也不分配 CPU/内存负载。
set -euo pipefail

HERE=$(cd "$(dirname "$0")" && pwd)
jar=""
work=""
port=""
block=0

usage() {
  echo "用法：verify-offline.sh --jar <文件> --work <空目录> [--port <端口>] [--block-outbound]" >&2
}

while [ $# -gt 0 ]; do
  case "$1" in
    --jar)
      jar=${2:-}
      shift 2
      ;;
    --work)
      work=${2:-}
      shift 2
      ;;
    --port)
      port=${2:-}
      shift 2
      ;;
    --block-outbound)
      block=1
      shift
      ;;
    *)
      echo "未知参数：$1" >&2
      usage
      exit 2
      ;;
  esac
done

if [ -z "$jar" ] || [ -z "$work" ]; then
  usage
  exit 2
fi
if [ -n "$port" ]; then
  case "$port" in
    *[!0-9]*)
      echo "端口必须是数字。" >&2
      exit 2
      ;;
  esac
fi

native_path() {
  if command -v cygpath >/dev/null 2>&1; then
    cygpath -m "$1"
  else
    printf '%s\n' "$1"
  fi
}

jar_native=$(native_path "$jar")
work_native=$(native_path "$work")
probe_native=$(native_path "$HERE/OfflineProbe.java")
if [ -f "$jar_native" ]; then
  jar=$jar_native
fi
if [ ! -f "$jar" ]; then
  echo "找不到 JAR：${jar}" >&2
  exit 2
fi
if [ -f "$probe_native" ]; then
  probe=$probe_native
else
  probe=$HERE/OfflineProbe.java
fi
if [ ! -f "$probe" ]; then
  echo "找不到 OfflineProbe.java。" >&2
  exit 1
fi

if [ -e "$work_native" ]; then
  if [ ! -d "$work_native" ] || [ -n "$(ls -A "$work_native")" ]; then
    echo "工作目录必须为空：${work_native}" >&2
    exit 2
  fi
fi
work=$work_native

real_java=""
if [ -n "${JAVA_HOME:-}" ] && [ -x "${JAVA_HOME}/bin/java" ]; then
  real_java=${JAVA_HOME}/bin/java
elif [ -n "${JAVA_HOME:-}" ] && [ -x "${JAVA_HOME}/bin/java.exe" ]; then
  real_java=${JAVA_HOME}/bin/java.exe
else
  real_java=$(command -v java || true)
fi
if [ -z "$real_java" ]; then
  echo "找不到 java。" >&2
  exit 1
fi
case "$real_java" in
  *java.exe) real_javac=${real_java%java.exe}javac.exe ;;
  *java) real_javac=${real_java%java}javac ;;
  *)
    echo "无法定位 javac。" >&2
    exit 1
    ;;
esac
if [ ! -x "$real_javac" ]; then
  echo "找不到 javac：${real_javac}" >&2
  exit 1
fi

bindir=$(mktemp -d)
classes=$(mktemp -d)
cleanup() {
  rm -rf "$bindir" "$classes"
}
trap cleanup EXIT

classes_native=$(native_path "$classes")
"$real_javac" -encoding UTF-8 -d "$classes_native" "$probe"

unset JAVA_TOOL_OPTIONS
unset _JAVA_OPTIONS
unset JAVA_OPTIONS
unset JDK_JAVA_OPTIONS
unset HTTP_PROXY
unset HTTPS_PROXY
unset ALL_PROXY
unset http_proxy
unset https_proxy
unset all_proxy

old_path=$PATH
export PATH=$bindir
hash -r
found=0
if command -v npm >/dev/null 2>&1 || command -v mvn >/dev/null 2>&1; then
  found=1
fi
export PATH=$old_path
hash -r
if [ "$found" -eq 1 ]; then
  echo "隔离后的 PATH 仍能找到 npm 或 Maven。" >&2
  exit 1
fi

probe_args=(--jar "$jar" --work "$work")
if [ -n "$port" ]; then
  probe_args+=(--port "$port")
fi
if [ "$block" -eq 1 ]; then
  probe_args+=(--block-outbound)
fi

export PATH=$bindir
set +e
"$real_java" -Dfile.encoding=UTF-8 -cp "$classes_native" OfflineProbe "${probe_args[@]}"
status=$?
set -e
export PATH=$old_path
exit "$status"
