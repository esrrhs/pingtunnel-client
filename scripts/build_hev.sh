#!/usr/bin/env bash
#
# 用 NDK ndk-build 编译 hev-socks5-tunnel（含官方 hev-jni.c JNI 桥接）。
#
# 产物：app/src/main/jniLibs/<abi>/libhev-socks5-tunnel.so
#
# 环境要求：Android NDK；首次运行会克隆源码并初始化子模块
# （third-part/lwip、yaml、hev-task-system 与 src/core）。
# 可用环境变量：HEV_ABIS（默认 "arm64-v8a x86_64"）、HEV_DIR、ANDROID_NDK_HOME。
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
HEV_DIR="${HEV_DIR:-$ROOT/third_party/hev-socks5-tunnel}"
OUT_DIR="$ROOT/app/src/main/jniLibs"
ABIS=(${HEV_ABIS:-arm64-v8a x86_64})

# 固定上游版本，确保补丁覆盖的文件与预期完全一致
PINNED_COMMIT=07bea57d20f71c9804055c2e2a0787d65a7277cb

if [ ! -d "$HEV_DIR" ]; then
    git clone https://github.com/heiher/hev-socks5-tunnel.git "$HEV_DIR"
fi
cd "$HEV_DIR"
git fetch origin "$PINNED_COMMIT" >/dev/null 2>&1 || true
git checkout -q "$PINNED_COMMIT"
git submodule update --init --recursive

# 应用本地补丁（patches/hev-socks5-tunnel）：给 JNI 增加 TProxyGetSessions
# 会话表导出（上游只注册了 4 个 JNI 方法，App 的实时连接页依赖该接口），
# 并把观测字段收敛进 HevSocks5SessionData，TCP 与 UDP 会话统一导出。
# 仅覆盖 src 根下对应的 7 个文件，misc/ 与 core/ 子模块不动。
PATCH_SRC="$ROOT/patches/hev-socks5-tunnel/src"
cp "$PATCH_SRC"/*.c "$PATCH_SRC"/*.h ./src/
echo "==> applied local session-export patch"

NDK="${ANDROID_NDK_HOME:-}"
if [ -z "$NDK" ]; then
    NDK=$(ls -d "${ANDROID_HOME:-$HOME/Library/Android/sdk}/ndk/"* 2>/dev/null | sort -V | tail -1)
fi
[ -n "$NDK" ] || { echo "错误：未找到 Android NDK（请设置 ANDROID_NDK_HOME）" >&2; exit 1; }

echo "==> ndk-build hev-socks5-tunnel (${ABIS[*]})"
# 仓库目录名不是 ndk-build 约定的 jni/，需显式指定项目路径与构建脚本
"$NDK/ndk-build" -j"$(getconf _NPROCESSORS_ONLN 2>/dev/null || sysctl -n hw.ncpu)" \
    NDK_PROJECT_PATH=. \
    NDK_APPLICATION_MK=Application.mk \
    APP_BUILD_SCRIPT=Android.mk \
    APP_ABI="${ABIS[*]}" \
    APP_PLATFORM=android-26 \
    APP_CFLAGS="-O3" \
    APP_SUPPORT_FLEXIBLE_PAGE_SIZES=true

for ABI in "${ABIS[@]}"; do
    mkdir -p "$OUT_DIR/$ABI"
    cp "libs/$ABI/libhev-socks5-tunnel.so" "$OUT_DIR/$ABI/"
done

echo "完成："
ls -lh "$OUT_DIR"/*/libhev-socks5-tunnel.so
