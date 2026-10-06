#!/bin/sh
# flux_gpu_common.sh — POSIX functions only. No exit. No package manager.
# Sourced by setup_hw_accel_guest.sh and prepended by Kotlin payloads.

# shellcheck disable=SC2034

flux_gpu_normalize() {
    _raw=$(printf '%s' "${1:-}" | tr '[:upper:]' '[:lower:]' | tr -d '[:space:]')
    case "$_raw" in
        turnip|adreno|snapdragon|qcom|qualcomm|kgsl|zink)
            printf '%s\n' turnip
            ;;
        panvk)
            printf '%s\n' panvk
            ;;
        virgl|virpipe|mali|powervr|xclipse|llvmpipe|soft|software|sw)
            printf '%s\n' virgl
            ;;
        ask|manual)
            printf '%s\n' ask
            ;;
        auto|"")
            printf '%s\n' ask
            ;;
        *)
            printf '%s\n' virgl
            ;;
    esac
}

flux_gpu_collect_hints() {
    _hints=""
    if command -v getprop >/dev/null 2>&1; then
        for _k in \
            ro.hardware ro.hardware.chipname ro.chipname ro.board.platform \
            ro.soc.model ro.soc.manufacturer ro.product.board \
            ro.hardware.egl ro.hardware.vulkan ro.gfx.driver.0
        do
            _hints="$_hints $(getprop "$_k" 2>/dev/null || true)"
        done
    fi
    if [ -r /proc/cpuinfo ]; then
        _hints="$_hints $(grep -E 'Hardware|model name|Processor' /proc/cpuinfo 2>/dev/null | head -20 || true)"
    fi
    if [ -e /dev/kgsl-3d0 ] || [ -e /dev/kgsl ]; then
        _hints="$_hints kgsl kgsl-3d0"
    fi
    printf '%s\n' "$_hints" | tr '[:upper:]' '[:lower:]'
}

flux_gpu_auto_detect() {
    _hints=$(flux_gpu_collect_hints)
    _vendor=unknown
    if [ -e /dev/kgsl-3d0 ] || [ -e /dev/kgsl ]; then
        _vendor=adreno/snapdragon
        printf '%s\n' "turnip|$_vendor|$_hints"
        return 0
    fi
    if printf '%s' "$_hints" | grep -qE 'qcom|qualcomm|adreno|snapdragon|msm[0-9]|sdm[0-9]|sm[0-9]{3,4}|lahaina|taro|kalama|pineapple|kona|lito|bengal|holi|crow|blair|waipio|yupik|shima|atoll|trinket'; then
        _vendor=adreno/snapdragon
        printf '%s\n' "turnip|$_vendor|$_hints"
        return 0
    fi
    if printf '%s' "$_hints" | grep -qE 'mali|exynos|kirin|hisi|mediatek|mt6[0-9]|dimensity|helio|tensor|gs10|gs20|gs30'; then
        _vendor=mali
        printf '%s\n' "virgl|$_vendor|$_hints"
        return 0
    fi
    if printf '%s' "$_hints" | grep -qE 'powervr|imgtec|imagination|rogue'; then
        _vendor=powervr
        printf '%s\n' "virgl|$_vendor|$_hints"
        return 0
    fi
    if printf '%s' "$_hints" | grep -qE 'xclipse|amdgpu'; then
        _vendor=xclipse
        printf '%s\n' "virgl|$_vendor|$_hints"
        return 0
    fi
    printf '%s\n' "virgl|$_vendor|$_hints"
}

flux_gpu_arch() {
    _a=""
    if command -v dpkg >/dev/null 2>&1; then
        _a=$(dpkg --print-architecture 2>/dev/null || true)
    fi
    if [ -z "$_a" ]; then
        case "$(uname -m 2>/dev/null || true)" in
            aarch64|arm64) _a=arm64 ;;
            armv7*|armhf) _a=armhf ;;
            x86_64|amd64) _a=amd64 ;;
            *) _a=$(uname -m 2>/dev/null || echo unknown) ;;
        esac
    fi
    printf '%s\n' "$_a"
}

flux_gpu_write_state() {
    _mode=${1:-virgl}
    _vendor=${2:-unknown}
    mkdir -p /etc/fluxlinux
    printf '%s\n' "$_mode" > /etc/fluxlinux/gpu_mode
    printf '%s\n' "$_vendor" > /etc/fluxlinux/gpu_vendor
    chmod 644 /etc/fluxlinux/gpu_mode /etc/fluxlinux/gpu_vendor 2>/dev/null || true
    mkdir -p /etc/profile.d
    cat > /etc/profile.d/flux-gpu.sh << 'PROFILE'
# FluxLinux GPU mode helpers
if [ -r /etc/fluxlinux/gpu_mode ]; then
    FLUX_GPU_MODE=$(tr -d '[:space:]' </etc/fluxlinux/gpu_mode)
    export FLUX_GPU_MODE
fi
# Turnip installed (marker) → hardware GL/Vulkan in terminal sessions too.
if [ "${FLUX_GPU_MODE:-}" = turnip ] && [ -r /etc/fluxlinux/turnip_version ]; then
    export MESA_LOADER_DRIVER_OVERRIDE=kgsl
fi
# PanVK installed (marker) → zink on the Mali Vulkan ICD in terminal sessions.
if [ "${FLUX_GPU_MODE:-}" = panvk ] && [ -r /etc/fluxlinux/panvk_version ]; then
    export MESA_LOADER_DRIVER_OVERRIDE=zink
    export GALLIUM_DRIVER=zink
    export VK_ICD_FILENAMES=/usr/share/vulkan/icd.d/panfrost_icd.aarch64.json
    export MESA_VK_WSI_DEBUG=sw
fi
PROFILE
    chmod 644 /etc/profile.d/flux-gpu.sh 2>/dev/null || true
}

flux_gpu_has_kgsl_dri() {
    # Only kgsl_dri.so is the Freedreno-on-KGSL DRI. Stock Mesa msm_dri.so /
    # freedreno_dri.so are DRM drivers and must NOT force OVERRIDE=kgsl.
    if [ -e /usr/lib/aarch64-linux-gnu/dri/kgsl_dri.so ] \
        || [ -e /usr/lib64/dri/kgsl_dri.so ] \
        || [ -e /usr/lib/dri/kgsl_dri.so ] \
        || [ -e /usr/lib/aarch64-linux-musl/dri/kgsl_dri.so ]; then
        return 0
    fi
    ls /usr/lib*/dri/kgsl_dri.so /usr/lib/*/dri/kgsl_dri.so \
        >/dev/null 2>&1
}

flux_gpu_apply_runtime() {
    unset GALLIUM_DRIVER MESA_LOADER_DRIVER_OVERRIDE VK_ICD_FILENAMES
    unset LIBGL_ALWAYS_SOFTWARE TU_DEBUG MESA_VK_WSI_DEBUG
    MODE=virgl
    if [ -n "${FLUX_GPU_RUNTIME:-}" ]; then
        MODE=$(printf '%s' "$FLUX_GPU_RUNTIME" | tr -d '[:space:]' | tr '[:upper:]' '[:lower:]')
    elif [ -r /etc/fluxlinux/gpu_mode ]; then
        MODE=$(tr -d '[:space:]' </etc/fluxlinux/gpu_mode)
    fi
    case "$MODE" in turnip|panvk|virgl) ;; *) MODE=virgl ;; esac
    if [ "$MODE" = turnip ]; then
        if flux_gpu_has_kgsl_dri; then
            export MESA_LOADER_DRIVER_OVERRIDE=kgsl
        else
            export MESA_LOADER_DRIVER_OVERRIDE=zink
            export VK_ICD_FILENAMES=/usr/share/vulkan/icd.d/freedreno_icd.aarch64.json
        fi
        export TU_DEBUG=noconform
        export MESA_VK_WSI_DEBUG=sw
        export MESA_GL_VERSION_OVERRIDE=4.6
        export MESA_GLES_VERSION_OVERRIDE=3.2
        export MESA_NO_ERROR=1
    elif [ "$MODE" = panvk ]; then
        # PanVK (mali_kbase) Vulkan ICD + zink GL. Software WSI as with Turnip.
        export MESA_LOADER_DRIVER_OVERRIDE=zink
        export GALLIUM_DRIVER=zink
        export VK_ICD_FILENAMES=/usr/share/vulkan/icd.d/panfrost_icd.aarch64.json
        export MESA_VK_WSI_DEBUG=sw
    elif [ "$MODE" = virgl ]; then
        _sock="${VTEST_SOCKET_NAME:-/tmp/.virgl_test}"
        if [ -S "$_sock" ]; then
            export GALLIUM_DRIVER=virpipe
            export MESA_GL_VERSION_OVERRIDE=4.0
            export MESA_GLES_VERSION_OVERRIDE=3.1
            export MESA_NO_ERROR=1
        else
            export LIBGL_ALWAYS_SOFTWARE=1
            export GALLIUM_DRIVER=llvmpipe
            echo "FluxLinux(guest): VirGL socket missing — llvmpipe fallback"
        fi
    else
        export LIBGL_ALWAYS_SOFTWARE=1
        export GALLIUM_DRIVER=llvmpipe
    fi
    export GPU_MODE="$MODE"
}

flux_gpu_write_apply_env() {
    mkdir -p /usr/local/lib/fluxlinux
    cat > /usr/local/lib/fluxlinux/apply_gpu_env.sh << 'APPLY'
# /usr/local/lib/fluxlinux/apply_gpu_env.sh
# Sets GPU env in the current shell. Safe to source twice.
# VTEST_SOCKET_NAME must already be set by the start script
#   proot:  /tmp/.virgl_test
#   chroot: /mnt/host-tmp/.virgl_test

flux_gpu_has_kgsl_dri() {
    # Only kgsl_dri.so is the Freedreno-on-KGSL DRI. Stock Mesa msm_dri.so /
    # freedreno_dri.so are DRM drivers and must NOT force OVERRIDE=kgsl.
    if [ -e /usr/lib/aarch64-linux-gnu/dri/kgsl_dri.so ] \
        || [ -e /usr/lib64/dri/kgsl_dri.so ] \
        || [ -e /usr/lib/dri/kgsl_dri.so ] \
        || [ -e /usr/lib/aarch64-linux-musl/dri/kgsl_dri.so ]; then
        return 0
    fi
    ls /usr/lib*/dri/kgsl_dri.so /usr/lib/*/dri/kgsl_dri.so \
        >/dev/null 2>&1
}

flux_gpu_apply_runtime() {
    unset GALLIUM_DRIVER MESA_LOADER_DRIVER_OVERRIDE VK_ICD_FILENAMES
    unset LIBGL_ALWAYS_SOFTWARE TU_DEBUG MESA_VK_WSI_DEBUG
    MODE=virgl
    if [ -n "${FLUX_GPU_RUNTIME:-}" ]; then
        MODE=$(printf '%s' "$FLUX_GPU_RUNTIME" | tr -d '[:space:]' | tr '[:upper:]' '[:lower:]')
    elif [ -r /etc/fluxlinux/gpu_mode ]; then
        MODE=$(tr -d '[:space:]' </etc/fluxlinux/gpu_mode)
    fi
    case "$MODE" in turnip|panvk|virgl) ;; *) MODE=virgl ;; esac
    if [ "$MODE" = turnip ]; then
        if flux_gpu_has_kgsl_dri; then
            export MESA_LOADER_DRIVER_OVERRIDE=kgsl
        else
            export MESA_LOADER_DRIVER_OVERRIDE=zink
            export VK_ICD_FILENAMES=/usr/share/vulkan/icd.d/freedreno_icd.aarch64.json
        fi
        export TU_DEBUG=noconform
        export MESA_VK_WSI_DEBUG=sw
        export MESA_GL_VERSION_OVERRIDE=4.6
        export MESA_GLES_VERSION_OVERRIDE=3.2
        export MESA_NO_ERROR=1
    elif [ "$MODE" = panvk ]; then
        # PanVK (mali_kbase) Vulkan ICD + zink GL. Software WSI as with Turnip.
        export MESA_LOADER_DRIVER_OVERRIDE=zink
        export GALLIUM_DRIVER=zink
        export VK_ICD_FILENAMES=/usr/share/vulkan/icd.d/panfrost_icd.aarch64.json
        export MESA_VK_WSI_DEBUG=sw
    elif [ "$MODE" = virgl ]; then
        _sock="${VTEST_SOCKET_NAME:-/tmp/.virgl_test}"
        if [ -S "$_sock" ]; then
            export GALLIUM_DRIVER=virpipe
            export MESA_GL_VERSION_OVERRIDE=4.0
            export MESA_GLES_VERSION_OVERRIDE=3.1
            export MESA_NO_ERROR=1
        else
            export LIBGL_ALWAYS_SOFTWARE=1
            export GALLIUM_DRIVER=llvmpipe
            echo "FluxLinux(guest): VirGL socket missing — llvmpipe fallback"
        fi
    else
        export LIBGL_ALWAYS_SOFTWARE=1
        export GALLIUM_DRIVER=llvmpipe
    fi
    export GPU_MODE="$MODE"
}
APPLY
    chmod 644 /usr/local/lib/fluxlinux/apply_gpu_env.sh 2>/dev/null || true
}

flux_gpu_write_gpu_launch() {
    mkdir -p /usr/local/bin
    cat > /usr/local/bin/gpu-launch << 'EOF'
#!/bin/sh
# FluxLinux GPU launcher — reads /etc/fluxlinux/gpu_mode; override via FLUX_GPU_RUNTIME
if [ -r /usr/local/lib/fluxlinux/apply_gpu_env.sh ]; then
    # shellcheck disable=SC1091
    . /usr/local/lib/fluxlinux/apply_gpu_env.sh
    flux_gpu_apply_runtime
else
    MODE=virgl
    if [ -r /etc/fluxlinux/gpu_mode ]; then
        MODE=$(tr -d '[:space:]' </etc/fluxlinux/gpu_mode)
    fi
    [ -n "${FLUX_GPU_RUNTIME:-}" ] && MODE=$FLUX_GPU_RUNTIME
    case "$MODE" in
        turnip)
            export MESA_LOADER_DRIVER_OVERRIDE=zink
            export VK_ICD_FILENAMES=/usr/share/vulkan/icd.d/freedreno_icd.aarch64.json
            export TU_DEBUG=noconform
            export MESA_VK_WSI_DEBUG=sw
            export MESA_GL_VERSION_OVERRIDE=4.6
            export MESA_GLES_VERSION_OVERRIDE=3.2
            export MESA_NO_ERROR=1
            ;;
        panvk)
            export MESA_LOADER_DRIVER_OVERRIDE=zink
            export GALLIUM_DRIVER=zink
            export VK_ICD_FILENAMES=/usr/share/vulkan/icd.d/panfrost_icd.aarch64.json
            export MESA_VK_WSI_DEBUG=sw
            ;;
        virgl)
            export GALLIUM_DRIVER=virpipe
            export MESA_GL_VERSION_OVERRIDE=4.0
            export MESA_GLES_VERSION_OVERRIDE=3.1
            export MESA_NO_ERROR=1
            export VTEST_SOCKET_NAME=${VTEST_SOCKET_NAME:-/tmp/.virgl_test}
            ;;
        *)
            export LIBGL_ALWAYS_SOFTWARE=1
            export GALLIUM_DRIVER=llvmpipe
            ;;
    esac
    export GPU_MODE="$MODE"
fi
exec "$@"
EOF
    chmod 755 /usr/local/bin/gpu-launch
}

flux_gpu_disable_xfce_compositor() {
    echo "FluxLinux: Disabling XFCE compositor for Turnip..."
    for _userdir in /home/* /root; do
        [ -d "$_userdir" ] || continue
        _xfce="$_userdir/.config/xfce4/xfconf/xfce-perchannel-xml"
        mkdir -p "$_xfce" 2>/dev/null || true
        cat > "$_xfce/xfwm4.xml" << 'XFCEXML'
<?xml version="1.0" encoding="UTF-8"?>
<channel name="xfwm4" version="1.0">
  <property name="general" type="empty">
    <property name="use_compositing" type="bool" value="false"/>
  </property>
</channel>
XFCEXML
        if command -v stat >/dev/null 2>&1; then
            _own=$(stat -c '%U:%G' "$_userdir" 2>/dev/null || true)
            [ -n "$_own" ] && chown -R "$_own" "$_userdir/.config" 2>/dev/null || true
        fi
    done
}

flux_gpu_fake_dri() {
    echo "FluxLinux: Creating /dev/dri compatibility layer..."
    mkdir -p /dev/dri 2>/dev/null || true
    [ -e /dev/dri/card0 ] || ln -sf /dev/null /dev/dri/card0 2>/dev/null || true
    [ -e /dev/dri/renderD128 ] || ln -sf /dev/null /dev/dri/renderD128 2>/dev/null || true
    chmod 755 /dev/dri 2>/dev/null || true
}
