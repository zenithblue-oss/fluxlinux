package com.ivarna.fluxlinux.core.install

/**
 * Host `bootstrap.tar` pins for the GitHub release tag `rootfs`.
 *
 * Both flavors ship `assets/bootstrap.tar` in the APK; this URL is only a
 * repair fallback used when the asset is missing.
 *
 * Filename is stable per applicationId. A SHA change requires a **new**
 * filename (same D9 rule as distro rootfs) so old APKs keep working.
 */
object HostBootstrap {

    const val RELEASE_BASE =
        "https://github.com/abhay-byte/fluxlinux/releases/download/rootfs"

    const val IVARNA_PACKAGE = "com.ivarna.fluxlinux"
    const val ZENITHBLUE_PACKAGE = "com.zenithblue.fluxlinux"

    val IVARNA = PinnedReleaseArchive(
        fileName = "bootstrap_com.ivarna.fluxlinux.tar",
        sha256 = "5b16c6597d38380c0cab9471d3cf69a0c7a23d9a4191125bd9dd6ddc77277f5c",
        url = "$RELEASE_BASE/bootstrap_com.ivarna.fluxlinux.tar",
        minBytes = 50L * 1024L * 1024L
    )

    val ZENITHBLUE = PinnedReleaseArchive(
        fileName = "bootstrap_com.zenithblue.fluxlinux.tar",
        sha256 = "b0856e1009b8718455bfaf6cb5332e57473ab7d58200f8c28a6db89b30a30cfd",
        url = "$RELEASE_BASE/bootstrap_com.zenithblue.fluxlinux.tar",
        minBytes = 50L * 1024L * 1024L
    )

    fun forApplicationId(applicationId: String): PinnedReleaseArchive =
        when (applicationId) {
            ZENITHBLUE_PACKAGE -> ZENITHBLUE
            else -> IVARNA
        }

    /**
     * True when this flavor does **not** package `assets/bootstrap.tar`.
     * Both flavors now ship it (F-Droid builds it from source); the release
     * URL is only a repair fallback when the asset is missing.
     */
    @Suppress("UNUSED_PARAMETER")
    fun downloadsFromRelease(applicationId: String): Boolean = false
}
