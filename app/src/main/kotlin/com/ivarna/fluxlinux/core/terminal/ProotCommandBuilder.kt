package com.ivarna.fluxlinux.core.terminal

import android.content.Context
import android.util.Log
import com.ivarna.fluxlinux.core.utils.TerminalPreferences
import java.io.File
import java.io.FileInputStream
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Paths
import java.nio.file.attribute.PosixFilePermission

/** Builds shell arguments and environment map for proot sessions.
 *  Ported from termux-lib `ProotCommandBuilder`. */
object ProotCommandBuilder {

    /** Guest PATH only — host `$PREFIX/bin` must not leak (nested proot glue errors). */
    const val GUEST_PATH =
        "/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin"

    /**
     * Clean guest env for `proot-distro login … -- env -i …`.
     * Drops host `TMPDIR`/`PROOT_TMP_DIR`/`PATH` so guest uid 1000 never tries
     * to write the host glue dir or exec host `proot`.
     */
    fun guestLoginEnv(user: String): String =
        "env -i " + guestEnvVars(user, "\"\${TERM:-xterm-256color}\"").joinToString(" ")

    /** `KEY=value` list behind [guestLoginEnv]; [term] is the TERM value as written. */
    fun guestEnvVars(user: String, term: String): List<String> {
        val u = if (user == "root") "root" else "flux"
        val home = if (u == "root") "/root" else "/home/flux"
        // Do not force LANG/LC_ALL here: missing en_US.UTF-8 prints
        // "cannot change locale" from /bin/sh before profile.d can pick.
        // Guest flux-locale.sh / .zshrc select a locale that exists.
        return listOf(
            "HOME=$home", "USER=$u", "LOGNAME=$u", "TERM=$term", "LANG=C",
            "TMPDIR=/tmp", "XDG_RUNTIME_DIR=/tmp", "PATH=$GUEST_PATH",
            "PULSE_SERVER=tcp:127.0.0.1"
        )
    }

    // --- Direct launch: same proot argv/env as `proot-distro login`, no python. ---
    // Mirrors proot_distro/commands/login/{__init__,proot_cmd,bindings}.py and
    // sysdata.py for a normal-type container on Android (IS_TERMUX, not
    // isolated/minimal, no emulation, no custom binds).

    // proot-distro constants.py fake kernel identity (keeps `uname -a` unchanged).
    private const val FAKE_KERNEL_RELEASE = "6.17.0-PRoot-Distro"
    private const val FAKE_KERNEL_VERSION = "#1 SMP PREEMPT_DYNAMIC Fri, 10 Oct 2025 00:00:00 +0000"

    private val FAKE_PROC = listOf(
        "/proc/loadavg" to "loadavg",
        "/proc/stat" to "stat",
        "/proc/uptime" to "uptime",
        "/proc/version" to "version",
        "/proc/vmstat" to "vmstat",
        "/proc/sys/kernel/cap_last_cap" to "sysctl_entry_cap_last_cap",
        "/proc/sys/fs/inotify/max_user_watches" to "sysctl_inotify_max_user_watches",
        "/proc/sys/kernel/overflowuid" to "sysctl_kernel_overflowuid",
        "/proc/sys/kernel/overflowgid" to "sysctl_kernel_overflowgid",
    )

    private val SYSTEM_PATHS = listOf(
        "/apex", "/odm", "/product", "/system", "/system_ext", "/vendor",
        "/linkerconfig/ld.config.txt",
        "/linkerconfig/com.android.art/ld.config.txt",
        "/plat_property_contexts", "/property_contexts",
    )

    private val ANDROID_HOST_ENV_VARS = listOf(
        "ANDROID_ART_ROOT", "ANDROID_DATA", "ANDROID_I18N_ROOT",
        "ANDROID_ROOT", "ANDROID_RUNTIME_ROOT", "ANDROID_TZDATA_ROOT",
        "BOOTCLASSPATH", "DEX2OATBOOTCLASSPATH", "EXTERNAL_STORAGE",
    )

    private fun lexists(p: String) = Files.exists(Paths.get(p), LinkOption.NOFOLLOW_LINKS)

    private fun readable(p: String) =
        runCatching { FileInputStream(p).use { it.read() }; true }.getOrDefault(false)

    /** Dir with the world-execute bit (python: mode[-1] in 1/5/7). */
    private fun traversable(p: String) = runCatching {
        File(p).isDirectory &&
            PosixFilePermission.OTHERS_EXECUTE in Files.getPosixFilePermissions(Paths.get(p))
    }.getOrDefault(false)

    internal fun storageBinds(canRead: (String) -> Boolean = { File(it).canRead() }): List<String> {
        if (canRead("/storage")) {
            return if (canRead("/storage/emulated/0")) listOf(
                "--bind=/storage",
                "--bind=/storage/emulated/0:/sdcard",
                "--bind=/storage/emulated/0:/mnt/sdcard"
            ) else listOf("--bind=/storage")
        }
        val p = listOf("/storage/self/primary", "/storage/emulated/0", "/sdcard")
            .firstOrNull { canRead(it) } ?: return emptyList()
        return listOf("/mnt/sdcard", "/sdcard", "/storage/emulated/0", "/storage/self/primary")
            .map { "--bind=$p:$it" }
    }

    /**
     * proot argv + proot process env equal to
     * `proot-distro login <distro> [--shared-tmp] --user <user> -- <guestCmd…>`.
     * Returns null when the layout is not recognised (no rootfs / sysdata,
     * termux-type container, user missing from passwd) → caller uses python.
     *
     * The guest [guestCmd] runs directly instead of `<passwd shell> -c '<guestCmd>'`:
     * it starts with `env -i`, so the wrapper shell added nothing.
     */
    fun buildDirect(
        proot: String,
        prefix: String,
        termuxHome: String,
        pkg: String,
        distro: String,
        user: String,
        guestCmd: List<String>,
        hostEnv: Map<String, String>,
        useSharedTmp: Boolean = true,
        bindKgsl: Boolean = false
    ): Pair<Array<String>, HashMap<String, String>>? {
        val rootfs = File("$prefix/var/lib/proot-distro/containers/$distro/rootfs")
        val sysdata = File(rootfs.parentFile, "sysdata")
        if (!rootfs.isDirectory || !File(sysdata, "sys_empty").isDirectory) return null
        if (File("${rootfs.path}$prefix/bin/login").isFile) return null // termux-type
        val pw = runCatching { File(rootfs, "etc/passwd").readLines() }.getOrNull()
            ?.map { it.split(":") }
            ?.firstOrNull { it.size >= 7 && it[0] == user && it[2].isNotEmpty() }
            ?: return null
        val r = rootfs.path
        val home = pw[5].ifEmpty { "/" }
        val machine = System.getProperty("os.arch") ?: "aarch64"

        val a = mutableListOf(
            proot,
            "--kill-on-exit",
            "--link2symlink",
            "--sysvipc",
            "--kernel-release=\\Linux\\localhost\\$FAKE_KERNEL_RELEASE\\$FAKE_KERNEL_VERSION" +
                "\\$machine\\localdomain\\-1\\",
            "-L",
            "--change-id=${pw[2]}:${pw[3].ifEmpty { pw[2] }}",
            "--rootfs=$r",
            "--cwd=$home",
            "--bind=/dev", "--bind=/proc", "--bind=/sys",
            "--bind=/dev/urandom:/dev/random"
        )
        if (!lexists("/dev/fd")) a += "--bind=/proc/self/fd:/dev/fd"
        listOf("stdin", "stdout", "stderr").forEachIndexed { i, n ->
            if (!lexists("/dev/$n") && File("/proc/self/fd/$i").exists()) {
                a += "--bind=/proc/self/fd/$i:/dev/$n"
            }
        }
        a += "--bind=${sysdata.path}/sys_empty:/sys/fs/selinux"
        // Fake /proc only when the real entry is unreadable and the fake exists.
        for ((real, fake) in FAKE_PROC) {
            val f = File(sysdata, fake)
            if (f.isFile && !readable(real)) a += "--bind=${f.path}:$real"
        }
        File(rootfs, "tmp").mkdirs()
        // python: os.chmod(tmp, 0o1777). Stub-throws in JVM tests → ignored.
        runCatching { android.system.Os.chmod("$r/tmp", 1023) }
        a += "--bind=$r/tmp:/dev/shm"
        listOf(
            "/data/app", "/data/dalvik-cache",
            "/data/misc/apexdata/com.android.art/dalvik-cache"
        ).filter { traversable(it) }.forEach { a += "--bind=$it" }
        a += storageBinds()
        if (File("/data/data/$pkg/files/apps").isDirectory) a += "--bind=/data/data/$pkg/files/apps"
        a += "--bind=/data/data/$pkg/cache"
        a += "--bind=$termuxHome"
        for (p in SYSTEM_PATHS) {
            val real = runCatching { File(p).canonicalPath }.getOrNull() ?: continue
            val f = File(real)
            if ((f.isDirectory && traversable(real)) || (f.isFile && readable(real))) {
                a += "--bind=$real"
            }
        }
        a += "--bind=$prefix"
        if (useSharedTmp) a += "--bind=$prefix/tmp:/tmp"
        if (bindKgsl) a += "--bind=/dev/kgsl-3d0"
        a += guestCmd

        // proot process env (python builds it from scratch; never host LD_*).
        val env = HashMap<String, String>()
        env["PATH"] = "$GUEST_PATH:/usr/local/games:/usr/games:/system/bin:/system/xbin:$prefix/bin"
        env["MOZ_FAKE_NO_SANDBOX"] = "1"
        env["PULSE_SERVER"] = "127.0.0.1"
        for (k in ANDROID_HOST_ENV_VARS + listOf(
            "PROOT_NO_SECCOMP", "PROOT_VERBOSE", "PROOT_LOADER", "PROOT_LOADER_32", "PROOT_TMP_DIR"
        )) {
            hostEnv[k]?.takeIf { it.isNotEmpty() }?.let { env[k] = it }
        }
        env["HOME"] = home
        env["USER"] = user
        env["TERM"] = hostEnv["TERM"]?.takeIf { it.isNotEmpty() } ?: "xterm-256color"
        hostEnv["COLORTERM"]?.takeIf { it.isNotEmpty() }?.let { env["COLORTERM"] = it }
        val l2s = File(rootfs, ".l2s").also { it.mkdirs() }
        env["PROOT_L2S_DIR"] = l2s.path
        return a.toTypedArray() to env
    }

    /** zsh-first cascade (default pref). */
    val GUEST_LOGIN_SHELL: String get() = GuestLoginShell.prootLoginCascade(GuestLoginShell.DEFAULT)

    /**
     * Pure argv builder (unit-testable without Android).
     * Interactive login when [shellCmd] is a login sentinel
     * ([GuestLoginShell.isLoginSentinel]); otherwise a single-quoted guest
     * payload (host bash never expands `$HOME`/`$PATH`).
     *
     * The interactive guest binary comes from [loginShell] — the sentinel
     * string itself never picks the shell.
     *
     * @param distro proot-distro container name (`debian`, `alpine`, …)
     */
    fun buildArgs(
        shell: String,
        prootDistro: String,
        shellCmd: String,
        user: String = "flux",
        useSharedTmp: Boolean = true,
        distro: String = "debian",
        loginShell: GuestLoginShell = GuestLoginShell.DEFAULT
    ): Array<String> {
        val sharedTmpFlag = if (useSharedTmp) "--shared-tmp" else ""
        val env = guestLoginEnv(user)
        return if (GuestLoginShell.isLoginSentinel(shellCmd)) {
            arrayOf(
                shell, "-c",
                "exec python $prootDistro login $distro $sharedTmpFlag --user $user -- " +
                    "$env ${GuestLoginShell.prootLoginCascade(loginShell)}"
            )
        } else {
            // Single-quote guest payload so host bash never expands $HOME/$PATH/etc.
            // Escape embedded single quotes: ' → '\''
            // Use /bin/sh so Alpine (pre-zsh) and Debian both work.
            val escaped = shellCmd.replace("'", "'\\''")
            arrayOf(
                shell, "-c",
                "exec python $prootDistro login $distro $sharedTmpFlag --user $user -- " +
                    "$env /bin/sh -c '$escaped'"
            )
        }
    }

    fun build(
        ctx: Context,
        shellCmd: String,
        user: String = "flux",
        useSharedTmp: Boolean = true,
        distro: String = "debian",
        loginShell: GuestLoginShell = GuestLoginShell.DEFAULT
    ): Pair<Array<String>, HashMap<String, String>> {
        // Host package env + interactive TERM (guest login inherits package identity)
        val envMap = HostCommandBuilder.envMap(ctx, forceHostSetup = false, includeTerm = true)
        if (!TerminalPreferences.isLegacyProotLauncher(ctx)) {
            val guestCmd = listOf("/usr/bin/env", "-i") +
                guestEnvVars(user, envMap["TERM"] ?: "xterm-256color") +
                if (GuestLoginShell.isLoginSentinel(shellCmd)) {
                    listOf("/bin/sh", "-lc", GuestLoginShell.prootLoginScript(loginShell))
                } else {
                    listOf("/bin/sh", "-c", shellCmd)
                }
            buildDirect(
                proot = TermuxHostPaths.libProot(ctx).absolutePath,
                prefix = TermuxHostPaths.PREFIX,
                termuxHome = TermuxHostPaths.HOME,
                pkg = TermuxHostPaths.PACKAGE,
                distro = distro,
                user = user,
                guestCmd = guestCmd,
                hostEnv = envMap,
                useSharedTmp = useSharedTmp,
                bindKgsl = File("/dev/kgsl-3d0").exists() // Turnip needs the KGSL node
            )?.let { return it }
            Log.i("ProotCommandBuilder", "direct launch: layout not recognised for $distro, using proot-distro")
        }
        val shell = TermuxHostPaths.libBash(ctx).absolutePath
        val args = buildArgs(shell, TermuxHostPaths.PROOT_DISTRO, shellCmd, user, useSharedTmp, distro, loginShell)
        return args to envMap
    }
}
