package com.ivarna.fluxlinux.core.terminal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ProotCommandBuilderTest {

    @Test
    fun storageBinds_withAllFilesAccess_bindsStorageAndSdcard() {
        val b = ProotCommandBuilder.storageBinds { true }
        assertTrue(b.contains("--bind=/storage"))
        assertTrue(b.contains("--bind=/storage/emulated/0:/sdcard"))
    }

    @Test
    fun storageBinds_withoutPermission_bindsNoSdcard() {
        assertEquals(listOf("--bind=/storage"), ProotCommandBuilder.storageBinds { it == "/storage" })
        assertTrue(ProotCommandBuilder.storageBinds { false }.isEmpty())
    }

    @Test
    fun guestLoginEnv_setsPulseTcpLocalhost() {
        val env = ProotCommandBuilder.guestLoginEnv("flux")
        assertTrue(env.contains("PULSE_SERVER=tcp:127.0.0.1"))
        assertTrue(env.startsWith("env -i "))
        assertTrue(!env.contains("LD_LIBRARY_PATH"))
    }

    @Test
    fun login_defaults_to_debian() {
        val args = ProotCommandBuilder.buildArgs(
            shell = "/bin/bash",
            prootDistro = "/pd",
            shellCmd = "exec zsh",
            user = "flux"
        )
        val joined = args.joinToString(" ")
        assertTrue(joined.contains("login debian"))
        assertTrue(joined.contains("env -i"))
        assertTrue(joined.indexOf("if [ -x /bin/zsh ]") < joined.indexOf("if [ -x /bin/bash ]"))
    }

    @Test
    fun login_alpine() {
        val args = ProotCommandBuilder.buildArgs(
            shell = "/bin/bash",
            prootDistro = "/pd",
            shellCmd = "exec zsh",
            user = "flux",
            distro = "alpine"
        )
        val joined = args.joinToString(" ")
        assertTrue(joined.contains("login alpine"))
        assertTrue(!joined.contains("login debian"))
        assertTrue(joined.contains("env -i"))
        assertTrue(joined.contains("TMPDIR=/tmp"))
        assertTrue(joined.contains("PULSE_SERVER=tcp:127.0.0.1"))
        assertTrue(joined.contains("LANG=C"))
        assertTrue(!joined.contains("LC_ALL=en_US.UTF-8"))
        assertTrue(!joined.contains("LANG=en_US.UTF-8"))
        assertTrue(!joined.contains("PROOT_TMP_DIR="))
        assertTrue(joined.indexOf("if [ -x /bin/zsh ]") < joined.indexOf("if [ -x /bin/bash ]"))
    }

    @Test
    fun guest_payload_uses_sh() {
        val args = ProotCommandBuilder.buildArgs(
            shell = "/bin/bash",
            prootDistro = "/pd",
            shellCmd = "echo hi",
            user = "root",
            distro = "alpine"
        )
        val joined = args.joinToString(" ")
        assertTrue(joined.contains("login alpine"))
        assertTrue(joined.contains("sh -c"))
        assertTrue(joined.contains("env -i"))
        assertTrue(joined.contains("TMPDIR=/tmp"))
    }

    @Test
    fun bashPref_cascadePutsBashFirst_evenWhenSentinelSaysZsh() {
        val args = ProotCommandBuilder.buildArgs(
            shell = "/bin/bash",
            prootDistro = "/pd",
            shellCmd = "exec zsh",
            user = "flux",
            loginShell = GuestLoginShell.BASH
        )
        val joined = args.joinToString(" ")
        assertTrue(joined.indexOf("if [ -x /bin/bash ]") < joined.indexOf("if [ -x /bin/zsh ]"))
        assertTrue(joined.endsWith("exec /bin/sh -l; fi'"))
    }

    @Test
    fun execBashSentinel_isInteractiveWithDefaultZshCascade() {
        val args = ProotCommandBuilder.buildArgs(
            shell = "/bin/bash",
            prootDistro = "/pd",
            shellCmd = "exec bash",
            user = "flux"
        )
        val joined = args.joinToString(" ")
        assertFalse(joined.contains("/bin/sh -c '"))
        assertTrue(joined.contains("env -i"))
        assertTrue(joined.indexOf("if [ -x /bin/zsh ]") < joined.indexOf("if [ -x /bin/bash ]"))
    }

    @Test
    fun qwenShapedPayload_staysShC_noCascade() {
        val args = ProotCommandBuilder.buildArgs(
            shell = "/bin/bash",
            prootDistro = "/pd",
            shellCmd = "echo 'eHl6' | base64 -d | bash",
            user = "flux"
        )
        val joined = args.joinToString(" ")
        assertTrue(joined.contains("/bin/sh -c 'echo '\\''eHl6'\\'' | base64 -d | bash'"))
        assertFalse(joined.contains("if [ -x /bin/zsh ]"))
    }

    @Test
    fun workdirForm_staysPayloadWithMkdirAndCd() {
        val workdir = "mkdir -p /home/flux/p && cd /home/flux/p && exec zsh"
        val args = ProotCommandBuilder.buildArgs(
            shell = "/bin/bash",
            prootDistro = "/pd",
            shellCmd = workdir,
            user = "flux"
        )
        val joined = args.joinToString(" ")
        assertTrue(joined.contains("/bin/sh -c"))
        assertTrue(joined.contains("mkdir -p /home/flux/p"))
        assertTrue(joined.contains("cd /home/flux/p"))
        assertFalse(joined.contains("if [ -x /bin/zsh ]"))
    }

    @Test
    fun blankShellCmd_isInteractiveWithZshFirstCascade() {
        val args = ProotCommandBuilder.buildArgs(
            shell = "/bin/bash",
            prootDistro = "/pd",
            shellCmd = "",
            user = "flux"
        )
        val joined = args.joinToString(" ")
        assertFalse(joined.contains("/bin/sh -c '"))
        assertTrue(joined.contains("env -i"))
        assertTrue(joined.indexOf("if [ -x /bin/zsh ]") < joined.indexOf("if [ -x /bin/bash ]"))
    }

    @Test
    fun direct_launch_builds_proot_argv_without_python() {
        val prefix = kotlin.io.path.createTempDirectory("flux-direct").toFile()
        val container = java.io.File(prefix, "var/lib/proot-distro/containers/debian")
        val rootfs = java.io.File(container, "rootfs")
        java.io.File(rootfs, "etc").mkdirs()
        java.io.File(rootfs, "etc/passwd").writeText(
            "root:x:0:0:root:/root:/bin/bash\nflux:x:1000:1000::/home/flux:/bin/zsh\n"
        )
        java.io.File(container, "sysdata/sys_empty").mkdirs()
        val proot = "/data/app/x/lib/arm64/libproot.so"
        try {
            val (args, env) = ProotCommandBuilder.buildDirect(
                proot = proot, prefix = prefix.path, termuxHome = "${prefix.path}/home",
                pkg = "com.example", distro = "debian", user = "flux",
                guestCmd = listOf("/usr/bin/env", "-i", "/bin/sh", "-l"),
                hostEnv = mapOf("PROOT_LOADER" to "/nld/libloader.so", "LD_PRELOAD" to "x"),
                bindKgsl = true
            )!!
            assertEquals(proot, args[0])
            assertTrue(args.contains("--rootfs=${rootfs.path}"))
            assertTrue(args.contains("--link2symlink"))
            assertTrue(args.contains("--kill-on-exit"))
            assertTrue(args.contains("--change-id=1000:1000"))
            assertTrue(args.contains("--bind=${prefix.path}/tmp:/tmp"))
            assertTrue(args.contains("--bind=/dev/kgsl-3d0"))
            assertFalse(args.any { it.contains("python") || it.contains("proot-distro login") })
            assertEquals("/nld/libloader.so", env["PROOT_LOADER"])
            assertFalse(env.containsKey("LD_PRELOAD"))
            // Unknown layout → null (caller falls back to python).
            assertNull(
                ProotCommandBuilder.buildDirect(
                    proot, prefix.path, "/h", "p", "alpine", "flux", emptyList(), emptyMap()
                )
            )
        } finally {
            prefix.deleteRecursively()
        }
    }
}
