#!/bin/bash
# setup_office_ubuntu.sh
# Installs Office Productivity Stack
# Target: Ubuntu 26.04 LTS (Resolute) ARM64
# Compatible with: Chroot and Proot
# Usage: setup_office_ubuntu.sh [uninstall]
#
# Ubuntu is Debian-LIKE, not Debian: repos stay on ports.ubuntu.com, never
# add debian.org, PPAs, or ubuntu-desktop. Office packages live in universe,
# which the base rootfs does not enable.

# Office-specific packages. Shared system deps (dbus-x11, fonts) intentionally
# excluded — they're used by XFCE, browsers, and other components.
PKGS=(
    libreoffice
    libreoffice-gtk3
    libreoffice-writer
    libreoffice-calc
    libreoffice-impress
    thunderbird
    papers
    evince
    xournalpp
    fonts-noto
)

# ─── UNINSTALL MODE ──────────────────────────────────────────────────────
if [ "$1" = "uninstall" ]; then
    echo "FluxLinux: Uninstalling Office Productivity Environment..."

    export DEBIAN_FRONTEND=noninteractive
    apt remove -y --purge "${PKGS[@]}" 2>/dev/null || true
    apt autoremove -y 2>/dev/null || true

    echo "FluxLinux: Office Productivity Environment Uninstalled."
    exit 0
fi
# ─── END UNINSTALL MODE ──────────────────────────────────────────────────

# Error Handler
handle_error() {
    echo ""
    echo "❌ FluxLinux Error: Script failed at step: $1"
    echo "---------------------------------------------------"
    echo "Please check the error message above for details."
    echo "---------------------------------------------------"
    read -p "Press Enter to acknowledge error and exit..."
    exit 1
}

echo "FluxLinux: Setting up Office Productivity Environment..."
echo "Target: Ubuntu 26.04 LTS (Resolute) - ARM64"

# 0. Enable universe (thunderbird, xournalpp, npm and fonts-noto live there).
# The 26.04 rootfs ships DEB822 ubuntu.sources with main/restricted only.
# Rewrite in place — never append a new archive.ubuntu.com or PPA entry.
enable_universe() {
    echo "FluxLinux: Ensuring 'universe' component is enabled..."
    _changed=0

    # DEB822 (/etc/apt/sources.list.d/*.sources)
    for f in /etc/apt/sources.list.d/*.sources; do
        [ -f "$f" ] || continue
        grep -q '^Components:' "$f" || continue
        grep -qE '^Components:.*\buniverse\b' "$f" && continue
        _tmp="${f}.fluxnew.$$"
        sed -e 's|^\(Components:.*\)$|\1 universe|' "$f" > "$_tmp" \
            && mv -f "$_tmp" "$f" && _changed=1
    done

    # Legacy one-line format (/etc/apt/sources.list)
    if [ -f /etc/apt/sources.list ] && \
       grep -qE '^deb .*ports\.ubuntu\.com' /etc/apt/sources.list && \
       ! grep -qE '^deb .*ports\.ubuntu\.com.* universe' /etc/apt/sources.list; then
        _tmp="/etc/apt/sources.list.fluxnew.$$"
        sed -e '/^deb .*ports\.ubuntu\.com/ s|$| universe|' /etc/apt/sources.list > "$_tmp" \
            && mv -f "$_tmp" /etc/apt/sources.list && _changed=1
    fi

    [ "$_changed" -eq 1 ] && echo " [✅] universe enabled" || echo " [ℹ️] universe already present"
}

# 1. System Dependencies
echo "FluxLinux: Installing Dependencies..."
export DEBIAN_FRONTEND=noninteractive
enable_universe
apt update -y

# Install essential fonts first (these are small and reliable)
apt install -y \
    dbus-x11 \
    fonts-noto-core \
    fonts-liberation \
    fonts-dejavu \
    || handle_error "Dependencies & Fonts"

# Conditional NPM Install (Fix for NodeSource Conflict)
# If setup_webdev_ubuntu.sh ran, nodejs includes npm.
# If not, Ubuntu split packages might need explicit npm.
if ! command -v npm >/dev/null; then
    echo "FluxLinux: NPM not found (bundled), installing explicitly..."
    apt install -y npm || echo " [⚠️] NPM install warning (might be bundled)"
fi

# 2. LibreOffice Suite
echo "FluxLinux: Installing LibreOffice Suite..."
# Use --no-install-recommends to avoid large optional packages like fonts-noto-extra
# which can fail in proot environments due to resource constraints
apt install -y --no-install-recommends \
    libreoffice \
    libreoffice-gtk3 \
    || {
        echo "⚠️ LibreOffice install had issues, attempting to fix..."
        # Fix any broken packages (common in proot with large fonts)
        apt --fix-broken install -y
        dpkg --configure -a
        # Retry with just the core package
        apt install -y --no-install-recommends libreoffice-writer libreoffice-calc libreoffice-impress libreoffice-gtk3 \
            || handle_error "LibreOffice Installation"
    }

# 3. Email & PIM
echo "FluxLinux: Installing Email & Organization Tools..."
# Ubuntu ships thunderbird as a snap transitional deb (24.04+). snapd cannot
# run under proot and is pointless in chroot, so install only the real deb.
# Non-fatal: mail is optional, LibreOffice is the headline of this stack.
if apt-cache depends thunderbird 2>/dev/null | grep -qE '^[[:space:]]*(Pre)?Depends:[[:space:]]*snapd'; then
    echo " [⚠️] thunderbird in this release is a snap transitional package — skipped."
    echo "      Install a real deb manually if you need it (no PPAs)."
else
    apt install -y --no-install-recommends thunderbird \
        || echo " [⚠️] Thunderbird not installed (universe/mirror issue)."
fi

# 4. PDF Tools
echo "FluxLinux: Installing PDF Tools..."
# GNOME renamed Evince to Papers. On 26.04 papers is the stable release in
# main (50.x) while evince is still an alpha in universe (49~alpha), so try
# papers first and keep evince as the fallback for older bases.
# Non-fatal: LibreOffice can open PDFs anyway.
if ! apt install -y --no-install-recommends papers 2>/dev/null; then
    apt --fix-broken install -y 2>/dev/null || true
    dpkg --configure -a 2>/dev/null || true
    apt install -y --no-install-recommends evince 2>/dev/null || \
        echo " [⚠️] No PDF viewer installed (papers/evince unavailable). LibreOffice can still open PDFs."
fi

apt install -y --no-install-recommends xournalpp 2>/dev/null || \
    echo " [⚠️] xournalpp not installed (check that universe is enabled)."

# 5. Optional: Try to install extra fonts (non-fatal if fails)
echo "FluxLinux: Installing additional fonts (optional)..."
apt install -y fonts-noto 2>/dev/null || echo " [⚠️] Optional fonts skipped (proot limitation)"

# 6. Verification
verify_installation() {
    echo ""
    echo "🔎 FluxLinux: Verifying Installations..."
    echo "------------------------------------------------"

    if command -v libreoffice >/dev/null; then echo " [✅] LibreOffice"; else echo " [❌] LibreOffice Missing"; fi
    if command -v thunderbird >/dev/null; then echo " [✅] Thunderbird"; else echo " [⚠️] Thunderbird Skipped"; fi
    if command -v papers >/dev/null; then
        echo " [✅] Papers"
    elif command -v evince >/dev/null; then
        echo " [✅] Evince"
    else
        echo " [⚠️] PDF Viewer Missing"
    fi
    if command -v xournalpp >/dev/null; then echo " [✅] Xournal++"; else echo " [❌] Xournal++ Missing"; fi

    echo "------------------------------------------------"
    echo "🎉 Office Setup Complete!"
}

verify_installation

echo "Note: Check your Applications menu for installed tools."
read -p "Press Enter to close..."
