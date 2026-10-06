package com.ivarna.fluxlinux.core.gpu

import android.content.Context
import com.ivarna.fluxlinux.core.gpu.DriverRelease.Pkg
import java.io.File

/**
 * PanVK (Mesa Vulkan on mali_kbase) for MediaTek Mali Valhall v10+ guests:
 * zenithblue-oss/panvk-kbase-android. The repo mixes app tags (panplay-*,
 * panprobe-*) with driver tags (g615-v11-csf-v0.1.0-beta.N), all prereleases,
 * so we list releases and pick the newest `g*-csf-*`. One universal ICD covers
 * v10..v14 (the tag's g615-v11 is just the build profile), so no per-GPU tag.
 * Same flow as [GpuDriverInstaller]: host picks, guest downloads and verifies.
 */
object PanvkInstaller {

    const val MARKER = "/etc/fluxlinux/panvk_version"
    const val API = "https://api.github.com/repos/zenithblue-oss/panvk-kbase-android/releases?per_page=100"
    private const val BASE = "https://github.com/zenithblue-oss/panvk-kbase-android/releases/download"
    private const val PREFS = "fluxlinux_gpu_driver"
    private const val SO = "libvulkan_panfrost-glibc-aarch64.so"
    private const val ICD = "panfrost_icd.aarch64.json"

    val TAG_RE = Regex("""^g\d+-v\d+-csf-v(\d+\.\d+\.\d+\S*)$""")

    /** Known-good fallback (API down / rate limited / latest asset broken). */
    const val PIN_TAG = "g615-v11-csf-v0.1.0-beta.16"
    const val PIN_VERSION = "0.1.0-beta.16"
    const val PIN_SO_SHA = "bb92ce9c4dbbacd0899c0594a3b12dc29da73e0a45624e218a8c17ff3cb76def"
    const val PIN_ICD_SHA = "1c27f9363261d7080491b4c72ade8805f3f22ab6932123256515d2cf76580ff2"

    /** Guest spec: "version soUrl soSha icdUrl icdSha". */
    data class Spec(val version: String, val so: Pkg, val icd: Pkg) {
        fun line() = "$version ${so.url} ${so.sha256} ${icd.url} ${icd.sha256}"
    }

    fun pin() = Spec(
        PIN_VERSION,
        Pkg(PIN_VERSION, "$BASE/$PIN_TAG/$SO", PIN_SO_SHA),
        Pkg(PIN_VERSION, "$BASE/$PIN_TAG/$ICD", PIN_ICD_SHA),
    )

    /** The .so is glibc-only: Alpine and Chimera are musl. Void here is glibc. */
    fun glibc(distroId: String): Boolean =
        distroId.removeSuffix("_chroot") !in setOf("alpine", "chimera")

    // ── eligibility: MediaTek + Mali arch >= v10 ──

    sealed class Elig {
        object NotApplicable : Elig()
        data class Skip(val msg: String) : Elig()
        data class Ok(val arch: Int) : Elig()
    }

    private val MEDIATEK = Regex("""mediatek|(?:^|[^a-z0-9])mt\d{4}""")

    // Arm product -> Mesa PAN_ARCH. Anything below 100 (G31..G78) is pre-v10.
    private val ARCH = mapOf(
        310 to 10, 510 to 10, 610 to 10, 710 to 10,
        615 to 11, 715 to 11, 620 to 12, 720 to 12,
        625 to 13, 725 to 13, 925 to 13,
    )

    /** Mesa arch from a model string ("Mali-G615 6 cores r1p3 0xB8A3"), else the hex product id. */
    fun maliArch(info: String?): Int? {
        val s = info.orEmpty()
        if (Regex("""(?i)G1[-\s]""").containsMatchIn(s)) return 14
        Regex("""(?i)G(\d{2,3})(?!\d)""").find(s)?.groupValues?.get(1)?.toIntOrNull()?.let { n ->
            return if (n < 100) 9 else ARCH[n]
        }
        return Regex("""0x([0-9a-fA-F]{4})""").find(s)?.groupValues?.get(1)?.toInt(16)?.shr(12)
    }

    /**
     * Pure. [csf] = kbase exposes CSF-only sysfs (JM parts, i.e. v9 and older,
     * do not); used when gpuinfo is unreadable.
     */
    fun eligibility(signals: String, info: String?, csf: Boolean, hasMali: Boolean): Elig {
        if (!MEDIATEK.containsMatchIn(signals.lowercase())) return Elig.NotApplicable
        if (!hasMali) return Elig.Skip("No /dev/mali0 — PanVK needs the Mali kbase driver")
        val arch = maliArch(info) ?: if (csf) 10 else 9
        return if (arch >= 10) Elig.Ok(arch)
        else Elig.Skip("Mali ${info?.trim()?.ifEmpty { null } ?: "GPU"} is pre-Valhall-v10 — PanVK needs v10+ (G310/G510/G610/G710 or newer)")
    }

    fun current(signals: String): Elig {
        val d = "/sys/class/misc/mali0/device"
        val info = runCatching { File("$d/gpuinfo").readText() }.getOrNull()
        val csf = File("$d/firmware_config").exists() || File("$d/csg_scheduling_period").exists()
        return eligibility(signals, info, csf, File("/dev/mali0").exists())
    }

    // ── plan ──

    sealed class Plan {
        data class Skip(val msg: String) : Plan()
        object Offline : Plan()
        data class Install(val specs: List<Spec>) : Plan()
    }

    /** Pure decision; [apiJson] null = API failed. */
    fun plan(distroId: String, online: Boolean, apiJson: String?): Plan {
        if (!glibc(distroId)) {
            return Plan.Skip("PanVK needs a glibc distro ($distroId is musl) — software rendering stays")
        }
        if (!online) return Plan.Offline
        val so = DriverRelease.latest(apiJson, TAG_RE, allowPre = true) { it == SO }
        val icd = DriverRelease.latest(apiJson, TAG_RE, allowPre = true) { it == ICD }
        val latest = if (so != null && icd != null && so.version == icd.version) {
            Spec(so.version, so, icd)
        } else null
        return Plan.Install(listOfNotNull(latest, pin()).distinctBy { it.version })
    }

    /** Env for the guest hw-accel script. Blocking (network): off the main thread. */
    fun guestEnv(ctx: Context, distroId: String): Map<String, String> {
        val online = glibc(distroId) && DriverRelease.online(ctx)
        val json = if (online) DriverRelease.fetch(API) else null
        val p = plan(distroId, online, json)
        val prefs = prefs(ctx)
        prefs.edit().apply {
            if (p is Plan.Offline) putString("pending", distroId)
            else if (prefs.getString("pending", null) == distroId) remove("pending")
            (p as? Plan.Install)?.specs?.firstOrNull()?.version?.let { putString("panvk_latest", it) }
        }.apply()
        return when (p) {
            is Plan.Skip -> mapOf("FLUX_PANVK_MSG" to p.msg)
            Plan.Offline -> mapOf(
                "FLUX_PANVK_MSG" to
                    "No internet — GPU driver not installed. Linux will use software rendering until you retry"
            )
            is Plan.Install -> p.specs.withIndex().associate { (i, s) -> "FLUX_PANVK_${i + 1}" to s.line() }
        }
    }

    fun installedVersion(ctx: Context, distroId: String): String? =
        prefs(ctx).getString("panvk_installed_$distroId", null)
    fun latestVersion(ctx: Context): String? = prefs(ctx).getString("panvk_latest", null)

    fun updateAvailable(ctx: Context, distroId: String): Boolean {
        val latest = latestVersion(ctx) ?: return false
        val have = installedVersion(ctx, distroId) ?: return true
        return DriverRelease.isNewer(latest, have)
    }

    fun setInstalled(ctx: Context, distroId: String, v: String?) {
        prefs(ctx).edit().apply {
            if (v != null) putString("panvk_installed_$distroId", v) else remove("panvk_installed_$distroId")
        }.apply()
    }

    private fun prefs(ctx: Context) =
        ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
