#!/bin/bash
# 把已经构建好的 JAR、脚本、OpenAPI、依赖清单和部署说明打成离线发布目录，并写 SHA-256。
# 不下载依赖，不启动服务，不分配负载。
set -euo pipefail

jar=""
dependencies=""
openapi=""
deploy_doc=""
scripts=""
out=""
version=""

usage() {
  echo "用法：assemble-release.sh --jar <文件> --dependencies <文件> --openapi <文件> --deploy-doc <文件> --scripts <目录> --out <空目录> --version <版本>" >&2
}

while [ $# -gt 0 ]; do
  case "$1" in
    --jar)
      jar=${2:-}
      shift 2
      ;;
    --dependencies)
      dependencies=${2:-}
      shift 2
      ;;
    --openapi)
      openapi=${2:-}
      shift 2
      ;;
    --deploy-doc)
      deploy_doc=${2:-}
      shift 2
      ;;
    --scripts)
      scripts=${2:-}
      shift 2
      ;;
    --out)
      out=${2:-}
      shift 2
      ;;
    --version)
      version=${2:-}
      shift 2
      ;;
    *)
      echo "未知参数：$1" >&2
      usage
      exit 2
      ;;
  esac
done

require_file() {
  local path=$1 label=$2
  if [ -z "$path" ] || [ ! -f "$path" ]; then
    echo "缺少${label}：${path:-未提供}" >&2
    usage
    exit 2
  fi
}

require_file "$jar" "已构建的 JAR"
require_file "$dependencies" "依赖清单"
require_file "$openapi" "OpenAPI"
require_file "$deploy_doc" "部署说明"
if [ -z "$scripts" ] || [ ! -d "$scripts" ]; then
  echo "缺少脚本目录：${scripts:-未提供}" >&2
  usage
  exit 2
fi
if [ -z "$out" ]; then
  echo "缺少输出目录。" >&2
  usage
  exit 2
fi
if [ -z "$version" ] || ! printf '%s\n' "$version" | grep -Eq '^[0-9A-Za-z][0-9A-Za-z._+-]*$'; then
  echo "版本必须显式给出，且只能包含字母、数字、点、下划线、加号和减号。" >&2
  usage
  exit 2
fi

names="srm-common.sh srm-run install.sh backup.sh restore.sh upgrade.sh server-resource-manager.service application.yml.example README.md"
for name in $names; do
  if [ ! -f "$scripts/$name" ]; then
    echo "脚本目录缺少 ${name}" >&2
    exit 2
  fi
done

if [ -e "$out" ]; then
  if [ ! -d "$out" ]; then
    echo "输出路径不是目录：${out}" >&2
    exit 2
  fi
  if [ -n "$(ls -A "$out")" ]; then
    echo "输出目录必须为空：${out}" >&2
    exit 2
  fi
fi

if ! command -v sha256sum >/dev/null 2>&1; then
  echo "需要 sha256sum 才能写发布校验和。" >&2
  exit 1
fi

checksum_file() {
  local summed
  summed=$(sha256sum -- "$1") || return 1
  printf '%s\n' "${summed%% *}"
}

mkdir -p "$out/deploy"
cp -a "$jar" "$out/server-resource-manager.jar"
cp -a "$dependencies" "$out/DEPENDENCIES.txt"
cp -a "$openapi" "$out/openapi.yaml"
cp -a "$deploy_doc" "$out/DEPLOY.md"
for name in $names; do
  cp -a "$scripts/$name" "$out/deploy/$name"
done

printf '%s\n' \
  DEPENDENCIES.txt \
  DEPLOY.md \
  deploy/README.md \
  deploy/application.yml.example \
  deploy/backup.sh \
  deploy/install.sh \
  deploy/restore.sh \
  deploy/server-resource-manager.service \
  deploy/srm-common.sh \
  deploy/srm-run \
  deploy/upgrade.sh \
  openapi.yaml \
  server-resource-manager.jar \
  | LC_ALL=C sort | while IFS= read -r rel; do
  [ -n "$rel" ] || continue
  sum=$(checksum_file "$out/$rel") || exit 1
  printf '%s  %s\n' "$sum" "$rel"
done > "$out/SHA256SUMS"

if [ ! -s "$out/SHA256SUMS" ]; then
  echo "发布目录里没有可校验的文件。" >&2
  exit 1
fi

jar_sum=$(checksum_file "$out/server-resource-manager.jar")
sums_sum=$(checksum_file "$out/SHA256SUMS")
cat > "$out/MANIFEST" <<EOF
product=server-resource-manager
version=${version}
jar=server-resource-manager.jar
jar_sha256=${jar_sum}
checksums_sha256=${sums_sum}
EOF

echo "发布目录：${out}"
echo "jar_sha256=${jar_sum}"
