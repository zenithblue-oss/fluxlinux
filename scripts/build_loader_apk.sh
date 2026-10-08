#!/usr/bin/env bash
# Build app/src/main/assets/loader.apk (termux-x11 shell loader) from
# native/loader/Loader.java: javac + d8 → zip holding classes.dex.
# Env: ANDROID_HOME (or D8=/path/to/d8). d8 is pinned to build-tools
# $D8_BUILD_TOOLS (default 30.0.3, installed by the termux-am gradle build) so
# classes.dex does not depend on whichever build-tools happen to be installed
# (reproducible F-Droid builds).
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
SDK="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}"
D8="${D8:-$SDK/build-tools/${D8_BUILD_TOOLS:-30.0.3}/d8}"
[[ -x "$D8" ]] || { echo "error: d8 not found at $D8 (set ANDROID_HOME, D8_BUILD_TOOLS or D8)" >&2; exit 1; }
OUT="$ROOT/app/src/main/assets/loader.apk"
TMP="$(mktemp -d "${TMPDIR:-/tmp}/loader.XXXXXX")"
trap 'rm -rf "$TMP"' EXIT

javac --release 8 -nowarn -d "$TMP/classes" "$ROOT/native/loader/Loader.java"
"$D8" --release --min-api 26 --no-desugaring --output "$TMP/loader.zip" \
  "$TMP/classes/com/termux/x11/Loader.class"
cp -f "$TMP/loader.zip" "$OUT"
echo "[*] loader.apk → $OUT ($(wc -c < "$OUT") bytes)"
