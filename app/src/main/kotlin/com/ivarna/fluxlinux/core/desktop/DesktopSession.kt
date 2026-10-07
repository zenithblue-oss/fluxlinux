package com.ivarna.fluxlinux.core.desktop

import android.content.Context
import android.net.LocalSocket
import android.net.LocalSocketAddress
import com.ivarna.fluxlinux.core.terminal.TermuxHostPaths
import java.io.File
import java.io.IOException
import com.ivarna.fluxlinux.core.data.DistroRepository
import com.ivarna.fluxlinux.core.utils.StateManager

data class DesktopSession(
    val distroId: String,
    val distroName: String,      // "Debian", not "debian13_chroot"
    val type: Type,              // XFCE4 | KDE
    val phase: Phase             // Starting | Running
) {
    enum class Type { XFCE4, KDE }
    enum class Phase { Starting, Running }
}

object DesktopSessionQuery {
    fun current(context: Context, ui: DesktopLauncher.UiState): DesktopSession? {
        // Live DesktopLauncher state is authoritative and takes precedence over stored preferences.
        if ((ui.phase == DesktopLauncher.Phase.Starting || ui.phase == DesktopLauncher.Phase.Running) && ui.distroId != null) {
            val name = resolveDistroName(ui.distroId)
            val phase = when (ui.phase) {
                DesktopLauncher.Phase.Starting -> DesktopSession.Phase.Starting
                DesktopLauncher.Phase.Running -> DesktopSession.Phase.Running
                else -> DesktopSession.Phase.Running
            }
            return DesktopSession(
                distroId = ui.distroId,
                distroName = name,
                type = if (ui.desktop == "kde") DesktopSession.Type.KDE else DesktopSession.Type.XFCE4,
                phase = phase
            )
        }

        val runningDistros = StateManager.getDistrosWithGuiRunning(context)

        // Check for active KDE session in preferences
        val kdeId = runningDistros.firstOrNull { StateManager.getGuiRunningType(context, it) == "kde" }
        if (kdeId != null) {
            return DesktopSession(
                distroId = kdeId,
                distroName = resolveDistroName(kdeId),
                type = DesktopSession.Type.KDE,
                phase = DesktopSession.Phase.Running
            )
        }

        // Recover stale XFCE session pref if process died so user can stop it
        val staleId = runningDistros.firstOrNull()
        if (staleId != null) {
            return DesktopSession(
                distroId = staleId,
                distroName = resolveDistroName(staleId),
                type = DesktopSession.Type.XFCE4,
                phase = DesktopSession.Phase.Running
            )
        }

        return null
    }

    private fun xSocket() = File(TermuxHostPaths.FILES, "usr/tmp/.X11-unix/X0")

    /**
     * Host X server liveness without su or pids: works for proot and chroot (both bind the
     * same host socket). Dead = socket missing or connection refused; anything else
     * (e.g. EACCES) counts as alive so a live session is never cleared by mistake.
     */
    fun xServerAlive(sock: File = xSocket()): Boolean {
        if (!sock.exists()) return false
        return try {
            LocalSocket().use {
                it.connect(LocalSocketAddress(sock.absolutePath, LocalSocketAddress.Namespace.FILESYSTEM))
            }
            true
        } catch (e: IOException) {
            e.message?.let { it.contains("refused", true) || it.contains("No such file", true) } != true
        }
    }

    /** Clear a session pref left behind after the app/X server was killed. Blocking-ish: off main. */
    fun reconcileStale(context: Context) {
        if (DesktopLauncher.isSessionActive()) return
        val ids = StateManager.getDistrosWithGuiRunning(context)
        if (ids.isEmpty() || xServerAlive()) return
        // ponytail: re-check narrows (does not close) the race with a start finishing meanwhile
        if (DesktopLauncher.isSessionActive()) return
        ids.forEach {
            StateManager.setGuiRunning(context, it, false)
            StateManager.setGuiRunningType(context, it, "")
        }
        val sock = xSocket()
        sock.delete()
        File(sock.parentFile?.parentFile, ".X0-lock").delete()
    }

    private fun resolveDistroName(id: String): String {
        return DistroRepository.supportedDistros.find { it.id == id }?.name?.removeSuffix(" (Rooted)") ?: id
    }
}
