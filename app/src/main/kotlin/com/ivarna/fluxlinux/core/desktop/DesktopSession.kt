package com.ivarna.fluxlinux.core.desktop

import android.content.Context
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

    private fun resolveDistroName(id: String): String {
        return DistroRepository.supportedDistros.find { it.id == id }?.name?.removeSuffix(" (Rooted)") ?: id
    }
}
