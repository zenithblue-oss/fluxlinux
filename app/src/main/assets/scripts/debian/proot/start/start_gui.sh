#!/data/data/com.ivarna.fluxlinux/files/usr/bin/bash
# start_gui.sh - Launch XFCE4 Desktop Environment in PRoot Distro
# Paths: TermuxHostPaths via fluxlinux-host.env (SSOT)

DISTRO=${1:-debian}
PKG="${TERMUX_APP__PACKAGE_NAME:-com.ivarna.fluxlinux}"
_HOST_ENV="${TERMUX__PREFIX:-/data/data/${PKG}/files/usr}/etc/fluxlinux-host.env"
[ -r "$_HOST_ENV" ] && . "$_HOST_ENV"

# Termux paths (from SSOT env, with safe fallbacks)
PKG="${TERMUX_APP__PACKAGE_NAME:-$PKG}"
TERMUX_PREFIX="${TERMUX__PREFIX:-/data/data/$PKG/files/usr}"
TERMUX_HOME="${TERMUX__HOME:-/data/data/$PKG/files/home}"
export HOME="$TERMUX_HOME"
export TMPDIR="${TMPDIR:-$TERMUX_PREFIX/tmp}"
export PROOT_TMP_DIR="${PROOT_TMP_DIR:-$(dirname "$TERMUX_PREFIX")/proot-tmp}"
mkdir -p "$TMPDIR" "$PROOT_TMP_DIR" 2>/dev/null || true
chmod 1777 "$TMPDIR" 2>/dev/null || true
chmod 700 "$PROOT_TMP_DIR" 2>/dev/null || true
export PATH="$TERMUX_PREFIX/bin:$TERMUX_PREFIX/bin/applets:/system/bin:/system/xbin:$PATH"
export LD_LIBRARY_PATH="$TERMUX_PREFIX/lib:$TERMUX_PREFIX/opt/virglrenderer-android/lib"
export TERMUX_APP__PACKAGE_NAME="$PKG"
export TERMUX_X11_OVERRIDE_PACKAGE="$PKG"
export TERMUX__PREFIX="$TERMUX_PREFIX"
export TERMUX__HOME="$TERMUX_HOME"
export XKB_CONFIG_ROOT="$TERMUX_PREFIX/share/X11/xkb"

# Configure PulseAudio (use home to avoid root-owned stale tmp dirs)
export PULSE_RUNTIME_PATH="${HOME}/.pulse"
mkdir -p "$PULSE_RUNTIME_PATH" 2>/dev/null

# Kill stale host graphics services only — NEVER am force-stop own package
# (that would kill FluxLinux itself if the package name matches).
# Pulse is a host service: do not pkill it here (CLI audio stays up).
pkill -f "virgl_test_server" 2>/dev/null || true
pkill -f termux-x11 2>/dev/null || true
pkill -f "app_process.*termux-x11" 2>/dev/null || true
sleep 2
# Stale UNIX sockets make Loader fail with "server already running"
rm -f "$TMPDIR/.X0-lock" "$TMPDIR/.X1-lock" "$TMPDIR/.tX0-lock" \
  "$TMPDIR/.X11-unix/X0" "$TMPDIR/.X11-unix/X1" 2>/dev/null || true
# PREFIX/tmp must stay app_data_file. A leftover tmpfs:s0 label (older chroot
# start used chcon -R tmpfs) makes mkdir .X11-unix, the X lock, and dbus
# sockets fail with Permission denied under enforcing SELinux.
if [ -e "$TMPDIR/.X11-unix" ] && [ ! -d "$TMPDIR/.X11-unix" ]; then
  rm -f "$TMPDIR/.X11-unix" 2>/dev/null || true
fi
mkdir -p "$TMPDIR/.X11-unix" 2>/dev/null || {
  rm -rf "$TMPDIR/.X11-unix" 2>/dev/null || true
  mkdir -p "$TMPDIR/.X11-unix"
}
chmod 1777 "$TMPDIR" "$TMPDIR/.X11-unix" 2>/dev/null || true
_ctx=$(ls -Zd "$TERMUX_PREFIX" 2>/dev/null | awk '{print $1}')
if [ -n "$_ctx" ] && command -v chcon >/dev/null 2>&1; then
  chcon -R "$_ctx" "$TMPDIR" 2>/dev/null || chcon "$_ctx" "$TMPDIR" 2>/dev/null || true
fi

# Host Pulse (app uid). Supervisor logs FluxLinux: [AUDIO] … and never blocks XFCE.
if [ -x "$TERMUX_HOME/start_pulse_host.sh" ]; then
  bash "$TERMUX_HOME/start_pulse_host.sh" || true
elif command -v start_pulse_host.sh >/dev/null 2>&1; then
  start_pulse_host.sh || true
fi

# VirGL is optional. Custom package builds currently omit it; XFCE runs
# with software rendering until a compatible GPU bundle is available.
if command -v virgl_test_server_android >/dev/null; then
  echo "FluxLinux: Starting VirGL server..."
  virgl_test_server_android --socket-path "$TERMUX_PREFIX/tmp/.virgl_test" >/dev/null 2>&1 &
  sleep 2
  test -S "${TMPDIR}/.virgl_test" && echo "FluxLinux: VirGL socket ready" || \
    echo "FluxLinux: [WARN] VirGL socket not found"
else
  echo "FluxLinux: VirGL unavailable; using software rendering"
fi

# Start X server via app_process (needs system context)
echo "FluxLinux: Starting termux-x11 server..."
export XDG_RUNTIME_DIR="$TMPDIR"
export DISPLAY=:0

# Fix Android 14 SecurityException for writable dex files (loader.apk must be read-only)
chmod 0400 "$TERMUX_PREFIX/libexec/termux-x11/loader.apk" 2>/dev/null || true
chmod 0500 "$TERMUX_PREFIX/libexec/termux-x11" 2>/dev/null || true

# Fix broken xkb symlink if pointing to old com.termux prefix
if [ -L "$TERMUX_PREFIX/share/X11/xkb" ] && [ ! -e "$TERMUX_PREFIX/share/X11/xkb" ]; then
  rm -f "$TERMUX_PREFIX/share/X11/xkb"
  ln -s "$TERMUX_PREFIX/share/xkeyboard-config-2" "$TERMUX_PREFIX/share/X11/xkb"
fi

# Resolve our installed APK path so Loader can dlopen CmdEntryPoint classes
# tr -d '\r' strips Windows carriage returns from pm path output
if [ -z "$TERMUX_X11_APK_PATH" ]; then
  # /system/bin/pm — termux-exec rewrites bare "pm" to $PREFIX/bin/pm (missing).
  TERMUX_X11_APK_PATH=$(/system/bin/pm path "$PKG" 2>/dev/null | tr -d '\r' | sed 's/^package://')
fi
if [ -z "$TERMUX_X11_APK_PATH" ] || [ ! -f "$TERMUX_X11_APK_PATH" ]; then
  TERMUX_X11_APK_PATH=$(/system/bin/find /data/app -name "base.apk" -path "*$PKG*" 2>/dev/null | head -1)
fi
export TERMUX_X11_APK_PATH
echo "FluxLinux: APK path = $TERMUX_X11_APK_PATH"

# Extract libXlorie.so from our APK to /data/data app lib dir
# This is the only location app_process can dlopen without corrupting its linker namespace
APP_LIB_DIR="/data/data/$PKG/lib"
mkdir -p "$APP_LIB_DIR" 2>/dev/null
# Re-extract when the APK is newer, else an app update keeps running the old X server.
if { [ ! -f "$APP_LIB_DIR/libXlorie.so" ] || [ "$TERMUX_X11_APK_PATH" -nt "$APP_LIB_DIR/libXlorie.so" ]; } && [ -n "$TERMUX_X11_APK_PATH" ]; then
  echo "FluxLinux: Extracting libXlorie.so from APK..."
  # Try arm64 first, then armeabi-v7a
  ( cd "$APP_LIB_DIR" && \
    unzip -o "$TERMUX_X11_APK_PATH" 'lib/arm64-v8a/libXlorie.so' 2>/dev/null && \
    mv -f lib/arm64-v8a/libXlorie.so . && rm -rf lib ) || \
  ( cd "$APP_LIB_DIR" && \
    unzip -o "$TERMUX_X11_APK_PATH" 'lib/armeabi-v7a/libXlorie.so' 2>/dev/null && \
    mv -f lib/armeabi-v7a/libXlorie.so . && rm -rf lib )
  touch "$APP_LIB_DIR/libXlorie.so" 2>/dev/null # unzip keeps the 1981 entry mtime
  ls -la "$APP_LIB_DIR/libXlorie.so" 2>/dev/null && \
    echo "FluxLinux: libXlorie.so ready in $APP_LIB_DIR" || \
    echo "FluxLinux: [WARN] libXlorie.so extraction failed"
fi

# Launch the X server via app_process
# CLEAR LD_LIBRARY_PATH — setting it to Termux libs breaks system linker (libunwindstack.so)
# CmdEntryPoint finds libXlorie.so via ClassLoader resource lookup inside our APK
LD_LIBRARY_PATH="" LD_PRELOAD="" \
CLASSPATH="$TERMUX_PREFIX/libexec/termux-x11/loader.apk" \
TERMUX_X11_APK_PATH="$TERMUX_X11_APK_PATH" \
TERMUX_X11_OVERRIDE_PACKAGE="$PKG" \
LANG=en_US.UTF-8 \
/system/bin/app_process / \
  --nice-name="termux-x11" com.termux.x11.Loader :0 -legacy-drawing &
XSERVER_PID=$!
# Wait up to 15s for the X socket; ready marker only once it exists and the server lives.
_i=0
while [ ! -S "$TMPDIR/.X11-unix/X0" ] && [ "$_i" -lt 30 ] && kill -0 "$XSERVER_PID" 2>/dev/null; do
  sleep 0.5
  _i=$((_i + 1))
done
if [ ! -S "$TMPDIR/.X11-unix/X0" ] || ! kill -0 "$XSERVER_PID" 2>/dev/null; then
  echo "FluxLinux: [ERROR] X server did not create $TMPDIR/.X11-unix/X0 within 15s (or exited)."
  exit 1
fi
echo "FluxLinux: host X0 socket ready (X server PID=$XSERVER_PID)"

# Open X11 display activity in our app
echo "FluxLinux: Launching X11 display activity..."
am start -n "$PKG/com.termux.x11.MainActivity" \
  --activity-single-top \
  --activity-clear-top 2>/dev/null || \
am start -n "$PKG/com.termux.x11.MainActivity" 2>/dev/null
sleep 1

# Verify guest setup
ROOTFS="$TERMUX_PREFIX/var/lib/proot-distro/containers/$DISTRO/rootfs"
# FLUX_DESKTOP (env from DesktopLauncher): xfce4 (default) | kde
FLUX_DESKTOP="${FLUX_DESKTOP:-xfce4}"
case "$FLUX_DESKTOP" in
  kde) DE_CMD=startplasma-x11 ;;
  *) FLUX_DESKTOP=xfce4; DE_CMD=startxfce4 ;;
esac
if [ ! -e "$ROOTFS/usr/bin/$DE_CMD" ] && [ ! -e "$ROOTFS/usr/sbin/$DE_CMD" ]; then
  echo "FluxLinux: $FLUX_DESKTOP setup incomplete ($DE_CMD missing). Re-run environment setup."
  exit 1
fi

echo "FluxLinux: $DE_CMD=READY"

# Guest GPU mode from setup_hw_accel_debian.sh (/etc/fluxlinux/gpu_mode)
# turnip → Adreno/Zink; virgl → host virgl_test_server; else softpipe
GUEST_ROOTFS="$TERMUX_PREFIX/var/lib/proot-distro/containers/$DISTRO/rootfs"
GPU_MODE="virgl"
if [ -r "$GUEST_ROOTFS/etc/fluxlinux/gpu_mode" ]; then
  GPU_MODE=$(tr -d '[:space:]' <"$GUEST_ROOTFS/etc/fluxlinux/gpu_mode")
fi
# sanitize for embedding in guest shell
case "$GPU_MODE" in
  turnip|virgl) ;;
  *) GPU_MODE=virgl ;;
esac
echo "FluxLinux: Guest GPU mode=$GPU_MODE"

# Launch XFCE in proot — propagate guest exit code (never force exit 0)
GUEST_RC=0
if [ "$DISTRO" = "termux" ]; then
  export PULSE_SERVER=tcp:127.0.0.1
  env DISPLAY=:0 startxfce4
  GUEST_RC=$?
else
  # Single guest script: read mode file inside rootfs (no host quote hell).
  # Prefer bash when present (Debian + Alpine post-family); else sh.
  GUEST_SHELL=/bin/bash
  if [ ! -x "$ROOTFS/bin/bash" ] && [ ! -x "$ROOTFS/usr/bin/bash" ]; then
    GUEST_SHELL=/bin/sh
  fi
  _KGSL_BIND=""
  if [ -e /dev/kgsl-3d0 ]; then
    _KGSL_BIND="--bind=/dev/kgsl-3d0"
  fi
  python "$TERMUX_PREFIX/bin/proot-distro" login "$DISTRO" --shared-tmp $_KGSL_BIND -- \
    env FLUX_DESKTOP="$FLUX_DESKTOP" FLUX_GPU_RUNTIME="${FLUX_GPU_RUNTIME:-}" $GUEST_SHELL -c '
    export PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin
    unset PROOT_TMP_DIR
    export TMPDIR=/tmp
    export DISPLAY=:0
    export PULSE_SERVER=tcp:127.0.0.1
    export VTEST_SOCKET_NAME=/tmp/.virgl_test
    # Alpine glycin SVG loaders use bwrap — fails under proot and aborts GTK.
    export GLYCIN_DISABLE_SANDBOX=1
    # Fedora 43 / TW are glycin-only; no-glycin then cannot load PNG/SVG.
    if ls /usr/lib64/gdk-pixbuf-2.0/*/loaders/*png* /usr/lib/gdk-pixbuf-2.0/*/loaders/*png* >/dev/null 2>&1; then
      export GDK_DEBUG=no-glycin
    fi
    export GSK_RENDERER=cairo

    # Host/Termux may leak a bad session bus or XDG_CONFIG_DIRS without /etc
    # (xfce4-session failsafe: "XDG_CONFIG_DIRS must include /etc").
    unset DBUS_SESSION_BUS_ADDRESS DBUS_SESSION_BUS_PID DBUS_SESSION_BUS_WINDOWID
    export XDG_CONFIG_DIRS=/etc/xdg
    export XDG_DATA_DIRS=/usr/local/share:/usr/share
    # Runtime dir must be 700 *and* writable by flux. /tmp/runtime-flux lives on
    # --shared-tmp (host PREFIX/tmp, app uid) so guest flux cannot chmod/chown it.
    export XDG_RUNTIME_DIR=/home/flux/.cache/runtime
    mkdir -p "$XDG_RUNTIME_DIR" && chmod 700 "$XDG_RUNTIME_DIR"
    mkdir -p /tmp/.ICE-unix && chmod 1777 /tmp/.ICE-unix

    # Proot runs as the Android app uid; guest UIDs on disk that differ (e.g.
    # flux=10302 while host process is 10301) make mode-700 ~/.config unwritable
    # → xfconfd "Unable to create configuration directory" → failsafe session.
    # Match /home ownership (app uid). Avoid nested single quotes (this block is
    # already inside proot-distro login ... -c '...' ).
    if [ -d /home/flux ]; then
      _home_uid=$(stat -c %u /home 2>/dev/null || true)
      _home_gid=$(stat -c %g /home 2>/dev/null || true)
      if [ -n "$_home_uid" ]; then
        chown -R "$_home_uid:$_home_gid" /home/flux 2>/dev/null || true
        if [ "$_home_uid" = "0" ]; then
          chown -R flux:flux /home/flux 2>/dev/null || true
        fi
      fi
      mkdir -p /home/flux/.config /home/flux/.cache /home/flux/.local/share
      chmod 755 /home/flux 2>/dev/null || true
      # Open perms: host uid vs guest flux uid mismatch is common under proot
      chmod -R 777 /home/flux/.config /home/flux/.cache /home/flux/.local 2>/dev/null || true
    fi
    if [ -d /home/flux/.cache/runtime ]; then
      chmod 700 /home/flux/.cache/runtime 2>/dev/null || true
      chown flux:flux /home/flux/.cache/runtime 2>/dev/null || true
    fi
    # dbus machine-id (session bus soft-depends on it on some builds)
    if command -v dbus-uuidgen >/dev/null 2>&1; then
      dbus-uuidgen --ensure=/etc/machine-id 2>/dev/null || true
      mkdir -p /var/lib/dbus
      if [ ! -e /var/lib/dbus/machine-id ]; then
        ln -sf /etc/machine-id /var/lib/dbus/machine-id 2>/dev/null || \
          cp -f /etc/machine-id /var/lib/dbus/machine-id 2>/dev/null || true
      fi
    fi

    if [ -r /usr/local/lib/fluxlinux/apply_gpu_env.sh ]; then
      . /usr/local/lib/fluxlinux/apply_gpu_env.sh
      flux_gpu_apply_runtime
    else
      GPU_MODE=virgl
      if [ -r /etc/fluxlinux/gpu_mode ]; then
        GPU_MODE=$(tr -d "[:space:]" </etc/fluxlinux/gpu_mode)
      fi
      case "$GPU_MODE" in turnip|virgl) ;; *) GPU_MODE=virgl ;; esac
      unset GALLIUM_DRIVER MESA_LOADER_DRIVER_OVERRIDE VK_ICD_FILENAMES
      unset LIBGL_ALWAYS_SOFTWARE TU_DEBUG MESA_VK_WSI_DEBUG
      case "$GPU_MODE" in
        turnip)
          export MESA_LOADER_DRIVER_OVERRIDE=zink
          export VK_ICD_FILENAMES=/usr/share/vulkan/icd.d/freedreno_icd.aarch64.json
          export TU_DEBUG=noconform
          export MESA_VK_WSI_DEBUG=sw
          export MESA_GL_VERSION_OVERRIDE=4.6
          export MESA_GLES_VERSION_OVERRIDE=3.2
          ;;
        virgl)
          if [ -S /tmp/.virgl_test ]; then
            export GALLIUM_DRIVER=virpipe
          else
            export LIBGL_ALWAYS_SOFTWARE=1
            export GALLIUM_DRIVER=llvmpipe
            echo "FluxLinux(guest): VirGL socket missing — llvmpipe fallback"
          fi
          ;;
        *)
          export LIBGL_ALWAYS_SOFTWARE=1
          export GALLIUM_DRIVER=llvmpipe
          ;;
      esac
      export GPU_MODE
    fi
    echo "FluxLinux(guest): GPU mode=$GPU_MODE"
    # Fedora 43 / Tumbleweed GTK+glycin execs bwrap to load PNG/SVG.
    # Real bubblewrap needs user namespaces (missing under proot) and
    # "chmod a-x bwrap" becomes "Could not spawn bwrap: Permission denied"
    # which aborts xfce4-panel. Install Alpine-style shim instead.
    if [ ! -f /usr/bin/bwrap ] || ! grep -q "FluxLinux proot" /usr/bin/bwrap 2>/dev/null; then
      if [ -x /usr/bin/bwrap ] && [ ! -e /usr/bin/bwrap.real ]; then
        mv /usr/bin/bwrap /usr/bin/bwrap.real 2>/dev/null || true
      fi
      cat > /usr/bin/bwrap << "BWRAP_EOF"
#!/bin/sh
# FluxLinux: exec the real glycin loader, not an earlier --ro-bind source.
while [ $# -gt 0 ]; do
  case "$1" in
    /usr/libexec/glycin-loaders/*|/usr/lib/glycin-loaders/*|/usr/bin/true|/bin/true)
      if [ -f "$1" ] && [ -x "$1" ]; then
        exec "$@"
      fi
      ;;
  esac
  shift
done
echo "bwrap-shim: no command" >&2
exit 127
BWRAP_EOF
    fi
    chmod 755 /usr/bin/bwrap /usr/bin/bubblewrap 2>/dev/null || true
    # Use bash login shell for GUI (avoid zshrc noise); fall back to sh.
    FLUX_DE_CMD=startxfce4
    FLUX_KDE_ENV=
    if [ "$FLUX_DESKTOP" = kde ]; then
      FLUX_DE_CMD=startplasma-x11
      # GPU env for KDE: zink OpenGL compositing on Turnip/PanVK, none on software.
      mkdir -p /usr/local/lib/fluxlinux
      cat > /usr/local/lib/fluxlinux/flux_kde_env.sh << "KDE_EOF"
# Sourced after flux_gpu_apply_runtime. Plasma 6 kwin_x11: O2 = desktop OpenGL.
# OPT-IN (touch /etc/fluxlinux/kde_gl): KWin O2 on zink shows a black screen on
# PanVK (Xlorie, verified Poco X6 Pro); Turnip unverified. Default stays N.
export QT_QPA_PLATFORMTHEME=kde KWIN_COMPOSE=N
if [ -e /etc/fluxlinux/kde_gl ]; then case "$GPU_MODE" in turnip|panvk)
  export KWIN_COMPOSE=O2
  _icd=/usr/share/vulkan/icd.d/freedreno_icd.aarch64.json
  if [ "$GPU_MODE" = turnip ] && [ "$MESA_LOADER_DRIVER_OVERRIDE" = kgsl ] && [ -r "$_icd" ]; then
    export MESA_LOADER_DRIVER_OVERRIDE=zink GALLIUM_DRIVER=zink VK_ICD_FILENAMES=$_icd
    unset MESA_GL_VERSION_OVERRIDE MESA_GLES_VERSION_OVERRIDE
    _o=$(timeout -k 2 30 glxinfo -B 2>/dev/null) || _o=
    case "$_o" in
      *zink*) echo "FluxLinux(guest): KDE GL = zink on Turnip" ;;
      *)
        echo "FluxLinux(guest): zink-on-Turnip probe failed - kgsl fallback"
        unset GALLIUM_DRIVER VK_ICD_FILENAMES
        export MESA_LOADER_DRIVER_OVERRIDE=kgsl MESA_GL_VERSION_OVERRIDE=4.6 MESA_GLES_VERSION_OVERRIDE=3.2
        ;;
    esac
  fi
  ;;
esac; fi
# PanVK: every Qt process (kded, ksmserver, plasmashell...) opened its own Mali device
# (~260 MB each, 24 at login); the session OOM-killed the app on a 7.5 GB Poco X6 Pro.
# Compositing is off, so Qt needs no GL: software Quick + no xcb GL probe. Non-Qt GL
# (glxinfo, glxgears) still uses zink; GL Qt apps: env -u QT_XCB_GL_INTEGRATION -u QT_QUICK_BACKEND.
case "$GPU_MODE:$KWIN_COMPOSE" in panvk:N) export QT_QUICK_BACKEND=software QT_XCB_GL_INTEGRATION=none;; esac
# Persist compositing/effects off in the user config (like XFCE use_compositing=false);
# idempotent, keeps other keys. Opt-in O2 turns compositing back on.
_kw=$(command -v kwriteconfig6 || command -v kwriteconfig5)
if [ -n "$_kw" ]; then
  if [ "$KWIN_COMPOSE" = O2 ]; then $_kw --file kwinrc --group Compositing --key Enabled true
  else
    $_kw --file kwinrc --group Compositing --key Enabled false
    for _p in blur contrast slide translucency kwin4_effect_translucency; do $_kw --file kwinrc --group Plugins --key ${_p}Enabled false; done
    $_kw --file kdeglobals --group KDE --key AnimationDurationFactor 0
  fi
fi
KDE_EOF
      FLUX_KDE_ENV=". /usr/local/lib/fluxlinux/flux_kde_env.sh"
    fi
    FLUX_SU_SHELL=/bin/bash
    if [ ! -x /bin/bash ] && [ ! -x /usr/bin/bash ]; then
      FLUX_SU_SHELL=/bin/sh
    fi
    su -s "$FLUX_SU_SHELL" - flux -c "
      unset DBUS_SESSION_BUS_ADDRESS DBUS_SESSION_BUS_PID DBUS_SESSION_BUS_WINDOWID
      export DISPLAY=:0
      export PULSE_SERVER=tcp:127.0.0.1
      export XDG_CONFIG_DIRS=/etc/xdg
      export XDG_DATA_DIRS=/usr/local/share:/usr/share
      export HOME=/home/flux
      export XDG_CONFIG_HOME=/home/flux/.config
      export XDG_CACHE_HOME=/home/flux/.cache
      export XDG_DATA_HOME=/home/flux/.local/share
      export XDG_RUNTIME_DIR=/home/flux/.cache/runtime
      mkdir -p /home/flux/.config /home/flux/.cache /home/flux/.local/share
      mkdir -p \"\$XDG_RUNTIME_DIR\" && chmod 700 \"\$XDG_RUNTIME_DIR\"
      export VTEST_SOCKET_NAME=/tmp/.virgl_test
      export GLYCIN_DISABLE_SANDBOX=i-know-the-risks
      if ls /usr/lib64/gdk-pixbuf-2.0/*/loaders/*png* /usr/lib/gdk-pixbuf-2.0/*/loaders/*png* >/dev/null 2>&1; then
        export GDK_DEBUG=no-glycin
      fi
      export GSK_RENDERER=cairo
      if [ -r /usr/local/lib/fluxlinux/apply_gpu_env.sh ]; then
        . /usr/local/lib/fluxlinux/apply_gpu_env.sh
        flux_gpu_apply_runtime
      else
        export GPU_MODE=$GPU_MODE
        if [ \"\$GPU_MODE\" = turnip ]; then
          export MESA_LOADER_DRIVER_OVERRIDE=zink
          export VK_ICD_FILENAMES=/usr/share/vulkan/icd.d/freedreno_icd.aarch64.json
          export TU_DEBUG=noconform
          export MESA_VK_WSI_DEBUG=sw
          export MESA_GL_VERSION_OVERRIDE=4.6
          export MESA_GLES_VERSION_OVERRIDE=3.2
        elif [ \"\$GPU_MODE\" = virgl ] && [ -S /tmp/.virgl_test ]; then
          export GALLIUM_DRIVER=virpipe
        else
          export LIBGL_ALWAYS_SOFTWARE=1
          export GALLIUM_DRIVER=llvmpipe
        fi
      fi
      if [ \"$FLUX_GPU_RUNTIME\" = software ]; then
        unset MESA_LOADER_DRIVER_OVERRIDE VK_ICD_FILENAMES TU_DEBUG MESA_VK_WSI_DEBUG
        export LIBGL_ALWAYS_SOFTWARE=1 GALLIUM_DRIVER=llvmpipe GPU_MODE=software
      fi
      $FLUX_KDE_ENV
      if command -v dbus-run-session >/dev/null 2>&1; then
        exec dbus-run-session -- $FLUX_DE_CMD
      else
        exec dbus-launch --exit-with-session $FLUX_DE_CMD
      fi
    "
  '
  GUEST_RC=$?
fi

echo "FluxLinux: XFCE session ended (exit $GUEST_RC)"
exit $GUEST_RC
