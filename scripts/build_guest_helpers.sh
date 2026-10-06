#!/usr/bin/env bash
# Build the two aarch64 glibc guest helpers shipped as app assets:
#   scripts/common/setup/bwrap-proot-shim   (from bwrap-proot-shim.c)
#   scripts/opensuse/common/libevp_md2.so   (from native/guest/libevp_md2.c)
# Env: CC (default aarch64-linux-gnu-gcc; Debian: gcc-aarch64-linux-gnu).
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
CC="${CC:-aarch64-linux-gnu-gcc}"
A="$ROOT/app/src/main/assets/scripts"

$CC -O2 -s -o "$A/common/setup/bwrap-proot-shim" "$A/common/setup/bwrap-proot-shim.c"
$CC -O2 -s -shared -fPIC -nostdlib \
  -Wl,--version-script="$ROOT/native/guest/libevp_md2.map" \
  -o "$A/opensuse/common/libevp_md2.so" "$ROOT/native/guest/libevp_md2.c"
ls -l "$A/common/setup/bwrap-proot-shim" "$A/opensuse/common/libevp_md2.so"
