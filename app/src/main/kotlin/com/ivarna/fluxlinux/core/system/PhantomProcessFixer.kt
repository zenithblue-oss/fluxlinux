package com.ivarna.fluxlinux.core.system

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import com.ivarna.fluxlinux.core.utils.RootUtils
import kotlinx.coroutines.flow.MutableStateFlow
import rikka.shizuku.Shizuku

/**
 * Android 12+ kills an app's background child processes (Phantom Process Killer, exit 137).
 * Detect it, and disable it via root, Shizuku (shell uid) or adb from a PC.
 */
object PhantomProcessFixer {

    enum class State { ENABLED, DISABLED, UNKNOWN }

    private const val KEY = "settings_enable_monitor_phantom_procs"
    private const val PREFS = "fluxlinux_state"
    private const val PREF_HIDE = "phantom_warning_hidden"
    const val SHIZUKU_REQUEST_CODE = 4141

    /** Same commands for root, Shizuku and adb (adb gets an `adb shell` prefix). */
    val shellCommands = listOf(
        "/system/bin/device_config set_sync_disabled_for_tests persistent",
        "/system/bin/device_config put activity_manager max_phantom_processes 2147483647",
        "settings put global $KEY false"
    )

    val adbCommands: String get() = shellCommands.joinToString("\n") { "adb shell \"$it\"" }

    /** True when the OS has the phantom process monitor (Android 12+). */
    fun applicable(): Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S

    /**
     * Readable without root: only the Settings.Global flag (Android 12L+).
     * device_config needs shell, so Android 12 or an unset flag is UNKNOWN.
     */
    fun state(ctx: Context): State =
        when (Settings.Global.getString(ctx.contentResolver, KEY)) {
            "false", "0" -> State.DISABLED
            "true", "1" -> State.ENABLED
            else -> State.UNKNOWN
        }

    // ---- warning prompt ------------------------------------------------------------

    /** MainActivity shows the dialog while this is true. */
    val promptVisible = MutableStateFlow(false)
    @Volatile private var promptedThisRun = false

    fun warningHidden(ctx: Context): Boolean =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(PREF_HIDE, false)

    fun hideWarningForever(ctx: Context) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(PREF_HIDE, true).apply()
    }

    /** Call when a terminal or desktop session starts. Shows the dialog once per app run. */
    fun maybePrompt(ctx: Context) {
        if (!applicable() || promptedThisRun || warningHidden(ctx)) return
        if (state(ctx) == State.DISABLED) return
        promptedThisRun = true
        promptVisible.value = true
    }

    // ---- root ----------------------------------------------------------------------

    fun rootAvailable(): Boolean = RootUtils.isRootAvailable()

    /** Blocking; run off the main thread. Returns null on success, else an error text. */
    fun applyRoot(): String? {
        for (c in shellCommands) {
            val r = RootUtils.runRootCommand(c)
            if (!r.isSuccess) return r.error.ifBlank { "root command failed" }
        }
        return null
    }

    // ---- Shizuku -------------------------------------------------------------------

    fun shizukuRunning(): Boolean = runCatching { Shizuku.pingBinder() }.getOrDefault(false)

    fun shizukuGranted(): Boolean =
        runCatching { Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED }.getOrDefault(false)

    /** Asks the Shizuku manager for permission; result arrives on [onResult] (main thread). */
    fun requestShizuku(onResult: (Boolean) -> Unit) {
        val l = object : Shizuku.OnRequestPermissionResultListener {
            override fun onRequestPermissionResult(requestCode: Int, grantResult: Int) {
                if (requestCode != SHIZUKU_REQUEST_CODE) return
                Shizuku.removeRequestPermissionResultListener(this)
                onResult(grantResult == PackageManager.PERMISSION_GRANTED)
            }
        }
        Shizuku.addRequestPermissionResultListener(l)
        Shizuku.requestPermission(SHIZUKU_REQUEST_CODE)
    }

    /**
     * Blocking; run off the main thread. Null on success, else an error text.
     * ponytail: Shizuku.newProcess is private in API 13, so reflection instead of an AIDL UserService;
     * if a future Shizuku drops it, switch to a UserService (keep rule in proguard-rules.pro).
     */
    fun applyShizuku(): String? = try {
        val m = Shizuku::class.java.getDeclaredMethod(
            "newProcess", Array<String>::class.java, Array<String>::class.java, String::class.java
        ).apply { isAccessible = true }
        var err: String? = null
        for (c in shellCommands) {
            val p = m.invoke(null, arrayOf("sh", "-c", c), null, null) as Process
            p.outputStream.close()
            val out = p.errorStream.bufferedReader().readText()
            p.inputStream.bufferedReader().readText()
            if (p.waitFor() != 0) { err = out.ifBlank { "command failed: $c" }; break }
        }
        err
    } catch (e: Exception) {
        (e.cause ?: e).message ?: "Shizuku failed"
    }
}
