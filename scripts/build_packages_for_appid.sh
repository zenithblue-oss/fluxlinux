#!/usr/bin/env bash
# Build termux-packages .debs for a FluxLinux applicationId.
#
# Usage:
#   ./scripts/build_packages_for_appid.sh com.ivarna.fluxlinux bash
#   ./scripts/build_packages_for_appid.sh com.ivarna.fluxlinux --list bootstrap-host
#   ./scripts/build_packages_for_appid.sh com.zenithblue.fluxlinux --list bootstrap-host
#   ARCH=arm ./scripts/build_packages_for_appid.sh com.ivarna.fluxlinux coreutils
#
# Env:
#   ARCH                 default aarch64
#   FORCE=1              pass -f (force rebuild package)
#   FORCE_DEPS=1         pass -F (force rebuild package + deps)
#   CONTINUE_ON_FAIL=1   keep going after a package failure
#   TERMUX_PACKAGES_DIR  override path to termux-packages
#   TERMUX_DOCKER_RUN_EXTRA_ARGS  default: --network host --cpus 10 --memory 10g
#   NO_DOCKER=1          run build-package.sh on the host (F-Droid buildserver).
#                        Auto-set when docker is not installed. Host needs the
#                        termux build deps, NDK=<r29 path> and a writable
#                        /data/data/<applicationId> (see com.ivarna.fluxlinux.yml).
#
# FluxLinux package patches live in native/patches/<pkg>/ and are copied into
# the termux-packages package dir before building (submodule stays pristine in git).
set -uo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
CUSTOM_PACKAGE="${1:?usage: $0 <applicationId> <pkg…|--list name>}"
shift

ARCH="${ARCH:-aarch64}"
TP="${TERMUX_PACKAGES_DIR:-$ROOT/native/termux-packages}"
# Resolve symlink so paths match the docker volume root (termux-packages repo).
TP="$(cd "$TP" && pwd -P)"
# termux-packages puts *dependencies* in the default output/ even when -o is set.
# See termux_step_get_dependencies.sh. We always build into TP/output, then
# mirror only packages whose data paths match CUSTOM_PACKAGE into OUT_DIR.
BUILD_OUT_DIR="$TP/output"
OUT_DIR="$ROOT/native/output/${CUSTOM_PACKAGE}"
LOG_DIR="$ROOT/native/output/logs"
LOG="$LOG_DIR/${CUSTOM_PACKAGE//./_}-$(date +%Y%m%dT%H%M%S).log"
LIST_DIR="$ROOT/native/package-lists"

if [[ ! -d "$TP" ]]; then
  echo "error: termux-packages not found at $TP" >&2
  echo "  git submodule update --init native/termux-packages" >&2
  exit 1
fi

mkdir -p "$BUILD_OUT_DIR" "$OUT_DIR" "$LOG_DIR"

# Resolve package list
PKGS=()
if [[ "${1:-}" == "--list" ]]; then
  list_name="${2:?--list requires a name (e.g. bootstrap-host)}"
  list_file="$LIST_DIR/${list_name}.txt"
  if [[ ! -f "$list_file" ]]; then
    # allow bootstrap-host without .txt already handled; also bare name
    if [[ -f "$LIST_DIR/${list_name}" ]]; then
      list_file="$LIST_DIR/${list_name}"
    else
      echo "error: package list not found: $list_file" >&2
      exit 1
    fi
  fi
  while IFS= read -r line || [[ -n "$line" ]]; do
    line="${line%%#*}"
    line="$(echo "$line" | sed 's/^[[:space:]]*//;s/[[:space:]]*$//')"
    [[ -z "$line" ]] && continue
    PKGS+=("$line")
  done < "$list_file"
else
  if [[ $# -lt 1 ]]; then
    echo "error: pass package names or --list bootstrap-host" >&2
    exit 1
  fi
  PKGS=("$@")
fi

echo "[*] applicationId: $CUSTOM_PACKAGE"
echo "[*] arch:          $ARCH"
echo "[*] packages:      ${#PKGS[@]} → ${PKGS[*]}"
echo "[*] build output:  $BUILD_OUT_DIR  (inside docker volume)"
echo "[*] mirror output: $OUT_DIR"
echo "[*] log:           $LOG"
echo "[*] termux-packages: $TP"

"$ROOT/scripts/set_termux_package_name.sh" "$CUSTOM_PACKAGE" | tee -a "$LOG"

# Overlay FluxLinux patches (native/patches/<pkg>/*.patch) onto the package dirs.
for pdir in "$ROOT"/native/patches/*/; do
  [[ -d "$pdir" ]] || continue
  pkg_dir="$(ls -d "$TP"/{packages,x11-packages,root-packages}/"$(basename "$pdir")" 2>/dev/null | head -1)"
  if [[ -z "$pkg_dir" ]]; then
    echo "error: no termux package for patch dir $pdir" >&2
    exit 1
  fi
  cp -f "$pdir"* "$pkg_dir/"
  echo "[*] overlay $(ls "$pdir" | tr '\n' ' ')→ $pkg_dir" | tee -a "$LOG"
done

if [[ "${NO_DOCKER:-0}" != "1" ]] && ! command -v docker >/dev/null 2>&1; then
  NO_DOCKER=1
fi
if [[ "${NO_DOCKER:-0}" == "1" ]]; then
  BUILDER=(./build-package.sh)
  # As root in a user namespace, tar cannot chown to upstream tarball uids (EINVAL).
  export TAR_OPTIONS="${TAR_OPTIONS:+$TAR_OPTIONS }--no-same-owner"
  # termux defaults JAVA_HOME to Ubuntu's JDK 17 path; fall back to the host javac.
  if [[ ! -d "${TERMUX_JAVA_HOME:-/usr/lib/jvm/java-17-openjdk-amd64}" ]] && command -v javac >/dev/null; then
    export TERMUX_JAVA_HOME="$(dirname "$(dirname "$(readlink -f "$(command -v javac)")")")"
  fi
  # termux wants host clang-21 (Ubuntu image); use the distro clang otherwise.
  if ! command -v "clang-${TERMUX_HOST_LLVM_MAJOR_VERSION:-21}" >/dev/null && command -v clang >/dev/null; then
    export TERMUX_HOST_LLVM_MAJOR_VERSION="$(clang -dumpversion | cut -d. -f1)"
  fi
  # xcb-proto drops bytecode from the host python's version dir; the termux
  # image's python matches TERMUX_PYTHON_VERSION, Debian's (3.13) does not.
  sed -i 's|lib/python\$(python -c .*)/site-packages/xcbgen|lib/python${TERMUX_PYTHON_VERSION}/site-packages/xcbgen|' \
    "$TP/packages/xcb-proto/build.sh"
  # freedesktop.org answers curl with HTTP 418 (bot wall); Debian's orig tarball
  # is byte-identical and termux still checks TERMUX_PKG_SHA256.
  sed -i 's|https://www.freedesktop.org/software/pulseaudio/webrtc-audio-processing/webrtc-audio-processing-${TERMUX_PKG_VERSION}.tar.gz|https://deb.debian.org/debian/pool/main/w/webrtc-audio-processing/webrtc-audio-processing_${TERMUX_PKG_VERSION}.orig.tar.gz|' \
    "$TP/packages/libwebrtc-audio-processing/build.sh"
  # termux_setup_build_python cd's into the host-python cache dir on a cold cache
  # and never returns, so python's `autoreconf -fi` ran there instead of in the
  # target source (install-sh differed between cold and warm cache builds).
  # Always return to the source dir so cold and warm builds are identical.
  sed -i 's|^\([[:space:]]*termux_setup_build_python\)$|\1; cd "$TERMUX_PKG_SRCDIR"|' \
    "$TP/packages/python/build.sh"
  echo "[*] NO_DOCKER=1 — building on host (NDK=${NDK:-<termux default>})" | tee -a "$LOG"
else
  BUILDER=(./scripts/run-docker.sh ./build-package.sh)
fi

export TERMUX_DOCKER_RUN_EXTRA_ARGS="${TERMUX_DOCKER_RUN_EXTRA_ARGS:---network host --cpus 10 --memory 10g}"
echo "[*] TERMUX_DOCKER_RUN_EXTRA_ARGS=$TERMUX_DOCKER_RUN_EXTRA_ARGS" | tee -a "$LOG"

# Default output under termux-packages (docker-writable). Deps always land here.
BUILD_FLAGS=(-a "$ARCH")
if [[ "${FORCE_DEPS:-0}" == "1" ]]; then
  BUILD_FLAGS+=(-F)
elif [[ "${FORCE:-0}" == "1" ]]; then
  BUILD_FLAGS+=(-f)
fi

cd "$TP"
# Recreate container only when forced or missing. Destroying the container drops
# /data/data/.built-packages and forces full dep rebuilds on every script run.
if [[ "${NO_DOCKER:-0}" == "1" ]]; then
  :
elif [[ "${RECREATE_BUILDER:-0}" == "1" ]]; then
  echo "[*] RECREATE_BUILDER=1 — removing termux-package-builder" | tee -a "$LOG"
  docker rm -f termux-package-builder 2>/dev/null || true
elif ! docker inspect termux-package-builder >/dev/null 2>&1; then
  echo "[*] no builder container — will create on first package" | tee -a "$LOG"
else
  echo "[*] reusing existing termux-package-builder (set RECREATE_BUILDER=1 to reset)" | tee -a "$LOG"
fi

ok=0
fail=0
failed_pkgs=()

for pkg in "${PKGS[@]}"; do
  # Lists name subpackages (curl, xz-utils, …); build-package.sh needs the parent.
  src_pkg="$pkg"
  if ! ls -d {packages,x11-packages,root-packages}/"$pkg" >/dev/null 2>&1; then
    sub="$(ls {packages,x11-packages,root-packages}/*/"$pkg".subpackage.sh 2>/dev/null | head -1)"
    [[ -n "$sub" ]] && src_pkg="$(basename "$(dirname "$sub")")"
  fi
  echo "=== building $pkg (from $src_pkg) for $CUSTOM_PACKAGE ($(date +%T)) ===" | tee -a "$LOG"
  if "${BUILDER[@]}" "${BUILD_FLAGS[@]}" "$src_pkg" 2>&1 | tee -a "$LOG"; then
    echo "OK  $pkg" | tee -a "$LOG"
    ok=$((ok + 1))
  else
    echo "FAIL $pkg" | tee -a "$LOG"
    fail=$((fail + 1))
    failed_pkgs+=("$pkg")
    if [[ "${CONTINUE_ON_FAIL:-0}" != "1" ]]; then
      echo "[!] aborting (set CONTINUE_ON_FAIL=1 to keep going)" | tee -a "$LOG"
      break
    fi
  fi
done

# Mirror only debs whose archive paths match this applicationId.
# (Shared termux-packages/output may still contain other app-id debs.)
echo "[*] mirroring matching-prefix debs → $OUT_DIR" | tee -a "$LOG"
mkdir -p "$OUT_DIR"
mirrored=0
skipped=0
for deb in "$BUILD_OUT_DIR"/*.deb; do
  [[ -f "$deb" ]] || continue
  if "$ROOT/scripts/verify_deb_prefix.sh" "$deb" "$CUSTOM_PACKAGE" >/dev/null 2>&1; then
    cp -a "$deb" "$OUT_DIR/"
    mirrored=$((mirrored + 1))
  else
    skipped=$((skipped + 1))
  fi
done
echo "[*] mirrored=$mirrored skipped_other_prefix=$skipped" | tee -a "$LOG"

echo "" | tee -a "$LOG"
echo "[*] done: ok=$ok fail=$fail" | tee -a "$LOG"
if (( fail > 0 )); then
  echo "[*] failed: ${failed_pkgs[*]}" | tee -a "$LOG"
fi
echo "[*] debs in: $OUT_DIR"
ls -la "$OUT_DIR" 2>/dev/null | tail -30 | tee -a "$LOG" || true

# Explicit verify for requested seed packages when present
for pkg in "${PKGS[@]}"; do
  hit="$(ls -1 "$OUT_DIR"/${pkg}_*"${ARCH}".deb "$OUT_DIR"/${pkg}_*_all.deb 2>/dev/null | head -1 || true)"
  if [[ -n "$hit" ]]; then
    echo "[*] verifying $(basename "$hit")..." | tee -a "$LOG"
    "$ROOT/scripts/verify_deb_prefix.sh" "$hit" "$CUSTOM_PACKAGE" | tee -a "$LOG" || true
  fi
done

(( fail == 0 ))
