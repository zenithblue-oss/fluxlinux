package com.ivarna.fluxlinux.core.desktop

import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.Toast
import com.ivarna.fluxlinux.core.data.terminalComponentFor
import com.ivarna.fluxlinux.core.service.DesktopSessionService
import com.ivarna.fluxlinux.core.system.PhantomProcessFixer
import com.ivarna.fluxlinux.core.root.RootShell
import com.ivarna.fluxlinux.core.terminal.HostScriptDeployer
import com.ivarna.fluxlinux.core.terminal.ShellCommandRunner
import com.ivarna.fluxlinux.core.terminal.ShellJob
import com.ivarna.fluxlinux.core.terminal.TerminalLauncher
import com.ivarna.fluxlinux.core.terminal.TermuxHostPaths
import com.ivarna.fluxlinux.core.utils.StateManager
import com.ivarna.fluxlinux.core.utils.TermuxX11Preferences
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * Start/stop XFCE via embedded host scripts + in-process Termux:X11
 * (termux-lib / nativecode-ai parity — no external Termux / Termux:X11).
 *
 * Long-lived start uses [ShellCommandRunner.runStreamedCancelable] so other
 * install/script runners are never blocked. X11 opens only after a readiness
 * marker (e.g. `X server PID=`), not decorative banners. Desktop stdout is
 * captured to [GuiDesktopLog] for VIEW LOGS.
 */
object DesktopLauncher {

    private const val TAG = "DesktopLauncher"
    private val main = Handler(Looper.getMainLooper())

    enum class Phase { Idle, Starting, Running }

    data class UiState(
        val phase: Phase = Phase.Idle,
        val distroId: String? = null,
        /** Rolling in-memory transcript for live UI (mirrors file). */
        val logText: String = "",
        val logsAvailable: Boolean = false,
        /** True after first healthy start line — Open X11 is meaningful. */
        val displayReady: Boolean = false,
        /** One-shot signal for UI to auto-open log sheet when start begins / fails. */
        val autoShowLogsTick: Int = 0,
        val lastError: String? = null
    )

    private val _ui = MutableStateFlow(UiState())
    val uiState: StateFlow<UiState> = _ui.asStateFlow()

    @Volatile private var guiShellJob: ShellJob? = null
    @Volatile private var guiUserStopping: Boolean = false
    @Volatile private var guiX11Launched: Boolean = false
    @Volatile private var startInFlight: Boolean = false
    private val healthyLineSeen = AtomicBoolean(false)
    /** Ensures [onResult] is invoked at most once per start. */
    private val startResultDelivered = AtomicBoolean(false)

    enum class StartDecision {
        ALLOW,
        REOPEN_SAME,
        REFUSE_DIFFERENT
    }

    fun evaluateStartAttempt(
        existingSession: DesktopSession?,
        isSessionActive: Boolean,
        activeDistroId: String?,
        requestedDistroId: String
    ): StartDecision {
        if (existingSession != null) {
            return if (existingSession.distroId == requestedDistroId) {
                StartDecision.REOPEN_SAME
            } else {
                StartDecision.REFUSE_DIFFERENT
            }
        }
        if (isSessionActive) {
            return if (activeDistroId == requestedDistroId) {
                StartDecision.REOPEN_SAME
            } else {
                StartDecision.REFUSE_DIFFERENT
            }
        }
        return StartDecision.ALLOW
    }

    fun isSessionActive(): Boolean = _ui.value.phase != Phase.Idle || startInFlight

    /**
     * @param onResult main-thread; true when desktop readiness marker seen
     *   (once); false only on early fail before readiness.
     */
    fun start(ctx: Context, distroId: String, onResult: ((Boolean) -> Unit)? = null) {
        val app = ctx.applicationContext
        PhantomProcessFixer.maybePrompt(app)

        val existing = DesktopSessionQuery.current(app, _ui.value)
        if (existing != null && existing.distroId != distroId) {
            toast(app, "Stop ${existing.distroName} ${existing.type} first")
            onResult?.invoke(false)
            return
        }
        if (existing != null && existing.distroId == distroId) {
            toast(app, "Desktop already running")
            reopenDisplay(ctx)
            onResult?.invoke(true)
            return
        }

        // Claim slot before async prepareHost
        startInFlight = true
        _ui.update {
            it.copy(
                phase = Phase.Starting,
                distroId = distroId
            )
        }

        TerminalLauncher.prepareHost(app) { ok ->
            if (!ok) {
                startInFlight = false
                toast(app, "Host not ready — open Settings to initialize")
                finishStart(app, distroId, false, "Host not ready", onResult)
                return@prepareHost
            }
            // Redeploy host scripts so start_gui.sh matches this APK (termux-lib deployScripts).
            HostScriptDeployer.deployScripts(app)

            val method = methodFor(distroId)
            val profile = com.ivarna.fluxlinux.core.install.DistroInstallProfile.forId(distroId)
            // Chroot FS probe may need root (SELinux hides /data/local/tmp from app uid).
            // Never call isChrootInstalled on the main thread without a warm cache.
            if (method == "chroot") {
                val path = profile?.chrootPath
                    ?: com.ivarna.fluxlinux.core.root.ChrootPaths.CHROOT_PATH
                Thread {
                    RootShell.ensureBusyBoxResolver(app)
                    RootShell.resolveBusyBox()
                    val installed = TerminalLauncher.isChrootInstalled(path)
                    main.post {
                        if (!installed) {
                            startInFlight = false
                            toast(app, "Chroot not installed. Complete install first.")
                            finishStart(app, distroId, false, "Chroot not installed", onResult)
                        } else {
                            continueStart(app, distroId, method, profile, onResult)
                        }
                    }
                }.start()
                return@prepareHost
            } else {
                val prootName = profile?.prootName ?: "debian"
                if (!TerminalLauncher.isProotInstalled(app, prootName)) {
                    startInFlight = false
                    toast(app, "Distro not installed. Complete onboarding first.")
                    finishStart(app, distroId, false, "Distro not installed", onResult)
                    return@prepareHost
                }
            }

            continueStart(app, distroId, method, profile, onResult)
        }
    }

    private fun continueStart(
        app: Context,
        distroId: String,
        method: String,
        profile: com.ivarna.fluxlinux.core.install.DistroInstallProfile?,
        onResult: ((Boolean) -> Unit)?
    ) {
        val currentSession = DesktopSessionQuery.current(app, _ui.value)
        if (currentSession != null && currentSession.distroId != distroId) {
            startInFlight = false
            toast(app, "Stop ${currentSession.distroName} ${currentSession.type} first")
            onResult?.invoke(false)
            return
        }

        val scriptName = if (method == "chroot") "start_gui_chroot.sh" else "start_gui.sh"
        val script = File(TermuxHostPaths.HOME, scriptName)
        if (!script.isFile) {
            startInFlight = false
            GuiDesktopLog.header(app, "START", scriptName, method)
            GuiDesktopLog.append(app, "ERROR: missing host script ${script.absolutePath}")
            pushLog(app, forceShow = true)
            toast(app, "Desktop scripts missing — re-run host initialize")
            finishStart(app, distroId, false, "Missing $scriptName", onResult)
            return
        }
        if (method == "chroot") {
            val guestName = profile?.chrootStartGuiScript ?: "start_debian13_gui.sh"
            val guest = File(TermuxHostPaths.HOME, guestName)
            if (!guest.isFile) {
                startInFlight = false
                toast(app, "Chroot desktop launcher missing — re-run host initialize")
                finishStart(app, distroId, false, "Missing $guestName", onResult)
                return
            }
        }

        guiShellJob?.cancel()
        guiUserStopping = false
        guiX11Launched = false
        healthyLineSeen.set(false)
        startResultDelivered.set(false)

        GuiDesktopLog.clear(app)
        GuiDesktopLog.header(app, "START", scriptName, method)
        val supportLib = File(TermuxHostPaths.LIB, "libandroid-support.so")
        if (!supportLib.isFile) {
            GuiDesktopLog.append(
                app,
                "WARN: missing ${supportLib.absolutePath} — libbash may fail to link"
            )
        }

        _ui.update {
            it.copy(
                phase = Phase.Starting,
                distroId = distroId,
                logText = GuiDesktopLog.read(app),
                logsAvailable = true,
                displayReady = false,
                lastError = null,
                autoShowLogsTick = it.autoShowLogsTick + 1
            )
        }

        startFgs(app)
        toast(app, "Starting desktop…")
        Log.i(TAG, "startGui method=$method script=${script.absolutePath}")

        val bash = TermuxHostPaths.libBash(app).absolutePath
        // proot: container name; chroot: distro id for path resolution in start_gui_chroot
        val scriptArg = when {
            method == "chroot" -> distroId
            else -> profile?.prootName ?: "debian"
        }
        val args = arrayOf(bash, script.absolutePath, scriptArg)
        val envMap = HashMap<String, String>()
        RootShell.cachedBusyBox()?.let { envMap["FLUX_BB"] = it }

        guiShellJob = ShellCommandRunner.runStreamedCancelable(
            app,
            args,
            envMap = envMap.takeIf { it.isNotEmpty() },
            onLine = { line ->
                GuiDesktopLog.append(app, line)
                appendLive(line)
                if (isDesktopReadyLine(line)) {
                    onHealthyLine(app, distroId, onResult)
                }
            },
            onDone = { code ->
                GuiDesktopLog.append(app, "[exit $code]")
                appendLive("[exit $code]")
                when {
                    code == -1 || guiUserStopping -> {
                        // User stop owns flip to idle
                    }
                    code != 0 -> {
                        Log.w(TAG, "Desktop start failed exit=$code")
                        if (code == 137) {
                            toast(app, "Desktop killed by Android (exit 137). Disable the phantom process killer: Settings > Process killer fix")
                        } else {
                            toast(app, "Desktop start failed (exit $code) — see logs")
                        }
                        revertToIdle(app, distroId, "exit $code", showLogs = true)
                        // Only report failure if readiness never delivered success
                        deliverStartResult(onResult, false)
                    }
                    else -> {
                        // Clean desktop exit (session ended)
                        Log.i(TAG, "Desktop session ended cleanly")
                        revertToIdle(app, distroId, null, showLogs = false)
                    }
                }
            }
        )
    }

    fun stop(ctx: Context, distroId: String, onDone: (() -> Unit)? = null) {
        val app = ctx.applicationContext
        val method = methodFor(distroId)
        guiUserStopping = true
        startInFlight = false

        try {
            val stopBroadcast = Intent("com.termux.x11.ACTION_STOP")
            stopBroadcast.setPackage(app.packageName)
            app.sendBroadcast(stopBroadcast)
        } catch (e: Exception) {
            Log.w(TAG, "ACTION_STOP failed", e)
        }
        stopFgs(app)
        guiShellJob?.cancel()

        StateManager.setGuiRunning(app, distroId, false)
        StateManager.setGuiRunningType(app, distroId, "")

        val scriptName = if (method == "chroot") "stop_gui_chroot.sh" else "stop_gui.sh"
        HostScriptDeployer.deployScripts(app)
        val script = File(TermuxHostPaths.HOME, scriptName)
        Log.i(TAG, "stopGui method=$method script=${script.absolutePath}")
        GuiDesktopLog.header(app, "STOP", scriptName, method)
        appendLive("=== STOP method=$method script=$scriptName ===")

        if (!script.isFile) {
            GuiDesktopLog.append(app, "WARN: stop script missing ${script.absolutePath}")
            appendLive("WARN: stop script missing")
            _ui.update {
                it.copy(
                    phase = Phase.Idle,
                    displayReady = false,
                    logsAvailable = GuiDesktopLog.hasContent(app),
                    logText = GuiDesktopLog.read(app)
                )
            }
            toast(app, "Stopping desktop…")
            main.post { onDone?.invoke() }
            return
        }

        val bash = TermuxHostPaths.libBash(app).absolutePath
        val stopArg = when {
            method == "chroot" -> distroId
            else -> com.ivarna.fluxlinux.core.install.DistroInstallProfile
                .forId(distroId)?.prootName ?: "debian"
        }
        toast(app, "Stopping desktop…")
        val launchStop = {
            val envMap = HashMap<String, String>()
            RootShell.cachedBusyBox()?.let { envMap["FLUX_BB"] = it }
            guiShellJob = ShellCommandRunner.runStreamedCancelable(
                app,
                arrayOf(bash, script.absolutePath, stopArg),
                envMap = envMap.takeIf { it.isNotEmpty() },
                onLine = { line ->
                    GuiDesktopLog.append(app, line)
                    appendLive(line)
                },
                onDone = { code ->
                    GuiDesktopLog.append(app, "[exit $code]")
                    appendLive("[exit $code]")
                    _ui.update {
                        it.copy(
                            phase = Phase.Idle,
                            displayReady = false,
                            logsAvailable = GuiDesktopLog.hasContent(app),
                            logText = GuiDesktopLog.read(app)
                        )
                    }
                    onDone?.invoke()
                }
            )
        }
        if (method == "chroot") {
            Thread {
                RootShell.ensureBusyBoxResolver(app)
                RootShell.resolveBusyBox()
                main.post { launchStop() }
            }.start()
        } else {
            launchStop()
        }
        // Immediate idle for Open X11 button (stop stream may still print)
        _ui.update {
            it.copy(
                phase = Phase.Idle,
                displayReady = false,
                logsAvailable = true
            )
        }
    }

    /** Re-open the X11 surface without restarting the DE. */
    fun reopenDisplay(ctx: Context) {
        // Prefer Activity context so NEW_TASK is not required (BACK returns to Home).
        openX11(ctx)
    }

    fun readLog(ctx: Context): String = GuiDesktopLog.read(ctx.applicationContext)

    // ── private ────────────────────────────────────────────────────────────

    /**
     * True only for host/script readiness markers — not banner / early progress lines.
     * Matches `start_gui.sh` / `start_gui_chroot.sh` / chroot guest stage.
     */
    internal fun isDesktopReadyLine(line: String): Boolean {
        val t = line.trim()
        if (t.isEmpty()) return false
        return t.contains("X server PID=") ||
            t.contains("host X0 socket ready") ||
            t.contains("FLUX_DESKTOP_READY") ||
            t.contains("FLUXLINUX_DESKTOP_READY")
    }

    private fun deliverStartResult(onResult: ((Boolean) -> Unit)?, ok: Boolean) {
        if (startResultDelivered.compareAndSet(false, true)) {
            onResult?.invoke(ok)
        }
    }

    private fun onHealthyLine(app: Context, distroId: String, onResult: ((Boolean) -> Unit)?) {
        if (guiUserStopping) return
        // Only first readiness marker flips RUNNING / prefs / X11.
        val first = healthyLineSeen.compareAndSet(false, true)
        if (!first) return

        StateManager.setGuiRunning(app, distroId, true)
        StateManager.setGuiRunningType(app, distroId, "xfce4")
        runCatching { TermuxX11Preferences.applyToTermux(app) }

        _ui.update {
            it.copy(
                phase = Phase.Running,
                distroId = distroId,
                displayReady = true,
                logsAvailable = true
            )
        }

        deliverStartResult(onResult, true)
        if (!guiX11Launched) {
            guiX11Launched = true
            // Short delay so X server process can bind socket before surface open
            main.postDelayed({
                if (guiUserStopping) return@postDelayed
                if (_ui.value.phase == Phase.Idle) return@postDelayed
                openX11(app)
            }, 400)
        }
    }

    private fun appendLive(line: String) {
        _ui.update { st ->
            val next = (st.logText + line + "\n").takeLast(24_000)
            st.copy(logText = next, logsAvailable = true)
        }
    }

    private fun pushLog(app: Context, forceShow: Boolean) {
        _ui.update {
            it.copy(
                logText = GuiDesktopLog.read(app),
                logsAvailable = GuiDesktopLog.hasContent(app),
                autoShowLogsTick = if (forceShow) it.autoShowLogsTick + 1 else it.autoShowLogsTick
            )
        }
    }

    private fun finishStart(
        app: Context,
        distroId: String,
        ok: Boolean,
        error: String?,
        onResult: ((Boolean) -> Unit)?
    ) {
        if (!ok) {
            _ui.update {
                it.copy(
                    phase = Phase.Idle,
                    distroId = distroId,
                    lastError = error,
                    displayReady = false
                )
            }
        }
        deliverStartResult(onResult, ok)
    }

    private fun revertToIdle(
        app: Context,
        distroId: String,
        error: String?,
        showLogs: Boolean
    ) {
        startInFlight = false
        stopFgs(app)
        StateManager.setGuiRunning(app, distroId, false)
        StateManager.setGuiRunningType(app, distroId, "")
        guiX11Launched = false
        healthyLineSeen.set(false)
        // Keep startResultDelivered as-is so a late exit cannot re-fire onResult
        _ui.update {
            it.copy(
                phase = Phase.Idle,
                distroId = distroId,
                lastError = error,
                displayReady = false,
                logsAvailable = GuiDesktopLog.hasContent(app),
                logText = GuiDesktopLog.read(app),
                autoShowLogsTick = if (showLogs) it.autoShowLogsTick + 1 else it.autoShowLogsTick
            )
        }
    }

    private fun methodFor(distroId: String): String = try {
        terminalComponentFor(distroId).method
    } catch (_: Exception) {
        "proot"
    }

    private fun startFgs(app: Context): Boolean {
        return try {
            val fgs = Intent(app, DesktopSessionService::class.java)
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                app.startForegroundService(fgs)
            } else {
                app.startService(fgs)
            }
            true
        } catch (e: Exception) {
            Log.e(TAG, "Desktop FGS start failed", e)
            toast(app, "Desktop keep-alive service failed — display may sleep")
            false
        }
    }

    private fun stopFgs(app: Context) {
        try {
            app.stopService(Intent(app, DesktopSessionService::class.java))
        } catch (_: Exception) {
        }
    }

    private fun openX11(ctx: Context) {
        try {
            val x11 = Intent(ctx, com.termux.x11.MainActivity::class.java)
            // Match nativecode when started from an Activity (no NEW_TASK).
            // Application context (auto-open after healthy line) still needs NEW_TASK.
            if (ctx is android.app.Activity) {
                x11.addFlags(
                    Intent.FLAG_ACTIVITY_SINGLE_TOP or
                        Intent.FLAG_ACTIVITY_CLEAR_TOP
                )
            } else {
                x11.addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK or
                        Intent.FLAG_ACTIVITY_SINGLE_TOP or
                        Intent.FLAG_ACTIVITY_CLEAR_TOP
                )
            }
            ctx.startActivity(x11)
        } catch (e: Exception) {
            Log.e(TAG, "Open X11 activity failed", e)
            toast(ctx.applicationContext, "Failed to open display: ${e.message}")
        }
    }

    private fun toast(app: Context, msg: String) {
        main.post {
            Toast.makeText(app, msg, Toast.LENGTH_LONG).show()
        }
    }
}
