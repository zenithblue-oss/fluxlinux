package com.ivarna.fluxlinux.core.terminal

import android.content.Context
import android.system.Os
import android.util.Log
import java.io.File
import java.io.FileOutputStream

/**
 * Deploys host + distro shell scripts from assets into `$HOME` and the
 * termux-x11 loader APK.
 *
 * Host readiness (D6) = scripts + loader + bootstrap only. Rootfs archives are
 * **not** owned here anymore — [com.ivarna.fluxlinux.core.install.RootfsDownloader]
 * fetches the selected distro's rootfs on demand from the GitHub release tag
 * `rootfs`. Ivarna also fetches `bootstrap_<applicationId>.tar` from that tag
 * (see [com.ivarna.fluxlinux.core.install.HostBootstrap]).
 *
 * Fail-closed: a missing [HostScript.required] asset or loader makes
 * [deployScripts] return false so [TerminalLauncher.prepareHost] fails.
 */
object HostScriptDeployer {

    private const val TAG = "HostScriptDeployer"

    /**
     * Deploy marker (proot-opt-01): `home/.fluxlinux/scripts.deployed` holds the
     * current [DEPLOY_VERSION]. Bump the version whenever the script list or any
     * deployed asset changes so existing installs re-deploy on the next cold run.
     * A current marker makes [deployScripts] a no-op — no 30-script asset copy,
     * no pulse overlay, no loader churn — on successive warm sessions.
     */
    internal const val DEPLOY_VERSION = 1
    private const val MARKER_REL = "home/.fluxlinux/scripts.deployed"

    /** Marker file: content is the deployed [DEPLOY_VERSION]. */
    internal fun deployMarker(ctx: Context): File = File(ctx.filesDir, MARKER_REL)

    /** Version + install time: every app update (even same versionCode) redeploys scripts. */
    internal fun deployStamp(ctx: Context): String {
        val t = runCatching { ctx.packageManager.getPackageInfo(ctx.packageName, 0).lastUpdateTime }
            .getOrDefault(0L)
        return "$DEPLOY_VERSION|$t"
    }

    internal fun isDeployed(ctx: Context): Boolean {
        val marker = deployMarker(ctx)
        return marker.isFile && marker.readText().trim() == deployStamp(ctx)
    }

    internal fun markDeployed(ctx: Context) {
        val marker = deployMarker(ctx)
        marker.parentFile?.mkdirs()
        marker.writeText(deployStamp(ctx))
    }

    private data class HostScript(
        val name: String,
        val assetPath: String,
        val required: Boolean = true
    )

    private val HOST_SCRIPTS: List<HostScript> = listOf(
        HostScript("setup_termux.sh", "scripts/host/setup_termux.sh"),
        HostScript("start_pulse_host.sh", "scripts/host/start_pulse_host.sh"),
        HostScript("repair_pulse_guests.sh", "scripts/host/repair_pulse_guests.sh"),
        HostScript("setup_pulse_guest.sh", "scripts/common/setup/setup_pulse_guest.sh"),
        HostScript("flux_install.sh", "scripts/debian/proot/setup/flux_install.sh"),
        // Debian guest
        HostScript("setup_debian_family.sh", "scripts/debian/common/setup/setup_debian_family.sh"),
        HostScript(
            "setup_customization_debian.sh",
            "scripts/debian/common/setup/setup_customization_debian.sh"
        ),
        HostScript(
            "setup_hw_accel_debian.sh",
            "scripts/debian/common/setup/setup_hw_accel_debian.sh",
            required = false
        ),
        // Alpine guest
        HostScript("setup_alpine_family.sh", "scripts/alpine/common/setup/setup_alpine_family.sh"),
        HostScript(
            "setup_customization_alpine.sh",
            "scripts/alpine/common/setup/setup_customization_alpine.sh"
        ),
        // Shared glibc-guest helpers (Fedora / Void / openSUSE)
        HostScript("flux_guest_common.sh", "scripts/common/setup/flux_guest_common.sh"),
        HostScript("flux_gpu_common.sh", "scripts/common/setup/flux_gpu_common.sh"),
        HostScript(
            "setup_customization_xfce.sh",
            "scripts/common/setup/setup_customization_xfce.sh"
        ),
        HostScript("setup_hw_accel_guest.sh", "scripts/common/setup/setup_hw_accel_guest.sh"),
        HostScript(
            "bwrap-proot-shim.sh",
            "scripts/common/setup/bwrap-proot-shim.sh",
            required = false
        ),
        HostScript(
            "bwrap-proot-shim",
            "scripts/common/setup/bwrap-proot-shim",
            required = false
        ),
        HostScript("setup_fedora_family.sh", "scripts/fedora/common/setup/setup_fedora_family.sh"),
        HostScript("setup_void_family.sh", "scripts/void/common/setup/setup_void_family.sh"),
        HostScript(
            "setup_opensuse_family.sh",
            "scripts/opensuse/common/setup/setup_opensuse_family.sh"
        ),
        // Deepin / Chimera / Manjaro guests
        HostScript(
            "setup_deepin_family.sh",
            "scripts/deepin/common/setup/setup_deepin_family.sh"
        ),
        HostScript(
            "setup_chimera_family.sh",
            "scripts/chimera/common/setup/setup_chimera_family.sh"
        ),
        HostScript(
            "setup_manjaro_family.sh",
            "scripts/manjaro/common/setup/setup_manjaro_family.sh"
        ),
        HostScript(
            "setup_ubuntu_family.sh",
            "scripts/ubuntu/common/setup/setup_ubuntu_family.sh"
        ),
        HostScript(
            "setup_kali_family.sh",
            "scripts/kali/common/setup/setup_kali_family.sh"
        ),
        HostScript(
            "setup_parrot_family.sh",
            "scripts/parrot/common/setup/setup_parrot_family.sh"
        ),
        HostScript(
            "setup_arch_family.sh",
            "scripts/arch/common/setup/setup_arch_family.sh"
        ),
        // EVP_md2 stub for TW libldap vs OpenSSL 3.5 (sudo/zypper)
        HostScript(
            "libevp_md2.so",
            "scripts/opensuse/common/libevp_md2.so",
            required = false
        ),
        // Chroot setup/uninstall
        HostScript("setup_debian13_chroot.sh", "scripts/chroot/setup_debian13_chroot.sh"),
        HostScript("uninstall_debian13_chroot.sh", "scripts/chroot/uninstall_debian13_chroot.sh"),
        HostScript("setup_alpine_chroot.sh", "scripts/chroot/setup_alpine_chroot.sh"),
        HostScript("uninstall_alpine_chroot.sh", "scripts/chroot/uninstall_alpine_chroot.sh"),
        HostScript("fluxlinux_chroot.sh", "scripts/chroot/fluxlinux_chroot.sh"),
        HostScript("resolve_bb.sh", "scripts/chroot/resolve_bb.sh"),
        HostScript("chroot_processes.sh", "scripts/chroot/chroot_processes.sh", required = false),
        HostScript("chroot_size.sh", "scripts/chroot/chroot_size.sh", required = false),
        // Desktop (proot)
        HostScript("start_gui.sh", "scripts/debian/proot/start/start_gui.sh"),
        HostScript("stop_gui.sh", "scripts/debian/proot/stop/stop_gui.sh"),
        // Desktop (chroot host wrappers + root guest)
        HostScript("start_gui_chroot.sh", "scripts/chroot/start_gui_chroot.sh"),
        HostScript("stop_gui_chroot.sh", "scripts/chroot/stop_gui_chroot.sh"),
        HostScript("start_debian13_gui.sh", "scripts/chroot/start_debian13_gui.sh"),
        HostScript("stop_debian13_gui.sh", "scripts/chroot/stop_debian13_gui.sh"),
        HostScript("start_alpine_gui.sh", "scripts/chroot/start_alpine_gui.sh"),
        HostScript("stop_alpine_gui.sh", "scripts/chroot/stop_alpine_gui.sh"),
        HostScript("setup_guest_chroot.sh", "scripts/chroot/setup_guest_chroot.sh"),
        HostScript("uninstall_guest_chroot.sh", "scripts/chroot/uninstall_guest_chroot.sh"),
        HostScript("start_guest_gui.sh", "scripts/chroot/start_guest_gui.sh"),
        HostScript("stop_guest_gui.sh", "scripts/chroot/stop_guest_gui.sh"),
    )

    /**
     * Deploy host + distro scripts/assets into `$HOME` and the loader APK.
     * Idempotent: when the deploy marker is current and [force] is false the
     * whole loop is skipped (warm path). The package-path rewrite tree walk
     * (applyPackageToExtractedPrefix) was removed from here — it already runs
     * inside [BootstrapInstaller] on extraction, and re-walking usr/bin,
     * usr/etc, usr/share, python3.14 and include on every call is the startup
     * stall this optimization removes.
     *
     * @param force when true, always rewrite assets and refresh the marker
     * @return false when any required deploy step fails (fail-closed contract)
     */
    fun deployScripts(ctx: Context, force: Boolean = false): Boolean {
        // Cheap (one small file) and must run even on the warm path: app updates move nativeLibraryDir.
        runCatching { TermuxHostPaths.refreshHostEnvIfStale(ctx.filesDir, ctx) }
        if (!force && isDeployed(ctx)) return true
        return try {
            val homeDir = File(ctx.filesDir, "home").also { it.mkdirs() }
            var ok = true
            for (script in HOST_SCRIPTS) {
                val out = File(homeDir, script.name)
                try {
                    ctx.assets.open(script.assetPath).use { input ->
                        FileOutputStream(out).use { input.copyTo(it) }
                    }
                    out.setExecutable(true, false)
                } catch (e: Exception) {
                    Log.w(TAG, "Script ${script.assetPath} not found in assets", e)
                    if (script.required) ok = false
                }
            }
            try {
                val termuxDir = File(homeDir, ".termux").also { it.mkdirs() }
                val fontOut = File(termuxDir, "font.ttf")
                if (!fontOut.isFile) {
                    ctx.assets.open("fonts/font.ttf").use { input ->
                        FileOutputStream(fontOut).use { input.copyTo(it) }
                    }
                }
            } catch (_: Exception) {
            }
            overlayPulseRuntime(ctx)
            val loaderOk = deployLoaderApk(ctx)
            if (!loaderOk) Log.w(TAG, "loader.apk deploy failed — host not ready")
            if (ok && loaderOk) markDeployed(ctx)
            ok && loaderOk
        } catch (e: Exception) {
            Log.e(TAG, "Failed to deploy scripts", e)
            false
        }
    }

    private val pulseRuntimeSos = listOf(
        "libsoxr.so",
        "libsoxr-lsr.so",
        "libandroid-execinfo.so",
        "libFLAC.so",
        "libmp3lame.so"
    )

    /**
     * Copy missing Pulse runtime `.so` from flavor assets without re-extracting
     * the bootstrap (preserves proot containers). Also drop default.pa.d TCP/AAudio.
     *
     * Always chmod 0755: [File.setExecutable] is a no-op on some Android 16
     * file trees, and a mode-600 `.so` cannot be mmap'd PROT_EXEC by
     * untrusted_app (root still can — looks like “missing runtime libs”).
     */
    fun overlayPulseRuntime(ctx: Context) {
        val libDir = File(ctx.filesDir, "usr/lib")
        libDir.mkdirs()
        for (name in pulseRuntimeSos) {
            val dest = File(libDir, name)
            if (!dest.isFile || dest.length() == 0L) {
                try {
                    ctx.assets.open("pulse-runtime/$name").use { input ->
                        FileOutputStream(dest).use { input.copyTo(it) }
                    }
                    Log.i(TAG, "overlaid pulse-runtime $name")
                } catch (e: Exception) {
                    Log.w(TAG, "pulse-runtime overlay missed $name", e)
                }
            }
            chmodPulseLib(dest)
        }
        val lame0 = File(libDir, "libmp3lame.so.0")
        if (!lame0.exists()) {
            try {
                java.nio.file.Files.createSymbolicLink(
                    lame0.toPath(),
                    java.nio.file.Paths.get("libmp3lame.so")
                )
            } catch (_: Exception) {
                val src = File(libDir, "libmp3lame.so")
                if (src.isFile) src.copyTo(lame0, overwrite = true)
            }
        }
        chmodPulseLib(lame0)
        val paDir = File(ctx.filesDir, "usr/etc/pulse/default.pa.d")
        paDir.mkdirs()
        File(paDir, "99-fluxlinux.pa").writeText(
            ".nofail\n" +
                "load-module module-aaudio-sink\n" +
                "load-module module-sles-sink\n" +
                "load-module module-native-protocol-tcp " +
                "auth-ip-acl=127.0.0.1 auth-anonymous=1 listen=127.0.0.1\n"
        )
    }

    /** 0755 so the untrusted_app linker can PROT_EXEC the overlay `.so`. */
    internal fun chmodPulseLib(dest: File) {
        if (!dest.exists()) return
        try {
            Os.chmod(dest.absolutePath, 493)
        } catch (_: Exception) {
            dest.setReadable(true, true)
            dest.setExecutable(true, true)
        }
    }

    fun deployLoaderApk(ctx: Context): Boolean {
        return try {
            val dest = File(ctx.filesDir, "usr/libexec/termux-x11/loader.apk")
            if (dest.isFile && dest.length() > 0L) return true
            dest.parentFile?.mkdirs()
            ctx.assets.open("loader.apk").use { input ->
                FileOutputStream(dest).use { input.copyTo(it) }
            }
            dest.setReadOnly()
            Log.i(TAG, "Deployed loader.apk: ${dest.absolutePath}")
            true
        } catch (e: Exception) {
            Log.w(TAG, "loader.apk deploy failed (GUI helpers will warn)", e)
            false
        }
    }
}
