package com.ivarna.fluxlinux.core.gpu

import android.content.Context
import com.ivarna.fluxlinux.core.gpu.DriverRelease.Pkg
import com.ivarna.fluxlinux.core.root.ChrootPaths
import com.ivarna.fluxlinux.core.terminal.GpuAccelDetector
import com.ivarna.fluxlinux.core.terminal.HostCommandBuilder
import com.ivarna.fluxlinux.core.terminal.ShellCommandRunner
import com.ivarna.fluxlinux.core.terminal.TermuxHostPaths
import com.ivarna.fluxlinux.core.utils.RootUtils
import java.io.File

/**
 * Mesa Turnip (Adreno) for guests: lfdevs/mesa-for-android-container, STABLE
 * `mesa-*` releases only (never turnip-weekly / unpatched turnip-* / prerelease).
 * Host picks release + asset; the guest (setup_hw_accel_guest.sh) downloads,
 * checks sha256, extracts, runs ldconfig and writes [MARKER].
 */
object GpuDriverInstaller {

    const val MARKER = "/etc/fluxlinux/turnip_version"
    const val API = "https://api.github.com/repos/lfdevs/mesa-for-android-container/releases?per_page=30"
    private const val BASE = "https://github.com/lfdevs/mesa-for-android-container/releases/download"
    private const val PREFS = "fluxlinux_gpu_driver"

    /** Set by the foreground activity: called (any thread) when an install ended offline. */
    @Volatile var onPending: (() -> Unit)? = null

    val TAG_RE = Regex("""^mesa-(\d+\.\d+\.\d+-devel-\d{8})$""")

    /** Known-good stable fallback (API down / rate limited / latest asset broken). */
    const val PIN_VERSION = "26.3.0-devel-20260824"
    val PIN_SHA = mapOf(
        "debian_trixie" to "c014cf66bdbff96417ee30d34f006cf51df64ae04893d599711b0b6b73b52ccf",
        "ubuntu_resolute" to "ee762f0855c47f9a245df3ce53a46d40b2240ede9b5c9ddf7606e724362fec77",
        "fedora_44" to "fc5a7f80bdd99720b083ec0d6bf9d349c493858acb03753e9fd99af5e04bde0b",
        "void" to "9b6051ee45e2891de6c150598305814900f2e48088d5fedc3d75d8d50f644dc7",
        "alpine_3.24" to "2f3e14dc57fa4ec75297a67af747872e7c460d3f3fcfff9e758e479874578ea3",
    )

    /**
     * FluxLinux distro id -> asset distro suffix. Null = no matching build
     * (arch/manjaro ship a pacman package set, openSUSE/Chimera/Deepin have none).
     */
    fun assetSuffix(distroId: String): String? =
        when (distroId.removeSuffix("_chroot").removeSuffix("13")) {
            "debian", "kali", "parrot" -> "debian_trixie"
            "ubuntu" -> "ubuntu_resolute"
            "fedora" -> "fedora_44"
            "void" -> "void"
            "alpine" -> "alpine_3.24"
            else -> null
        }

    fun pin(suffix: String): Pkg? = PIN_SHA[suffix]?.let {
        Pkg(PIN_VERSION, "$BASE/mesa-$PIN_VERSION/mesa-for-android-container_${PIN_VERSION}_${suffix}_arm64.tar.gz", it)
    }

    /** README: Adreno 6xx/7xx/8xx. Unknown model (sysfs unreadable) = try. */
    fun adrenoSupported(model: String?): Boolean {
        val n = Regex("\\d{3}").find(model.orEmpty())?.value?.toIntOrNull() ?: return true
        return n in 600..899
    }

    sealed class Plan {
        object NotAdreno : Plan()
        data class Skip(val msg: String) : Plan()
        object Offline : Plan()
        data class Install(val pkgs: List<Pkg>) : Plan()
    }

    /** Pure decision; [apiJson] null = API failed. */
    fun plan(
        distroId: String, adreno: Boolean, gpuModel: String?, online: Boolean, apiJson: String?
    ): Plan {
        if (!adreno) return Plan.NotAdreno
        if (!adrenoSupported(gpuModel)) {
            return Plan.Skip("Adreno ${gpuModel?.trim()} not supported by Turnip (needs 6xx/7xx/8xx)")
        }
        val suffix = assetSuffix(distroId)
            ?: return Plan.Skip("No Turnip build for this distro — software rendering stays")
        if (!online) return Plan.Offline
        val latest = DriverRelease.latest(apiJson, TAG_RE) {
            it.startsWith("mesa-for-android-container_") && it.contains("_${suffix}_arm64.tar")
        }
        return Plan.Install(DriverRelease.candidates(latest, pin(suffix)))
    }

    /**
     * Env for the guest hw-accel script. Blocking (network): call off the main
     * thread. Empty map when the device is not Adreno.
     */
    fun guestEnv(ctx: Context, distroId: String): Map<String, String> {
        val det = GpuAccelDetector.detect()
        if (det.mode == GpuAccelDetector.MODE_PANVK) return PanvkInstaller.guestEnv(ctx, distroId)
        val adreno =det.mode == GpuAccelDetector.MODE_TURNIP && det.vendorHint.startsWith("adreno")
        val model = runCatching { File("/sys/class/kgsl/kgsl-3d0/gpu_model").readText() }.getOrNull()
        val online = adreno && DriverRelease.online(ctx)
        val json = if (online) DriverRelease.fetch(API) else null
        val p = plan(distroId, adreno, model, online, json)
        val prefs = prefs(ctx)
        prefs.edit().apply {
            if (p is Plan.Offline) putString("pending", distroId) else remove("pending")
            val latest = (p as? Plan.Install)?.pkgs?.firstOrNull()?.version
            if (latest != null) putString("latest", latest)
        }.apply()
        return when (p) {
            Plan.NotAdreno -> emptyMap()
            is Plan.Skip -> mapOf("FLUX_TURNIP_MSG" to p.msg)
            Plan.Offline -> mapOf(
                "FLUX_TURNIP_MSG" to
                    "No internet — GPU driver not installed. Linux will use software rendering until you retry"
            )
            is Plan.Install -> p.pkgs.withIndex().associate { (i, k) ->
                "FLUX_TURNIP_${i + 1}" to "${k.version} ${k.url} ${k.sha256}"
            }
        }
    }

    fun pendingDistro(ctx: Context): String? = prefs(ctx).getString("pending", null)
    fun installedVersion(ctx: Context, distroId: String): String? =
        prefs(ctx).getString("installed_$distroId", null)
    fun latestVersion(ctx: Context): String? = prefs(ctx).getString("latest", null)

    fun updateAvailable(ctx: Context, distroId: String): Boolean {
        val latest = latestVersion(ctx) ?: return false
        val have = installedVersion(ctx, distroId) ?: return true
        return DriverRelease.isNewer(latest, have)
    }

    /** Read the guest marker and store it in prefs. Blocking; off main thread. */
    fun refreshInstalled(ctx: Context, distroId: String, prootName: String, chroot: Boolean) {
        val panvk = GpuAccelDetector.detect().mode == GpuAccelDetector.MODE_PANVK
        val marker = if (panvk) PanvkInstaller.MARKER else MARKER
        val raw = try {
            if (chroot) {
                val path = com.ivarna.fluxlinux.core.install.DistroInstallProfile.forId(distroId)
                    ?.chrootPath ?: ChrootPaths.CHROOT_PATH
                RootUtils.runRootCommand("cat $path$marker").takeIf { it.isSuccess }?.output
            } else {
                val bash = TermuxHostPaths.libBash(ctx).absolutePath
                ShellCommandRunner.runCaptureExit(
                    ctx,
                    arrayOf(bash, "-c",
                        "exec python ${TermuxHostPaths.PROOT_DISTRO} login $prootName -- cat $marker"),
                    HostCommandBuilder.envMap(ctx, includeTerm = false)
                ).takeIf { it.first == 0 }?.second
            }
        } catch (_: Exception) {
            null
        }
        val v = raw?.lines()?.lastOrNull { it.isNotBlank() }?.trim()
            ?.takeIf { if (panvk) PanvkInstaller.TAG_RE.matches("g0-v0-csf-v$it") else TAG_RE.matches("mesa-$it") }
        if (panvk) return PanvkInstaller.setInstalled(ctx, distroId, v)
        prefs(ctx).edit().apply {
            if (v != null) putString("installed_$distroId", v) else remove("installed_$distroId")
        }.apply()
    }

    private fun prefs(ctx: Context) =
        ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
