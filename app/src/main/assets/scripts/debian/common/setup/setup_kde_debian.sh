#!/bin/bash
# setup_kde_debian.sh
# Base post-install configuration for KDE Plasma on Debian-based distros (PRoot / Chroot)
# Mirrors what setup_debian_family.sh does, but installs KDE Plasma instead of XFCE4
# Usage: setup_kde_debian.sh <distro_name> [uninstall]

# KDE-specific packages. Shared system packages (xorg, xauth, dbus-x11,
# tigervnc-standalone-server, sddm) and the 'flux' user are intentionally
# excluded — they're used by XFCE and other components.
PKGS=(
    kde-standard
    spectacle
    kwallet-pam
    qt5ct
    qt5-style-plugins
    systemsettings
    plasma-discover
    kinfocenter
    okular
    gwenview
    kcalc
    kwrite
    kfind
    filelight
    partitionmanager
)

# ─── UNINSTALL MODE ──────────────────────────────────────────────────────
# Detect uninstall as either first or second argument (since DISTRO_NAME=$1 may come first).
if [ "$1" = "uninstall" ] || [ "$2" = "uninstall" ]; then
    DISTRO_NAME="${1:-debian}"
    [ "$1" = "uninstall" ] && DISTRO_NAME="debian"

    echo "FluxLinux: Uninstalling KDE Plasma Environment..."

    export DEBIAN_FRONTEND=noninteractive
    apt remove -y --purge "${PKGS[@]}" 2>/dev/null || true
    apt autoremove -y 2>/dev/null || true

    # Remove KDE-specific marker and config
    rm -f /home/flux/.fluxlinux/kde_installed
    rm -rf /home/flux/.vnc  # KDE xstartup; vncserver will be re-created by xfce install if needed

    echo "FluxLinux: KDE Plasma Environment Uninstalled."
    exit 0
fi
# ─── END UNINSTALL MODE ──────────────────────────────────────────────────

DISTRO_NAME="${1:-debian}"
LOG_FILE="/tmp/fluxlinux_kde_install.log"

echo "FluxLinux: Configuring ${DISTRO_NAME} with KDE Plasma..."

# Redirect all output to log
exec > >(tee -a "$LOG_FILE") 2>&1

# ── Error Handler ──────────────────────────────────────────────────────────────
handle_error() {
    local STEP="$1"
    echo ""
    echo "❌ FluxLinux KDE Error: Script failed at step: $STEP"
    echo "──────────────────────────────────────────────────────"
    echo "Log saved to: $LOG_FILE"
    echo ""
    echo "Please copy the log content and send it to the developer:"
    echo "  Email: abhay02delhi@gmail.com"
    echo "  GitHub: https://github.com/abhay-byte/fluxlinux/issues"
    echo "──────────────────────────────────────────────────────"
    read -p "Press Enter to acknowledge error and exit..."

    # Notify FluxLinux app of failure
    am start -a android.intent.action.VIEW \
        -d "fluxlinux://callback?result=failure&name=kde_plasma_install_${DISTRO_NAME}" \
        2>/dev/null || true

    exit 1
}

export DEBIAN_FRONTEND=noninteractive

# 1. Update and install KDE Plasma core + goodies
echo "[1/7] Updating package lists..."
apt update -y || handle_error "apt update"

echo "[2/7] Installing KDE Plasma desktop (full suite)..."
# kde-standard: meta-package for complete KDE Plasma 6 suite on Debian Trixie
# Includes: plasmashell, kwin, systemsettings, dolphin, konsole, kate, ark,
#           gwenview, okular, spectacle, kcalc, kscreen, kinfocenter, and more
apt install -y \
    kde-standard \
    sddm \
    dbus-x11 \
    xorg \
    xauth \
    tigervnc-standalone-server || handle_error "KDE Plasma package installation"

# 2. Install useful KDE apps + optional packages (non-critical, failures are tolerated)
echo "[3/7] Installing KDE apps..."
apt install -y --no-install-recommends \
    spectacle \
    kwallet-pam \
    qt5ct \
    qt5-style-plugins \
    systemsettings \
    plasma-discover \
    kinfocenter \
    okular \
    gwenview \
    kcalc \
    kwrite \
    kfind \
    filelight \
    partitionmanager 2>/dev/null || true

# 3. Create user 'flux'
echo "[4/7] Creating user flux..."
if ! id "flux" &>/dev/null; then
    useradd -m -s /bin/bash flux || handle_error "Create user flux"
    echo "flux:flux" | chpasswd
    usermod -aG sudo flux
fi

# 4. Configure Sudo NOPASSWD
echo "[5/7] Configuring sudo..."
echo "flux ALL=(ALL) NOPASSWD:ALL" > /etc/sudoers.d/flux || handle_error "Configure sudo"

# 5. Configure VNC for KDE (xstartup)
echo "[6/7] Setting up KDE session launcher..."
mkdir -p /home/flux/.vnc
cat > /home/flux/.vnc/xstartup << 'VNCEOF'
#!/bin/bash
export PULSE_SERVER=127.0.0.1
export DBUS_SESSION_BUS_ADDRESS=$(dbus-launch --print-address)
xrdb $HOME/.Xresources 2>/dev/null
exec startplasma-x11
VNCEOF
chmod +x /home/flux/.vnc/xstartup
chown -R flux:flux /home/flux/.vnc

# Compositing + effects off system-wide by default (XFCE parity; GPU accel path).
# Per-user config is kept in sync at session start (flux_kde_env.sh).
mkdir -p /etc/xdg
[ -e /etc/xdg/kwinrc ] || cat > /etc/xdg/kwinrc << 'KWEOF'
[Compositing]
Enabled=false

[Plugins]
blurEnabled=false
contrastEnabled=false
slideEnabled=false
translucencyEnabled=false
kwin4_effect_translucencyEnabled=false
KWEOF
[ -e /etc/xdg/kdeglobals ] || printf '[KDE]\nAnimationDurationFactor=0\n' > /etc/xdg/kdeglobals

# 6. Disable SDDM (we use Termux:X11, not a display manager)
systemctl disable sddm 2>/dev/null || true

# 7. Mark as KDE-capable environment
echo "[7/7] Marking installation complete..."
mkdir -p /home/flux/.fluxlinux
touch /home/flux/.fluxlinux/kde_installed
chown -R flux:flux /home/flux/.fluxlinux

echo ""
echo "✅ FluxLinux: KDE Plasma base setup for ${DISTRO_NAME} complete!"
echo "Log saved at: $LOG_FILE"

# Notify FluxLinux app of success
am start -a android.intent.action.VIEW \
    -d "fluxlinux://callback?result=success&name=kde_plasma_install_${DISTRO_NAME}" \
    2>/dev/null || true
