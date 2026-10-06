package com.ivarna.fluxlinux.core.desktop

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** KDE runs through the same built-in X11 start_gui*.sh as XFCE (FLUX_DESKTOP=kde). */
class KdeDesktopContractTest {

    private fun asset(rel: String): String {
        val cwd = File("").absoluteFile
        val f = listOf(File(cwd, rel), File(cwd, "app/$rel"), File(cwd.parentFile, rel))
            .firstOrNull { it.exists() } ?: error("missing $rel")
        return f.readText()
    }

    @Test
    fun prootAndChrootStartScripts_selectPlasmaForKde() {
        val proot = asset("src/main/assets/scripts/debian/proot/start/start_gui.sh")
        val guest = asset("src/main/assets/scripts/chroot/start_debian13_gui.sh")
        val wrapper = asset("src/main/assets/scripts/chroot/start_gui_chroot.sh")
        assertTrue(proot.contains("kde) DE_CMD=startplasma-x11"))
        assertTrue(proot.contains("exec dbus-run-session -- \$FLUX_DE_CMD"))
        assertTrue(guest.contains("FLUX_DE_CMD=startplasma-x11"))
        assertTrue(wrapper.contains("FLUX_DESKTOP='\${FLUX_DESKTOP:-xfce4}'"))
        for (s in listOf(proot, guest)) assertTrue(s.contains("FLUX_GPU_RUNTIME"))
    }

    @Test
    fun kdeLaunch_hasNoExternalTermuxIntent() {
        val factory = asset("src/main/kotlin/com/ivarna/fluxlinux/core/data/TermuxIntentFactory.kt")
        assertFalse(factory.contains("buildLaunchKdeGui"))
        assertFalse(factory.contains("buildStopKdeGui"))
    }
}
