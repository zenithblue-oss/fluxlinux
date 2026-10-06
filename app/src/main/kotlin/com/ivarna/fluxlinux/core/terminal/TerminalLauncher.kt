package com.ivarna.fluxlinux.core.terminal

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.ivarna.fluxlinux.core.chroot.ChrootDetection
import java.io.File
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Entry point for card actions that need the embedded host before a terminal
 * session opens: extract bootstrap → deploy scripts/loader → setup_termux
 * validation (host gate). Rootfs archives are NOT part of host readiness —
 * the selected distro's rootfs is downloaded at install time
 * (RootfsDownloader). Ivarna also downloads the host bootstrap tarball from
 * the same GitHub `rootfs` tag when it is not packaged in the APK.
 * All heavy work runs on a background executor; [onDone]
 * is dispatched on the main thread.
 *
 * Fail-closed: false unless extract AND scripts/loader deploy AND setup_termux succeed.
 * Recovery (B2): a failed setup_termux clears only the setup marker (never wipes
 * the prefix); re-extract happens on a later call only if the extract tree/marker
 * is invalid. Installed proot containers are preserved by [BootstrapInstaller].
 */
object TerminalLauncher {

    private const val TAG = "TerminalLauncher"
    internal val executor: ExecutorService = Executors.newSingleThreadExecutor()
    internal val mainHandler = Handler(Looper.getMainLooper())

    /** Marker written by setup_termux.sh; checked by host status cards. */
    fun isHostSetupDone(ctx: Context): Boolean =
        TermuxHostPaths.setupTermuxMarker(ctx).exists()

    fun isBootstrapExtracted(ctx: Context): Boolean =
        BootstrapInstaller.isExtracted(ctx)

    /** Filesystem truth for "Debian (proot) installed" (P4-T13 migration SSOT). */
    fun isDebianProotInstalled(ctx: Context): Boolean = isProotInstalled(ctx, "debian")

    /**
     * Proot container looks installed. Alpine minirootfs uses absolute
     * `bin/sh -> /bin/busybox`; [File.exists] follows the link on the host and
     * returns false — so also accept busybox/apk/bash and symlink nodes.
     */
    fun isProotInstalled(ctx: Context, prootName: String): Boolean {
        val root = File(ctx.filesDir, "usr/var/lib/proot-distro/containers/$prootName/rootfs")
        return guestRootfsHasShell(root)
    }

    /** Host-safe guest rootfs shell probe (absolute busybox symlinks OK). */
    fun guestRootfsHasShell(root: File): Boolean {
        if (!root.isDirectory) return false
        val sh = File(root, "bin/sh")
        if (sh.exists()) return true
        try {
            if (java.nio.file.Files.isSymbolicLink(sh.toPath())) return true
        } catch (_: Exception) {
        }
        if (File(root, "bin/busybox").isFile) return true
        if (File(root, "sbin/apk").isFile) return true
        if (File(root, "usr/bin/bash").isFile) return true
        // Chimera: /bin -> usr/bin; probe both real paths so a broken bin
        // symlink cannot false-negative (apk v3 lives at usr/lib/apk).
        if (File(root, "usr/bin/sh").isFile) return true
        if (File(root, "usr/bin/apk").isFile) return true
        try {
            if (java.nio.file.Files.isSymbolicLink(File(root, "bin/ash").toPath())) return true
        } catch (_: Exception) {
        }
        return false
    }

    /**
     * Debian chroot installed — delegates to [ChrootDetection] (auto-detect +
     * root probe; SELinux-safe).
     */
    fun isDebianChrootInstalled(): Boolean =
        isChrootInstalled(com.ivarna.fluxlinux.core.root.ChrootPaths.DEBIAN_CHROOT_PATH)

    fun isChrootInstalled(chrootPath: String): Boolean =
        ChrootDetection.isInstalled(chrootPath)

    fun invalidateChrootInstalledCache() {
        ChrootDetection.invalidate()
    }

    /**
     * After a distro uninstall session (or its deep-link callback): drop the
     * chroot TTL cache, clear prefs, and bump the Home/Distros refresh so the
     * card does not stay "Installed" while the rootfs is already gone.
     *
     * Home/Distros read [isDistroInstalledOnFs], not prefs. Chroot detection
     * on the main thread never re-probes, so a stale true cache would stick
     * until process death. Proot is filesystem-only (no cache).
     */
    fun refreshInstalledAfterUninstall(ctx: Context, distroId: String) {
        val appCtx = ctx.applicationContext
        val profile = com.ivarna.fluxlinux.core.install.DistroInstallProfile.forId(distroId)
        if (profile?.method == "chroot") {
            val path = profile.chrootPath
            if (!path.isNullOrEmpty()) {
                ChrootDetection.invalidate(path)
                ChrootDetection.markUninstalled(path)
            } else {
                ChrootDetection.invalidate()
            }
        }
        try {
            com.ivarna.fluxlinux.core.utils.StateManager.clearDistroState(appCtx, distroId)
        } catch (_: Exception) {
        }
        com.ivarna.fluxlinux.core.utils.StateManager.triggerRefresh()

        val chrootPath = profile?.chrootPath
        if (profile?.method == "chroot" && !chrootPath.isNullOrEmpty()) {
            executor.execute {
                val still = try {
                    ChrootDetection.probe(forceRoot = true, path = chrootPath).installed
                } catch (_: Exception) {
                    false
                }
                if (still) {
                    mainHandler.post {
                        com.ivarna.fluxlinux.core.utils.StateManager.triggerRefresh()
                    }
                }
            }
        }
    }

    fun isDebianChrootXfceInstalled(): Boolean =
        isChrootXfceInstalled(com.ivarna.fluxlinux.core.root.ChrootPaths.DEBIAN_CHROOT_PATH)

    fun isChrootXfceInstalled(chrootPath: String): Boolean =
        ChrootDetection.isXfceInstalled(chrootPath)

    /** @return true when the distro rootfs exists on disk for [distroId]. */
    fun isDistroInstalledOnFs(ctx: Context, distroId: String): Boolean {
        val profile = com.ivarna.fluxlinux.core.install.DistroInstallProfile.forId(distroId)
            ?: return false
        return when (profile.method) {
            "chroot" -> isChrootInstalled(profile.chrootPath ?: return false)
            else -> isProotInstalled(ctx, profile.prootName)
        }
    }

    /**
     * Ensure bootstrap extracted + scripts deployed + setup validated (async).
     * Fail-closed: false unless extract AND script/loader deploy AND
     * setup_termux all succeed (rootfs is NOT part of this gate — D6).
     * Recovery (B2): a setup_termux failure NEVER wipes the prefix —
     * installed proot containers are preserved by [BootstrapInstaller]; a corrupt
     * tree (missing marker) re-extracts on the next call with containers preserved.
     *
     * @param forceHostSetup re-run setup_termux validation even when the marker exists
     *   (used by Settings "Initialize Host Environment" / Prerequisites).
     */
    fun prepareHost(
        ctx: Context,
        forceHostSetup: Boolean = false,
        progress: (done: Long, total: Long, phase: String) -> Unit = { _, _, _ -> },
        onDone: (Boolean) -> Unit = {}
    ) {
        executor.execute {
            val ok = prepareHostBlocking(ctx, forceHostSetup, progress)
            Log.i(TAG, "prepareHost ok=$ok")
            mainHandler.post { onDone(ok) }
        }
    }

    /** Blocking variant (background thread only) used by tests / RootShell flows. */
    fun prepareHostBlocking(
        ctx: Context,
        forceHostSetup: Boolean = false,
        progress: (done: Long, total: Long, phase: String) -> Unit = { _, _, _ -> }
    ): Boolean {
        // Fast path (proot-opt-01): host already set up + bootstrap extracted →
        // skip ensureExtracted / deployScripts / tree sweeps entirely and return
        // immediately (< 5ms). Pulse is started after a guest session opens
        // (FluxTerminalSessionManager) and by the desktop start_gui*.sh scripts.
        if (!forceHostSetup && isHostSetupDone(ctx) && BootstrapInstaller.isExtracted(ctx)) {
            return true
        }
        // Corrupt/partial tree (no valid marker) → clean re-extract (containers preserved).
        if (!BootstrapInstaller.ensureExtracted(ctx, onProgress = progress)) {
            return false
        }
        if (!HostScriptDeployer.deployScripts(ctx)) {
            return false
        }
        if (!forceHostSetup && isHostSetupDone(ctx)) return true

        val marker = TermuxHostPaths.setupTermuxMarker(ctx)
        try {
            val (exit, out) = ShellCommandRunner.runCaptureExit(
                ctx,
                HostCommandBuilder.build(
                    ctx,
                    TermuxHostPaths.hostScript(ctx, "setup_termux.sh").absolutePath,
                    forceHostSetup = true
                ).first
            )
            Log.i(TAG, "setup_termux exit=$exit\n$out")
            if (exit == 0) return true
            marker.delete()
            Log.w(TAG, "setup_termux failed — host NOT ready (no destructive recovery)")
        } catch (e: Exception) {
            Log.w(TAG, "setup_termux run failed", e)
            marker.delete()
        }
        return false
    }
}
