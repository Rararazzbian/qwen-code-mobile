#!/usr/bin/env bash
# Fetches and stages the on-device runtime: Termux's Android proot build
# (patched to load its libraries from the APK's native library directory),
# an Alpine aarch64 root filesystem, and this repository's Qwen Code bundle.
# Requires curl, ar, tar, sha256sum and patchelf (`pip install patchelf`).
set -euo pipefail

cd "$(dirname "$0")/.."
APP_DIR="$PWD"
REPO_ROOT="$(cd ../.. && pwd)"
CACHE="$APP_DIR/.runtime-cache"
JNI="$APP_DIR/app/src/main/jniLibs/arm64-v8a"
ASSETS="$APP_DIR/app/src/main/assets/runtime"
QWEN_DIST="${QWEN_DIST:-$REPO_ROOT/dist}"

TERMUX=https://packages.termux.dev/apt/termux-main/pool/main
ALPINE=https://dl-cdn.alpinelinux.org/alpine/v3.24/releases/aarch64
DOWNLOADS=(
  "$TERMUX/p/proot/proot_5.1.107.102_aarch64.deb 10a394b585c2fc2b2c8502544f4e5f1be581dc8bfff79e8ba62f16207423e030"
  "$TERMUX/libt/libtalloc/libtalloc_2.5.0_aarch64.deb 556591f43bb773ad8777e1a29522640866a55f95dab71914418b94a8c58ad5a7"
  "$TERMUX/liba/libandroid-shmem/libandroid-shmem_0.7_aarch64.deb 0da3a24d558b93c92bcf8d611e0826a99ff96e396b148e6cdf33b47c47c57ff6"
  "$ALPINE/alpine-minirootfs-3.24.4-aarch64.tar.gz 4b14c18f7e90a063b61462ea1462a5732c154bf6adad594974fe6487cd98a8de"
)

if [ ! -f "$QWEN_DIST/cli.js" ]; then
  echo "Missing $QWEN_DIST/cli.js. Run 'npm run build && npm run bundle' from the repository root first." >&2
  exit 1
fi
command -v patchelf >/dev/null || { echo "patchelf is required (pip install patchelf)." >&2; exit 1; }

mkdir -p "$CACHE" "$JNI" "$ASSETS"
for entry in "${DOWNLOADS[@]}"; do
  read -r url sha <<<"$entry"
  file="$CACHE/$(basename "$url")"
  [ -f "$file" ] || curl -fsSL --retry 3 -o "$file" "$url"
  echo "$sha  $file" | sha256sum -c --quiet -
done

EXTRACT="$CACHE/termux"
rm -rf "$EXTRACT" && mkdir -p "$EXTRACT"
for deb in "$CACHE"/*.deb; do
  (cd "$EXTRACT" && ar x "$deb" data.tar.xz && tar xJf data.tar.xz && rm data.tar.xz)
done
USR="$EXTRACT/data/data/com.termux/files/usr"

# Android only executes files from the APK's native library directory, which
# keeps only lib*.so names, so every file is renamed and its links rewritten.
install -m 755 "$USR/bin/proot" "$JNI/libproot.so"
install -m 755 "$USR/libexec/proot/loader" "$JNI/libproot-loader.so"
install -m 755 "$USR/lib/libtalloc.so.2.5.0" "$JNI/libtalloc.so"
install -m 755 "$USR/lib/libandroid-shmem.so" "$JNI/libandroid-shmem.so"
patchelf --remove-rpath --replace-needed libtalloc.so.2 libtalloc.so "$JNI/libproot.so"
patchelf --remove-rpath --set-soname libtalloc.so "$JNI/libtalloc.so"
patchelf --remove-rpath "$JNI/libandroid-shmem.so"

cp "$CACHE/alpine-minirootfs-3.24.4-aarch64.tar.gz" "$ASSETS/rootfs.tgz"
tar czf "$ASSETS/qwen-code.tgz" -C "$QWEN_DIST" .
(cd "$ASSETS" && sha256sum qwen-code.tgz | cut -c1-16 >qwen-code.version)

echo "Runtime staged:"
ls -la "$JNI" "$ASSETS"
