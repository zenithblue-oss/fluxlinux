package com.ivarna.fluxlinux.core.install

import android.content.Context
import android.util.Base64
import com.ivarna.fluxlinux.core.data.Distro
import com.ivarna.fluxlinux.core.data.DistroRepository
import com.ivarna.fluxlinux.core.data.ScriptManager

/**
 * Minimal base desktop install: rootfs + family (XFCE) + customization.
 * Distro-specific paths come from [DistroInstallProfile].
 */
object BaseDesktopInstallPlan {

    /** @deprecated Prefer [DistroInstallProfile.familyScript] for the active distro. */
    const val FAMILY_SCRIPT = "debian/common/setup/setup_debian_family.sh"
    /** @deprecated Prefer [DistroInstallProfile.customizationScript]. */
    const val CUSTOMIZATION_SCRIPT = "debian/common/setup/setup_customization_debian.sh"

    data class Phase(val id: String, val label: String, val weight: Int)

    fun distroById(id: String): Distro? =
        DistroRepository.supportedDistros.find { it.id == id }

    fun methodFor(distroId: String): String = DistroInstallProfile.methodFor(distroId)

    fun profileFor(distroId: String): DistroInstallProfile? =
        DistroInstallProfile.forId(distroId)

    fun phasesFor(method: String, displayName: String = "Debian"): List<Phase> =
        if (method == "chroot") {
            listOf(
                Phase("R0", "Checking root access…", 5),
                Phase("HOST", "Preparing host (may download bootstrap from GitHub)…", 15),
                Phase("DL", "Downloading $displayName rootfs…", 10),
                Phase("ROOTFS", "Installing $displayName chroot rootfs…", 30),
                Phase("XFCE", "Installing XFCE desktop…", 25),
                Phase("CUSTOM", "Applying Flux customization…", 15),
            )
        } else {
            listOf(
                Phase("HOST", "Preparing host (may download bootstrap from GitHub)…", 20),
                Phase("DL", "Downloading $displayName rootfs…", 15),
                Phase("ROOTFS", "Installing $displayName rootfs + XFCE…", 35),
                Phase("CUSTOM", "Applying Flux customization…", 30),
            )
        }

    /** Back-compat overload used by existing tests. */
    fun phasesFor(method: String): List<Phase> = phasesFor(method, "Debian")

    fun familySetupPayload(ctx: Context, theme: String, distroId: String = "debian"): String {
        val profile = DistroInstallProfile.forId(distroId)
            ?: DistroInstallProfile.require("debian")
        val sm = ScriptManager(ctx)
        val family = sm.getScriptContent(profile.familyScript)
        val common = runCatching {
            sm.getScriptContent("common/setup/flux_guest_common.sh")
        }.getOrDefault("")
        return buildString {
            append("export FLUX_THEME='").append(theme).append("'\n")
            append("export FLUX_DESKTOP_ENV='xfce'\n")
            append("export PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin\n\n")
            if (common.isNotBlank()) {
                append(common).append("\n\n")
            }
            append(family)
        }
    }

    fun familySetupB64(ctx: Context, theme: String, distroId: String = "debian"): String =
        Base64.encodeToString(
            familySetupPayload(ctx, theme, distroId).toByteArray(Charsets.UTF_8),
            Base64.NO_WRAP
        )

    fun customizationPayload(ctx: Context, theme: String, distroId: String = "debian"): String {
        val profile = DistroInstallProfile.forId(distroId)
            ?: DistroInstallProfile.require("debian")
        val body = ScriptManager(ctx).getScriptContent(profile.customizationScript)
        return buildString {
            append("export FLUX_THEME='").append(theme).append("'\n")
            // Guest installs pokemon (60s budget). Host never ships it.
            append("export FLUX_SKIP_POKEMON='0'\n")
            append("export PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin\n\n")
            append(body)
        }
    }

    /**
     * Guest hw-accel payload: [flux_gpu_common.sh] + installer.
     * [fluxGpu] must already be `turnip`, `virgl`, or `ask` — never `auto`.
     */
    fun hwAccelPayload(
        ctx: Context,
        distroId: String,
        fluxGpu: String,
        vendor: String,
        extraEnv: Map<String, String> = emptyMap()
    ): String {
        val profile = DistroInstallProfile.forId(distroId)
            ?: DistroInstallProfile.require("debian")
        val sm = ScriptManager(ctx)
        val installerPath = profile.hwAccelScript ?: "common/setup/setup_hw_accel_guest.sh"
        val installer = sm.getScriptContent(installerPath)
        val common = runCatching {
            sm.getScriptContent("common/setup/flux_gpu_common.sh")
        }.getOrDefault("")
        return buildString {
            append("export FLUX_GPU='").append(fluxGpu).append("'\n")
            append("export FLUX_GPU_VENDOR='").append(vendor).append("'\n")
            extraEnv.forEach { (k, v) ->
                append("export ").append(k).append("='").append(v.replace("'", "")).append("'\n")
            }
            append("export PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin\n\n")
            if (common.isNotBlank()) {
                append(common).append("\n\n")
            }
            append(installer)
        }
    }
}
