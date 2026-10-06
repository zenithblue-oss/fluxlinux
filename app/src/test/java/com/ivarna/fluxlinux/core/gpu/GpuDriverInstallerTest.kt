package com.ivarna.fluxlinux.core.gpu

import com.ivarna.fluxlinux.core.gpu.GpuDriverInstaller.Plan
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GpuDriverInstallerTest {

    private val sha = "a".repeat(64)

    private fun asset(suffix: String, ver: String, digest: String = "sha256:$sha") =
        """{"name":"mesa-for-android-container_${ver}_${suffix}_arm64.tar.gz",
            "digest":"$digest","browser_download_url":"https://x/$ver/$suffix.tgz"}"""

    private fun rel(tag: String, pre: Boolean, vararg assets: String) =
        """{"tag_name":"$tag","prerelease":$pre,"draft":false,"assets":[${assets.joinToString(",")}]}"""

    private val json = "[" + listOf(
        rel("turnip-weekly", true, asset("debian_trixie", "w")),
        rel("turnip-26.9.0-devel-20261001", false, asset("debian_trixie", "26.9.0-devel-20261001")),
        rel("mesa-26.9.0-devel-20261001", true, asset("debian_trixie", "26.9.0-devel-20261001")),
        rel("mesa-26.3.0-devel-20260824", false, asset("debian_trixie", "26.3.0-devel-20260824")),
        rel("mesa-26.2.0-devel-20260709", false, asset("debian_trixie", "26.2.0-devel-20260709")),
        rel("mesa-25.2.7-4.fc43-adreno", false, asset("debian_trixie", "25.2.7")),
    ).joinToString(",") + "]"

    @Test
    fun latest_ignoresWeeklyTurnipPrereleaseAndOddTags() {
        val p = GpuDriverInstaller.plan("debian", true, "750", true, json) as Plan.Install
        assertEquals("26.3.0-devel-20260824", p.pkgs.first().version)
        assertEquals(sha, p.pkgs.first().sha256)
    }

    @Test
    fun apiFailure_usesPinnedOnly() {
        val p = GpuDriverInstaller.plan("debian", true, "750", true, null) as Plan.Install
        assertEquals(1, p.pkgs.size)
        assertEquals(GpuDriverInstaller.PIN_VERSION, p.pkgs[0].version)
        assertEquals(GpuDriverInstaller.PIN_SHA["debian_trixie"], p.pkgs[0].sha256)
    }

    @Test
    fun newerLatest_getsPinnedAsFallback() {
        val newer = "[" + rel("mesa-26.4.0-devel-20261101", false, asset("void", "26.4.0-devel-20261101")) + "]"
        val p = GpuDriverInstaller.plan("void", true, null, true, newer) as Plan.Install
        assertEquals(listOf("26.4.0-devel-20261101", GpuDriverInstaller.PIN_VERSION), p.pkgs.map { it.version })
    }

    @Test
    fun latestEqualsPin_notDuplicated() {
        val pin = GpuDriverInstaller.pin("debian_trixie")!!
        val j = "[" + rel("mesa-${pin.version}", false,
            """{"name":"mesa-for-android-container_${pin.version}_debian_trixie_arm64.tar.gz",
               "digest":"sha256:${pin.sha256}","browser_download_url":"${pin.url}"}""") + "]"
        val p = GpuDriverInstaller.plan("debian", true, null, true, j) as Plan.Install
        assertEquals(1, p.pkgs.size)
    }

    @Test
    fun noAssetForDistroInRelease_fallsBackToPin() {
        val p = GpuDriverInstaller.plan("fedora", true, null, true, json) as Plan.Install
        assertEquals(listOf(GpuDriverInstaller.PIN_VERSION), p.pkgs.map { it.version })
    }

    @Test
    fun badOrMissingDigest_assetRejected() {
        val j = "[" + rel("mesa-26.4.0-devel-20261101", false,
            asset("debian_trixie", "26.4.0-devel-20261101", digest = "sha256:zz")) + "]"
        assertNull(DriverRelease.latest(j, GpuDriverInstaller.TAG_RE) { true })
        assertNull(DriverRelease.latest("not json", GpuDriverInstaller.TAG_RE) { true })
    }

    @Test
    fun distroMapping() {
        assertEquals("debian_trixie", GpuDriverInstaller.assetSuffix("debian13_chroot"))
        assertEquals("debian_trixie", GpuDriverInstaller.assetSuffix("kali"))
        assertEquals("ubuntu_resolute", GpuDriverInstaller.assetSuffix("ubuntu_chroot"))
        assertEquals("fedora_44", GpuDriverInstaller.assetSuffix("fedora"))
        assertEquals("alpine_3.24", GpuDriverInstaller.assetSuffix("alpine_chroot"))
        assertEquals("void", GpuDriverInstaller.assetSuffix("void"))
        assertNull(GpuDriverInstaller.assetSuffix("archlinux"))
        assertNull(GpuDriverInstaller.assetSuffix("opensuse"))
    }

    @Test
    fun skipAndOfflinePaths() {
        assertEquals(Plan.NotAdreno, GpuDriverInstaller.plan("debian", false, null, true, json))
        assertEquals(Plan.Offline, GpuDriverInstaller.plan("debian", true, "750", false, null))
        assertTrue(GpuDriverInstaller.plan("debian", true, "Adreno 540", true, json) is Plan.Skip)
        assertTrue(GpuDriverInstaller.plan("opensuse", true, "750", true, json) is Plan.Skip)
        assertTrue(GpuDriverInstaller.adrenoSupported(null))
        assertTrue(GpuDriverInstaller.adrenoSupported("Adreno(TM) 840"))
    }

    @Test
    fun versionCompare() {
        assertTrue(DriverRelease.isNewer("26.3.0-devel-20260824", "26.2.0-devel-20260709"))
        assertTrue(DriverRelease.isNewer("26.2.0-devel-20260710", "26.2.0-devel-20260709"))
        assertTrue(!DriverRelease.isNewer("26.2.0-devel-20260709", "26.2.0-devel-20260709"))
    }
}
