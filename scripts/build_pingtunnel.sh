#!/usr/bin/env bash
#
# 交叉编译 pingtunnel client 为 Android 可执行文件。
#
# 产物：app/src/main/jniLibs/<abi>/libpingtunnel.so
#   —— 实际是 Go 可执行文件，借 .so 名装入 APK；安装后位于 nativeLibraryDir，
#      该目录在 API 29+ 仍允许 exec（files/ 目录已不允许）。
#   —— 需在 app/build.gradle.kts 中保持 useLegacyPackaging = true 让 so 落盘。
#
# 免 root 原理：所用的 pingtunnel 版本包含上游 commit 699904d
# （icmp_listen_android.go），在 Android 上以无特权 ICMP datagram ping socket
# （icmp.ListenPacket("udp4") = SOCK_DGRAM + IPPROTO_ICMP）替代 raw socket，
# 普通 App 持有 INTERNET 权限即可收发自定义 ICMP echo 包。
#
# 说明：
#   开启 CGO（链接 bionic）以使用系统解析器：Android 没有 /etc/resolv.conf，
#   纯 Go 解析器（CGO_ENABLED=0）无法解析 server 域名。
#
# 环境要求：Go 1.26+、Android NDK。
# 可用环境变量：PINGTUNNEL_ABIS（默认 "arm64-v8a x86_64"，与 abiFilters 一致）、
#               PINGTUNNEL_DIR（默认 third_party/pingtunnel，可指向本地源码）、
#               PINGTUNNEL_COMMIT（从 GitHub 检出的固定版本）、ANDROID_NDK_HOME。
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
PT_DIR="${PINGTUNNEL_DIR:-$ROOT/third_party/pingtunnel}"
# pin 到包含 Android ping socket 支持的已知稳定提交（699904d 之后）
PT_COMMIT="${PINGTUNNEL_COMMIT:-df16e52}"
OUT_DIR="$ROOT/app/src/main/jniLibs"
ABIS=(${PINGTUNNEL_ABIS:-arm64-v8a x86_64})

if [ ! -d "$PT_DIR" ]; then
    git clone https://github.com/esrrhs/pingtunnel.git "$PT_DIR"
fi

# 与模板 build_spp.sh 不同：这里固定到已知提交，不追上游最新，保证行为稳定。
# PINGTUNNEL_DIR 指向非 git 目录（如解压源码）时跳过检出。
if [ -d "$PT_DIR/.git" ]; then
    cd "$PT_DIR"
    if [ "$(git rev-parse --short HEAD 2>/dev/null || true)" != "$PT_COMMIT" ]; then
        echo "==> 检出 pingtunnel@$PT_COMMIT"
        git fetch origin || git fetch --unshallow origin || true
        git checkout -q "$PT_COMMIT"
    fi
    echo "==> 下载 Go 依赖"
    go mod download
    cd "$ROOT"
fi

NDK="${ANDROID_NDK_HOME:-}"
if [ -z "$NDK" ]; then
    NDK=$(ls -d "${ANDROID_HOME:-$HOME/Library/Android/sdk}/ndk/"* 2>/dev/null | sort -V | tail -1)
fi
[ -n "$NDK" ] || { echo "错误：未找到 Android NDK（请设置 ANDROID_NDK_HOME）" >&2; exit 1; }
HOST_TAG=darwin-x86_64
[ "$(uname -s)" = "Linux" ] && HOST_TAG=linux-x86_64
TOOLCHAIN="$NDK/toolchains/llvm/prebuilt/$HOST_TAG/bin"
# 部分发行版（含 Apple silicon 上的官方 NDK）目录名仍为 darwin-x86_64，
# 按实际存在的目录探测，缺哪个换哪个
if [ ! -d "$TOOLCHAIN" ] && [ -d "$NDK/toolchains/llvm/prebuilt/darwin-arm64/bin" ]; then
    TOOLCHAIN="$NDK/toolchains/llvm/prebuilt/darwin-arm64/bin"
fi
[ -d "$TOOLCHAIN" ] || { echo "错误：NDK 工具链不存在：$TOOLCHAIN" >&2; exit 1; }

BUILD_TIME="$(date -u +"%Y-%m-%dT%H:%M:%SZ")"
GIT_COMMIT="$(git -C "$PT_DIR" rev-parse --short HEAD 2>/dev/null || echo "$PT_COMMIT")"
# CGO 经外部链接器（clang）最终链接，要求其按 16KB 页对齐 LOAD 段，
# 满足 Android 15+ 的 16KB page size 要求（否则系统提示应用不合规）。
LDFLAGS="-s -w -X 'github.com/esrrhs/pingtunnel.BuildTime=$BUILD_TIME' -X 'github.com/esrrhs/pingtunnel.GitCommit=$GIT_COMMIT' -X 'github.com/esrrhs/pingtunnel.GitBranch=android' -extldflags=-Wl,-z,max-page-size=16384"

# pingtunnel 的 main 包在 cmd/ 子目录
cd "$PT_DIR/cmd"

for ABI in "${ABIS[@]}"; do
    case "$ABI" in
        arm64-v8a)   GOARCH=arm64; CLANG=aarch64-linux-android26-clang ;;
        armeabi-v7a) GOARCH=arm;   CLANG=armv7a-linux-androideabi26-clang ;;
        x86_64)      GOARCH=amd64; CLANG=x86_64-linux-android26-clang ;;
        x86)         GOARCH=386;   CLANG=i686-linux-android26-clang ;;
        *) echo "错误：未知 ABI $ABI" >&2; exit 1 ;;
    esac
    echo "==> 编译 pingtunnel ($ABI, go$GOARCH)"
    mkdir -p "$OUT_DIR/$ABI"
    CC="$TOOLCHAIN/$CLANG" \
    GOOS=android GOARCH="$GOARCH" CGO_ENABLED=1 \
        go build -trimpath -ldflags "$LDFLAGS" -o "$OUT_DIR/$ABI/libpingtunnel.so" .
done

echo "完成："
ls -lh "$OUT_DIR"/*/libpingtunnel.so
