package com.ivarna.fluxlinux.core.terminal

import android.content.Context
import android.util.Log
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Host PulseAudio supervisor (app uid). Guests are TCP clients only.
 * [ensureStarted] is once-per-process; [start]/[restart] are explicit UI actions.
 */
object PulseHost {

    private const val TAG = "PulseHost"
    private const val LOG_NAME = "pulse_host.log"
    private const val MAX_LOG_BYTES = 256L * 1024L

    private val startedThisProcess = AtomicBoolean(false)

    data class Status(
        val running: Boolean,
        val sink: String,
        val tcpOk: Boolean,
        val raw: String
    ) {
        val healthy: Boolean
            get() = running && tcpOk && sink.isNotBlank() &&
                sink != "auto_null" && sink != "null"

        val label: String
            get() = when {
                !running -> "Stopped"
                sink.isBlank() || sink == "auto_null" || sink == "null" ->
                    "Running (dummy sink)"
                !tcpOk -> "Running (no TCP)"
                else -> "Running"
            }

        val detail: String
            get() = buildString {
                if (running) {
                    append("sink=").append(sink.ifBlank { "?" })
                    append("  tcp=")
                    append(if (tcpOk) "127.0.0.1:4713" else "down")
                } else {
                    append("Host Pulse is not running")
                }
            }
    }

    fun logFile(ctx: Context): File = File(ctx.cacheDir, LOG_NAME)

    fun readLog(ctx: Context): String = try {
        val f = logFile(ctx)
        if (f.isFile) f.readText() else ""
    } catch (_: Exception) {
        ""
    }

    fun hasLog(ctx: Context): Boolean = try {
        val f = logFile(ctx)
        f.isFile && f.length() > 0L
    } catch (_: Exception) {
        false
    }

    fun clearLog(ctx: Context) {
        runCatching { logFile(ctx).writeText("") }
    }

    fun appendLog(ctx: Context, line: String) {
        try {
            val f = logFile(ctx)
            val text = if (line.endsWith("\n")) line else "$line\n"
            if (f.exists() && f.length() + text.length > MAX_LOG_BYTES) {
                val tail = f.readText().takeLast((MAX_LOG_BYTES / 2).toInt())
                f.writeText(tail + text)
            } else {
                f.appendText(text)
            }
        } catch (_: Exception) {
        }
    }

    /** Cheap once-per-process start, fired after a guest session opens. Blocking — bg thread. */
    fun ensureStarted(ctx: Context) {
        if (!startedThisProcess.compareAndSet(false, true)) return
        val out = runSupervisor(ctx)
        if (!supervisorOk(out)) startedThisProcess.set(false)
    }

    /**
     * Supervisor printed a real success line. `[AUDIO] FAIL` exits 0 so desktop
     * still starts — treat that (and a blank capture) as retryable.
     */
    fun supervisorOk(output: String): Boolean {
        if (output.isBlank()) return false
        if (output.contains("[AUDIO] FAIL")) return false
        return output.contains("[AUDIO] already running") ||
            output.contains("[AUDIO] sink=")
    }

    /** Probe only — never starts the daemon. Call from a background thread. */
    fun query(ctx: Context): Status {
        val pactl = TermuxHostPaths.libPactl(ctx).absolutePath
        // Exec libpactl.so directly. Do not go through toybox `env` (paths contain
        // `=`) or `libbash -c` + `exec "$0"` (termux-exec then looks up `$0`).
        val extra = hashMapOf(
            "PULSE_SERVER" to "tcp:127.0.0.1",
            "PULSE_RUNTIME_PATH" to "${TermuxHostPaths.HOME}/.pulse",
            "XDG_RUNTIME_DIR" to "${TermuxHostPaths.HOME}/.pulse-runtime",
            "LD_PRELOAD" to ""
        )
        val (exit, out) = try {
            ShellCommandRunner.runCaptureExit(ctx, arrayOf(pactl, "info"), extra)
        } catch (e: Exception) {
            -1 to (e.message ?: "pactl info failed")
        }
        val statusOut = buildString {
            if (exit == 0 && out.contains("Default Sink:", ignoreCase = true)) {
                appendLine("FLUX_PULSE_RUNNING=1")
                appendLine("FLUX_PULSE_TCP=1")
                append(out.trimEnd())
            } else {
                appendLine("FLUX_PULSE_RUNNING=0")
                appendLine("FLUX_PULSE_TCP=0")
                if (out.isNotBlank()) append(out.trimEnd())
            }
        }
        appendLog(ctx, "=== STATUS exit=$exit ===")
        appendLog(ctx, statusOut.trimEnd())
        return parseStatus(statusOut)
    }

    /** Start or heal via [start_pulse_host.sh]. Call from a background thread. */
    fun start(ctx: Context): String {
        startedThisProcess.set(true)
        runCatching { HostScriptDeployer.deployScripts(ctx) }
        return runSupervisor(ctx)
    }

    /**
     * Stop then start. Only for the explicit Restart button — desktop stop
     * must not go through this.
     */
    fun restart(ctx: Context): String {
        runCatching { HostScriptDeployer.deployScripts(ctx) }
        appendLog(ctx, "=== RESTART ===")
        runHostBash(
            ctx,
            """
            unset PULSE_SERVER
            unset LD_PRELOAD
            _pid=""
            [ -r "${'$'}HOME/.pulse/pid" ] && _pid=${'$'}(tr -d ' \n' < "${'$'}HOME/.pulse/pid")
            [ -n "${'$'}_pid" ] && kill "${'$'}_pid" >/dev/null 2>&1 || true
            _p=${'$'}(pidof libpulseaudio.so 2>/dev/null || true)
            [ -n "${'$'}_p" ] && kill ${'$'}_p >/dev/null 2>&1 || true
            """.trimIndent()
        )
        try {
            Thread.sleep(350)
        } catch (_: InterruptedException) {
        }
        startedThisProcess.set(true)
        return runSupervisor(ctx)
    }

    /**
     * Stage setup_pulse_guest.sh + flux_guest_common.sh into each installed
     * PRoot (and chroot when su works) and run the client repair. Call from a
     * background thread — package install can take minutes when pactl is missing.
     */
    fun repairGuests(ctx: Context): String {
        runCatching { HostScriptDeployer.deployScripts(ctx) }
        val script = TermuxHostPaths.hostScript(ctx, "repair_pulse_guests.sh")
        if (!script.isFile) {
            val msg = "repair_pulse_guests.sh not deployed"
            appendLog(ctx, "FluxLinux: [AUDIO] FAIL $msg")
            return msg
        }
        return try {
            val (exit, out) = ShellCommandRunner.runCaptureExit(
                ctx,
                HostCommandBuilder.build(ctx, script.absolutePath).first
            )
            appendLog(ctx, "=== GUEST REPAIR exit=$exit ===")
            appendLog(ctx, out.trimEnd())
            out
        } catch (e: Exception) {
            val msg = e.message ?: "guest repair failed"
            appendLog(ctx, "FluxLinux: [AUDIO] FAIL $msg")
            msg
        }
    }

    /** Toast copy for [repairGuests] output — do not claim success on FAIL/WARN. */
    fun repairToast(output: String): String {
        val lines = output.lineSequence().map { it.trim() }.toList()
        if (lines.any { it.contains("[AUDIO] FAIL") }) return "Guest repair failed"
        val repaired = lines.count {
            it.contains("[AUDIO] repair proot ") || it.contains("[AUDIO] repair chroot ")
        }
        val warns = lines.count { it.contains("[AUDIO] WARN") }
        val guestPactl = lines.count { line ->
            line.contains("[AUDIO] guest pactl=") &&
                !line.contains("/data/data/") &&
                (line.contains("/usr/bin/pactl") ||
                    line.contains("/usr/sbin/pactl") ||
                    line.contains("/bin/pactl"))
        }
        if (repaired == 0) return "No guests to repair"
        if (guestPactl == 0) return "Guest repair had errors"
        if (warns > 0 || guestPactl < repaired) return "Guest repair partial"
        return "Guest audio repaired"
    }

    fun parseStatus(output: String): Status {
        val running = output.lineSequence().any { it.trim() == "FLUX_PULSE_RUNNING=1" }
        val tcpOk = output.lineSequence().any { it.trim() == "FLUX_PULSE_TCP=1" }
        val sink = parseDefaultSink(output)
        return Status(running = running, sink = sink, tcpOk = tcpOk, raw = output)
    }

    fun parseDefaultSink(output: String): String {
        output.lineSequence().forEach { line ->
            val t = line.trim()
            if (t.startsWith("Default Sink:", ignoreCase = true)) {
                return t.substringAfter(':').trim()
            }
        }
        return ""
    }

    private fun runSupervisor(ctx: Context): String {
        val script = TermuxHostPaths.hostScript(ctx, "start_pulse_host.sh")
        if (!script.isFile) {
            val msg = "start_pulse_host.sh not deployed"
            Log.w(TAG, msg)
            appendLog(ctx, "FluxLinux: [AUDIO] FAIL $msg")
            startedThisProcess.set(false)
            return msg
        }
        return try {
            val (exit, out) = ShellCommandRunner.runCaptureExit(
                ctx,
                HostCommandBuilder.build(ctx, script.absolutePath).first
            )
            Log.i(TAG, "supervisor exit=$exit\n$out")
            appendLog(ctx, "=== SUPERVISOR exit=$exit ===")
            appendLog(ctx, out.trimEnd())
            out
        } catch (e: Exception) {
            Log.w(TAG, "supervisor failed", e)
            appendLog(ctx, "FluxLinux: [AUDIO] FAIL ${e.message}")
            startedThisProcess.set(false)
            e.message ?: "supervisor failed"
        }
    }

    private fun runHostBash(ctx: Context, script: String): Pair<Int, String> {
        return try {
            val shell = TermuxHostPaths.libBash(ctx).absolutePath
            ShellCommandRunner.runCaptureExit(ctx, arrayOf(shell, "-c", script))
        } catch (e: Exception) {
            Log.w(TAG, "host bash failed", e)
            -1 to (e.message ?: "host bash failed")
        }
    }
}
