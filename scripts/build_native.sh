#!/usr/bin/env bash
#
# 一键构建全部 native 产物（pingtunnel client + hev-socks5-tunnel）。
# 在 ./gradlew assembleDebug 之前必须先执行本脚本。
set -euo pipefail

DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

bash "$DIR/build_pingtunnel.sh"
bash "$DIR/build_hev.sh"

echo
echo "== jniLibs 产物清单 =="
find "$DIR/../app/src/main/jniLibs" -type f -exec ls -lh {} \;
