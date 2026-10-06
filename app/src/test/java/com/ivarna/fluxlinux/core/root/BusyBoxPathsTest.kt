package com.ivarna.fluxlinux.core.root

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class BusyBoxPathsTest {

    private val expectedCandidates = listOf(
        "/data/adb/ksu/bin/busybox",
        "/data/adb/ap/bin/busybox",
        "/data/adb/magisk/busybox",
        "/data/adb/modules/busybox-ndk/system/xbin/busybox",
        "/data/adb/modules/busybox-ndk/system/bin/busybox",
        "/debug_ramdisk/busybox",
        "/sbin/busybox",
        "/system/xbin/busybox",
        "/system/bin/busybox",
    )

    @Test
    fun candidates_order_exact() {
        assertEquals(expectedCandidates, BusyBoxPaths.CANDIDATES)
    }

    @Test
    fun first_three_are_ksu_apatch_magisk() {
        assertEquals("/data/adb/ksu/bin/busybox", BusyBoxPaths.CANDIDATES[0])
        assertEquals("/data/adb/ap/bin/busybox", BusyBoxPaths.CANDIDATES[1])
        assertEquals("/data/adb/magisk/busybox", BusyBoxPaths.CANDIDATES[2])
    }

    @Test
    fun constants_exact() {
        assertEquals("/data/local/tmp/flux_busybox", BusyBoxPaths.PINNED)
        assertEquals("scripts/chroot/resolve_bb.sh", BusyBoxPaths.RESOLVER_ASSET)
        assertEquals("/data/local/tmp/fluxlinux_resolve_bb.sh", BusyBoxPaths.RESOLVER_ON_DEVICE)
    }

    @Test
    fun helper_and_resolver_contain_every_candidate() {
        val helper = readAsset("scripts/chroot/fluxlinux_chroot.sh")
        val resolver = readAsset("scripts/chroot/resolve_bb.sh")
        for (path in BusyBoxPaths.CANDIDATES) {
            assertTrue("helper missing $path", helper.contains(path))
            assertTrue("resolver missing $path", resolver.contains(path))
        }
    }

    @Test
    fun helper_and_resolver_reject_prefix() {
        val helper = readAsset("scripts/chroot/fluxlinux_chroot.sh")
        val resolver = readAsset("scripts/chroot/resolve_bb.sh")
        for (src in listOf(helper, resolver)) {
            assertTrue(src.contains("*com.termux*"))
            assertTrue(src.contains("*fluxlinux*"))
            assertTrue(src.contains("*nativecode*"))
        }
    }

    @Test
    fun bb_ok_requires_chroot_and_mount() {
        val helper = readAsset("scripts/chroot/fluxlinux_chroot.sh")
        val resolver = readAsset("scripts/chroot/resolve_bb.sh")
        for (src in listOf(helper, resolver)) {
            assertTrue(src.contains("bb_has"))
            assertTrue(src.contains("bb_ok"))
            assertTrue(src.contains("bb_has \"$1\" chroot"))
            assertTrue(src.contains("bb_has \"$1\" mount"))
        }
    }

    @Test
    fun helper_version_is_v29() {
        val helper = readAsset("scripts/chroot/fluxlinux_chroot.sh")
        assertTrue(helper.contains("fluxlinux-chroot v2.9"))
        assertTrue(helper.contains("VERSION_STR=\"fluxlinux-chroot v2.9\""))
        assertEquals("fluxlinux-chroot v2.9", ChrootPaths.CHROOT_HELPER_VERSION)
    }

    @Test
    fun helper_mentions_system_mount_and_chroot() {
        val helper = readAsset("scripts/chroot/fluxlinux_chroot.sh")
        assertTrue(helper.contains("/system/bin/mount"))
        assertTrue(helper.contains("/system/bin/chroot"))
    }

    @Test
    fun phase2_scripts_source_resolver() {
        val files = listOf(
            "scripts/chroot/setup_guest_chroot.sh",
            "scripts/chroot/setup_debian13_chroot.sh",
            "scripts/chroot/setup_alpine_chroot.sh",
            "scripts/arch/chroot/setup/setup_arch_chroot.sh",
            "scripts/chroot/start_guest_gui.sh",
            "scripts/chroot/start_debian13_gui.sh",
            "scripts/chroot/start_alpine_gui.sh",
            "scripts/chroot/stop_guest_gui.sh",
            "scripts/chroot/stop_debian13_gui.sh",
            "scripts/chroot/stop_alpine_gui.sh",
            "scripts/chroot/uninstall_guest_chroot.sh",
            "scripts/chroot/uninstall_debian13_chroot.sh",
            "scripts/chroot/uninstall_alpine_chroot.sh",
            "scripts/debian/chroot/setup/setup_debian13_chroot.sh",
            "scripts/debian/chroot/setup/setup_debian_chroot.sh",
            "scripts/debian/chroot/setup/uninstall_debian13.sh",
            "scripts/debian/chroot/setup/uninstall_debian_chroot.sh",
            "scripts/chroot/start_gui_chroot.sh",
            "scripts/chroot/stop_gui_chroot.sh",
        )
        for (rel in files) {
            val text = readAsset(rel)
            assertTrue(
                "$rel must source resolve_bb.sh or call resolve_bb",
                text.contains("resolve_bb.sh") || text.contains("resolve_bb")
            )
        }
    }

    @Test
    fun termuxIntentFactory_has_no_bare_busybox_chroot() {
        val src = readRepoFile("app/src/main/kotlin/com/ivarna/fluxlinux/core/data/TermuxIntentFactory.kt")
        assertFalse(src.contains("busybox chroot"))
    }

    @Test
    fun last_resort_pin_requires_exec_probe() {
        val files = listOf(
            "scripts/chroot/setup_guest_chroot.sh",
            "scripts/chroot/setup_debian13_chroot.sh",
            "scripts/chroot/setup_alpine_chroot.sh",
            "scripts/arch/chroot/setup/setup_arch_chroot.sh",
            "scripts/chroot/start_guest_gui.sh",
            "scripts/chroot/start_debian13_gui.sh",
            "scripts/chroot/start_alpine_gui.sh",
            "scripts/chroot/stop_guest_gui.sh",
            "scripts/chroot/stop_debian13_gui.sh",
            "scripts/chroot/stop_alpine_gui.sh",
            "scripts/chroot/uninstall_guest_chroot.sh",
            "scripts/chroot/uninstall_debian13_chroot.sh",
            "scripts/chroot/uninstall_alpine_chroot.sh",
            "scripts/debian/chroot/setup/setup_debian13_chroot.sh",
            "scripts/debian/chroot/setup/setup_debian_chroot.sh",
            "scripts/debian/chroot/setup/uninstall_debian13.sh",
            "scripts/debian/chroot/setup/uninstall_debian_chroot.sh",
        )
        val assignOnly = Regex(
            """\[ -x /data/local/tmp/flux_busybox \][^\n]*\n\s*BB=/data/local/tmp/flux_busybox"""
        )
        for (rel in files) {
            val text = readAsset(rel)
            val m = assignOnly.find(text)
            if (m != null) {
                val window = text.substring(m.range.first.coerceAtLeast(0), m.range.last + 1)
                assertTrue(
                    "$rel assigns pin from [ -x ] without --list/bb_ok",
                    window.contains("--list") || window.contains("bb_ok")
                )
            }
            assertTrue(
                "$rel last-resort pin must exec-probe --list",
                !text.contains("[ -x /data/local/tmp/flux_busybox ]; then") ||
                    text.contains("/data/local/tmp/flux_busybox --list")
            )
        }
    }

    @Test
    fun termuxIntentFactory_does_not_default_dead_pin() {
        val src = readRepoFile("app/src/main/kotlin/com/ivarna/fluxlinux/core/data/TermuxIntentFactory.kt")
        assertFalse(
            src.contains("BB=\"\${FLUX_BB:-/data/local/tmp/flux_busybox}\"") ||
                src.contains("BB=\"\${'$'}{FLUX_BB:-/data/local/tmp/flux_busybox}\"")
        )
        assertTrue(src.contains("/data/adb/ksu/bin/busybox"))
        assertTrue(src.contains("flux_busybox --list"))
    }

    private fun readAsset(rel: String): String {
        val name = rel.removePrefix("scripts/")
        val cwd = File("").absoluteFile
        val candidates = listOf(
            File(cwd, "src/main/assets/scripts/$name"),
            File(cwd, "app/src/main/assets/scripts/$name")
        )
        return candidates.first { it.isFile }.readText()
    }

    private fun readRepoFile(rel: String): String {
        val cwd = File("").absoluteFile
        val candidates = listOf(
            File(cwd, rel.removePrefix("app/")),
            File(cwd, rel)
        )
        return candidates.first { it.isFile }.readText()
    }
}
