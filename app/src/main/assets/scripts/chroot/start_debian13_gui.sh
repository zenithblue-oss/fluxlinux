#!/system/bin/sh
# start_debian13_gui.sh — root: mount Debian chroot + launch XFCE4
# Called by start_gui_chroot.sh after host Pulse/VirGL/X11 are up.
# Paths: app package via FLUX_PACKAGE / TARGET_PREFIX (ivarna or zenithblue). Sticky guest /tmp preserved.

DEBIANPATH="${DEBIANPATH:-/data/local/tmp/chrootDebian13}"
if [ -z "${TARGET_PREFIX:-}" ]; then
  if [ -n "${FLUX_PREFIX:-}" ]; then
    TARGET_PREFIX="$FLUX_PREFIX"
  elif [ -n "${FLUX_PACKAGE:-}" ]; then
    TARGET_PREFIX="/data/data/${FLUX_PACKAGE}/files/usr"
  elif [ -d /data/data/com.zenithblue.fluxlinux/files/usr ]; then
    TARGET_PREFIX="/data/data/com.zenithblue.fluxlinux/files/usr"
  else
    TARGET_PREFIX="/data/data/com.ivarna.fluxlinux/files/usr"
  fi
fi
USERNAME="${USERNAME:-flux}"

echo "========================================"
echo "FluxLinux: Chroot XFCE (root stage)"
echo "  rootfs=$DEBIANPATH"
echo "========================================"

# resolve root BusyBox (manager built-in; NDK module not required)
_rr=""
for _c in \
  "${FLUX_RESOLVE_BB:-}" \
  "$(dirname "$0")/resolve_bb.sh" \
  /data/local/tmp/fluxlinux_resolve_bb.sh
do
  [ -n "$_c" ] && [ -f "$_c" ] && _rr="$_c" && break
done
if [ -n "$_rr" ]; then
  # shellcheck disable=SC1090
  . "$_rr"
  resolve_bb || true
fi
if [ -z "${BB:-}" ]; then
  # sidecar missing (desktop/uninstall/staged setup) — same B1 walk as resolve_bb
  if [ -n "${FLUX_BB:-}" ] && [ -x "$FLUX_BB" ] &&
     "$FLUX_BB" --list >/dev/null 2>&1; then BB="$FLUX_BB"; fi
  if [ -z "${BB:-}" ] && [ -x /data/local/tmp/flux_busybox ] &&
     /data/local/tmp/flux_busybox --list >/dev/null 2>&1; then
    BB=/data/local/tmp/flux_busybox
  fi
  if [ -z "${BB:-}" ]; then
    for path in \
      /data/adb/ksu/bin/busybox \
      /data/adb/ap/bin/busybox \
      /data/adb/magisk/busybox \
      /data/adb/modules/busybox-ndk/system/xbin/busybox \
      /data/adb/modules/busybox-ndk/system/bin/busybox \
      /debug_ramdisk/busybox \
      /sbin/busybox \
      /system/xbin/busybox \
      /system/bin/busybox
    do
      if [ -x "$path" ]; then BB="$path"; break; fi
    done
  fi
fi
if [ -z "${BB:-}" ]; then
  echo "FluxLinux: ERROR — root-capable busybox not found" >&2
  exit 1
fi

echo "FluxLinux: busybox=$BB"

if [ ! -d "$DEBIANPATH" ]; then
  echo "FluxLinux: ERROR — chroot missing: $DEBIANPATH"
  exit 1
fi
if [ ! -x "$DEBIANPATH/usr/bin/startxfce4" ] && [ ! -f "$DEBIANPATH/usr/bin/startxfce4" ]; then
  echo "FluxLinux: ERROR — startxfce4 missing. Re-run chroot environment setup."
  exit 1
fi

# Soft SELinux (HyperOS / enforcing) — flux pattern; fail soft
if command -v getenforce >/dev/null 2>&1; then
  SELINUX_STATUS=$(getenforce 2>/dev/null || true)
  echo "FluxLinux: SELinux=$SELINUX_STATUS"
  if [ "$SELINUX_STATUS" = "Enforcing" ]; then
    setenforce 0 2>/dev/null && echo "FluxLinux: SELinux → Permissive (until reboot)" \
      || echo "FluxLinux: [WARN] setenforce 0 failed"
  fi
fi
# PREFIX/tmp must stay app_data_file. Labeling it tmpfs:s0 makes termux-x11
# fail to create .X11-unix / .tX0-lock / dbus sockets when SELinux is enforcing.
if command -v chcon >/dev/null 2>&1; then
  _ctx=$(ls -Zd "$TARGET_PREFIX" 2>/dev/null | awk '{print $1}')
  if [ -n "$_ctx" ]; then
    chcon -R "$_ctx" "$TARGET_PREFIX/tmp" 2>/dev/null || true
  fi
fi
restorecon -RF "$TARGET_PREFIX/tmp" 2>/dev/null || true

HELPER="${HELPER:-/data/local/tmp/fluxlinux_chroot.sh}"
echo "[1/5] Mounts (SSOT if available)..."
# Wait for host Loader to create X0 before --x11 bind
mkdir -p "$TARGET_PREFIX/tmp/.X11-unix" 2>/dev/null || true
chmod 1777 "$TARGET_PREFIX/tmp/.X11-unix" 2>/dev/null || true
i=0
while [ $i -lt 15 ]; do
  if [ -S "$TARGET_PREFIX/tmp/.X11-unix/X0" ]; then
    echo "FluxLinux: host X0 socket ready"
    break
  fi
  i=$((i + 1))
  sleep 1
done
if [ ! -S "$TARGET_PREFIX/tmp/.X11-unix/X0" ]; then
  echo "FluxLinux: [WARN] host X0 not seen yet — continuing"
fi

if [ -f "$HELPER" ]; then
  export FLUX_CHROOT="$DEBIANPATH"
  export FLUX_PREFIX="$TARGET_PREFIX"
  export FLUX_HOST_TMP="${TARGET_PREFIX}/tmp"
  # Legacy aliases (if an older helper still reads NC_*)
  export NC_CHROOT="$DEBIANPATH"
  export NC_PREFIX="$TARGET_PREFIX"
  export NC_HOST_TMP="${TARGET_PREFIX}/tmp"
  sh "$HELPER" mount --x11 || true
  echo "[2/5] X11 via fluxlinux_chroot mount --x11"
else
  /system/bin/mount -o remount,dev,suid /data 2>/dev/null \
    || $BB mount -o remount,dev,suid /data 2>/dev/null || true
  $BB mount --bind /dev "$DEBIANPATH/dev" 2>/dev/null || true
  $BB mount --bind /sys "$DEBIANPATH/sys" 2>/dev/null || true
  $BB mount -t proc proc "$DEBIANPATH/proc" 2>/dev/null || true
  $BB mount -t devpts devpts "$DEBIANPATH/dev/pts" 2>/dev/null || true
  mkdir -p "$DEBIANPATH/dev/shm"
  $BB mount -t tmpfs -o size=512M,mode=1777 tmpfs "$DEBIANPATH/dev/shm" 2>/dev/null || true
  mkdir -p "$DEBIANPATH/tmp" "$DEBIANPATH/mnt/host-tmp"
  if grep -q " $DEBIANPATH/tmp " /proc/mounts 2>/dev/null; then
    $BB umount "$DEBIANPATH/tmp" 2>/dev/null || $BB umount -l "$DEBIANPATH/tmp" 2>/dev/null || true
  fi
  chmod 1777 "$DEBIANPATH/tmp" 2>/dev/null || true
  $BB mount --bind "$TARGET_PREFIX/tmp" "$DEBIANPATH/mnt/host-tmp" 2>/dev/null || true
  mkdir -p "$DEBIANPATH/sdcard"
  $BB mount --bind /sdcard "$DEBIANPATH/sdcard" 2>/dev/null || true
  echo "[2/5] X11 socket bind (legacy)..."
  mkdir -p "$DEBIANPATH/tmp/.X11-unix"
  if grep -q " $DEBIANPATH/tmp/.X11-unix " /proc/mounts 2>/dev/null; then
    $BB umount "$DEBIANPATH/tmp/.X11-unix" 2>/dev/null || $BB umount -l "$DEBIANPATH/tmp/.X11-unix" 2>/dev/null || true
  fi
  $BB mount --bind "$TARGET_PREFIX/tmp/.X11-unix" "$DEBIANPATH/tmp/.X11-unix" 2>/dev/null \
    || mount --bind "$TARGET_PREFIX/tmp/.X11-unix" "$DEBIANPATH/tmp/.X11-unix" 2>/dev/null || true
fi

echo "[3/5] Kill stale XFCE in chroot..."
if [ -f "$HELPER" ]; then
  sh "$HELPER" sh --user root -- \
    "killall -9 xfce4-session xfwm4 xfdesktop xfce4-panel plasmashell kwin_x11 startplasma-x11 plasma_session ksmserver dbus-launch dbus-daemon 2>/dev/null; true" \
    >/dev/null 2>&1 || true
else
  $BB chroot "$DEBIANPATH" /bin/su - root -c \
    "killall -9 xfce4-session xfwm4 xfdesktop xfce4-panel plasmashell kwin_x11 startplasma-x11 plasma_session ksmserver dbus-launch dbus-daemon 2>/dev/null; true" \
    >/dev/null 2>&1
fi

echo "[4/5] GPU mode + launch ${FLUX_DESKTOP:-xfce4} as $USERNAME..."
# FLUX_DESKTOP (env from DesktopLauncher): xfce4 (default) | kde. Expanded into the guest script below.
FLUX_DE_CMD=startxfce4
FLUX_KDE_ENV=
if [ "${FLUX_DESKTOP:-xfce4}" = kde ]; then
  FLUX_DE_CMD=startplasma-x11
  # Qt/KDE reject /tmp as XDG_RUNTIME_DIR (wrong perms); use a private 0700 dir.
  # GPU env for KDE: zink OpenGL compositing on Turnip/PanVK, none on software.
  mkdir -p "$DEBIANPATH/usr/local/lib/fluxlinux"
  cat > "$DEBIANPATH/usr/local/lib/fluxlinux/flux_kde_env.sh" << 'KDE_EOF'
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
  chmod 644 "$DEBIANPATH/usr/local/lib/fluxlinux/flux_kde_env.sh"
  FLUX_KDE_ENV="export XDG_RUNTIME_DIR=/home/$USERNAME/.cache/runtime; mkdir -p /home/$USERNAME/.cache/runtime; chmod 700 /home/$USERNAME/.cache/runtime; . /usr/local/lib/fluxlinux/flux_kde_env.sh"
fi
# Guest script: sticky /tmp X11 + host-tmp VirGL + gpu_mode file
$BB chroot "$DEBIANPATH" /bin/bash -c "
export PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin
export TMPDIR=/tmp
su - $USERNAME -c '
  export DISPLAY=:0
  export PULSE_SERVER=tcp:127.0.0.1
  export XDG_RUNTIME_DIR=/tmp
  export VTEST_SOCKET_NAME=/mnt/host-tmp/.virgl_test

  if [ -r /usr/local/lib/fluxlinux/apply_gpu_env.sh ]; then
    . /usr/local/lib/fluxlinux/apply_gpu_env.sh
    flux_gpu_apply_runtime
  else
    GPU_MODE=virgl
    if [ -r /etc/fluxlinux/gpu_mode ]; then
      GPU_MODE=\$(tr -d \"[:space:]\" </etc/fluxlinux/gpu_mode)
    fi
    case \"\$GPU_MODE\" in turnip|virgl) ;; *) GPU_MODE=virgl ;; esac
    if [ \"\$GPU_MODE\" = turnip ]; then
      export MESA_LOADER_DRIVER_OVERRIDE=zink
      export VK_ICD_FILENAMES=/usr/share/vulkan/icd.d/freedreno_icd.aarch64.json
      export TU_DEBUG=noconform
      export MESA_VK_WSI_DEBUG=sw
      export MESA_GL_VERSION_OVERRIDE=4.6
      export MESA_GLES_VERSION_OVERRIDE=3.2
    elif [ \"\$GPU_MODE\" = virgl ] && [ -S /mnt/host-tmp/.virgl_test ]; then
      export GALLIUM_DRIVER=virpipe
    else
      export LIBGL_ALWAYS_SOFTWARE=1
      export GALLIUM_DRIVER=llvmpipe
      echo \"FluxLinux(guest): software GL fallback\"
    fi
    export GPU_MODE
  fi
  echo \"FluxLinux(guest): GPU mode=\$GPU_MODE\"

  xfconf-query -c xfwm4 -p /general/use_compositing -s false 2>/dev/null || true
  if [ x$FLUX_GPU_RUNTIME = xsoftware ]; then
    unset MESA_LOADER_DRIVER_OVERRIDE VK_ICD_FILENAMES TU_DEBUG MESA_VK_WSI_DEBUG
    export LIBGL_ALWAYS_SOFTWARE=1 GALLIUM_DRIVER=llvmpipe GPU_MODE=software
  fi
  $FLUX_KDE_ENV
  exec dbus-launch --exit-with-session $FLUX_DE_CMD
'
"
rc=$?
echo "[5/5] XFCE session ended (exit $rc)"
echo "========================================"
exit $rc
