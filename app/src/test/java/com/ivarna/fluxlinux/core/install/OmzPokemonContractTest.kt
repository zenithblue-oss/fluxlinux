package com.ivarna.fluxlinux.core.install

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Locks the OMZ + pokemon-colorscripts contract for Deepin / Chimera / Manjaro:
 * guest script must try (not skip-by-default), runner must not hardcode skip=1,
 * and chroot payload must export skip=0 (no host git).
 */
class OmzPokemonContractTest {

    private fun repoFile(rel: String): File {
        val cwd = File("").absoluteFile
        val candidates = listOf(
            File(cwd, rel),
            File(cwd, "app/$rel"),
            File(cwd.parentFile, rel),
            File(cwd.parentFile, "app/$rel")
        )
        return candidates.firstOrNull { it.isFile }
            ?: error("missing $rel (cwd=$cwd)")
    }

    @Test
    fun sharedCustomization_triesPokemonAndClonesOmz() {
        val text = repoFile(
            "src/main/assets/scripts/common/setup/setup_customization_xfce.sh"
        ).readText()
        assertTrue(text.contains("gitlab.com/phoneybadger/pokemon-colorscripts.git"))
        assertTrue(text.contains("FLUX_SKIP_POKEMON:-0"))
        assertFalse(
            "default skip must not be 1",
            text.contains("FLUX_SKIP_POKEMON:-1")
        )
        assertTrue(text.contains("github.com/ohmyzsh/ohmyzsh.git"))
        assertTrue(text.contains("zsh-autosuggestions"))
        assertTrue(text.contains("zsh-syntax-highlighting"))
        assertTrue(text.contains("agnosterzak"))
        assertTrue(text.contains("git missing (required for Oh My Zsh / pokemon)"))
        assertTrue(text.contains("apk add git zsh"))
        assertTrue(text.contains("python3"))
        assertTrue(text.contains("OMZ/pokemon still run") || text.contains("continuing (OMZ/pokemon"))
        assertTrue(text.contains("_flux_timeout_works"))
        assertTrue(text.contains("sigaction(32)"))
    }

    @Test
    fun onboardingRunner_doesNotHardcodePokemonSkip() {
        val text = repoFile(
            "src/main/kotlin/com/ivarna/fluxlinux/core/install/OnboardingInstallRunner.kt"
        ).readText()
        assertFalse(text.contains("FLUX_SKIP_POKEMON=1"))
        assertTrue(text.contains("FLUX_SKIP_POKEMON=0"))
    }

    @Test
    fun chrootPayload_exportsPokemonTry() {
        val text = repoFile(
            "src/main/kotlin/com/ivarna/fluxlinux/core/install/BaseDesktopInstallPlan.kt"
        ).readText()
        assertTrue(text.contains("FLUX_SKIP_POKEMON='0'"))
    }

    @Test
    fun distroSettings_doesNotDefaultPokemonSkip() {
        val text = repoFile(
            "src/main/kotlin/com/ivarna/fluxlinux/MainActivity.kt"
        ).readText()
        assertFalse(
            text.contains("merged[\"FLUX_SKIP_POKEMON\"] = \"1\"")
        )
    }

    @Test
    fun dcmFamilies_installGitZshPython() {
        val deepin = repoFile(
            "src/main/assets/scripts/deepin/common/setup/setup_deepin_family.sh"
        ).readText()
        assertTrue(deepin.contains("git zsh python3"))
        val chimera = repoFile(
            "src/main/assets/scripts/chimera/common/setup/setup_chimera_family.sh"
        ).readText()
        assertTrue(chimera.contains("git zsh python"))
        val manjaro = repoFile(
            "src/main/assets/scripts/manjaro/common/setup/setup_manjaro_family.sh"
        ).readText()
        assertTrue(manjaro.contains("git zsh python"))
        assertTrue(
            "CheckSpace must be disabled before first pacman -S (no sed)",
            manjaro.contains("_flux_disable_checkspace")
        )
        assertFalse(
            "bootstrap has no sed — do not sed -i CheckSpace",
            manjaro.contains("sed -i 's/^CheckSpace")
        )
        assertTrue(manjaro.contains("dbus-broker-units"))
        assertTrue(manjaro.contains("python sed gzip"))
        assertTrue(
            "family install must ship fastfetch so the shell always has it",
            manjaro.contains("fastfetch")
        )
        assertTrue(manjaro.contains("_flux_ensure_locale"))
        assertTrue(manjaro.contains("_flux_ensure_hostname"))
        assertFalse(
            "--needed glibc is a no-op when glibc is already installed",
            manjaro.contains("pacman -S --noconfirm --needed glibc")
        )
        val common = repoFile(
            "src/main/assets/scripts/common/setup/flux_guest_common.sh"
        ).readText()
        assertTrue(
            "pacman guests must restore glibc i18n data",
            common.contains("pacman -S --noconfirm glibc")
        )
        assertTrue(
            "en_US.UTF-8 must be generated so agnosterzak can paint @flux",
            common.contains("en_US.UTF-8 UTF-8")
        )
        assertTrue(
            "directory locales work when locale-archive is empty under proot",
            common.contains("localedef --no-archive")
        )
        assertTrue(common.contains("_flux_sanitize_lang"))
    }

    @Test
    fun sharedCustomization_noDnfZypperAdwaitaHardcode() {
        val text = repoFile(
            "src/main/assets/scripts/common/setup/setup_customization_xfce.sh"
        ).readText()
        assertFalse(
            "dnf/zypper must not force SEL_ICON=Adwaita",
            Regex(
                """SEL_ICON="Adwaita"[\s\S]{0,80}(dnf|zypper)|(dnf|zypper)[\s\S]{0,80}SEL_ICON="Adwaita""""
            ).containsMatchIn(text)
        )
        // leftover comment-only mention is ok; the assignment after dnf/zypper is not
        val afterIconDefault = text.substringAfter("SEL_ICON=\"Papirus-Dark\"")
        assertFalse(
            afterIconDefault.contains("SEL_ICON=\"Adwaita\"") &&
                afterIconDefault.substringBefore("papirus_xfce_ok")
                    .contains("command -v dnf")
        )
        assertTrue(text.contains("papirus_xfce_ok") || text.contains("applications-internet"))
        assertTrue(
            "dangling Papirus status/@2x symlinks must be replaced before mkdir/cp",
            text.contains("_flux_ensure_dir")
        )
        assertTrue(text.contains("\"folders\""))
        assertTrue(text.contains("\"/\""))
        assertFalse(text.contains("(pacman)"))
        assertTrue(text.contains("locale -a"))
        assertTrue(text.contains("C.UTF-8"))
        assertTrue(text.contains("grep -qxFi"))
        assertFalse(
            "bare fastfetch dumps every Android bind mount",
            text.contains("|| fastfetch 2>/dev/null")
        )
        assertTrue(text.contains("gtk-3.0/settings.ini") || text.contains("gtk-icon-theme-name"))
    }

    @Test
    fun fedoraFamily_generatesEnUsLocale() {
        val fedora = repoFile(
            "src/main/assets/scripts/fedora/common/setup/setup_fedora_family.sh"
        ).readText()
        assertTrue(
            fedora.contains("glibc-langpack-en") || fedora.contains("localedef")
        )
        assertTrue(fedora.contains("en_US.UTF-8"))
        // Fedora 44+ moved `su` out of shadow-utils; chroot flux login needs util-linux.
        assertTrue(fedora.contains("util-linux"))
        val common = repoFile(
            "src/main/assets/scripts/common/setup/flux_guest_common.sh"
        ).readText()
        assertTrue(common.contains("_flux_ensure_en_us_locale"))
        assertTrue(common.contains("glibc-langpack-en"))
        assertTrue(common.contains("_flux_sanitize_lang"))
        assertTrue(common.contains("localedef --no-archive"))
    }

    @Test
    fun chrootSetup_disablesPacmanCheckSpace() {
        val text = repoFile(
            "src/main/assets/scripts/chroot/setup_guest_chroot.sh"
        ).readText()
        assertTrue(text.contains("s/^CheckSpace/#CheckSpace/"))
        assertTrue(text.contains("var/cache/pacman/pkg"))
    }

    @Test
    fun startScripts_doNotRelabelHostTmpAsTmpfs() {
        val scripts = listOf(
            "src/main/assets/scripts/debian/proot/start/start_gui.sh",
            "src/main/assets/scripts/chroot/start_gui_chroot.sh",
            "src/main/assets/scripts/chroot/start_guest_gui.sh",
            "src/main/assets/scripts/chroot/start_debian13_gui.sh",
            "src/main/assets/scripts/chroot/start_alpine_gui.sh",
            "src/main/assets/scripts/debian/chroot/setup/setup_debian13_chroot.sh"
        )
        for (rel in scripts) {
            val text = repoFile(rel).readText()
            assertFalse(
                "$rel must not chcon PREFIX/tmp to tmpfs:s0",
                text.contains("chcon -R u:object_r:tmpfs:s0")
            )
        }
        val proot = repoFile(
            "src/main/assets/scripts/debian/proot/start/start_gui.sh"
        ).readText()
        assertTrue(proot.contains("XDG_RUNTIME_DIR=/home/flux/.cache/runtime"))
        assertFalse(proot.contains("XDG_RUNTIME_DIR=/tmp/runtime-flux"))
    }

    @Test
    fun startScripts_sourceApplyGpuEnv() {
        val scripts = listOf(
            "src/main/assets/scripts/debian/proot/start/start_gui.sh",
            "src/main/assets/scripts/chroot/start_guest_gui.sh",
            "src/main/assets/scripts/chroot/start_alpine_gui.sh",
            "src/main/assets/scripts/chroot/start_debian13_gui.sh"
        )
        for (rel in scripts) {
            val text = repoFile(rel).readText()
            assertTrue(
                "$rel must source apply_gpu_env.sh",
                text.contains("/usr/local/lib/fluxlinux/apply_gpu_env.sh")
            )
            assertTrue(
                "$rel must call flux_gpu_apply_runtime",
                text.contains("flux_gpu_apply_runtime")
            )
        }
    }

    @Test
    fun debianHwAccel_isThinWrapper() {
        val rel = "src/main/assets/scripts/debian/common/setup/setup_hw_accel_debian.sh"
        val text = repoFile(rel).readText()
        val lines = text.lines().filter { it.isNotBlank() && !it.trimStart().startsWith("#") }
        assertTrue("debian hw wrapper should be thin, got ${lines.size} lines", lines.size < 30)
        assertTrue(text.contains("setup_hw_accel_guest"))
    }

    @Test
    fun guestHwAccel_usesAppPickedStableTurnipWithShaAndMarker() {
        val text = repoFile(
            "src/main/assets/scripts/common/setup/setup_hw_accel_guest.sh"
        ).readText()
        assertTrue(text.contains("FLUX_TURNIP_"))
        assertTrue(text.contains("sha256sum"))
        assertTrue(text.contains("/etc/fluxlinux/turnip_version"))
        assertFalse(text.contains("turnip-weekly"))
        assertFalse(text.contains("/turnip-"))
    }
}
