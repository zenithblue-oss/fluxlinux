package com.ivarna.fluxlinux.core.terminal

/**
 * SSOT for the interactive guest login shell (bash / zsh) and the sentinel /
 * cascade / `--shell` helpers used by the proot and chroot command builders.
 *
 * Pure JVM — no Android imports (unit-testable with FakeContext-free tests).
 */
enum class GuestLoginShell(val id: String) {
    ZSH("zsh"),
    BASH("bash");

    /** Value for fluxlinux_chroot.sh `login --shell`. */
    val chrootFlag: String get() = id

    companion object {
        val DEFAULT: GuestLoginShell = ZSH

        /** Legacy default `shellCmd` — means "interactive login", not "force zsh". */
        const val INTERACTIVE_SENTINEL = "exec zsh"

        fun fromId(raw: String?): GuestLoginShell =
            if (raw.equals(BASH.id, ignoreCase = true)) BASH else ZSH

        /**
         * Login sentinels only — **Proot** interactive check.
         * Does **not** treat the workdir form as interactive (that would drop
         * the payload's mkdir/cd). trim() so padded sentinels still count.
         */
        fun isLoginSentinel(shellCmd: String): Boolean {
            val t = shellCmd.trim()
            return t.isEmpty() ||
                t == INTERACTIVE_SENTINEL ||
                t == "exec bash" ||
                t == "/bin/bash --login"
        }

        /**
         * Chroot interactive check: sentinels **or** workspace workdir form.
         */
        fun isInteractiveLogin(shellCmd: String): Boolean =
            isLoginSentinel(shellCmd) || parseInteractiveWorkdir(shellCmd) != null

        /**
         * Workspace form (chroot-only): `mkdir -p DIR && cd DIR && exec zsh|bash`
         * (same path, no single quotes). Null if not that form.
         */
        fun parseInteractiveWorkdir(shellCmd: String): String? {
            val t = shellCmd.trim()
            val m = Regex("""^mkdir -p (.+) && cd \1 && exec (?:zsh|bash)$""").matchEntire(t)
                ?: return null
            val dir = m.groupValues[1].trim()
            return dir.takeIf { it.isNotEmpty() && !it.contains('\'') }
        }

        /**
         * Proot in-guest cascade. Preference-first, then the other of
         * {bash, zsh}, then sh. Checks the `/bin` directory only (today's contract).
         */
        fun prootLoginCascade(preferred: GuestLoginShell): String =
            "/bin/sh -lc '${prootLoginScript(preferred)}'"

        /** Script body of [prootLoginCascade] (argv form for the direct proot launch). */
        fun prootLoginScript(preferred: GuestLoginShell): String {
            val first = preferred.id
            val second = if (preferred == ZSH) "bash" else "zsh"
            return "if [ -x /bin/$first ]; then exec /bin/$first -l; " +
                "elif [ -x /bin/$second ]; then exec /bin/$second -l; else exec /bin/sh -l; fi"
        }
    }
}
