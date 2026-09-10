package com.ivarna.fluxlinux.core.data

import com.ivarna.fluxlinux.core.model.SupportedDistro
import com.ivarna.fluxlinux.ui.theme.FluxAccentMagenta
import com.ivarna.fluxlinux.ui.theme.FluxAccentCyan
import androidx.compose.ui.graphics.Color
import com.ivarna.fluxlinux.R
import java.util.Locale


object DistroRepository {

    fun sortForDistroPage(distros: List<Distro>): List<Distro> =
        distros.sortedWith(
            compareBy<Distro> { it.comingSoon }
                .thenBy { it.name.lowercase(Locale.ROOT) }
                .thenBy { it.id }
        )

    
    // Shared Components for Debian-based distros
    private val debianComponents = listOf(
        DistroComponent(
            id = "xfce4_desktop",
            name = "XFCE4 Desktop",
            description = "Base XFCE4 desktop environment — re-run to repair or update.",
            scriptName = "debian/common/setup/setup_debian_family.sh",
            sizeEstimate = "300 MB",
            isMandatory = false
        ),
        DistroComponent(
            id = "hw_accel",
            name = "Hardware Acceleration",
            description = "Turnip (Adreno) or VirGL GPU setup. Mandatory for GUI.",
            scriptName = "common/setup/setup_hw_accel_guest.sh",
            sizeEstimate = "50 MB",
            isMandatory = true
        ),
        DistroComponent(
            id = "customization",
            name = "XFCE4 Customization",
            description = "FluxLinux Theme, Wallpapers, Fonts, and 2x Scaling for XFCE4.",
            scriptName = "debian/common/setup/setup_customization_debian.sh",
            sizeEstimate = "200 MB"
        ),
        DistroComponent(
            id = "kde_plasma",
            name = "KDE Plasma Desktop",
            description = "Full KDE Plasma DE with Konsole, Dolphin, Kate, Spectacle & goodies.",
            scriptName = "debian/common/setup/setup_kde_debian.sh",
            sizeEstimate = "800 MB"
        ),
        DistroComponent(
            id = "kde_customization",
            name = "KDE Desktop Customization",
            description = "FluxLinux theme, Papirus icons, wallpapers & Zsh for KDE Plasma.",
            scriptName = "debian/common/setup/setup_customization_kde_debian.sh",
            sizeEstimate = "250 MB"
        ),
        DistroComponent(
            id = "app_dev",
            name = "App Development",
            description = "Android SDK, Flutter, IntelliJ IDEA, OpenJDK.",
            scriptName = "debian/common/setup/setup_appdev_debian.sh",
            sizeEstimate = "2.5 GB"
        ),
        DistroComponent(
            id = "web_dev",
            name = "Web Development",
            description = "Node.js, VS Code, Nginx, Python, Git.",
            scriptName = "debian/common/setup/setup_webdev_debian.sh",
            sizeEstimate = "800 MB"
        ),
        DistroComponent(
            id = "gen_dev",
            name = "General Coding",
            description = "C++, Rust, Go, LunarVim, Neovim, Build Essentials.",
            scriptName = "debian/common/setup/setup_gengdev_debian.sh",
            sizeEstimate = "800 MB"
        ),
        DistroComponent(
            id = "cybersec",
            name = "Cyber Security",
            description = "Kali Tools, Metasploit, Nmap, Wireshark, Aircrack-ng.",
            scriptName = "debian/common/setup/setup_cybersec_debian.sh",
            sizeEstimate = "2 GB"
        ),
        DistroComponent(
            id = "data_science",
            name = "Data Science",
            description = "Jupyter, Python Data Stack (Pandas, NumPy), R.",
            scriptName = "debian/common/setup/setup_datascience_debian.sh",
            sizeEstimate = "1 GB"
        ),
        DistroComponent(
            id = "gamedev",
            name = "Game Development",
            description = "Godot Engine, Blender, Raylib.",
            scriptName = "debian/common/setup/setup_gamedev_debian.sh",
            sizeEstimate = "1 GB"
        ),
         DistroComponent(
            id = "video_editing",
            name = "Video Editing",
            description = "Kdenlive, Shotcut, OpenShot, Flowblade.",
            scriptName = "debian/common/setup/setup_video_editing_debian.sh",
            sizeEstimate = "1 GB"
        ),
        DistroComponent(
            id = "office",
            name = "Office Suite",
            description = "LibreOffice, PDF Viewer, Email Client.",
            scriptName = "debian/common/setup/setup_office_debian.sh",
            sizeEstimate = "500 MB"
        ),
        DistroComponent(
            id = "graphic_design",
            name = "Graphic Design",
            description = "GIMP, Inkscape, Krita, and Blender for creative work.",
            scriptName = "debian/common/setup/setup_graphic_design_debian.sh",
            sizeEstimate = "1.2 GB"
        ),
        DistroComponent(
            id = "vulkan_llamacpp",
            name = "Vulkan Llama.cpp",
            description = "GPU-accelerated LLM inference via Vulkan. Uses Turnip on Adreno devices.",
            scriptName = "debian/common/setup/setup_vulkan_llamacpp_debian.sh",
            sizeEstimate = "500 MB"
        ),
        DistroComponent(
            id = "qwen35_model",
            name = "Qwen3.5-0.8B Model",
            description = "Download Qwen3.5-0.8B GGUF (Q4_0). Requires Vulkan Llama.cpp installed first.",
            scriptName = "debian/common/setup/setup_qwen35_debian.sh",
            sizeEstimate = "507 MB"
        ),
        DistroComponent(
            id = "qwen25_model",
            name = "Qwen2.5-1.5B Model",
            description = "Qwen2.5-1.5B-Instruct GGUF (Vulkan GPU compatible). Replaces Qwen3.5 GDN.",
            scriptName = "debian/common/setup/setup_qwen25_debian.sh",
            sizeEstimate = "935 MB"
        ),
        DistroComponent(
            id = "emulation",
            name = "Retro Emulation",
            description = "RetroArch, various emulator cores.",
            scriptName = "debian/common/setup/setup_emulation_debian.sh",
            sizeEstimate = "1 GB",
            comingSoon = true
        )
    )

    // Alpine MVP components (apk/musl — no Debian module scripts)
    private val alpineComponents = listOf(
        DistroComponent(
            id = "xfce4_desktop",
            name = "XFCE4 Desktop",
            description = "Base XFCE4 desktop via apk (Alpine community).",
            scriptName = "alpine/common/setup/setup_alpine_family.sh",
            sizeEstimate = "250 MB",
            isMandatory = false
        ),
        DistroComponent(
            id = "hw_accel",
            name = "Hardware Acceleration",
            description = "Turnip (Adreno) or VirGL GPU setup for Alpine.",
            scriptName = "common/setup/setup_hw_accel_guest.sh",
            sizeEstimate = "80 MB",
            isMandatory = true
        ),
        DistroComponent(
            id = "customization",
            name = "XFCE4 Customization",
            description = "FluxLinux theme, wallpapers, fonts, and Zsh for Alpine.",
            scriptName = "alpine/common/setup/setup_customization_alpine.sh",
            sizeEstimate = "150 MB"
        )
    )

    private fun glibcXfceComponents(familyScript: String) = listOf(
        DistroComponent(
            id = "xfce4_desktop",
            name = "XFCE4 Desktop",
            description = "Base XFCE4 desktop, user flux, Mesa, and dbus.",
            scriptName = familyScript,
            sizeEstimate = "350 MB",
            isMandatory = false
        ),
        DistroComponent(
            id = "hw_accel",
            name = "Hardware Acceleration",
            description = "Turnip (Adreno) or VirGL GPU setup for this guest.",
            scriptName = "common/setup/setup_hw_accel_guest.sh",
            sizeEstimate = "80 MB",
            isMandatory = true
        ),
        DistroComponent(
            id = "customization",
            name = "XFCE4 Customization",
            description = "FluxLinux theme, Papirus icons, Nerd Font, and Zsh.",
            scriptName = "common/setup/setup_customization_xfce.sh",
            sizeEstimate = "150 MB"
        )
    )

    private val fedoraComponents = glibcXfceComponents(
        "fedora/common/setup/setup_fedora_family.sh"
    )
    private val voidComponents = glibcXfceComponents(
        "void/common/setup/setup_void_family.sh"
    )
    private val opensuseComponents = glibcXfceComponents(
        "opensuse/common/setup/setup_opensuse_family.sh"
    )
    private val deepinComponents = glibcXfceComponents(
        "deepin/common/setup/setup_deepin_family.sh"
    )
    private val chimeraComponents = glibcXfceComponents(
        "chimera/common/setup/setup_chimera_family.sh"
    )
    private val manjaroComponents = glibcXfceComponents(
        "manjaro/common/setup/setup_manjaro_family.sh"
    )
    private val ubuntuComponents = glibcXfceComponents(
        "ubuntu/common/setup/setup_ubuntu_family.sh"
    ) + DistroComponent(
        id = "office",
        name = "Office Suite",
        description = "LibreOffice, PDF Viewer, Email Client.",
        scriptName = "ubuntu/common/setup/setup_office_ubuntu.sh",
        sizeEstimate = "500 MB"
    )
    private val kaliComponents = glibcXfceComponents(
        "kali/common/setup/setup_kali_family.sh"
    )
    private val parrotComponents = glibcXfceComponents(
        "parrot/common/setup/setup_parrot_family.sh"
    )
    private val archlinuxComponents = glibcXfceComponents(
        "arch/common/setup/setup_arch_family.sh"
    )

    val supportedDistros = listOf(
        // Currently Available
        Distro(
            id = "debian",
            name = "Debian",
            description = "The universal operating system. Stable and reliable.",
            color = FluxAccentMagenta,
            iconRes = R.drawable.distro_debian,
            comingSoon = false,
            prootSupported = true,
            chrootSupported = false, // Pass 2: debian card is ALWAYS termux-flux-terminal (proot)
            configuration = SupportedDistro.DEBIAN,
            components = debianComponents
        ),

        Distro(
            id = "debian13_chroot",
            name = "Debian (Rooted)",
            description = "High-performance Debian 13 (Trixie) environment via Chroot (Requires Root).",
            color = FluxAccentMagenta,
            iconRes = R.drawable.distro_debian,
            comingSoon = false,
            prootSupported = false,
            chrootSupported = true,
            configuration = SupportedDistro.DEBIAN,
            components = debianComponents
        ),

        Distro(
            id = "alpine",
            name = "Alpine",
            description = "Security-oriented, lightweight musl/apk Linux (proot).",
            color = Color(0xFF0D597F),
            iconRes = R.drawable.distro_alpine,
            comingSoon = false,
            prootSupported = true,
            chrootSupported = false,
            configuration = SupportedDistro.ALPINE,
            components = alpineComponents
        ),

        Distro(
            id = "alpine_chroot",
            name = "Alpine (Rooted)",
            description = "Lightweight Alpine 3.24 chroot environment (Requires Root).",
            color = Color(0xFF0D597F),
            iconRes = R.drawable.distro_alpine,
            comingSoon = false,
            prootSupported = false,
            chrootSupported = true,
            configuration = SupportedDistro.ALPINE,
            components = alpineComponents
        ),

        Distro(
            id = "fedora",
            name = "Fedora",
            description = "Fedora 44 with dnf/dnf5 and XFCE4 (proot).",
            color = Color(0xFF294172),
            iconRes = R.drawable.distro_fedora,
            comingSoon = false,
            prootSupported = true,
            chrootSupported = false,
            configuration = SupportedDistro.FEDORA,
            components = fedoraComponents
        ),
        Distro(
            id = "fedora_chroot",
            name = "Fedora (Rooted)",
            description = "Fedora 44 chroot environment (Requires Root).",
            color = Color(0xFF294172),
            iconRes = R.drawable.distro_fedora,
            comingSoon = false,
            prootSupported = false,
            chrootSupported = true,
            configuration = SupportedDistro.FEDORA,
            components = fedoraComponents
        ),
        Distro(
            id = "void",
            name = "Void",
            description = "Independent glibc/xbps rolling distro with XFCE4 (proot).",
            color = Color(0xFF478061),
            iconRes = R.drawable.distro_void,
            comingSoon = false,
            prootSupported = true,
            chrootSupported = false,
            configuration = SupportedDistro.VOID,
            components = voidComponents
        ),
        Distro(
            id = "void_chroot",
            name = "Void (Rooted)",
            description = "Void Linux chroot environment (Requires Root).",
            color = Color(0xFF478061),
            iconRes = R.drawable.distro_void,
            comingSoon = false,
            prootSupported = false,
            chrootSupported = true,
            configuration = SupportedDistro.VOID,
            components = voidComponents
        ),
        Distro(
            id = "opensuse",
            name = "openSUSE",
            description = "openSUSE Tumbleweed with zypper and XFCE4 (proot).",
            color = Color(0xFF73BA25),
            iconRes = R.drawable.distro_opensuse,
            comingSoon = false,
            prootSupported = true,
            chrootSupported = false,
            configuration = SupportedDistro.OPENSUSE,
            components = opensuseComponents
        ),
        Distro(
            id = "opensuse_chroot",
            name = "openSUSE (Rooted)",
            description = "openSUSE Tumbleweed chroot environment (Requires Root).",
            color = Color(0xFF73BA25),
            iconRes = R.drawable.distro_opensuse,
            comingSoon = false,
            prootSupported = false,
            chrootSupported = true,
            configuration = SupportedDistro.OPENSUSE,
            components = opensuseComponents
        ),
        Distro(
            id = "deepin",
            name = "Deepin",
            description = "Deepin 25 with apt and XFCE4 (proot).",
            color = Color(0xFF2CA7F8),
            iconRes = R.drawable.distro_deepin,
            comingSoon = false,
            prootSupported = true,
            chrootSupported = false,
            configuration = SupportedDistro.DEEPIN,
            components = deepinComponents
        ),
        Distro(
            id = "deepin_chroot",
            name = "Deepin (Rooted)",
            description = "Deepin 25 chroot environment (Requires Root).",
            color = Color(0xFF2CA7F8),
            iconRes = R.drawable.distro_deepin,
            comingSoon = false,
            prootSupported = false,
            chrootSupported = true,
            configuration = SupportedDistro.DEEPIN,
            components = deepinComponents
        ),
        Distro(
            id = "chimera",
            name = "Chimera",
            description = "Chimera Linux (musl, apk v3) with XFCE4 (proot).",
            color = Color(0xFFFF6B35),
            iconRes = R.drawable.distro_chimera,
            comingSoon = false,
            prootSupported = true,
            chrootSupported = false,
            configuration = SupportedDistro.CHIMERA,
            components = chimeraComponents
        ),
        Distro(
            id = "chimera_chroot",
            name = "Chimera (Rooted)",
            description = "Chimera Linux chroot environment (Requires Root).",
            color = Color(0xFFFF6B35),
            iconRes = R.drawable.distro_chimera,
            comingSoon = false,
            prootSupported = false,
            chrootSupported = true,
            configuration = SupportedDistro.CHIMERA,
            components = chimeraComponents
        ),
        Distro(
            id = "manjaro",
            name = "Manjaro",
            description = "Manjaro ARM with pacman and XFCE4 (proot).",
            color = Color(0xFF35BF5C),
            iconRes = R.drawable.distro_manjaro,
            comingSoon = false,
            prootSupported = true,
            chrootSupported = false,
            configuration = SupportedDistro.MANJARO,
            components = manjaroComponents
        ),
        Distro(
            id = "manjaro_chroot",
            name = "Manjaro (Rooted)",
            description = "Manjaro ARM chroot environment (Requires Root).",
            color = Color(0xFF35BF5C),
            iconRes = R.drawable.distro_manjaro,
            comingSoon = false,
            prootSupported = false,
            chrootSupported = true,
            configuration = SupportedDistro.MANJARO,
            components = manjaroComponents
        ),
        Distro(
            id = "ubuntu",
            name = "Ubuntu",
            description = "Ubuntu 26.04 with XFCE4 (proot).",
            color = Color(0xFFE95420),
            iconRes = R.drawable.distro_ubuntu,
            comingSoon = false,
            prootSupported = true,
            chrootSupported = false,
            configuration = SupportedDistro.UBUNTU,
            components = ubuntuComponents
        ),
        Distro(
            id = "ubuntu_chroot",
            name = "Ubuntu (Rooted)",
            description = "Ubuntu 26.04 chroot environment (Requires Root).",
            color = Color(0xFFE95420),
            iconRes = R.drawable.distro_ubuntu,
            comingSoon = false,
            prootSupported = false,
            chrootSupported = true,
            configuration = SupportedDistro.UBUNTU,
            components = ubuntuComponents
        ),
        Distro(
            id = "kali",
            name = "Kali",
            description = "Kali Rolling with XFCE4 (proot).",
            color = Color(0xFF367BF5),
            iconRes = R.drawable.distro_kali,
            comingSoon = false,
            prootSupported = true,
            chrootSupported = false,
            configuration = SupportedDistro.KALI,
            components = kaliComponents
        ),
        Distro(
            id = "kali_chroot",
            name = "Kali (Rooted)",
            description = "Kali Rolling chroot environment (Requires Root).",
            color = Color(0xFF367BF5),
            iconRes = R.drawable.distro_kali,
            comingSoon = false,
            prootSupported = false,
            chrootSupported = true,
            configuration = SupportedDistro.KALI,
            components = kaliComponents
        ),
        Distro(
            id = "parrot",
            name = "Parrot",
            description = "Parrot 7.2 with XFCE4 (proot).",
            color = Color(0xFF00D9FF),
            iconRes = R.drawable.distro_parrot,
            comingSoon = false,
            prootSupported = true,
            chrootSupported = false,
            configuration = SupportedDistro.PARROT,
            components = parrotComponents
        ),
        Distro(
            id = "parrot_chroot",
            name = "Parrot (Rooted)",
            description = "Parrot 7.2 chroot environment (Requires Root).",
            color = Color(0xFF00D9FF),
            iconRes = R.drawable.distro_parrot,
            comingSoon = false,
            prootSupported = false,
            chrootSupported = true,
            configuration = SupportedDistro.PARROT,
            components = parrotComponents
        ),
        Distro(
            id = "archlinux",
            name = "Arch",
            description = "Arch Linux ARM with XFCE4 (proot).",
            color = Color(0xFF1793D1),
            iconRes = R.drawable.distro_arch,
            comingSoon = false,
            prootSupported = true,
            chrootSupported = false,
            configuration = SupportedDistro.ARCH,
            components = archlinuxComponents
        ),
        Distro(
            id = "archlinux_chroot",
            name = "Arch (Rooted)",
            description = "Arch Linux ARM chroot environment (Requires Root).",
            color = Color(0xFF1793D1),
            iconRes = R.drawable.distro_arch,
            comingSoon = false,
            prootSupported = false,
            chrootSupported = true,
            configuration = SupportedDistro.ARCH,
            components = archlinuxComponents
        ),
        
        // Coming Soon - Sorted alphabetically
        Distro(
            id = "adelie",
            name = "Adélie Linux",
            description = "Independent Linux distribution committed to integrity and simplicity.",
            color = Color(0xFF9C27B0),
            iconRes = R.drawable.distro_adelie,
            comingSoon = true,
            prootSupported = false, // no i686 support
            chrootSupported = true
        ),
        Distro(
            id = "artix",
            name = "Artix Linux",
            description = "Arch-based distribution without systemd.",
            color = Color(0xFF10A0CC),
            iconRes = R.drawable.distro_artix,
            comingSoon = true,
            prootSupported = true, // aarch64 only
            chrootSupported = true
        ),
        Distro(
            id = "backbox",
            name = "BackBox",
            description = "Ubuntu-based distribution for penetration testing.",
            color = Color(0xFF000000),
            iconRes = R.drawable.distro_backbox,
            comingSoon = true,
            prootSupported = false, // Not in proot-distro
            chrootSupported = true
        ),
        Distro(
            id = "centos_stream",
            name = "CentOS Stream",
            description = "Continuously delivered distro that tracks ahead of RHEL.",
            color = Color(0xFF262577),
            iconRes = R.drawable.distro_centos_stream,
            comingSoon = true,
            prootSupported = false, // Not in proot-distro
            chrootSupported = true
        ),

        Distro(
            id = "gentoo",
            name = "Gentoo",
            description = "Flexible, source-based Linux distribution.",
            color = Color(0xFF54487A),
            iconRes = R.drawable.distro_gentoo,
            comingSoon = true,
            prootSupported = false, // Not in proot-distro
            chrootSupported = true
        ),
        Distro(
            id = "openkylin",
            name = "OpenKylin",
            description = "Community-driven Linux distribution from China.",
            color = Color(0xFF0066CC),
            iconRes = R.drawable.distro_openkylin,
            comingSoon = true,
            prootSupported = false, // Not in proot-distro
            chrootSupported = true
        ),

        Distro(
            id = "rocky",
            name = "Rocky Linux",
            description = "Enterprise-grade Linux distribution.",
            color = Color(0xFF10B981),
            iconRes = R.drawable.distro_rocky,
            comingSoon = true,
            prootSupported = true, // only 64bit
            chrootSupported = true
        ),
    )

    /**
     * Installable PRoot or Chroot card for the same family as [id].
     * Falls back to the first installable card of that method.
     */
    fun iconResFor(distroId: String?): Int? =
        distroId?.let { id -> supportedDistros.find { it.id == id }?.iconRes }

    fun installableVariant(id: String, chroot: Boolean): Distro? {
        val current = supportedDistros.find { it.id == id }
        val family = current?.configuration
        val matches: (Distro) -> Boolean = { d ->
            !d.comingSoon && if (chroot) d.chrootSupported else d.prootSupported
        }
        if (family != null) {
            supportedDistros.firstOrNull { matches(it) && it.configuration == family }
                ?.let { return it }
        }
        return supportedDistros.firstOrNull(matches)
    }
}
