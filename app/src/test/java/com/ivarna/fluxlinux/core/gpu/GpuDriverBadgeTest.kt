package com.ivarna.fluxlinux.core.gpu

import com.ivarna.fluxlinux.core.terminal.GpuAccelDetector
import org.junit.Assert.assertEquals
import org.junit.Test

class GpuDriverBadgeTest {
    @Test fun turnipInstalled() =
        assertEquals("Turnip 26.3.0", GpuDriverInstaller.badge(GpuAccelDetector.MODE_TURNIP, "26.3.0-devel-20260824", null))

    @Test fun panvkInstalled() =
        assertEquals("PanVK 0.1.0-beta.17", GpuDriverInstaller.badge(GpuAccelDetector.MODE_PANVK, null, "0.1.0-beta.17"))

    @Test fun eligibleButMissing() {
        assertEquals("Needs driver", GpuDriverInstaller.badge(GpuAccelDetector.MODE_TURNIP, null, null))
        assertEquals("Needs driver", GpuDriverInstaller.badge(GpuAccelDetector.MODE_PANVK, "x", null))
    }

    @Test fun virgl() =
        assertEquals("VirGL", GpuDriverInstaller.badge(GpuAccelDetector.MODE_VIRGL, "1", "1"))

    @Test fun unknownMode() =
        assertEquals("Software fallback", GpuDriverInstaller.badge("other", "1", "1"))
}
