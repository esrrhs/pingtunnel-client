#!/usr/bin/env bash
# 在模拟器就绪后执行：准备设备（root、降载、内嵌 pingtunnel server）、
# 起宿主机演示站点，然后交给 record_demo.py 一镜到底录屏。
#
# 本地：先启动 AVD 并 adb devices 能看到设备，然后 bash scripts/ci_record_demo.sh
# CI  ：由 .github/workflows/record-demo.yml 在 emulator-runner 里调用。
set -euxo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"
ADB="${ADB:-adb}"
PKG=com.esrrhs.pingtunnel.client
APK="${DEMO_APK:-app/build/outputs/apk/debug/app-debug.apk}"
PORT=19080

# ---------------- 设备准备 ----------------
"$ADB" root >/dev/null 2>&1 || true
"$ADB" wait-for-device
"$ADB" shell input keyevent 82 || true
"$ADB" shell settings put global window_animation_scale 0
"$ADB" shell settings put global transition_animation_scale 0
"$ADB" shell settings put global animator_duration_scale 0

# 录制期间禁用抢 CPU 的非必需后台包（保留 Chrome / 系统 UI / Launcher）
for pkg in \
  com.google.android.tts \
  com.google.android.googlequicksearchbox \
  com.google.android.as \
  com.google.android.apps.wellbeing \
  com.google.android.apps.messaging \
  com.google.android.ims \
  com.google.android.projection.gearhead ; do
  "$ADB" shell pm disable-user --user 0 "$pkg" >/dev/null 2>&1 || true
done

# Chrome 降载：单渲染进程、关闭 Vulkan（SwiftShader Vulkan 是 CPU 大头）
"$ADB" shell "echo 'chrome --renderer-process-limit=1 --disable-features=Vulkan' > /data/local/tmp/chrome-command-line" || true

# ---------------- 构建并启动设备内 pingtunnel server ----------------
ABI="$("$ADB" shell getprop ro.product.cpu.abi | tr -d '\r')"
case "$ABI" in
  arm64-v8a) GOARCH=arm64; TRIPLE=aarch64-linux-android26-clang ;;
  x86_64)    GOARCH=amd64; TRIPLE=x86_64-linux-android26-clang ;;
  *) echo "不支持的 ABI: $ABI" >&2; exit 1 ;;
esac

NDK="${ANDROID_NDK_HOME:-}"
if [ -z "$NDK" ]; then
  NDK=$(ls -d "${ANDROID_HOME:-$HOME/Library/Android/sdk}/ndk/"* 2>/dev/null | sort -V | tail -1)
fi
[ -n "$NDK" ] || { echo "未找到 Android NDK" >&2; exit 1; }
HOST_TAG=darwin-x86_64
[ "$(uname -s)" = "Linux" ] && HOST_TAG=linux-x86_64
[ -d "$NDK/toolchains/llvm/prebuilt/$HOST_TAG" ] || HOST_TAG=darwin-arm64
CC="$NDK/toolchains/llvm/prebuilt/$HOST_TAG/bin/$TRIPLE"
[ -x "$CC" ] || { echo "NDK clang 不存在: $CC" >&2; exit 1; }

PT_DIR="$ROOT/third_party/pingtunnel"
OVERLAY=/tmp/pingtunnel-demo-overlay.json
mkdir -p "$ROOT/build/demo"
cat > "$OVERLAY" <<EOF
{"Replace": {"$PT_DIR/icmp_listen_android.go": "$ROOT/scripts/e2e/icmp_listen_android_raw.go"}}
EOF

# CGO 打开 bionic 解析器：设备上没有 /etc/resolv.conf，纯 Go 解析器无法解析域名
echo "==> 构建 pingtunnel server ($ABI)"
(
  cd "$PT_DIR/cmd"
  CGO_ENABLED=1 GOOS=android GOARCH="$GOARCH" CC="$CC" \
    go build -overlay "$OVERLAY" -trimpath \
    -ldflags "-s -w -extldflags=-Wl,-z,max-page-size=16384" \
    -o "$ROOT/build/demo/pingtunnel-server" .
)

"$ADB" shell "pkill -f '[p]ingtunnel-server -type'" 2>/dev/null || true
"$ADB" push "$ROOT/build/demo/pingtunnel-server" /data/local/tmp/pingtunnel-server >/dev/null
"$ADB" shell "chmod 755 /data/local/tmp/pingtunnel-server"
# cwd 必须是可写目录：loggo 会尝试在 cwd 打开 pingtunnel.stderr 等日志文件
# adb shell 有时会挂在后台进程上不返回，用后台 adb 客户端 + 超时 kill 让它脱离
"$ADB" shell "cd /data/local/tmp && setsid ./pingtunnel-server -type server -password e2e-test-pass -loglevel info >pt-server.log 2>&1 < /dev/null &" &
ADB_START_PID=$!
sleep 4
kill "$ADB_START_PID" 2>/dev/null || true
wait "$ADB_START_PID" 2>/dev/null || true
sleep 1
"$ADB" shell "pgrep -f '[p]ingtunnel-server -type'" \
  || { "$ADB" shell "tail -20 /data/local/tmp/pt-server.log"; exit 1; }

# root 自身的出站流量必须直连，否则 server 拨号会被自己的 VPN 抓回形成环路
"$ADB" shell "ip rule del pref 12999 2>/dev/null; ip rule add pref 12999 uidrange 0-0 lookup eth0"

# ---------------- 宿主机演示站点 ----------------
python3 -u scripts/demo_site_server.py "$PORT" &
SITE_PID=$!

cleanup() {
  kill "$SITE_PID" 2>/dev/null || true
  "$ADB" shell "pkill -f '[p]ingtunnel-server -type'" 2>/dev/null || true
}
trap cleanup EXIT
sleep 1

export DEMO_APK="$APK"
export ADB
python3 -u scripts/record_demo.py
