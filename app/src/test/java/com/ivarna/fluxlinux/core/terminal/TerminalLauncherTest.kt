package com.ivarna.fluxlinux.core.terminal

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import kotlin.io.path.createTempDirectory

/** Fast-path / deploy-marker unit tests for the proot-opt-01 optimizations. */
class TerminalLauncherTest {

    @Test
    fun prepareHostBlocking_returnsFastWhenHostReady() {
        val dir = createTempDirectory("flux-launcher").toFile()
        try {
            val files = dir
            // A valid extracted tree: termux-exec lib + versioned marker.
            File(files, "usr/lib").mkdirs()
            File(files, "usr/lib/libtermux-exec.so").writeText("fake")
            File(files, "home/.fluxlinux").mkdirs()
            File(files, "home/.fluxlinux/bootstrap.extracted").writeText(
                "${BootstrapInstaller.EXTRACT_VERSION}|${TermuxHostPaths.PACKAGE}"
            )
            // Host setup marker -> isHostSetupDone.
            File(files, "home/.fluxlinux/setup_termux.done").writeText("ok")

            val ctx = FakeContext(files, "$files/lib")
            // Current-install deploy stamp: warm path only re-checks host env + stamp.
            HostScriptDeployer.markDeployed(ctx)

            val start = System.nanoTime()
            assertTrue(TerminalLauncher.prepareHostBlocking(ctx))
            val ms = (System.nanoTime() - start) / 1_000_000
            // Warm path must NOT walk the prefix tree or write the 30 scripts.
            // Allow a comfortable margin for JVM startup noise; the old path
            // took multiple seconds on device.
            assertTrue("fast-path took ${ms}ms", ms < 1000)
            // Host env was refreshed to the current nativeLibraryDir.
            assertTrue(TermuxHostPaths.hostEnvFile(files).readText().contains("$files/lib"))
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun deployScripts_respectsDeployMarker() {
        val dir = createTempDirectory("flux-deploy").toFile()
        try {
            val ctx = FakeContext(dir, "$dir/jni")
            // No marker and no assets -> deploy must fail closed (not mark).
            assertFalse(HostScriptDeployer.deployScripts(ctx))
            assertFalse(HostScriptDeployer.isDeployed(ctx))
            // Simulate a successful first deploy by marking manually; the next
            // call must short-circuit without touching assets (which throw here).
            val marker = HostScriptDeployer.deployMarker(ctx)
            marker.parentFile?.mkdirs()
            marker.writeText(HostScriptDeployer.deployStamp(ctx))
            assertTrue(HostScriptDeployer.deployScripts(ctx))
            assertTrue(HostScriptDeployer.isDeployed(ctx))
            // A stale/old version invalidates the marker (forces re-deploy).
            marker.writeText("0")
            assertFalse(HostScriptDeployer.isDeployed(ctx))
        } finally {
            dir.deleteRecursively()
        }
    }
}