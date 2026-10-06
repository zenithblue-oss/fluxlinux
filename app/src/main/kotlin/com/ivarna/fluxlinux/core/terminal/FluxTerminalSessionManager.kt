package com.ivarna.fluxlinux.core.terminal

import android.content.Context
import com.ivarna.fluxlinux.core.data.Distro
import kotlinx.coroutines.flow.StateFlow
import com.termux.view.TerminalView

/**
 * Thin facade over [SessionRegistry] + [InstallSessionFactory] +
 * [GuestSessionFactory] + [UninstallSessionFactory] (Pass 2 split, plan §2.5).
 *
 * Call sites (TerminalScreen / MainActivity / HomeScreen) keep using this object;
 * product behavior is unchanged. All session-open paths require an explicit
 * `method` for card actions — see each factory.
 */
object FluxTerminalSessionManager {

    const val MAX_TABS = SessionRegistry.MAX_TABS

    /** Guest opens between the tab-limit check and SessionRegistry.add (main thread only). */
    private var pendingOpens = 0

    /**
     * Outcome of a session-open request — lets UI show distinct toasts
     * (R3: tab limit vs host prepare vs open failure).
     */
    enum class SessionOpenResult {
        /** Session opened and attached to a tab. */
        OPENED,
        /** Tab limit reached (MAX_TABS). */
        MAX_TABS,
        /** Host bootstrap extract / setup_termux failed (guest paths). */
        HOST_PREPARE_FAILED,
        /** Embedded host not ready and prepare failed (host shell). */
        HOST_NOT_READY,
        /** Unexpected registry add failure. */
        OPEN_FAILED
    }

    val activeIndex: StateFlow<Int> get() = SessionRegistry.activeIndex
    val revision: StateFlow<Int> get() = SessionRegistry.revision
    val sessionCount: Int get() = SessionRegistry.sessionCount
    val activeSession: SessionRegistry.ManagedSession? get() = SessionRegistry.activeSession

    fun isOpen(): Boolean = SessionRegistry.isOpen()
    fun titles(): List<String> = SessionRegistry.titles()
    fun sessions(): List<SessionRegistry.ManagedSession> = SessionRegistry.sessions()

    fun switchSession(index: Int) = SessionRegistry.switchSession(index)
    fun closeSession(ctx: Context, index: Int) = SessionRegistry.closeSession(ctx, index)
    fun closeAll(ctx: Context) = SessionRegistry.closeAll(ctx)
    fun attachView(view: TerminalView) = SessionRegistry.attachView(view)
    fun detachView() = SessionRegistry.detachView()

    /**
     * Interactive guest session after ensuring the host is prepared. Use from UI threads.
     * [method] MUST come from `terminalComponentFor(distroId).method` for card actions (plan §2.6).
     * [shellCmd] default `"exec zsh"` is the interactive sentinel — the guest binary
     * comes from `TerminalPreferences` (GuestSessionFactory injection point).
     * Reports a distinct [SessionOpenResult] so the UI can toast the right failure (R3).
     */
    fun openSessionAfterHost(
        ctx: Context,
        type: String,
        title: String = type,
        shellCmd: String = "exec zsh",
        method: String,
        distroId: String? = null,
        onResult: (SessionOpenResult) -> Unit = {}
    ) {
        com.ivarna.fluxlinux.core.system.PhantomProcessFixer.maybePrompt(ctx)
        // Count in-flight opens too, so rapid taps cannot pass the check before
        // any add lands. SessionRegistry.add still rejects as a backstop.
        // pendingOpens is touched on the main thread only (here + mainHandler posts).
        if (SessionRegistry.sessionCount + pendingOpens >= MAX_TABS) {
            onResult(SessionOpenResult.MAX_TABS)
            return
        }
        pendingOpens++
        // Host prep + argv/env build (su probe for chroot) off main; only the
        // TerminalSession construction + registry add run on main (needs Looper).
        TerminalLauncher.executor.execute {
            // Chroot never uses the host $PREFIX — skip host prep (proot keeps it).
            val opener = runCatching {
                if (method != "chroot" && !TerminalLauncher.prepareHostBlocking(ctx)) {
                    TerminalLauncher.mainHandler.post {
                        pendingOpens--
                        onResult(SessionOpenResult.HOST_PREPARE_FAILED)
                    }
                    return@execute
                }
                GuestSessionFactory.prepareSession(ctx, type, title, shellCmd, method, distroId)
            }.getOrNull()
            TerminalLauncher.mainHandler.post {
                val opened = try {
                    opener?.invoke() == true
                } finally {
                    pendingOpens--
                }
                onResult(if (opened) SessionOpenResult.OPENED else SessionOpenResult.OPEN_FAILED)
                // Guest audio is a TCP client of host Pulse; start it after the shell is up.
                if (opened) {
                    val app = ctx.applicationContext
                    Thread { PulseHost.ensureStarted(app) }.start()
                }
            }
        }
    }

    /**
     * Interactive guest session (host already prepared).
     * [shellCmd] default `"exec zsh"` = interactive sentinel; guest binary comes
     * from `TerminalPreferences` (zsh default, bash opt-in).
     */
    fun openSession(
        ctx: Context,
        type: String,
        title: String = type,
        shellCmd: String = "exec zsh",
        method: String
    ): Boolean = GuestSessionFactory.openSession(ctx, type, title, shellCmd, method)

    /**
     * Interactive host (embedded Termux prefix) shell — HOST selector card.
     * Synchronous fast path; callers that need the R4 host-ready gate should use
     * [openHostShellAfterReady] instead.
     */
    fun openHostShell(ctx: Context, title: String = "Host Shell"): Boolean =
        GuestSessionFactory.openHostShell(ctx, title)

    /**
     * Interactive host shell after ensuring the embedded host is ready (async).
     * Probes libbash + bootstrap extraction; prepares the host when missing (R4).
     */
    fun openHostShellAfterReady(
        ctx: Context,
        title: String = "Host Shell",
        onResult: (SessionOpenResult) -> Unit = {}
    ) {
        if (!SessionRegistry.hasFreeTab()) {
            onResult(SessionOpenResult.MAX_TABS)
            return
        }
        if (GuestSessionFactory.hostShellReady(ctx)) {
            onResult(
                if (GuestSessionFactory.openHostShell(ctx, title)) {
                    SessionOpenResult.OPENED
                } else {
                    SessionOpenResult.OPEN_FAILED
                }
            )
            return
        }
        TerminalLauncher.prepareHost(ctx) { ok ->
            if (!ok) {
                onResult(SessionOpenResult.HOST_NOT_READY)
                return@prepareHost
            }
            onResult(
                if (GuestSessionFactory.openHostShell(ctx, title)) {
                    SessionOpenResult.OPENED
                } else {
                    SessionOpenResult.OPEN_FAILED
                }
            )
        }
    }

    /** Host script session (e.g. `flux_install.sh debian`). */
    fun openHostScriptSession(
        ctx: Context,
        scriptName: String,
        title: String = scriptName,
        args: Array<String> = emptyArray(),
        forceHostSetup: Boolean = false,
        onFinished: (() -> Unit)? = null
    ): Boolean =
        InstallSessionFactory.openHostScriptSession(
            ctx, scriptName, title, args, forceHostSetup, onFinished
        )

    /** Host command session (e.g. `proot-distro remove debian`). */
    fun openHostCommandSession(
        ctx: Context,
        command: String,
        title: String = "Host Shell"
    ): Boolean = InstallSessionFactory.openHostCommandSession(ctx, command, title)

    /** Root shell session running [scriptPath] on the host via su. */
    fun openRootScriptSession(
        ctx: Context,
        scriptPath: String,
        title: String = "Root Shell",
        onFinished: (() -> Unit)? = null
    ): Boolean = InstallSessionFactory.openRootScriptSession(ctx, scriptPath, title, onFinished)

    /** Distro install session (proot → flux_install.sh; chroot → setup script as root). */
    fun openInstallSession(
        ctx: Context,
        distro: Distro,
        setupB64: String? = null,
        onFinished: (() -> Unit)? = null
    ): Boolean = InstallSessionFactory.openInstallSession(ctx, distro, setupB64, onFinished)

    /** Distro uninstall session. */
    fun openUninstallSession(ctx: Context, distro: Distro): Boolean =
        UninstallSessionFactory.openUninstallSession(ctx, distro)

    /** Component install/uninstall session inside the guest. */
    fun openComponentSession(
        ctx: Context,
        distro: Distro,
        scriptContent: String,
        title: String,
        extraEnv: Map<String, String> = emptyMap(),
        isUninstall: Boolean = false,
        onFinished: (() -> Unit)? = null
    ): Boolean = GuestSessionFactory.openComponentSession(
        ctx, distro, scriptContent, title, extraEnv, isUninstall, onFinished
    )
}
