#!/bin/bash
# setup_customization_kde_debian.sh
# Applies "FluxLinux" branding and customization to Debian KDE Plasma Desktop
# Mirrors setup_customization_debian.sh structure exactly, adapted for KDE
# Works for both Chroot and Proot environments (run as root, switches to user 'flux')

CUSTOM_USER="flux"
CUSTOM_GROUP="users"
USER_HOME="/home/$CUSTOM_USER"
THEME_DIR="/usr/share/themes"
ICON_DIR="/usr/share/icons"
LOG_FILE="/tmp/fluxlinux_kde_customization.log"

# Redirect all output to log
exec > >(tee -a "$LOG_FILE") 2>&1

# Error Handler
handle_error() {
    echo ""
    echo "❌ FluxLinux KDE Customization Error: Script failed at step: $1"
    echo "─────────────────────────────────────────────────────────────"
    echo "Log saved to: $LOG_FILE"
    echo ""
    echo "Please copy the log and send it to the developer:"
    echo "  Email: abhay02delhi@gmail.com"
    echo "  GitHub: https://github.com/abhay-byte/fluxlinux/issues"
    echo "─────────────────────────────────────────────────────────────"
    read -p "Press Enter to acknowledge error and exit..."

    # Notify FluxLinux app of failure
    am start -a android.intent.action.VIEW \
        -d "fluxlinux://callback?result=failure&name=kde_customization" \
        2>/dev/null || true

    exit 1
}

echo "FluxLinux: Starting KDE Plasma Customization..."

# 1. Install Dependencies
echo "FluxLinux: Installing customization tools..."
export DEBIAN_FRONTEND=noninteractive
apt update -y
apt install -y curl fastfetch wget unzip fontconfig locales breeze breeze-gtk-theme || handle_error "Dependency Installation"

# Setup Locales for proper font rendering in ZSH/Terminal
echo "FluxLinux: Setting up locales..."
echo "en_US.UTF-8 UTF-8" > /etc/locale.gen
locale-gen
update-locale LANG=en_US.UTF-8

# Install optional packages (may not be available on all Debian versions)
apt install -y qt5ct qt5-style-plugins systemsettings plasma-discover kinfocenter 2>/dev/null || true

# 2. Deploy Assets (From GitHub Release debian-v1)
ASSET_REPO="abhay-byte/fluxlinux"
ASSET_TAG="debian-v1"
BASE_URL="https://github.com/$ASSET_REPO/releases/download/$ASSET_TAG"

echo "FluxLinux: Downloading assets from $BASE_URL..."

# Helper to extract all contents
extract_all_assets() {
    local URL="$1"
    local TARGET_DIR="$2"
    local TEMP_ZIP="/tmp/$(basename "$URL")"

    echo " - Downloading $(basename "$URL")..."
    wget -q --show-progress "$URL" -O "$TEMP_ZIP"

    echo " - Extracting to $TARGET_DIR..."
    unzip -q -o "$TEMP_ZIP" -d "$TARGET_DIR"
    rm "$TEMP_ZIP"

    # Extract any nested tarballs
    find "$TARGET_DIR" -maxdepth 1 -name "*.tar.xz" -exec tar -xf {} -C "$TARGET_DIR" \;
    find "$TARGET_DIR" -maxdepth 1 -name "*.tar.gz" -exec tar -xzf {} -C "$TARGET_DIR" \;
    rm -f "$TARGET_DIR"/*.tar.xz "$TARGET_DIR"/*.tar.gz
}

# 3. Theme Selection Prompt
if [ -n "$FLUX_THEME" ]; then
    echo "FluxLinux: Auto-applying Theme: $FLUX_THEME"
    if [ "$FLUX_THEME" == "light" ]; then
        THEME_CHOICE="2"
    else
        THEME_CHOICE="1"
    fi
else
    echo "------------------------------------------------"
    echo "Select Theme Preference:"
    echo "1) Dark (Default)"
    echo "2) Light"
    read -p "Enter choice [1-2]: " THEME_CHOICE
    echo "------------------------------------------------"
fi

# Icons: Papirus-Dark only (no full Papirus light pack)
SEL_ICON="Papirus-Dark"

if [ "$THEME_CHOICE" == "2" ]; then
    echo "FluxLinux: Light Mode Selected (icons: Papirus-Dark only)."
    SEL_GTK_THEME="Space-light"
    SEL_CURSOR="Vimix-cursors"
    SEL_WALLPAPER="fluxlinux-light.png"
    KDE_COLOR_SCHEME="BreezeLight"
    KWIN_THEME="Breeze"
else
    echo "FluxLinux: Dark Mode Selected (icons: Papirus-Dark only)."
    SEL_GTK_THEME="Space-transparency"
    SEL_CURSOR="Vimix-white-cursors"
    SEL_WALLPAPER="fluxlinux-dark.png"
    KDE_COLOR_SCHEME="BreezeDark"
    KWIN_THEME="Breeze"
fi

# Install GTK Themes (same as XFCE — KDE apps use GTK theme via qt5-style-plugins)
echo "FluxLinux: Installing Themes..."
mkdir -p "$THEME_DIR"
extract_all_assets "$BASE_URL/theme.zip" "$THEME_DIR"

# Install Icons (Papirus works perfectly in KDE)
echo "FluxLinux: Installing Icons..."
mkdir -p "$ICON_DIR"
extract_all_assets "$BASE_URL/icons.zip" "$ICON_DIR"

# Install Cursors
echo "FluxLinux: Installing Cursors..."
extract_all_assets "$BASE_URL/cursor.zip" "$ICON_DIR"

# Wallpaper Setup
WALLPAPER_DIR="$USER_HOME/Pictures/Wallpapers"
mkdir -p "$WALLPAPER_DIR"
chown -R "$CUSTOM_USER:$CUSTOM_GROUP" "$USER_HOME/Pictures" 2>/dev/null

echo "FluxLinux: Downloading Wallpaper..."
TEMP_WP_ZIP="/tmp/wallpaper.zip"
wget -q --show-progress "$BASE_URL/wallpaper.zip" -O "$TEMP_WP_ZIP"
unzip -o -j "$TEMP_WP_ZIP" -d "$WALLPAPER_DIR"
rm "$TEMP_WP_ZIP"
[ -f "$WALLPAPER_DIR/dark.png" ] && mv "$WALLPAPER_DIR/dark.png" "$WALLPAPER_DIR/fluxlinux-dark.png"
[ -f "$WALLPAPER_DIR/light.png" ] && mv "$WALLPAPER_DIR/light.png" "$WALLPAPER_DIR/fluxlinux-light.png"
chown "$CUSTOM_USER:$CUSTOM_GROUP" "$WALLPAPER_DIR"/*

WALLPAPER_PATH="$WALLPAPER_DIR/$SEL_WALLPAPER"

# Install JetBrains Mono Nerd Font (identical to XFCE4 script)
FONT_DIR="/usr/share/fonts/truetype/jetbrains-mono-nerd"
FONT_INSTALLED=false

if fc-list | grep -qi "JetBrainsMono Nerd"; then
    echo "FluxLinux: JetBrains Mono Nerd Font already installed."
    FONT_INSTALLED=true
fi

if [ "$FONT_INSTALLED" = false ]; then
    echo "FluxLinux: Installing JetBrains Mono Nerd Font..."
    mkdir -p "$FONT_DIR"
    NERD_FONT_URL="https://github.com/ryanoasis/nerd-fonts/releases/latest/download/JetBrainsMono.zip"
    TEMP_ZIP="/tmp/JetBrainsMono.zip"
    echo " - Downloading JetBrains Mono Nerd Font..."
    wget -q --show-progress "$NERD_FONT_URL" -O "$TEMP_ZIP" || {
        echo "FluxLinux: Direct download failed, trying from release..."
        wget -q --show-progress "$BASE_URL/font.zip" -O "$TEMP_ZIP" || handle_error "Font Download"
    }
    echo " - Extracting font files..."
    unzip -o -j "$TEMP_ZIP" "*.ttf" -d "$FONT_DIR" 2>/dev/null || \
    unzip -o "$TEMP_ZIP" -d "$FONT_DIR" 2>/dev/null
    find "$FONT_DIR" -type f ! -name "*.ttf" ! -name "*.otf" -delete 2>/dev/null
    rm -f "$TEMP_ZIP"
    chmod 644 "$FONT_DIR"/*.ttf 2>/dev/null
    chmod 644 "$FONT_DIR"/*.otf 2>/dev/null
    echo " - Rebuilding font cache..."
    fc-cache -fv "$FONT_DIR"
    su -s /bin/bash - "$CUSTOM_USER" -c "fc-cache -f" 2>/dev/null
    if fc-list | grep -qi "JetBrainsMono Nerd"; then
        echo "FluxLinux: ✓ JetBrains Mono Nerd Font installed successfully!"
    else
        echo "FluxLinux: ⚠ Font may not be properly registered."
        ls -la "$FONT_DIR"
    fi
fi

# 4. Apply KDE Settings via config files
echo "FluxLinux: Applying KDE Plasma Settings..."

KDE_CONFIG="$USER_HOME/.config"
mkdir -p "$KDE_CONFIG"

# ── kdeglobals: global KDE theme, icons, fonts, cursor ──────────────────────
echo "FluxLinux: Writing kdeglobals..."
cat > "$KDE_CONFIG/kdeglobals" << EOF
[General]
ColorScheme=$KDE_COLOR_SCHEME
Name=$KDE_COLOR_SCHEME
shadeSortColumn=true
fixed=JetBrainsMonoNerdFontMono,10,-1,5,50,0,0,0,0,0
font=JetBrainsMonoNerdFont,10,-1,5,50,0,0,0,0,0
menuFont=JetBrainsMonoNerdFont,10,-1,5,50,0,0,0,0,0
smallestReadableFont=JetBrainsMonoNerdFont,8,-1,5,50,0,0,0,0,0
toolBarFont=JetBrainsMonoNerdFont,10,-1,5,50,0,0,0,0,0

[Icons]
Theme=$SEL_ICON

[KDE]
AnimationDurationFactor=0
LookAndFeelPackage=org.kde.breezedark.desktop
ShowDeleteCommand=false
SingleClick=false
widgetStyle=Breeze

[WM]
activeFont=JetBrainsMonoNerdFont,10,-1,5,700,0,0,0,0,0
EOF

# ── kcminputrc: cursor theme ─────────────────────────────────────────────────
echo "FluxLinux: Writing kcminputrc (cursor)..."
cat > "$KDE_CONFIG/kcminputrc" << EOF
[Mouse]
cursorSize=24
cursorTheme=$SEL_CURSOR
EOF

# ── kwinrc: window manager (Breeze), compositing, tiling ────────────────────
echo "FluxLinux: Writing kwinrc..."
cat > "$KDE_CONFIG/kwinrc" << EOF
[Compositing]
Backend=QPainter
Enabled=false
OpenGLIsUnsafe=true

[Plugins]
blurEnabled=false
contrastEnabled=false
slideEnabled=false
translucencyEnabled=false
kwin4_effect_translucencyEnabled=false

[Windows]
BorderlessMaximizedWindows=false
FocusPolicy=ClickToFocus
TitlebarDoubleClickCommand=Maximize
SnapAgainst=1

[org.kde.kdecoration2]
ButtonsOnLeft=M
ButtonsOnRight=HIA
library=org.kde.breeze
theme=Breeze
EOF

# ── plasmarc: Plasma shell theme ─────────────────────────────────────────────
echo "FluxLinux: Writing plasmarc..."
cat > "$KDE_CONFIG/plasmarc" << EOF
[Theme]
name=breeze-dark
EOF

# ── plasma-org.kde.plasma.desktop-appletsrc: wallpaper + panel layout ────────
echo "FluxLinux: Writing Plasma desktop config (wallpaper + panel)..."
cat > "$KDE_CONFIG/plasma-org.kde.plasma.desktop-appletsrc" << EOF
[Containments][1]
ItemGeometriesHorizontal=
activityId=
formfactor=0
immutability=1
lastScreen=0
location=0
plugin=org.kde.plasma.folder
wallpaperplugin=org.kde.image

[Containments][1][General]
ToolBoxButtonState=topcenter

[Containments][1][Wallpaper][org.kde.image][General]
Image=$WALLPAPER_PATH
PreviewImage=$WALLPAPER_PATH

[Containments][2]
activityId=
formfactor=2
immutability=1
lastScreen=0
location=4
plugin=org.kde.panel
wallpaperplugin=org.kde.image

[Containments][2][Applets][3]
immutability=1
plugin=org.kde.plasma.kickoff

[Containments][2][Applets][4]
immutability=1
plugin=org.kde.plasma.taskmanager

[Containments][2][Applets][5]
immutability=1
plugin=org.kde.plasma.systemtray

[Containments][2][Applets][6]
immutability=1
plugin=org.kde.plasma.digitalclock

[Containments][2][Applets][7]
immutability=1
plugin=org.kde.plasma.showdesktop

[Containments][2][General]
AppletOrder=3;4;5;6;7
EOF

# ── kglobalshortcutsrc: keyboard shortcuts (KDE equivalent) ─────────────────
echo "FluxLinux: Writing keyboard shortcuts (kglobalshortcutsrc)..."
cat > "$KDE_CONFIG/kglobalshortcutsrc" << 'EOF'
[kwin]
Show Desktop=Meta+D,Meta+D,Show Desktop
Window Maximize=Meta+Up,Meta+Up,Maximize Window
Window Close=Alt+F4,Alt+F4,Close Window
Walk Through Windows=Alt+Tab,Alt+Tab,Walk Through Windows
Reverse Walk Through Windows=Alt+Shift+Tab,Alt+Shift+Tab,Reverse Walk Through Windows
Window Fullscreen=Meta+F,none,Make Window Fullscreen
Window Minimize=Meta+M,none,Minimize Window
Window Move=Meta+W,none,Move Window
Window Resize=none,none,Resize Window

[org.kde.konsole.desktop]
NewTab=Ctrl+T,Ctrl+T,New Tab
NewWindow=Ctrl+N,Ctrl+N,New Window

[plasma-desktop]
_launch=none,none,KDE Plasma Desktop
EOF

# ── kcmfonts: DPI / font scaling (2x for mobile displays) ───────────────────
echo "FluxLinux: Writing font DPI config..."
cat > "$KDE_CONFIG/kcmfonts" << EOF
[General]
forceFontDPI=192
EOF

# ── Konsole profile: replaces xfce4-terminal config ─────────────────────────
echo "FluxLinux: Configuring Konsole..."
KONSOLE_DIR="$USER_HOME/.local/share/konsole"
mkdir -p "$KONSOLE_DIR"

cat > "$KONSOLE_DIR/FluxLinux.profile" << 'EOF'
[Appearance]
ColorScheme=Breeze
Font=JetBrainsMonoNerdFontMono,12,-1,5,400,0,0,0,0,0,0,0,0,0,0,1
antialias=true

[General]
Command=/bin/zsh
Name=FluxLinux
Parent=FALLBACK/

[Interaction Options]
AutoCopySelectedText=true
TrimLeadingWhitespacesInSelectedText=true
TrimTrailingWhitespacesInSelectedText=true

[Scrolling]
HistoryMode=2
HistorySize=10000

[Terminal Features]
BidiRenderingEnabled=true
BlinkingCursorEnabled=false
EOF

# Set as default Konsole profile
KONSOLE_CONFIG_DIR="$USER_HOME/.config"
cat > "$KONSOLE_CONFIG_DIR/konsolerc" << 'EOF'
[Desktop Entry]
DefaultProfile=FluxLinux.profile

[KonsoleWindow]
RememberWindowSize=true

[TabBar]
CloseTabOnMiddleMouseButton=true
TabBarVisibility=ShowTabBarWhenNeeded
EOF

chown -R "$CUSTOM_USER:$CUSTOM_GROUP" "$KONSOLE_DIR" "$KONSOLE_CONFIG_DIR/konsolerc"

# Fix ownership on all KDE configs
chown -R "$CUSTOM_USER:$CUSTOM_GROUP" "$KDE_CONFIG"
echo "FluxLinux: KDE settings applied successfully!"

# 5. Configure Zsh and Terminal (identical to XFCE4 customization)
echo "FluxLinux: Configuring Zsh and Terminal..."

# Install zsh
echo "FluxLinux: Installing zsh..."
apt-get install -y zsh 2>/dev/null

# Install Oh My Zsh for flux user
echo "FluxLinux: Installing Oh My Zsh..."

# Check for corrupt installation
if [ -d "$USER_HOME/.oh-my-zsh" ] && [ ! -f "$USER_HOME/.oh-my-zsh/oh-my-zsh.sh" ]; then
    echo "FluxLinux: Detected corrupt Oh My Zsh installation. Removing..."
    rm -rf "$USER_HOME/.oh-my-zsh"
fi

# Install Oh My Zsh if missing (curl installer, then git clone fallback)
if [ ! -d "$USER_HOME/.oh-my-zsh" ] || [ ! -f "$USER_HOME/.oh-my-zsh/oh-my-zsh.sh" ]; then
    rm -rf "$USER_HOME/.oh-my-zsh"
    su -s /bin/bash - "$CUSTOM_USER" -c 'RUNZSH=no CHSH=no sh -c "$(curl -fsSL https://raw.githubusercontent.com/ohmyzsh/ohmyzsh/master/tools/install.sh)"' 2>/dev/null || true
fi
if [ ! -f "$USER_HOME/.oh-my-zsh/oh-my-zsh.sh" ]; then
    echo "FluxLinux: Oh My Zsh installer failed — trying git clone fallback..."
    rm -rf "$USER_HOME/.oh-my-zsh"
    su -s /bin/bash - "$CUSTOM_USER" -c "git clone --depth 1 https://github.com/ohmyzsh/ohmyzsh.git '$USER_HOME/.oh-my-zsh'" 2>/dev/null || true
fi
if [ ! -f "$USER_HOME/.oh-my-zsh/oh-my-zsh.sh" ]; then
    echo "FluxLinux: WARNING: Oh My Zsh not installed — .zshrc will skip source (no hard fail)"
fi

ZSH_CUSTOM="$USER_HOME/.oh-my-zsh/custom"

# Install Zsh plugins
echo "FluxLinux: Installing Zsh plugins..."
su -s /bin/bash - "$CUSTOM_USER" -c "git clone https://github.com/zsh-users/zsh-autosuggestions '$ZSH_CUSTOM/plugins/zsh-autosuggestions'" 2>/dev/null
su -s /bin/bash - "$CUSTOM_USER" -c "git clone https://github.com/zsh-users/zsh-syntax-highlighting '$ZSH_CUSTOM/plugins/zsh-syntax-highlighting'" 2>/dev/null
su -s /bin/bash - "$CUSTOM_USER" -c "git clone --depth 1 https://github.com/marlonrichert/zsh-autocomplete.git '$ZSH_CUSTOM/plugins/zsh-autocomplete'" 2>/dev/null

# Install agnosterzak theme
echo "FluxLinux: Installing agnosterzak theme..."
su -s /bin/bash - "$CUSTOM_USER" -c "mkdir -p '$ZSH_CUSTOM/themes'"
su -s /bin/bash - "$CUSTOM_USER" -c "curl -fsSL https://raw.githubusercontent.com/zakaziko99/agnosterzak-ohmyzsh-theme/master/agnosterzak.zsh-theme -o '$ZSH_CUSTOM/themes/agnosterzak.zsh-theme'" 2>/dev/null

# Install pokemon-colorscripts
echo "FluxLinux: Installing pokemon-colorscripts..."
POKEMON_TEMP="/tmp/pokemon-colorscripts"
rm -rf "$POKEMON_TEMP"
git clone https://gitlab.com/phoneybadger/pokemon-colorscripts.git "$POKEMON_TEMP" 2>/dev/null
cd "$POKEMON_TEMP" && ./install.sh 2>/dev/null
cd - > /dev/null
rm -rf "$POKEMON_TEMP"

# Configure .zshrc
echo "FluxLinux: Configuring .zshrc..."
ZSHRC="$USER_HOME/.zshrc"

# Write complete optimized .zshrc (performance fixes from screenshot)
# - Removed zsh-autocomplete (extremely slow on PRoot, 35s+ startup -> 1.5s)
# - Backgrounded visuals with &! (async, don't block shell startup)
# - DISABLE_AUTO_UPDATE / DISABLE_UPDATE_PROMPT (no prompts on launch)
# - ZSH_DISABLE_COMPFIX (no compaudit, faster init)
echo "FluxLinux: Writing optimized .zshrc..."
# Defensive: never hard-fail on missing oh-my-zsh / pokemon (network installs often skip).
cat > "$ZSHRC" << 'ZSHEOF'
# PATH setup - local bin, npm global modules
export PATH="$HOME/.local/bin:/opt/nodejs/bin:$PATH"

# Setup Locales
export LANG=en_US.UTF-8
export LC_ALL=en_US.UTF-8

# Fix XDG_RUNTIME_DIR (not set in PRoot/chroot — no systemd-logind)
export XDG_RUNTIME_DIR="${XDG_RUNTIME_DIR:-/tmp}"

# Background visuals - don't block shell startup; skip missing tools (no error spam)
{
  if command -v fastfetch >/dev/null 2>&1; then
    fastfetch --config termux 2>/dev/null || fastfetch 2>/dev/null || true
  fi
  if command -v pokemon-colorscripts >/dev/null 2>&1; then
    pokemon-colorscripts --no-title -r 1,2,3 2>/dev/null || true
  fi
} &!

# oh-my-zsh (optional — install may fail offline; shell still usable without it)
export ZSH="${ZSH:-$HOME/.oh-my-zsh}"
if [ -f "$ZSH/oh-my-zsh.sh" ]; then
  ZSH_THEME="agnosterzak"
  DISABLE_UPDATE_PROMPT=true
  DISABLE_AUTO_UPDATE=true
  ZSH_DISABLE_COMPFIX=true
  # Removed zsh-autocomplete (very slow), kept essential plugins
  plugins=(git zsh-autosuggestions zsh-syntax-highlighting)
  source "$ZSH/oh-my-zsh.sh"
fi
ZSHEOF
chown "$CUSTOM_USER:$CUSTOM_GROUP" "$ZSHRC"

# Download fastfetch config
mkdir -p "$USER_HOME/.local/share/fastfetch/presets"
curl -fsSL https://raw.githubusercontent.com/abhay-byte/Linux_Setup/dev/config/termux.jsonc \
    -o "$USER_HOME/.local/share/fastfetch/presets/termux.jsonc" 2>/dev/null

# Fastfetch and pokemon are already included in the optimized .zshrc above (backgrounded)

# Set zsh as default shell for flux user
chsh -s /bin/zsh "$CUSTOM_USER" 2>/dev/null

chown -R "$CUSTOM_USER:$CUSTOM_GROUP" "$USER_HOME/.oh-my-zsh" "$USER_HOME/.zshrc" "$USER_HOME/.local" 2>/dev/null

echo "FluxLinux: Zsh configuration complete!"

# 6. Reload KDE Shell
echo "FluxLinux: Reloading KDE Plasma Shell..."
su -s /bin/bash - "$CUSTOM_USER" -c "
    export DISPLAY=:0
    # Kill and restart plasmashell to apply theme changes
    kquitapp5 plasmashell 2>/dev/null
    sleep 2
    nohup plasmashell > /dev/null 2>&1 &
    sleep 1
    # Restart KWin compositor
    kwin_x11 --replace > /dev/null 2>&1 &
" 2>/dev/null || true

echo "FluxLinux: KDE Customization Complete!"
echo "Log saved at: $LOG_FILE"
echo "------------------------------------------------"
read -p "Press Enter to close..."

# Notify FluxLinux app of success
am start -a android.intent.action.VIEW \
    -d "fluxlinux://callback?result=success&name=kde_customization" \
    2>/dev/null || true
