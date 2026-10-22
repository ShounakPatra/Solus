#!/usr/bin/env bash
set -euo pipefail
task_root="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)"
task_ndk="${1:?Android NDK directory required}"
task_output="${2:?JNI output directory required}"
case "$(uname -s)" in
  Linux) task_host=linux-x86_64 ;;
  Darwin) task_host=darwin-x86_64 ;;
  *) echo 'Use build-candle.ps1 on Windows' >&2; exit 1 ;;
esac
task_bin="$task_ndk/toolchains/llvm/prebuilt/$task_host/bin"
export CARGO_TARGET_AARCH64_LINUX_ANDROID_LINKER="$task_bin/aarch64-linux-android28-clang"
export CC_aarch64_linux_android="$CARGO_TARGET_AARCH64_LINUX_ANDROID_LINKER"
export AR_aarch64_linux_android="$task_bin/llvm-ar"
export CARGO_BUILD_JOBS="${CARGO_BUILD_JOBS:-2}"
cargo build --locked --manifest-path "$task_root/app/src/main/rust/Cargo.toml" --target aarch64-linux-android --release
mkdir -p -- "$task_output/arm64-v8a"
cp -- "$task_root/app/src/main/rust/target/aarch64-linux-android/release/libsolus_candle.so" "$task_output/arm64-v8a/"
