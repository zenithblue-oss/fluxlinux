package com.ivarna.fluxlinux.core.root

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import java.io.BufferedReader
import java.io.File
import java.io.FileOutputStream
import java.io.InputStreamReader
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * RootShell — Singleton for executing commands as root via KernelSU / Magisk su.
 * Ported from termux-lib `RootShellService`.
 *
 * Requirements:
 *  - KernelSU or Magisk must be installed on device.
 *  - The app package must be granted superuser access in the KSU / Magisk manager.
 *
 * All callbacks ([onLine], [onDone]) are dispatched on the main thread.
 */
object RootShell {

    private const val TAG = "RootShell"
    private val executor = Executors.newCachedThreadPool()
    // Lazy so JVM unit tests never touch Looper unless a callback path runs.
    private val mainHandler: Handler by lazy { Handler(Looper.getMainLooper()) }

    /** Temp dir where asset scripts are staged before execution. */
    private const val SCRIPTS_TMP = "/data/local/tmp/fluxlinux_scripts"

    /**
     * Working su argv prefix, e.g. `["/system/bin/su","-c"]` or `["/system/bin/sh","-c"]` with
     * embedded su. Discovered once via [resolveSuInvocation]; null if root unavailable.
     *
     * IMPORTANT: do NOT gate on File.exists() — KernelSU/Magisk often hide su from the app
     * mount namespace until exec; File.exists() false positives skip the only working path.
     */
    @Volatile
    private var cachedSuInvocation: List<String>? = null

    @Volatile
    private var cachedBusyBoxPath: String? = null

    /** [ensureChrootHelper] succeeded this process (helper + resolver staged). */
    private val chrootHelperReady = AtomicBoolean(false)

    /** Re-stage on next [ensureChrootHelper] (an uninstall script may delete the helper). */
    fun invalidateChrootHelper() = chrootHelperReady.set(false)

    // ─────────────────────────────────────────────────────────────────────────
    // Public API
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Returns true if a working root shell (su) is available.
     * Runs synchronously — always call from a background thread.
     */
    fun isRootAvailable(): Boolean = resolveSuInvocation() != null

    /**
     * Async root probe. Runs [isRootAvailable] off the UI thread; [onResult] always on main.
     */
    fun probeRootAvailable(forceClearCache: Boolean = false, onResult: (Boolean) -> Unit) {
        executor.execute {
            if (forceClearCache) clearSuCache()
            val ok = try {
                isRootAvailable()
            } catch (_: Exception) {
                false
            }
            mainHandler.post { onResult(ok) }
        }
    }

    /** Result of a blocking root capture (exit code + stdout). */
    data class CaptureResult(val exitCode: Int, val stdout: String)

    /**
     * Capture stdout of a root command (blocking). Empty string on failure.
     * Background thread only. [timeoutMs] > 0 aborts hung su (exit -2).
     */
    fun capture(cmd: String, timeoutMs: Long = 0L): String =
        captureResult(cmd, timeoutMs).stdout

    /**
     * Capture stdout + exit code of a root command (blocking).
     * Background thread only.
     *
     * @param timeoutMs 0 = wait forever; >0 kills process after timeout (exitCode -2).
     * @param onLine optional live line callback (reader thread) for install UIs.
     * @param processHolder optional live Process for cancel/destroyForcibly.
     * @return [CaptureResult] with exitCode -1 if no su / exception, -2 on timeout.
     */
    fun captureResult(
        cmd: String,
        timeoutMs: Long = 0L,
        onLine: ((String) -> Unit)? = null,
        processHolder: AtomicReference<Process?>? = null
    ): CaptureResult {
        val inv = resolveSuInvocation() ?: return CaptureResult(-1, "")
        return try {
            val args = buildSuArgs(inv, cmd)
            val pb = ProcessBuilder(args).redirectErrorStream(true).start()
            try { pb.outputStream.close() } catch (_: Exception) {}
            processHolder?.set(pb)
            try {
                if (onLine != null) {
                    // Stream lines while process runs (install progress).
                    val sb = StringBuilder()
                    val readerThread = Thread {
                        try {
                            pb.inputStream.bufferedReader().use { reader ->
                                var line: String?
                                while (reader.readLine().also { line = it } != null) {
                                    val l = line ?: continue
                                    synchronized(sb) { sb.append(l).append('\n') }
                                    try {
                                        onLine(l)
                                    } catch (_: Exception) {
                                    }
                                }
                            }
                        } catch (_: Exception) {
                        }
                    }.also { it.isDaemon = true; it.start() }

                    val finished = if (timeoutMs > 0L) {
                        pb.waitFor(timeoutMs, TimeUnit.MILLISECONDS)
                    } else {
                        pb.waitFor()
                        true
                    }
                    if (!finished) {
                        pb.destroyForcibly()
                        try {
                            readerThread.join(500)
                        } catch (_: Exception) {
                        }
                        Log.w(TAG, "capture timeout after ${timeoutMs}ms cmd=${cmd.take(80)}")
                        val partial = synchronized(sb) { sb.toString() }
                        return CaptureResult(-2, partial)
                    }
                    try {
                        readerThread.join(5_000)
                    } catch (_: Exception) {
                    }
                    val code = try {
                        pb.exitValue()
                    } catch (_: Exception) {
                        -1
                    }
                    CaptureResult(code, synchronized(sb) { sb.toString() })
                } else {
                    val outFuture = executor.submit<String> {
                        pb.inputStream.bufferedReader().use { it.readText() }
                    }
                    val finished = if (timeoutMs > 0L) {
                        pb.waitFor(timeoutMs, TimeUnit.MILLISECONDS)
                    } else {
                        pb.waitFor()
                        true
                    }
                    if (!finished) {
                        pb.destroyForcibly()
                        val partial = try {
                            outFuture.get(500, TimeUnit.MILLISECONDS)
                        } catch (_: Exception) {
                            ""
                        }
                        Log.w(TAG, "capture timeout after ${timeoutMs}ms cmd=${cmd.take(80)}")
                        return CaptureResult(-2, partial)
                    }
                    val out = try {
                        outFuture.get(5, TimeUnit.SECONDS)
                    } catch (e: Exception) {
                        Log.w(TAG, "capture read failed: ${e.message}")
                        ""
                    }
                    val code = try {
                        pb.exitValue()
                    } catch (_: Exception) {
                        -1
                    }
                    CaptureResult(code, out)
                }
            } finally {
                processHolder?.compareAndSet(pb, null)
            }
        } catch (e: Exception) {
            Log.w(TAG, "capture failed: ${e.message}")
            CaptureResult(-1, "")
        }
    }

    /**
     * Execute a shell command as root.
     * [onLine] is called on the Main thread for each stdout/stderr line.
     * [onDone] is called on the Main thread with the exit code.
     */
    fun execute(
        cmd: String,
        onLine: (String) -> Unit = {},
        onDone: (Int) -> Unit = {}
    ) {
        executor.execute {
            val inv = resolveSuInvocation()
            val code = if (inv == null) {
                mainHandler.post { onLine("[RootShell] ERROR: no working su binary") }
                -1
            } else {
                runCommand(buildSuArgs(inv, cmd), onLine)
            }
            mainHandler.post { onDone(code) }
        }
    }

    /**
     * Execute a shell command as root synchronously (blocking).
     * Must be called from a background thread. Returns the exit code.
     */
    fun executeSync(cmd: String): Int {
        val inv = resolveSuInvocation() ?: return -1
        return runCommand(buildSuArgs(inv, cmd)) {}
    }

    /**
     * Execute a shell script file as root.
     * [scriptPath] must be an absolute path to a script on the device.
     */
    fun executeScript(
        scriptPath: String,
        onLine: (String) -> Unit = {},
        onDone: (Int) -> Unit = {}
    ) {
        execute("sh \"$scriptPath\"", onLine, onDone)
    }

    /**
     * Copy an asset to /data/local/tmp/fluxlinux_scripts/, make it executable,
     * then run it as root.
     */
    fun executeScriptAsset(
        context: Context,
        assetName: String,
        onLine: (String) -> Unit = {},
        onDone: (Int) -> Unit = {}
    ) {
        executor.execute {
            val stagedPath = stageAsset(context, assetName)
            if (stagedPath == null) {
                mainHandler.post {
                    onLine("[RootShell] ERROR: Failed to stage asset '$assetName'")
                    onDone(-1)
                }
                return@execute
            }
            val inv = resolveSuInvocation()
            val code = if (inv == null) {
                mainHandler.post { onLine("[RootShell] ERROR: no working su binary") }
                -1
            } else {
                runCommand(buildSuArgs(inv, "sh \"$stagedPath\""), onLine)
            }
            mainHandler.post { onDone(code) }
        }
    }

    /**
     * Discover a working su invocation. Tries absolute paths without File.exists(),
     * plus `sh -c` wrappers. Caches the first success. Background thread only.
     */
    fun resolveSuInvocation(): List<String>? {
        cachedSuInvocation?.let { return it }

        val trials: List<List<String>> = listOf(
            // Direct (KernelSU / Magisk common)
            listOf("/system/bin/su", "-c"),
            listOf("/system/xbin/su", "-c"),
            listOf("/sbin/su", "-c"),
            listOf("/debug_ramdisk/su", "-c"),
            listOf("su", "-c"),
            // Magisk sometimes wants uid first: su 0 -c 'cmd'
            listOf("/system/bin/su", "0", "-c"),
            listOf("su", "0", "-c"),
            // sh -c wrapper form
            listOf("/system/bin/sh", "-c", "SU_WRAP:/system/bin/su"),
            listOf("/system/bin/sh", "-c", "SU_WRAP:su"),
            listOf("/system/bin/sh", "-c", "SU_WRAP:/debug_ramdisk/su")
        )

        for (trial in trials) {
            if (trySuProbe(trial)) {
                cachedSuInvocation = trial
                Log.i(TAG, "resolveSuInvocation OK: $trial")
                return trial
            }
        }
        Log.w(TAG, "resolveSuInvocation: no working su")
        return null
    }

    /** Clear cached su (e.g. after user grants root in manager). */
    fun clearSuCache() {
        cachedSuInvocation = null
    }

    /**
     * Test-only hook: seed the discovered su invocation so JVM unit tests can
     * exercise argv builders without probing device `su` binaries.
     */
    fun seedSuInvocationForTest(invocation: List<String>?) {
        cachedSuInvocation = invocation
    }

    /**
     * Resolve a root-capable BusyBox via the staged shell resolver (su only).
     * Does not stage the sidecar — callers must [ensureBusyBoxResolver] first.
     * Background thread only. Caches the first success.
     */
    fun resolveBusyBox(): String? {
        cachedBusyBoxPath?.let { return it }
        val pkg = com.ivarna.fluxlinux.BuildConfig.APPLICATION_ID
        val probe =
            "export FLUX_PACKAGE='$pkg'; " +
                "if [ -f ${BusyBoxPaths.RESOLVER_ON_DEVICE} ]; then " +
                ". ${BusyBoxPaths.RESOLVER_ON_DEVICE}; " +
                "resolve_bb && printf '%s\\n' \"\$BB\" && exit 0; " +
                "fi; exit 1"
        val result = captureResult(probe, timeoutMs = 8_000L)
        if (result.exitCode != 0) return null
        val path = result.stdout.lineSequence()
            .map { it.trim() }
            .lastOrNull { it.startsWith("/") }
        if (path.isNullOrEmpty()) return null
        cachedBusyBoxPath = path
        return path
    }

    /**
     * Async BusyBox probe. Runs [resolveBusyBox] off the UI thread; [onResult] on main.
     */
    fun probeBusyBox(forceClearCache: Boolean = false, onResult: (String?) -> Unit) {
        executor.execute {
            if (forceClearCache) clearBusyBoxCache()
            val path = try {
                resolveBusyBox()
            } catch (_: Exception) {
                null
            }
            mainHandler.post { onResult(path) }
        }
    }

    /** Cache only — never probes. */
    fun cachedBusyBox(): String? = cachedBusyBoxPath

    fun clearBusyBoxCache() {
        cachedBusyBoxPath = null
    }

    fun seedBusyBoxForTest(path: String?) {
        cachedBusyBoxPath = path
    }

    /**
     * Stage [BusyBoxPaths.RESOLVER_ASSET] to [BusyBoxPaths.RESOLVER_ON_DEVICE]
     * using the same su cp/chmod pattern as [ensureChrootHelper].
     */
    fun ensureBusyBoxResolver(ctx: Context): Boolean {
        return try {
            val staged = stageAsset(ctx, BusyBoxPaths.RESOLVER_ASSET)
                ?: run {
                    val tmp = File(ctx.cacheDir, "resolve_bb.sh")
                    ctx.assets.open(BusyBoxPaths.RESOLVER_ASSET).use { input ->
                        tmp.outputStream().use { input.copyTo(it) }
                    }
                    tmp.absolutePath
                }
            var code = executeSync(
                "cp -f '$staged' ${BusyBoxPaths.RESOLVER_ON_DEVICE} && " +
                    "chmod 755 ${BusyBoxPaths.RESOLVER_ON_DEVICE}"
            )
            if (code != 0) {
                val bytes = ctx.assets.open(BusyBoxPaths.RESOLVER_ASSET).use { it.readBytes() }
                val b64 = android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP)
                code = executeSync(
                    "echo $b64 | base64 -d > ${BusyBoxPaths.RESOLVER_ON_DEVICE} && " +
                        "chmod 755 ${BusyBoxPaths.RESOLVER_ON_DEVICE}"
                )
            }
            if (code != 0) {
                Log.w(TAG, "ensureBusyBoxResolver root stage failed exit=$code")
                return false
            }
            Log.i(TAG, "ensureBusyBoxResolver staged ${BusyBoxPaths.RESOLVER_ON_DEVICE}")
            true
        } catch (e: Exception) {
            Log.w(TAG, "ensureBusyBoxResolver failed: ${e.message}")
            false
        }
    }

    /**
     * Single shell snippet that runs [cmd] as root — for TerminalSession / `sh -c` only.
     * Escapes [cmd] in single quotes so `;` `&&` `$` inside the guest chain stay intact.
     */
    fun shellRootCommand(cmd: String): String {
        val escaped = cmd.replace("'", "'\\''")
        val inv = cachedSuInvocation ?: resolveSuInvocation()
        return when {
            inv != null && inv.size >= 3 && inv[2].startsWith("SU_WRAP:") -> {
                val suBin = inv[2].removePrefix("SU_WRAP:")
                "$suBin -c '$escaped'"
            }
            inv != null && inv.isNotEmpty() && inv.last() == "-c" -> {
                val prefix = inv.dropLast(1).joinToString(" ")
                "$prefix -c '$escaped'"
            }
            else -> "/system/bin/su -c '$escaped'"
        }
    }

    /**
     * Stage [ChrootPaths.CHROOT_HELPER] from assets when missing or version stamp
     * mismatches. Uses [RootShell] su discovery (not hard-coded `/system/bin/su`).
     * Safe to call from a background thread; may briefly invoke su.
     *
     * Lives here (not in ChrootCommandBuilder) so RootShell stays su-only and the
     * RootShell ↔ ChrootCommandBuilder cycle stays broken (plan §2.5).
     */
    @Synchronized // warm-up (TerminalToolSelector) and session prep may race on the cp
    fun ensureChrootHelper(ctx: Context): Boolean {
        if (chrootHelperReady.get()) return true
        return try {
            val bbOk = ensureBusyBoxResolver(ctx)
            val existing = capture(
                "head -n 2 ${ChrootPaths.CHROOT_HELPER} 2>/dev/null || true",
                timeoutMs = 4_000L
            )
            if (existing.contains(ChrootPaths.CHROOT_HELPER_VERSION)) {
                chrootHelperReady.set(bbOk)
                return true
            }

            val staged = stageAsset(ctx, ChrootPaths.CHROOT_HELPER_ASSET)
                ?: run {
                    val tmp = File(ctx.cacheDir, "fluxlinux_chroot.sh")
                    ctx.assets.open(ChrootPaths.CHROOT_HELPER_ASSET).use { input ->
                        tmp.outputStream().use { input.copyTo(it) }
                    }
                    tmp.absolutePath
                }

            var code = executeSync(
                "cp -f '$staged' ${ChrootPaths.CHROOT_HELPER} && " +
                    "chmod 755 ${ChrootPaths.CHROOT_HELPER} && " +
                    "grep -q '${ChrootPaths.CHROOT_HELPER_VERSION}' ${ChrootPaths.CHROOT_HELPER}"
            )
            if (code != 0) {
                // Last resort: base64 stream (app cannot write /data/local/tmp)
                val bytes = ctx.assets.open(ChrootPaths.CHROOT_HELPER_ASSET).use { it.readBytes() }
                val b64 = android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP)
                code = executeSync(
                    "echo $b64 | base64 -d > ${ChrootPaths.CHROOT_HELPER} && " +
                        "chmod 755 ${ChrootPaths.CHROOT_HELPER}"
                )
            }
            if (code != 0) {
                Log.w(TAG, "ensureChrootHelper root stage failed exit=$code")
                return false
            }
            Log.i(TAG, "ensureChrootHelper staged ${ChrootPaths.CHROOT_HELPER}")
            chrootHelperReady.set(bbOk)
            true
        } catch (e: Exception) {
            Log.w(TAG, "ensureChrootHelper failed: ${e.message}")
            false
        }
    }

    /**
     * Run a command inside the Debian 13 chroot as [user] via SSOT helper.
     * Pass [context] so the helper is staged from assets when missing.
     */
    fun executeInChroot(
        cmd: String,
        user: String = "flux",
        chrootPath: String = ChrootPaths.CHROOT_PATH,
        onLine: (String) -> Unit = {},
        onDone: (Int) -> Unit = {},
        context: Context? = null
    ) {
        context?.let { ensureChrootHelper(it) }
        val u = if (user == "root") "root" else "flux"
        val b64 = android.util.Base64.encodeToString(
            cmd.toByteArray(Charsets.UTF_8),
            android.util.Base64.NO_WRAP
        )
        execute(buildChrootHelperCmd(u, b64, chrootPath), onLine, onDone)
    }

    /**
     * Blocking capture inside chroot (bg thread only). Prefer for probe/status.
     * Pass [context] to stage helper from assets when missing.
     */
    fun captureInChroot(
        cmd: String,
        user: String = "flux",
        chrootPath: String = ChrootPaths.CHROOT_PATH,
        timeoutMs: Long = 60_000L,
        context: Context? = null,
        onLine: ((String) -> Unit)? = null,
        processHolder: AtomicReference<Process?>? = null
    ): CaptureResult {
        context?.let { ensureChrootHelper(it) }
        val u = if (user == "root") "root" else "flux"
        val b64 = android.util.Base64.encodeToString(
            cmd.toByteArray(Charsets.UTF_8),
            android.util.Base64.NO_WRAP
        )
        return captureResult(
            buildChrootHelperCmd(u, b64, chrootPath),
            timeoutMs,
            onLine = onLine,
            processHolder = processHolder
        )
    }

    /**
     * Self-heal: if helper missing under /data/local/tmp, copy from app home/staged.
     * Then `sh helper b64 …`.
     */
    private fun buildChrootHelperCmd(user: String, b64: String, chrootPath: String): String {
        val helper = ChrootPaths.CHROOT_HELPER
        val resolver = BusyBoxPaths.RESOLVER_ON_DEVICE
        val pkg = com.ivarna.fluxlinux.BuildConfig.APPLICATION_ID
        val prefix = "/data/data/$pkg/files/usr"
        val hostTmp = "$prefix/tmp"
        val bb = cachedBusyBox()
        val fluxBb = if (!bb.isNullOrEmpty()) "export FLUX_BB='$bb'; " else ""
        // Always pin package paths so zenithblue (and future flavors) do not fall
        // back to the script's ivarna defaults when FLUX_* is unset.
        //
        // Android /system/bin/sh rejects `VAR=val if …; then` (assignment before
        // compound command → "syntax error: unexpected 'then'"). Use export +
        // semicolons so the if-list is a normal compound statement.
        return "${fluxBb}export FLUX_PACKAGE='$pkg' FLUX_PREFIX='$prefix' " +
            "FLUX_HOST_TMP='$hostTmp' FLUX_CHROOT='$chrootPath'; " +
            "if [ ! -f $helper ]; then " +
            "for _s in " +
            "/data/data/$pkg/files/home/fluxlinux_chroot.sh " +
            "/data/data/$pkg/files/staged_scripts/fluxlinux_chroot.sh; do " +
            "[ -f \"\$_s\" ] && cp -f \"\$_s\" $helper && chmod 755 $helper && break; " +
            "done; fi; " +
            "if [ ! -f $resolver ]; then " +
            "for _s in " +
            "/data/data/$pkg/files/home/resolve_bb.sh " +
            "/data/data/$pkg/files/staged_scripts/resolve_bb.sh; do " +
            "[ -f \"\$_s\" ] && cp -f \"\$_s\" $resolver && chmod 755 $resolver && break; " +
            "done; fi; " +
            "if [ -f $helper ]; then sh $helper b64 --user $user -- $b64; " +
            "else echo '[RootShell] missing $helper — reinstall chroot or open a chroot session once' >&2; exit 127; fi"
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Internal helpers
    // ─────────────────────────────────────────────────────────────────────────

    private fun buildSuArgs(invocation: List<String>, cmd: String): List<String> {
        if (invocation.size >= 3 && invocation[2].startsWith("SU_WRAP:")) {
            val suBin = invocation[2].removePrefix("SU_WRAP:")
            val escaped = cmd.replace("'", "'\\''")
            return listOf(invocation[0], invocation[1], "$suBin -c '$escaped'")
        }
        return invocation + cmd
    }

    private fun trySuProbe(invocation: List<String>): Boolean {
        return try {
            val args = buildSuArgs(invocation, "id")
            Log.d(TAG, "trySuProbe: $args")
            val pb = ProcessBuilder(args).redirectErrorStream(true).start()
            try { pb.outputStream.close() } catch (_: Exception) {}
            // Read first (small output) — avoids pipe deadlock; process exits on deny quickly.
            val outFuture = executor.submit<String> {
                pb.inputStream.bufferedReader().use { it.readText() }
            }
            // Hard timeout: a hanging su prompt must not block app/test threads.
            val finished = pb.waitFor(2, TimeUnit.SECONDS)
            if (!finished) {
                pb.destroyForcibly()
                Log.w(TAG, "trySuProbe timeout: $args")
                return false
            }
            val out = try {
                outFuture.get(1, TimeUnit.SECONDS)
            } catch (_: Exception) {
                ""
            }
            val code = pb.exitValue()
            Log.d(TAG, "trySuProbe exit=$code out=${out.trim().take(120)}")
            code == 0 && out.contains("uid=0")
        } catch (e: Exception) {
            Log.w(TAG, "trySuProbe fail: ${e.message}")
            false
        }
    }

    /** Streams stdout+stderr from a ProcessBuilder command, returns exit code. */
    private fun runCommand(
        args: List<String>,
        onLine: (String) -> Unit
    ): Int {
        Log.d(TAG, "runCommand: ${args.joinToString(" ")}")
        return try {
            val pb = ProcessBuilder(args)
                .redirectErrorStream(true)
                .start()
            try { pb.outputStream.close() } catch (_: Exception) {}

            val reader = BufferedReader(InputStreamReader(pb.inputStream))
            var line: String?
            while (reader.readLine().also { line = it } != null) {
                val l = line ?: continue
                Log.d(TAG, l)
                mainHandler.post { onLine(l) }
            }
            reader.close()
            val code = pb.waitFor()
            Log.d(TAG, "runCommand exit=$code")
            code
        } catch (e: Exception) {
            Log.e(TAG, "runCommand exception: ${e.message}")
            mainHandler.post { onLine("[RootShell] Exception: ${e.message}") }
            -1
        }
    }

    /**
     * Copy an asset to [filesDir]/staged_scripts/, make executable, return absolute path or null.
     * Background thread only when followed by root I/O.
     */
    fun stageAsset(context: Context, assetName: String): String? {
        return try {
            val dir = File(context.filesDir, "staged_scripts")
            dir.mkdirs()
            dir.setExecutable(true, false)
            dir.setReadable(true, false)

            val scriptFile = File(dir, File(assetName).name)
            context.assets.open(assetName).use { input ->
                FileOutputStream(scriptFile).use { output -> input.copyTo(output) }
            }
            scriptFile.setExecutable(true, false)
            scriptFile.setReadable(true, false)
            Log.d(TAG, "Staged asset $assetName → ${scriptFile.absolutePath}")
            scriptFile.absolutePath
        } catch (e: Exception) {
            Log.e(TAG, "stageAsset failed for $assetName: ${e.message}")
            null
        }
    }
}
