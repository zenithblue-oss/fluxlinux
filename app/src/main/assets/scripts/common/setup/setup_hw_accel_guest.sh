#!/bin/sh
# setup_hw_accel_guest.sh — Mesa / Turnip / VirGL for every live guest.
# POSIX sh (Alpine / Chimera). Download/extract failure → virgl, exit 0.

set -eu

if [ "$(id -u)" -ne 0 ]; then
    echo "FluxLinux: ERROR: must run as root (got uid=$(id -u))."
    exit 1
fi

export PATH="/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin${PATH:+:$PATH}"
mkdir -p /tmp /var/tmp /etc/fluxlinux /usr/local/lib/fluxlinux
chmod 1777 /tmp /var/tmp 2>/dev/null || true

# Bundle: Kotlin prepends flux_gpu_common.sh, or runner stages it next to us.
if ! type flux_gpu_normalize >/dev/null 2>&1; then
    for _f in /tmp/flux_gpu_common.sh /usr/local/lib/fluxlinux/flux_gpu_common.sh; do
        if [ -r "$_f" ]; then
            # shellcheck disable=SC1090
            . "$_f"
            break
        fi
    done
fi
if ! type flux_gpu_normalize >/dev/null 2>&1; then
    echo "FluxLinux: flux_gpu_common.sh missing — writing virgl and exiting 0"
    printf '%s\n' virgl > /etc/fluxlinux/gpu_mode
    exit 0
fi

# Persist common for later Settings re-runs (best-effort).
if [ -r /tmp/flux_gpu_common.sh ]; then
    cp -f /tmp/flux_gpu_common.sh /usr/local/lib/fluxlinux/flux_gpu_common.sh 2>/dev/null || true
fi

echo "FluxLinux: Hardware acceleration (guest)..."

_pkg_add() {
    if grep -q '^ID="chimera"' /usr/lib/os-release 2>/dev/null \
        || grep -q '^ID=chimera' /etc/os-release 2>/dev/null \
        || { [ -d /usr/lib/apk ] && [ ! -d /lib/apk ]; }; then
        apk update 2>/dev/null || true
        apk add "$@"
    elif command -v apk >/dev/null 2>&1; then
        # Alpine apk v2
        apk update 2>/dev/null || true
        apk add --no-cache "$@" 2>/dev/null || apk add "$@"
    elif command -v dnf5 >/dev/null 2>&1; then
        dnf5 -y --setopt=install_weak_deps=False --setopt=tsflags=nodocs,noscripts install "$@"
    elif command -v dnf >/dev/null 2>&1; then
        dnf -y --setopt=install_weak_deps=False --setopt=tsflags=nodocs,noscripts install "$@"
    elif command -v xbps-install >/dev/null 2>&1; then
        xbps-install -Sy >/dev/null 2>&1 || true
        xbps-install -y "$@"
    elif command -v zypper >/dev/null 2>&1; then
        zypper --non-interactive --gpg-auto-import-keys refresh >/dev/null 2>&1 || true
        zypper --non-interactive install --no-recommends --auto-agree-with-licenses "$@"
    elif command -v pacman >/dev/null 2>&1; then
        # Manjaro ARM — never rewrite mirrors to ALARM.
        pacman -Sy --noconfirm >/dev/null 2>&1 || true
        pacman -S --noconfirm --needed "$@"
    elif command -v apt-get >/dev/null 2>&1; then
        # Deepin: beige/crimson only — never add debian.org.
        DEBIAN_FRONTEND=noninteractive apt-get update >/dev/null 2>&1 || true
        DEBIAN_FRONTEND=noninteractive apt-get install -y --no-install-recommends "$@"
    else
        echo "FluxLinux: no supported package manager for hw accel"
        return 1
    fi
}

echo "FluxLinux: Installing stock Mesa..."
if command -v dnf >/dev/null 2>&1 || command -v dnf5 >/dev/null 2>&1; then
    _pkg_add mesa-dri-drivers mesa-libGL mesa-libEGL mesa-vulkan-drivers mesa-utils vulkan-tools curl \
        || _pkg_add mesa-dri-drivers mesa-libGL mesa-utils curl || true
elif command -v xbps-install >/dev/null 2>&1; then
    _pkg_add mesa mesa-dri MesaLib curl 2>/dev/null || _pkg_add mesa mesa-dri curl || true
elif command -v zypper >/dev/null 2>&1; then
    _pkg_add Mesa-dri Mesa-libGL1 Mesa-libEGL1 Mesa-demo-x curl 2>/dev/null \
        || _pkg_add Mesa-dri Mesa-libGL1 curl || true
elif command -v pacman >/dev/null 2>&1; then
    _pkg_add mesa mesa-utils vulkan-mesa-layers curl 2>/dev/null \
        || _pkg_add mesa mesa-utils curl || true
elif command -v apk >/dev/null 2>&1; then
    if grep -q '^ID=chimera' /etc/os-release 2>/dev/null \
        || grep -q '^ID="chimera"' /usr/lib/os-release 2>/dev/null \
        || { [ -d /usr/lib/apk ] && [ ! -d /lib/apk ]; }; then
        _pkg_add mesa mesa-dri mesa-gl curl 2>/dev/null \
            || _pkg_add mesa mesa-dri curl || true
    else
        # Alpine v2: family already uses mesa-dri-gallium
        _pkg_add mesa-dri-gallium mesa-gl mesa curl 2>/dev/null \
            || _pkg_add mesa-dri-gallium mesa-gl curl || true
    fi
elif command -v apt-get >/dev/null 2>&1; then
    _pkg_add mesa-utils libgl1-mesa-dri libegl1 mesa-vulkan-drivers curl 2>/dev/null \
        || _pkg_add mesa-utils libgl1-mesa-dri libegl1 curl || true
fi

_flux_gpu_fetch() {
    _url=$1
    _out=$2
    if command -v curl >/dev/null 2>&1; then
        curl -L --fail --connect-timeout 15 --max-time 180 -o "$_out" "$_url"
    elif command -v wget >/dev/null 2>&1; then
        wget -T 180 -O "$_out" "$_url"
    else
        return 1
    fi
}

_flux_gpu_pin_mesa() {
    if command -v apt-get >/dev/null 2>&1; then
        mkdir -p /etc/apt/preferences.d
        cat > /etc/apt/preferences.d/pin-mesa << 'PINEOF'
# FluxLinux: Mesa pinned — runtime upgraded via mesa-for-android-container
Package: libgl1-mesa-dri
Pin: version *
Pin-Priority: -1

Package: mesa-libgallium
Pin: version *
Pin-Priority: -1

Package: libglx-mesa0
Pin: version *
Pin-Priority: -1

Package: libegl-mesa0
Pin: version *
Pin-Priority: -1

Package: mesa-va-drivers
Pin: version *
Pin-Priority: -1

Package: mesa-vdpau-drivers
Pin: version *
Pin-Priority: -1

Package: mesa-vulkan-drivers
Pin: version *
Pin-Priority: -1
PINEOF
        echo "FluxLinux: Mesa packages pinned (apt)."
    elif command -v dnf >/dev/null 2>&1 || command -v dnf5 >/dev/null 2>&1; then
        if command -v dnf >/dev/null 2>&1 && dnf versionlock --help >/dev/null 2>&1; then
            dnf versionlock add mesa-dri-drivers mesa-libGL mesa-libEGL mesa-vulkan-drivers 2>/dev/null \
                || echo "FluxLinux: dnf versionlock skipped"
        else
            echo "FluxLinux: dnf versionlock plugin not present — skip pin"
        fi
    fi
}

# ── mode ─────────────────────────────────────────────────────────────────────

RAW_FLUX_GPU="${FLUX_GPU:-}"
NORMALIZED=$(flux_gpu_normalize "$RAW_FLUX_GPU")
VENDOR_HINT="${FLUX_GPU_VENDOR:-unknown}"
MODE=""

want_menu=0
case "$RAW_FLUX_GPU" in
    ask|manual) want_menu=1 ;;
esac

if [ "$want_menu" = 1 ] && [ -t 0 ]; then
    echo "============================================"
    echo "      Select your GPU / Acceleration Mode"
    echo "============================================"
    echo "1) Adreno (Turnip) — Snapdragon / KGSL"
    echo "2) VirGL (Universal) — Mali / PowerVR / other"
    echo "============================================"
    printf 'Enter choice [1-2]: '
    GPU_CHOICE=""
    read -r GPU_CHOICE || GPU_CHOICE=""
    case "${GPU_CHOICE:-}" in
        1) MODE=turnip; VENDOR_HINT=manual-adreno ;;
        2) MODE=virgl; VENDOR_HINT=manual-virgl ;;
        *)
            echo "Invalid choice. Defaulting to VirGL."
            MODE=virgl
            VENDOR_HINT=invalid-default-virgl
            ;;
    esac
elif [ "$NORMALIZED" = ask ]; then
    DET=$(flux_gpu_auto_detect)
    MODE=$(printf '%s' "$DET" | cut -d'|' -f1)
    VENDOR_HINT=$(printf '%s' "$DET" | cut -d'|' -f2)
    DETECT_HINTS=$(printf '%s' "$DET" | cut -d'|' -f3-)
    echo "FluxLinux: Auto-detected GPU mode=$MODE vendor=$VENDOR_HINT"
    echo "FluxLinux: hints: $DETECT_HINTS"
else
    MODE=$NORMALIZED
    if [ "$MODE" = turnip ]; then
        [ "$VENDOR_HINT" = unknown ] && VENDOR_HINT=env-adreno
    else
        [ "$VENDOR_HINT" = unknown ] && VENDOR_HINT=env-other
    fi
    echo "FluxLinux: FLUX_GPU=${RAW_FLUX_GPU} → mode=$MODE"
fi

ARCH=$(flux_gpu_arch)
if [ "$MODE" = turnip ] && [ "$ARCH" != arm64 ] && [ "$ARCH" != aarch64 ]; then
    echo "FluxLinux: [WARN] Turnip not available for arch=$ARCH — VirGL."
    MODE=virgl
    VENDOR_HINT="${VENDOR_HINT}+arch-fallback"
fi

# ── turnip tarball ───────────────────────────────────────────────────────────

# The app picked the stable release for this distro (FLUX_TURNIP_1 = latest,
# FLUX_TURNIP_2 = pinned fallback), each "version url sha256". Marker file =
# installed version. No plan (not Adreno / unsupported / offline) → VirGL.

if [ "$MODE" = turnip ]; then
    TURNIP_OK=""
    rm -f /etc/fluxlinux/turnip_version
    for _n in 1 2; do
        eval "_spec=\${FLUX_TURNIP_$_n:-}"
        [ -n "$_spec" ] || continue
        # shellcheck disable=SC2086
        set -- $_spec
        echo "FluxLinux: Downloading Mesa/Turnip $1..."
        if _flux_gpu_fetch "$2" /tmp/turnip.tar.gz \
            && [ "$(sha256sum /tmp/turnip.tar.gz | cut -d' ' -f1)" = "$3" ] \
            && tar -zxf /tmp/turnip.tar.gz -C /; then
            command -v ldconfig >/dev/null 2>&1 && ldconfig || true
            printf '%s\n' "$1" > /etc/fluxlinux/turnip_version
            TURNIP_OK=$1
            break
        fi
        echo "FluxLinux: [WARN] Mesa/Turnip $1 download, sha256 or extract failed."
    done
    rm -f /tmp/turnip.tar.gz
    if [ -n "$TURNIP_OK" ]; then
        echo "FluxLinux: Mesa/Turnip $TURNIP_OK installed."
        _flux_gpu_pin_mesa
    else
        echo "FluxLinux: ${FLUX_TURNIP_MSG:-GPU driver download failed — software rendering until you retry}"
        MODE=virgl
        VENDOR_HINT="${VENDOR_HINT}+turnip-unavailable"
    fi
fi

if [ "$MODE" = turnip ]; then
    flux_gpu_disable_xfce_compositor
    flux_gpu_fake_dri
fi

if [ "$MODE" = virgl ]; then
    echo "FluxLinux: VirGL mode — guest uses GALLIUM_DRIVER=virpipe when the socket exists."
fi

flux_gpu_write_state "$MODE" "$VENDOR_HINT"
flux_gpu_write_apply_env
flux_gpu_write_gpu_launch

echo ""
echo "============================================"
echo "  Hardware Acceleration Setup Complete!"
echo "============================================"
echo "Mode:   $MODE"
echo "Vendor: $VENDOR_HINT"
echo "State:  /etc/fluxlinux/gpu_mode"
echo "============================================"
exit 0
