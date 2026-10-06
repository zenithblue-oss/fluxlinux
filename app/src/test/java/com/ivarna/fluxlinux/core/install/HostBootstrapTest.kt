package com.ivarna.fluxlinux.core.install

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HostBootstrapTest {

    @Test
    fun pins_areDistinctAndOnRootfsTag() {
        assertEquals(
            "bootstrap_com.ivarna.fluxlinux.tar",
            HostBootstrap.IVARNA.fileName
        )
        assertEquals(
            "bootstrap_com.zenithblue.fluxlinux.tar",
            HostBootstrap.ZENITHBLUE.fileName
        )
        assertTrue(HostBootstrap.IVARNA.sha256.matches(Regex("[0-9a-f]{64}")))
        assertTrue(HostBootstrap.ZENITHBLUE.sha256.matches(Regex("[0-9a-f]{64}")))
        assertTrue(HostBootstrap.IVARNA.sha256 != HostBootstrap.ZENITHBLUE.sha256)
        assertTrue(HostBootstrap.IVARNA.url.startsWith(HostBootstrap.RELEASE_BASE))
        assertTrue(HostBootstrap.ZENITHBLUE.url.endsWith(HostBootstrap.ZENITHBLUE.fileName))
        assertTrue(HostBootstrap.IVARNA.minBytes >= 50L * 1024L * 1024L)
    }

    @Test
    fun forApplicationId_selectsFlavorPin() {
        assertEquals(
            HostBootstrap.IVARNA,
            HostBootstrap.forApplicationId(HostBootstrap.IVARNA_PACKAGE)
        )
        assertEquals(
            HostBootstrap.ZENITHBLUE,
            HostBootstrap.forApplicationId(HostBootstrap.ZENITHBLUE_PACKAGE)
        )
        assertEquals(HostBootstrap.IVARNA, HostBootstrap.forApplicationId("unknown"))
    }

    @Test
    fun downloadsFromRelease_neverBothFlavorsShipBootstrap() {
        assertFalse(HostBootstrap.downloadsFromRelease(HostBootstrap.IVARNA_PACKAGE))
        assertFalse(HostBootstrap.downloadsFromRelease(HostBootstrap.ZENITHBLUE_PACKAGE))
    }
}
