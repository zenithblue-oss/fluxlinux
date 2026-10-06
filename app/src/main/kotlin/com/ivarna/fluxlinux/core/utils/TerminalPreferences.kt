package com.ivarna.fluxlinux.core.utils

import android.content.Context
import com.ivarna.fluxlinux.core.terminal.GuestLoginShell

/**
 * Global terminal UI prefs (font zoom + ExtraKeys toolbar + guest login shell).
 * Mirrors nativecode-ai `pref_show_extra_keys` / font size behaviour.
 */
object TerminalPreferences {

    private const val PREFS_NAME = "flux_terminal_prefs"
    private const val KEY_FONT_SIZE = "font_size"
    private const val KEY_SHOW_EXTRA_KEYS = "show_extra_keys"
    private const val KEY_GUEST_LOGIN_SHELL = "guest_login_shell"
    private const val KEY_LEGACY_PROOT_LAUNCHER = "legacy_proot_launcher"

    const val FONT_MIN = 10
    const val FONT_MAX = 48
    const val FONT_DEFAULT = 24

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun getFontSize(context: Context): Int =
        prefs(context).getInt(KEY_FONT_SIZE, FONT_DEFAULT).coerceIn(FONT_MIN, FONT_MAX)

    fun setFontSize(context: Context, size: Int) {
        prefs(context).edit()
            .putInt(KEY_FONT_SIZE, size.coerceIn(FONT_MIN, FONT_MAX))
            .apply()
    }

    fun isExtraKeysEnabled(context: Context): Boolean =
        prefs(context).getBoolean(KEY_SHOW_EXTRA_KEYS, true)

    fun setExtraKeysEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_SHOW_EXTRA_KEYS, enabled).apply()
    }

    fun getGuestLoginShell(context: Context): GuestLoginShell =
        GuestLoginShell.fromId(
            prefs(context).getString(KEY_GUEST_LOGIN_SHELL, null)
        )

    fun setGuestLoginShell(context: Context, shell: GuestLoginShell) {
        prefs(context).edit()
            .putString(KEY_GUEST_LOGIN_SHELL, shell.id)
            .apply()
    }

    /** true → open proot terminals via python `proot-distro login` (old path). */
    fun isLegacyProotLauncher(context: Context): Boolean =
        prefs(context).getBoolean(KEY_LEGACY_PROOT_LAUNCHER, false)

    fun setLegacyProotLauncher(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_LEGACY_PROOT_LAUNCHER, enabled).apply()
    }

    fun preferZsh(context: Context): Boolean =
        getGuestLoginShell(context) == GuestLoginShell.ZSH
}
