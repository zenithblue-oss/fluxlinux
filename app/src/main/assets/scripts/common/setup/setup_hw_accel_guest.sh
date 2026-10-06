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
if { [ "$MODE" = turnip ] || [ "$MODE" = panvk ]; } && [ "$ARCH" != arm64 ] && [ "$ARCH" != aarch64 ]; then
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

# ── PanVK (MediaTek Mali v10+) ───────────────────────────────────────────────

# The glibc .so is built against a newer userland than Debian/Ubuntu ship: it
# NEEDs a shared libSPIRV-Tools.so (distros ship only static) and libwayland
# 1.24 symbols. On apt guests build a small shim from the static archives (+ inert
# wayland stubs, X11 WSI never calls them). Returns 0 when the ICD loads.
_flux_panvk_deps() {
    _l=/usr/local/lib64/libvulkan_panfrost.so
    _bad() { ldd -r "$_l" 2>&1 | grep -E 'not found|undefined symbol'; }
    [ -z "$(_bad)" ] && return 0
    command -v apt-get >/dev/null 2>&1 || return 1
    echo "FluxLinux: PanVK needs extra libs, building shim (gcc + spirv-tools)..."
    _pkg_add spirv-tools gcc libc6-dev || return 1
    _m=$(gcc -print-multiarch 2>/dev/null || echo aarch64-linux-gnu)
    _wl=$(ls /usr/lib/$_m/libwayland-client.so.0 2>/dev/null | head -1)
    _defs=""
    grep -q wl_fixes_interface "$_wl" 2>/dev/null || _defs="$_defs -DSTUB_FIXES"
    grep -q wl_display_dispatch_queue_timeout "$_wl" 2>/dev/null || _defs="$_defs -DSTUB_TIMEOUT"
    cat > /tmp/panvk_shim.c << 'SHIMEOF'
#ifdef STUB_FIXES
struct wl_interface { const char *name; int version; int method_count; const void *methods; int event_count; const void *events; };
const struct wl_interface wl_fixes_interface = {"wl_fixes", 1, 0, 0, 0, 0};
#endif
#ifdef STUB_TIMEOUT
int wl_display_dispatch_queue_timeout(void *d, void *q, const void *t) { return -1; }
#endif
SHIMEOF
    _a=/usr/lib/$_m
    # shellcheck disable=SC2086
    gcc -shared -fPIC $_defs -x c -o /usr/local/lib/libSPIRV-Tools.so /tmp/panvk_shim.c -x none \
        -Wl,--whole-archive $_a/libSPIRV-Tools.a $_a/libSPIRV-Tools-opt.a -Wl,--no-whole-archive \
        -Wl,-soname,libSPIRV-Tools.so $_a/libstdc++.so.6 || return 1
    rm -f /tmp/panvk_shim.c
    ldconfig 2>/dev/null || true
    [ -z "$(_bad)" ]
}

# FLUX_PANVK_1 = latest, FLUX_PANVK_2 = pinned fallback, each
# "version so_url so_sha icd_url icd_sha". glibc .so → /usr/local/lib64 (the
# path the ICD json names), ICD → /usr/share/vulkan/icd.d. Marker = version.
if [ "$MODE" = panvk ]; then
    PANVK_OK=""
    PANVK_TOUCHED=""
    if [ -e /lib/ld-musl-aarch64.so.1 ] || [ -e /usr/lib/ld-musl-aarch64.so.1 ]; then
        FLUX_PANVK_MSG="PanVK needs a glibc distro — software rendering stays"
        rm -f /etc/fluxlinux/panvk_version
    else
        for _n in 1 2; do
            eval "_spec=\${FLUX_PANVK_$_n:-}"
            [ -n "$_spec" ] || continue
            # shellcheck disable=SC2086
            set -- $_spec
            echo "FluxLinux: Downloading PanVK $1..."
            if _flux_gpu_fetch "$2" /tmp/panvk.so \
                && [ "$(sha256sum /tmp/panvk.so | cut -d' ' -f1)" = "$3" ] \
                && _flux_gpu_fetch "$4" /tmp/panvk.json \
                && [ "$(sha256sum /tmp/panvk.json | cut -d' ' -f1)" = "$5" ]; then
                PANVK_TOUCHED=1
                mkdir -p /usr/local/lib64 /usr/share/vulkan/icd.d
                install -m 755 /tmp/panvk.so /usr/local/lib64/libvulkan_panfrost.so
                install -m 644 /tmp/panvk.json /usr/share/vulkan/icd.d/panfrost_icd.aarch64.json
                if _flux_panvk_deps; then
                    printf '%s\n' "$1" > /etc/fluxlinux/panvk_version
                    PANVK_OK=$1
                    break
                fi
                rm -f /etc/fluxlinux/panvk_version /usr/local/lib64/libvulkan_panfrost.so \
                    /usr/share/vulkan/icd.d/panfrost_icd.aarch64.json
                FLUX_PANVK_MSG="PanVK driver cannot load on this distro (missing libs) — software rendering stays"
                continue
            fi
            echo "FluxLinux: [WARN] PanVK $1 download or sha256 failed."
        done
        # Offline/failed re-run must not break a working install.
        if [ -z "$PANVK_OK" ] && [ -z "$PANVK_TOUCHED" ] && [ -r /etc/fluxlinux/panvk_version ]; then
            PANVK_OK=$(tr -d '[:space:]' </etc/fluxlinux/panvk_version)
        fi
    fi
    rm -f /tmp/panvk.so /tmp/panvk.json
    if [ -n "$PANVK_OK" ]; then
        echo "FluxLinux: PanVK $PANVK_OK installed."
    else
        echo "FluxLinux: ${FLUX_PANVK_MSG:-GPU driver download failed — software rendering until you retry}"
        MODE=virgl
        VENDOR_HINT="${VENDOR_HINT}+panvk-unavailable"
    fi
fi

if [ "$MODE" = turnip ] || [ "$MODE" = panvk ]; then
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
