package com.ivarna.fluxlinux.core.terminal

import android.content.Context
import android.os.Build
import android.util.Log
import java.io.File

/**
 * Host GPU vendor → guest accel mode.
 *
 * Snapdragon / Adreno (KGSL) → Turnip
 * Everything else (Mali, PowerVR, Xclipse, unknown) → VirGL
 *
 * Detect only. Distro package names and tarball URLs live in guest scripts.
 */
object GpuAccelDetector {

    private const val TAG = "GpuAccelDetector"
    private const val PREFS = "fluxlinux_state"
    private const val KEY_MODE = "flux_gpu"
    private const val KEY_VENDOR = "flux_gpu_vendor"

    const val MODE_TURNIP = "turnip"
    const val MODE_VIRGL = "virgl"
    const val MODE_PANVK = "panvk"
    const val MODE_AUTO = "auto"
    const val MODE_ASK = "ask"

    data class Detection(
        val mode: String,
        val vendorHint: String,
        val signals: String
    )

    fun detect(): Detection {
        val parts = mutableListOf<String>()
        fun add(label: String, value: String?) {
            val v = value?.trim().orEmpty()
            if (v.isNotEmpty()) parts += "$label=$v"
        }

        add("HARDWARE", Build.HARDWARE)
        add("BOARD", Build.BOARD)
        add("DEVICE", Build.DEVICE)
        add("PRODUCT", Build.PRODUCT)
        add("MANUFACTURER", Build.MANUFACTURER)
        add("BRAND", Build.BRAND)
        add("MODEL", Build.MODEL)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            add("SOC_MANUFACTURER", Build.SOC_MANUFACTURER)
            add("SOC_MODEL", Build.SOC_MODEL)
        }

        for (key in PROP_KEYS) {
            add(key, sysProp(key))
        }

        val kgsl = File("/dev/kgsl-3d0").exists() || File("/dev/kgsl-3d0").canRead()
        if (kgsl) parts += "kgsl=/dev/kgsl-3d0"

        val blob = parts.joinToString(" ").lowercase()
        var result = classify(blob, kgsl)
        // MediaTek Mali v10+ → PanVK (kbase). Everything else stays VirGL.
        if (result.mode == MODE_VIRGL &&
            com.ivarna.fluxlinux.core.gpu.PanvkInstaller.current(blob) is
                com.ivarna.fluxlinux.core.gpu.PanvkInstaller.Elig.Ok
        ) {
            result = result.copy(mode = MODE_PANVK)
        }
        Log.i(
            TAG,
            "GPU detect → mode=${result.mode} vendor=${result.vendorHint} signals=${result.signals}"
        )
        return result
    }

    fun fluxGpuEnv(): String = detect().mode

    /**
     * Settings / component env resolver. Never returns `auto`.
     * `ask`/`manual` stay `ask` so a TTY guest menu can run.
     */
    fun resolveFluxGpu(raw: String?): String {
        return when (normalize(raw)) {
            MODE_AUTO -> fluxGpuEnv()
            MODE_ASK -> MODE_ASK
            MODE_TURNIP -> MODE_TURNIP
            MODE_PANVK -> MODE_PANVK
            else -> MODE_VIRGL
        }
    }

    fun persist(ctx: Context, d: Detection) {
        ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_MODE, d.mode)
            .putString(KEY_VENDOR, d.vendorHint)
            .apply()
    }

    fun readPersisted(ctx: Context): Pair<String?, String?> {
        val p = ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return p.getString(KEY_MODE, null) to p.getString(KEY_VENDOR, null)
    }

    /**
     * Alias table shared with guest [flux_gpu_normalize].
     * Returns `turnip`, `virgl`, `ask`, or `auto`. Unknown → `virgl`.
     */
    fun normalize(raw: String?): String {
        val v = raw?.trim()?.lowercase().orEmpty()
        return when (v) {
            "", MODE_AUTO -> MODE_AUTO
            MODE_ASK, "manual" -> MODE_ASK
            MODE_PANVK -> MODE_PANVK
            MODE_TURNIP, "adreno", "snapdragon", "qcom", "qualcomm", "kgsl", "zink" ->
                MODE_TURNIP
            MODE_VIRGL, "virpipe", "mali", "powervr", "xclipse",
            "llvmpipe", "soft", "software", "sw" ->
                MODE_VIRGL
            else -> MODE_VIRGL
        }
    }

    /** Pure classifier — no [Build] / getprop. Unit-test entry point. */
    fun classify(blob: String, kgslPresent: Boolean): Detection {
        val lower = blob.lowercase()
        val isAdreno = kgslPresent || matchesAdreno(lower)
        val mode = if (isAdreno) MODE_TURNIP else MODE_VIRGL
        val vendor = when {
            isAdreno -> "adreno/snapdragon"
            matchesMali(lower) -> "mali"
            matchesPowerVr(lower) -> "powervr"
            matchesXclipse(lower) -> "xclipse"
            else -> "unknown"
        }
        return Detection(mode = mode, vendorHint = vendor, signals = lower)
    }

    fun matchesAdreno(blob: String): Boolean {
        if (ADRENO_WORDS.any { containsToken(blob, it) }) return true
        // msm8953 / sdm845 — prefix plus digits, not a random "msm" substring.
        if (MSM_SDM.containsMatchIn(blob)) return true
        // SoC-shaped sm#### (sm8150 / sm4xxx). Do not treat bare "sm4" as a hit.
        if (SM_SOC.containsMatchIn(blob)) return true
        return false
    }

    fun matchesMali(blob: String): Boolean =
        MALI_WORDS.any { containsToken(blob, it) || (it.startsWith("mt") && blob.contains(it)) }

    fun matchesPowerVr(blob: String): Boolean = POWERVR_WORDS.any { containsToken(blob, it) }

    fun matchesXclipse(blob: String): Boolean = XCLIPSE_WORDS.any { containsToken(blob, it) }

    /** Token match so "sun" does not hit "samsung". */
    internal fun containsToken(blob: String, token: String): Boolean {
        if (token.isEmpty()) return false
        val re = Regex("(?:^|[^a-z0-9])${Regex.escape(token)}(?:[^a-z0-9]|$)")
        return re.containsMatchIn(blob)
    }

    private fun sysProp(key: String): String {
        return try {
            val clazz = Class.forName("android.os.SystemProperties")
            val get = clazz.getMethod("get", String::class.java, String::class.java)
            (get.invoke(null, key, "") as? String).orEmpty()
        } catch (_: Throwable) {
            try {
                val p = ProcessBuilder("getprop", key)
                    .redirectErrorStream(true)
                    .start()
                p.inputStream.bufferedReader().use { it.readText() }.trim()
            } catch (_: Throwable) {
                ""
            }
        }
    }

    private val PROP_KEYS = listOf(
        "ro.hardware",
        "ro.hardware.chipname",
        "ro.chipname",
        "ro.board.platform",
        "ro.soc.model",
        "ro.soc.manufacturer",
        "ro.product.board",
        "ro.hardware.egl",
        "ro.hardware.vulkan",
        "ro.gfx.driver.0",
        "ro.opengles.version"
    )

    private val ADRENO_WORDS = listOf(
        "qcom", "qualcomm", "adreno", "kgsl", "snapdragon",
        "sm8150", "sm8250", "sm8350", "sm8450", "sm8550", "sm8650", "sm8750",
        "lahaina", "taro", "kalama", "pineapple", "canoe", "sun",
        "kona", "lito", "bengal", "holi", "crow", "ravelin", "parrot", "blair",
        "anorak", "hamoa", "volcano", "pitti", "niobe", "cliq", "shima", "yupik",
        "atoll", "trinket", "guppy", "strait", "bitra", "waipio"
    )

    private val MSM_SDM = Regex("""(?:^|[^a-z0-9])(?:msm|sdm)[0-9]""")

    // sm + 3–4 digits as a token, or sm4/5/6/7/8 followed by 3 digits (sm8550).
    private val SM_SOC = Regex("""(?:^|[^a-z0-9])sm(?:[0-9]{3,4}|[4-8][0-9]{3})(?:[^0-9]|$)""")

    private val MALI_WORDS = listOf(
        "mali", "exynos", "kirin", "hisi", "mediatek", "mt68", "mt67", "mt69",
        "dimensity", "helio", "tensor", "gs10", "gs20", "gs30"
    )

    private val POWERVR_WORDS = listOf("powervr", "imgtec", "imagination", "rogue")

    private val XCLIPSE_WORDS = listOf("xclipse", "amdgpu", "samsung_xclipse")
}
