package com.ivarna.fluxlinux.core.gpu

import com.ivarna.fluxlinux.core.gpu.PanvkInstaller.Elig
import com.ivarna.fluxlinux.core.gpu.PanvkInstaller.Plan
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PanvkInstallerTest {

    private val sha = "b".repeat(64)

    private fun asset(name: String, tag: String) =
        """{"name":"$name","digest":"sha256:$sha","browser_download_url":"https://x/$tag/$name"}"""

    private fun rel(tag: String, vararg names: String) =
        """{"tag_name":"$tag","prerelease":true,"draft":false,"assets":[${names.joinToString(",") { asset(it, tag) }}]}"""

    private val so = "libvulkan_panfrost-glibc-aarch64.so"
    private val icd = "panfrost_icd.aarch64.json"

    private val json = "[" + listOf(
        rel("panplay-v9.9.9", so, icd),
        rel("panprobe-v9.9.9", so, icd),
        rel("g615-v11-csf-v0.1.0-beta.9", so, icd),
        rel("g615-v11-csf-v0.1.0-beta.17", so, icd),
        rel("g615-v11-csf-v0.1.0-beta.16", so, icd),
    ).joinToString(",") + "]"

    @Test
    fun latest_picksNewestDriverTag_notPanplayOrPanprobe() {
        val p = PanvkInstaller.plan("debian", true, json) as Plan.Install
        assertEquals(listOf("0.1.0-beta.17", PanvkInstaller.PIN_VERSION), p.specs.map { it.version })
        assertTrue(p.specs[0].so.url.contains("g615-v11-csf-v0.1.0-beta.17"))
    }

    @Test
    fun tagFilter_excludesAppTags() {
        assertFalse(PanvkInstaller.TAG_RE.matches("panplay-v1.2.2"))
        assertFalse(PanvkInstaller.TAG_RE.matches("panprobe-v1.2.2"))
        assertTrue(PanvkInstaller.TAG_RE.matches("g615-v11-csf-v0.1.0-beta.16"))
    }

    @Test
    fun apiDownOrNoDriverTag_fallsBackToPin() {
        for (j in listOf(null, "not json", "[" + rel("panplay-v1.0.0", so, icd) + "]")) {
            val p = PanvkInstaller.plan("debian", true, j) as Plan.Install
            assertEquals(listOf(PanvkInstaller.pin()), p.specs)
        }
        assertEquals(PanvkInstaller.PIN_SO_SHA, PanvkInstaller.pin().so.sha256)
        assertEquals(PanvkInstaller.PIN_ICD_SHA, PanvkInstaller.pin().icd.sha256)
    }

    @Test
    fun latestMissingIcdAsset_usesPin() {
        val j = "[" + rel("g615-v11-csf-v0.1.0-beta.30", so) + "]"
        val p = PanvkInstaller.plan("debian", true, j) as Plan.Install
        assertEquals(listOf(PanvkInstaller.PIN_VERSION), p.specs.map { it.version })
    }

    @Test
    fun offline_andMuslSkip() {
        assertEquals(Plan.Offline, PanvkInstaller.plan("debian", false, null))
        assertTrue(PanvkInstaller.plan("alpine", true, json) is Plan.Skip)
        assertTrue(PanvkInstaller.plan("alpine_chroot", false, json) is Plan.Skip)
        assertTrue(PanvkInstaller.plan("chimera", true, json) is Plan.Skip)
        assertTrue(PanvkInstaller.glibc("void"))
        assertTrue(PanvkInstaller.glibc("ubuntu_chroot"))
    }

    @Test
    fun maliArchTable() {
        assertEquals(9, PanvkInstaller.maliArch("Mali-G57 MC2"))
        assertEquals(9, PanvkInstaller.maliArch("Mali-G78"))
        assertEquals(10, PanvkInstaller.maliArch("Mali-G610"))
        assertEquals(11, PanvkInstaller.maliArch("Mali-G615 6 cores r1p3 0xB8A3"))
        assertEquals(11, PanvkInstaller.maliArch("Immortalis-G715"))
        assertEquals(12, PanvkInstaller.maliArch("Mali-G720"))
        assertEquals(13, PanvkInstaller.maliArch("Immortalis-G925"))
        assertEquals(14, PanvkInstaller.maliArch("Mali-G1-Ultra"))
        assertEquals(11, PanvkInstaller.maliArch("0xB8A3"))
    }

    @Test
    fun eligibility() {
        val mtk = "soc_manufacturer=mediatek ro.board.platform=mt6897"
        assertTrue(PanvkInstaller.eligibility(mtk, "Mali-G615 6 cores r1p3 0xB8A3", true, true) is Elig.Ok)
        assertTrue(PanvkInstaller.eligibility(mtk, "Mali-G57 MC2", false, true) is Elig.Skip)
        // gpuinfo unreadable: CSF sysfs decides (JM = pre-v10)
        assertTrue(PanvkInstaller.eligibility(mtk, null, false, true) is Elig.Skip)
        assertTrue(PanvkInstaller.eligibility(mtk, "", true, true) is Elig.Ok)
        assertTrue(PanvkInstaller.eligibility(mtk, "Mali-G615", true, false) is Elig.Skip)
        assertEquals(Elig.NotApplicable, PanvkInstaller.eligibility("qcom kalama", "Mali-G615", true, true))
        assertEquals(Elig.NotApplicable, PanvkInstaller.eligibility("exynos2400 mali-g720", "Mali-G720", true, true))
    }
}
