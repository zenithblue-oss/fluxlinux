package com.ivarna.fluxlinux.core.data

import android.content.Intent
import android.content.Context
import com.ivarna.fluxlinux.core.data.Distro
import com.ivarna.fluxlinux.core.data.ScriptManager

/**
 * Legacy external-Termux intent builder.
 *
 * Pass 2 (embedded terminal plan §2.4/§4.5): install / run shell / uninstall /
 * component paths for `debian` / `debian13_chroot` / `debian_chroot` MUST NOT
 * use this factory — use `terminalComponentFor(distroId)` + the session
 * factories. Remaining legitimate call sites: GUI Start/Stop (deferred GUI
 * pass) and legacy optional tweaks. The install/buildRun* builders below are
 * DEAD (no product caller) — do not re-wire them. Do not start Pulse here.
 */
object TermuxIntentFactory {

    private const val ACTION_RUN_COMMAND = "com.termux.RUN_COMMAND"
    private const val EXTRA_COMMAND_PATH = "com.termux.RUN_COMMAND_PATH"
    private const val EXTRA_ARGUMENTS = "com.termux.RUN_COMMAND_ARGUMENTS"
    private const val EXTRA_WORKDIR = "com.termux.RUN_COMMAND_WORKDIR"
    private const val EXTRA_BACKGROUND = "com.termux.RUN_COMMAND_BACKGROUND"
    private const val EXTRA_SESSION_ACTION = "com.termux.RUN_COMMAND_SESSION_ACTION"

    private const val TERMUX_BASH_PATH = "/data/data/com.termux/files/usr/bin/bash"
    private const val TERMUX_HOME_DIR = "/data/data/com.termux/files/home"

    /**
     * Creates an intent to execute a bash script string in Termux.
     */
    fun buildRunCommandIntent(
        scriptContent: String,
        runInBackground: Boolean = false
    ): Intent {
        return Intent(ACTION_RUN_COMMAND).apply {
            setClassName("com.termux", "com.termux.app.RunCommandService")
            putExtra(EXTRA_COMMAND_PATH, TERMUX_BASH_PATH)
            putExtra(EXTRA_ARGUMENTS, arrayOf("-c", scriptContent))
            putExtra(EXTRA_WORKDIR, TERMUX_HOME_DIR)
            putExtra(EXTRA_BACKGROUND, runInBackground)
            // 0 = ACTION_FAIL_ON_SESSION_EXIT (keep session open if it fails?)
            // let's default to just running.
        }
    }

    /**
     * A simple "Ping" command to check if connection works.
     */
    fun buildTestConnectionIntent(): Intent {
        return buildRunCommandIntent("echo 'FluxLinux: Connection Established!' && sleep 2")
    }

    /**
     * Generates the install command string for manual execution.
     */
    fun getInstallCommand(distroId: String, setupScript: String? = null, installScriptContent: String, guiScriptContent: String): String {
        // Enforce newline termination for safety
        val safeInstallScript = if (!installScriptContent.endsWith("\n")) "$installScriptContent\n" else installScriptContent
        val safeGuiScript = if (!guiScriptContent.endsWith("\n")) "$guiScriptContent\n" else guiScriptContent
        
        val installScriptB64 = android.util.Base64.encodeToString(safeInstallScript.toByteArray(), android.util.Base64.NO_WRAP)
        val guiScriptB64 = android.util.Base64.encodeToString(safeGuiScript.toByteArray(), android.util.Base64.NO_WRAP)
        
        val setupB64 = if (!setupScript.isNullOrEmpty()) {
            android.util.Base64.encodeToString(setupScript.toByteArray(), android.util.Base64.NO_WRAP)
        } else {
            "null"
        }
        
        // Use Base64 decoding to write files. This avoids fragile 'cat << EOF' constructs in terminals
        // and handles special characters safely.
        return """
            echo "$installScriptB64" | base64 -d > ${'$'}HOME/flux_install.sh
            chmod +x ${'$'}HOME/flux_install.sh
            
            echo "$guiScriptB64" | base64 -d > ${'$'}HOME/start_gui.sh
            chmod +x ${'$'}HOME/start_gui.sh
            
            bash ${'$'}HOME/flux_install.sh $distroId "$setupB64"
        """.trimIndent()
    }

    /**
     * Just opens Termux (launcher intent).
     */
    fun buildOpenTermuxIntent(context: android.content.Context): Intent? {
        return context.packageManager.getLaunchIntentForPackage("com.termux")
    }

    /**
     * Installs a specific distro... (Deprecated: User Manual Fallback Preferred)
     */
    fun buildInstallIntent(distroId: String, setupScript: String? = null): Intent {
        // Use the native helper script we created in setup_termux.sh
        // Usage: bash ~/flux_install.sh <distro> <base64_setup>
        
        val setupB64 = if (!setupScript.isNullOrEmpty()) {
            android.util.Base64.encodeToString(setupScript.toByteArray(), android.util.Base64.NO_WRAP)
        } else {
            "null"
        }
        
        val command = "bash $TERMUX_HOME_DIR/flux_install.sh $distroId \"$setupB64\""
        return buildRunCommandIntent(command)
    }

    /**
     * Uninstalls/Removes a specific distro.
     */
    fun buildUninstallIntent(distroId: String): Intent {
        val callbackUrl = "fluxlinux://callback?result=success&name=distro_uninstall_$distroId"
        
        val command = when {
            distroId == "termux" -> {
                "pkg uninstall -y xfce4 xfce4-terminal tigervnc && echo 'FluxLinux: Termux Native Desktop Removed.' && sleep 1 && am start -a android.intent.action.VIEW -d \"$callbackUrl\""
            }
            distroId == "debian13_chroot" -> {
                // Chroot: Inline uninstall logic (unmount, remove, callback)
                // This avoids dependency on pre-deployed script files
                """
                su -c '
                DEBIANPATH="/data/local/tmp/chrootDebian13"
                echo "Unmounting filesystems..."
                for mnt in $(grep "${'$'}DEBIANPATH" /proc/mounts | awk "{print \${'$'}2}" | sort -r); do
                    umount -l "${'$'}mnt" 2>/dev/null
                done
                echo "Removing chroot directory..."
                rm -rf "${'$'}DEBIANPATH"
                rm -f /data/local/tmp/start_debian13*.sh /data/local/tmp/enter_debian13.sh /data/local/tmp/run_debian13_root.sh /data/local/tmp/stop_debian13*.sh /data/local/tmp/uninstall_debian13.sh
                echo "Chroot removed successfully!"
                am start -a android.intent.action.VIEW -d "$callbackUrl"
                '
                """.trimIndent()
            }
            distroId == "debian_chroot" -> {
                // Chroot: Inline uninstall logic
                """
                su -c '
                DEBIANPATH="/data/local/tmp/chrootDebian"
                echo "Unmounting filesystems..."
                for mnt in $(grep "${'$'}DEBIANPATH" /proc/mounts | awk "{print \${'$'}2}" | sort -r); do
                    umount -l "${'$'}mnt" 2>/dev/null
                done
                echo "Removing chroot directory..."
                rm -rf "${'$'}DEBIANPATH"
                rm -f /data/local/tmp/start_debian*.sh /data/local/tmp/enter_debian.sh /data/local/tmp/stop_debian*.sh /data/local/tmp/uninstall_debian*.sh
                echo "Chroot removed successfully!"
                am start -a android.intent.action.VIEW -d "$callbackUrl"
                '
                """.trimIndent()
            }
            distroId.contains("chroot") -> {
                // Generic chroot fallback
                "su -c \"rm -rf /data/local/tmp/chroot*\" && echo 'Chroot removed.' && am start -a android.intent.action.VIEW -d \"$callbackUrl\""
            }
            else -> {
                // PRoot: Try proot-distro remove, retry once if it fails, then fallback to manual removal
                """
                echo "Attempting to remove $distroId..."
                if proot-distro remove $distroId 2>/dev/null; then
                    echo "FluxLinux: $distroId Uninstalled."
                else
                    echo "First attempt failed, retrying..."
                    sleep 1
                    if proot-distro remove $distroId 2>/dev/null; then
                        echo "FluxLinux: $distroId Uninstalled."
                    else
                        echo "proot-distro command failed, using manual removal..."
                        rm -rf ${'$'}PREFIX/var/lib/proot-distro/containers/$distroId \
                            ${'$'}PREFIX/var/lib/proot-distro/installed-rootfs/$distroId
                        if [ -e ${'$'}PREFIX/var/lib/proot-distro/containers/$distroId ]; then
                            if [ -x /system/bin/su ]; then
                                /system/bin/su -c "rm -rf ${'$'}PREFIX/var/lib/proot-distro/containers/$distroId" </dev/null
                            fi
                        fi
                        echo "FluxLinux: $distroId manually removed."
                    fi
                fi
                sleep 2
                am start -a android.intent.action.VIEW -d "$callbackUrl"
                """.trimIndent()
            }
        }
        return buildRunCommandIntent(command)
    }

    /**
     * EXTENDED INSTALL: Generates a compound script to install base + components
     */
    /**
     * Generates a raw bash script string for installing the base distro.
     * This is intended to be copied to the clipboard.
     */
    fun getBaseInstallScript(context: Context, distro: Distro): String {
        val scriptManager = ScriptManager(context)
        
        // 1. Select Base Script
        val baseScriptName = when (distro.id) {
            "debian13_chroot" -> "debian/chroot/setup/setup_debian13_chroot.sh"
            "debian_chroot" -> "debian/chroot/setup/setup_debian_chroot.sh"
            "termux" -> "termux/setup_termux.sh"   // Base Termux deps (proot, X11, etc.)
            "archlinux" -> "arch/common/setup/setup_arch_family.sh"
            else -> "debian/common/setup/setup_debian_family.sh"
        }
        
        var fullScript = ""
        
        // --- STEP LOGGING HELPER ---
        fullScript += """
            CURRENT_STEP=1
            log_step() {
                echo -e "\n\033[1;36m[STEP ${'$'}{CURRENT_STEP}] ${'$'}1\033[0m"
                ((CURRENT_STEP++))
            }
        """.trimIndent() + "\n\n"
        
        // 0. Prepend Termux Setup (Dependency Check) if not running it directly AND not Chroot
        if (distro.id != "termux" && !distro.id.contains("chroot")) {
             val termuxSetup = scriptManager.getScriptContent("termux/setup_termux.sh")
             // Strip the shebang and exit/callback from setup_termux
             var cleanSetup = termuxSetup.replace("#!/bin/bash", "")
             cleanSetup = cleanSetup.replace("exit 0", "# exit 0 deferred from setup_termux")
             cleanSetup = cleanSetup.replace("am start -a android.intent.action.VIEW", "# Deferred callback from setup_termux")
             
             // Remove the "Skipping" check to ensure dependencies are verified
             cleanSetup = cleanSetup.replace(Regex("if \\[ -f \"\\\$MARKER_FILE\" ]; then[\\s\\S]*?fi"), "# Marker check removed for full install")

             fullScript += "# --- FLUXLINUX TERMUX SETUP (Dependencies) ---\n"
             fullScript += "log_step \"Installing Termux Dependencies (Proot, X11)...\"\n"
             fullScript += cleanSetup
             fullScript += "\n\n# --- DISTRO INSTALLATION ---\n"
        }
        
        fullScript += "log_step \"Installing Base System (${distro.name})...\"\n"
        
        // --- BASE INSTALL LOGIC SPLIT ---
        val isChroot = distro.id.contains("chroot")
        val isTermux = distro.id == "termux"
        
        if (isTermux) {
             // Termux: Dependencies were installed in Step 1 (setup_termux.sh)
             // We might just echo success or run a specific termux desktop setup found in setup_debian_family?
             // Actually setup_debian_family is for Debian. Termux needs its own.
             // Currently setup_termux does most work. We can just append baseScriptName if it's not setup_termux (which it is).
             if (baseScriptName != "termux/setup_termux.sh") {
                 fullScript += scriptManager.getScriptContent(baseScriptName)
             } else {
                 fullScript += "echo 'Termux Environment Ready.'\n"
             }
        } else if (isChroot) {
             // Chroot: Script is self-contained (runs on host). Run it directly.
             // Chroot scripts handle their own internal steps.
             fullScript += scriptManager.getScriptContent(baseScriptName)
        } else {
             // Proot: Needs 'proot-distro install' FIRST, then wrap the config script.
             
             // 1. Install Distro Image
             fullScript += "log_step \"Downloading & Installing Proot Image...\"\n"
             fullScript += "proot-distro install ${distro.id} || echo 'Distro already installed or warning'\n"
             
             // 2. Wrap Configuration Script
             fullScript += "log_step \"Configuring Distro Environment...\"\n"
             val baseConfig = scriptManager.getScriptContent(baseScriptName)
             val baseConfigB64 = android.util.Base64.encodeToString(baseConfig.toByteArray(), android.util.Base64.NO_WRAP)
             
             // Use echo with base64 - avoids all heredoc/trimIndent issues
             fullScript += "echo '$baseConfigB64' | base64 -d > \$HOME/flux_base_setup.sh\n"
             fullScript += "chmod +x \$HOME/flux_base_setup.sh\n"
             fullScript += "\n# Run inside Proot (with fixed PATH)\n"
             fullScript += "proot-distro login ${distro.id} --shared-tmp -- bash -c \"export PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin && bash /data/data/com.termux/files/home/flux_base_setup.sh\"\n"
             fullScript += "rm -f \$HOME/flux_base_setup.sh\n"
              
             // Deploy start_gui.sh separately using echo
             val guiScriptB64 = android.util.Base64.encodeToString(scriptManager.getScriptContent("debian/proot/start/start_gui.sh").toByteArray(), android.util.Base64.NO_WRAP)
             fullScript += "\n# Deploy Start GUI Script\nlog_step \"Updating Launch Scripts...\"\n"
             fullScript += "echo '$guiScriptB64' | base64 -d > \$HOME/start_gui.sh\n"
             fullScript += "chmod +x \$HOME/start_gui.sh\n"
             
             // Deploy stop_gui.sh as well
             val stopGuiScriptB64 = android.util.Base64.encodeToString(scriptManager.getScriptContent("debian/proot/stop/stop_gui.sh").toByteArray(), android.util.Base64.NO_WRAP)
             fullScript += "echo '$stopGuiScriptB64' | base64 -d > \$HOME/stop_gui.sh\n"
             fullScript += "chmod +x \$HOME/stop_gui.sh\n"
        }
        
        // 2. Modify Base Script to defer exit/callback if present (mostly for chroot scripts that have it)
        fullScript = fullScript.replace("exit 0", "# exit 0 deferred")
        // Remove the old AM callback
        fullScript = fullScript.replace("am start -a android.intent.action.VIEW -d \"fluxlinux://callback?result=success", "# Deferred callback")
        
        // 5. Wrap the entire script in a self-extracting runner
        // GZIP COMPRESSION OPTIMIZATION to reduce Clipboard size.
        val safeScript = if (!fullScript.endsWith("\n")) "$fullScript\n" else fullScript
        
        val byteArrayOutputStream = java.io.ByteArrayOutputStream()
        java.util.zip.GZIPOutputStream(byteArrayOutputStream).use { it.write(safeScript.toByteArray()) }
        val fullScriptGzipB64 = android.util.Base64.encodeToString(byteArrayOutputStream.toByteArray(), android.util.Base64.NO_WRAP)
        
        // Generate a random ID for the EOF marker to avoid collisions
        val eofMarker = "EOF_FLUX_INSTALL_${System.currentTimeMillis()}"
        val targetPath = if (isChroot) "/data/local/tmp/flux_full_install.sh" else "$TERMUX_HOME_DIR/flux_full_install.sh"
        val runnerCmd = if (isChroot) "sh" else "bash"
        
        // Command to decode: echo "..." | base64 -d | gunzip > script.sh
        
        val runnerScript = StringBuilder()
        
        if (isChroot) {
            runnerScript.append("su -c '\n")
        }
        
        runnerScript.append("cat << '$eofMarker' > $targetPath.b64\n")
        runnerScript.append(fullScriptGzipB64)
        runnerScript.append("\n$eofMarker\n")
        
        // Decode GZIP
        runnerScript.append("base64 -d $targetPath.b64 | gunzip > $targetPath\n")
        runnerScript.append("rm $targetPath.b64\n")
        runnerScript.append("chmod +x $targetPath\n")
        runnerScript.append("$runnerCmd $targetPath\n")
        
        // --- ADD CALLBACK ---
        // --- ADD CALLBACK ---
        val callbackName = "base_install"
        val callbackUrl = "fluxlinux://callback?result=success&name=$callbackName"
        val errorUrl = "fluxlinux://callback?result=failure&name=$callbackName"
        
        runnerScript.append("if [ $? -eq 0 ]; then\n")
        runnerScript.append("    am start -a android.intent.action.VIEW -d \"$callbackUrl\"\n")
        runnerScript.append("else\n")
        runnerScript.append("    echo \"FluxLinux: Installation Failed!\"\n")
        runnerScript.append("    am start -a android.intent.action.VIEW -d \"$errorUrl\"\n")
        runnerScript.append("fi\n")
        
        if (isChroot) {
            runnerScript.append("'")
        }
        
        return runnerScript.toString()
    }

    /**
     * EXTENDED INSTALL: Generates a compound script to install base + components
     * DEPRECATED: Use getCompoundInstallScript and Manual Flow.
     */
    // Deprecated buildCompoundInstallIntent removed.

    /**
     * Launches a specific distro in CLI mode (login as flux user).
     */
    fun buildLaunchCliIntent(distroId: String): Intent {
        if (distroId == "termux") {
             return buildRunCommandIntent("echo 'You are already in Termux Native environment!' && sleep 2")
        }
        
        if (distroId == "debian_chroot") {
            // Launch Chroot CLI using Android Root (su)
            return buildRunCommandIntent("su -c \"sh /data/local/tmp/enter_debian.sh\"", runInBackground = false)
        }

        if (distroId == "debian13_chroot") {
            // Launch Debian 13 Chroot CLI using Android Root (su)
            return buildRunCommandIntent("su -c \"sh /data/local/tmp/enter_debian13.sh\"", runInBackground = false)
        }
        
        if (distroId == "arch_chroot") {
            // Launch Arch Chroot CLI (via generated script)
            return buildRunCommandIntent("su -c \"sh /data/local/tmp/enter_arch.sh\"", runInBackground = false)
        }
        
        // Default to 'flux' user if setup, fallback to root if not (proot-distro handles login)
        val command = "proot-distro login $distroId --user flux"
        return buildRunCommandIntent(command, runInBackground = false)
    }

    /**
     * Launches a specific chroot distro in CLI mode as ROOT user.
     * Only works for chroot distros (debian13_chroot, debian_chroot, arch_chroot).
     */
    fun buildLaunchRootCliIntent(distroId: String): Intent {
        if (distroId == "debian_chroot") {
            // Launch Chroot CLI as Root
            return buildRunCommandIntent("su -c \"sh /data/local/tmp/enter_debian_root.sh\"", runInBackground = false)
        }

        if (distroId == "debian13_chroot") {
            // Launch Debian 13 Chroot CLI as Root
            return buildRunCommandIntent("su -c \"sh /data/local/tmp/enter_debian13_root.sh\"", runInBackground = false)
        }
        
        if (distroId == "arch_chroot") {
            // Launch Arch Chroot CLI as Root
            return buildRunCommandIntent("su -c \"sh /data/local/tmp/enter_arch_root.sh\"", runInBackground = false)
        }
        
        // For non-chroot distros, fall back to regular CLI (proot doesn't support true root)
        return buildLaunchCliIntent(distroId)
    }

    /**
     * Launches a Qwen2.5-1.5B-Instruct interactive chat session via llama-vulkan inside the distro.
     * Requires: Vulkan Llama.cpp + Qwen2.5 model installed in the distro.
     */
    fun buildRunLlmIntent(context: Context, distroId: String): Intent {
        val scriptManager = ScriptManager(context)
        val scriptContent = scriptManager.getScriptContent("debian/common/addon/launch_qwen25.sh")
        val scriptB64 = android.util.Base64.encodeToString(scriptContent.toByteArray(), android.util.Base64.NO_WRAP)
        val command = "proot-distro login $distroId -- bash -c 'echo \"$scriptB64\" | base64 -d | bash'"
        return buildRunCommandIntent(command, runInBackground = false)
    }

    /**
     * Runs a specific feature script inside the distro.
     * Uses Base64 injection to avoid quoting/escape issues.
     */
    fun buildRunFeatureScriptIntent(distroId: String, scriptContent: String, callbackName: String? = null, isUninstall: Boolean = false): Intent {
        val safeScript = if (!scriptContent.endsWith("\n")) "$scriptContent\n" else scriptContent
        val scriptB64 = android.util.Base64.encodeToString(safeScript.toByteArray(), android.util.Base64.NO_WRAP)

        // When uninstalling, pass "uninstall" as $1 to the script so it can
        // branch into its removal path. Install path runs with no args.
        val scriptArg = if (isUninstall) " uninstall" else ""

        // Success/error callback commands. Hoisted so every install path
        // (termux, chroot, proot) propagates the script's exit code back to
        // the app — without this, failed installs silently report success.
        // `${'$'}` is Kotlin-template-escaped so the resulting bash string
        // contains a literal `$` for shell variable expansion.
        val successCallbackCmd = if (callbackName != null) {
            "am start -a android.intent.action.VIEW -d \"fluxlinux://callback?result=success&name=$callbackName\""
        } else ":"
        val errorCallbackCmd = if (callbackName != null) {
            "am start -a android.intent.action.VIEW -d \"fluxlinux://callback?result=error&name=$callbackName\""
        } else ":"

        if (distroId == "termux") {
            // Termux Native: script runs directly in Termux host (no proot, no chroot)
            val command = """
                echo "$scriptB64" | base64 -d > $TERMUX_HOME_DIR/flux_feature.sh
                chmod +x $TERMUX_HOME_DIR/flux_feature.sh
                bash $TERMUX_HOME_DIR/flux_feature.sh$scriptArg
                STATUS=${'$'}?
                rm -f $TERMUX_HOME_DIR/flux_feature.sh
                if [ ${'$'}STATUS -eq 0 ]; then
                    $successCallbackCmd
                else
                    $errorCallbackCmd
                fi
                exit ${'$'}STATUS
            """.trimIndent()
            return buildRunCommandIntent(command, runInBackground = false)
        }

        if (distroId == "debian_chroot") {
            // For Chroot, we must decode the script on the HOST (Android)
            // Write to Termux's tmp directory since that's what gets mounted into the chroot.
            // Capture the chroot's exit code inside the su -c block, then fire
            // the matching callback after su exits.
            val termuxTmp = "/data/data/com.termux/files/usr/tmp"
            val innerCommand = """
                su -c '
                mkdir -p $termuxTmp;
                echo "$scriptB64" | base64 -d > $termuxTmp/flux_feature.sh;
                chmod +x $termuxTmp/flux_feature.sh;
                BB="${'$'}{FLUX_BB:-}";
                if [ -z "${'$'}BB" ] || ! [ -x "${'$'}BB" ] || ! "${'$'}BB" --list >/dev/null 2>&1; then
                  BB="";
                  for path in /data/adb/ksu/bin/busybox /data/adb/ap/bin/busybox /data/adb/magisk/busybox; do
                    if [ -x "${'$'}path" ] && "${'$'}path" --list >/dev/null 2>&1; then BB="${'$'}path"; break; fi;
                  done;
                fi;
                if [ -z "${'$'}BB" ]; then BB="${'$'}(command -v busybox)"; fi;
                if [ -z "${'$'}BB" ] || ! "${'$'}BB" --list >/dev/null 2>&1; then
                  if [ -x /data/local/tmp/flux_busybox ] && /data/local/tmp/flux_busybox --list >/dev/null 2>&1; then
                    BB=/data/local/tmp/flux_busybox;
                  fi;
                fi;
                "${'$'}BB" chroot /data/local/tmp/chrootDebian /bin/su - root -c "bash /tmp/flux_feature.sh$scriptArg";
                STATUS=${'$'}?;
                rm -f $termuxTmp/flux_feature.sh;
                exit ${'$'}STATUS;
                ';
                sleep 1;
                if [ ${'$'}? -eq 0 ]; then $successCallbackCmd; else $errorCallbackCmd; fi
            """.trimIndent().replace("\n", " ")

            return buildRunCommandIntent(innerCommand, runInBackground = false)
        }

        if (distroId == "debian13_chroot") {
            // Debian 13 Chroot Feature Script
            // Uses generated helper for robustness, falls back to inline mounts if missing.
            // Write to Termux's tmp directory since that's what gets mounted into the chroot
            // This ensures the script is visible inside the chroot after /tmp is mounted
            val termuxTmp = "/data/data/com.termux/files/usr/tmp"
            val innerCommand = """
                su -c '
                ROOT_RUNNER="/data/local/tmp/run_debian13_root.sh";
                if [ -f "${'$'}ROOT_RUNNER" ]; then
                    mkdir -p $termuxTmp;
                    echo "$scriptB64" | base64 -d > $termuxTmp/flux_feature.sh;
                    chmod +x $termuxTmp/flux_feature.sh;
                    sh "${'$'}ROOT_RUNNER" "bash /tmp/flux_feature.sh$scriptArg";
                    STATUS=${'$'}?;
                    rm -f $termuxTmp/flux_feature.sh;
                else
                    mnt=/data/local/tmp/chrootDebian13;
                    mkdir -p $termuxTmp;
                    mount -o remount,dev,suid /data >/dev/null 2>&1;
                    mount -t proc proc ${'$'}mnt/proc >/dev/null 2>&1;
                    mount -t sysfs sysfs ${'$'}mnt/sys >/dev/null 2>&1;
                    mount -o bind /dev ${'$'}mnt/dev >/dev/null 2>&1;
                    mount -o bind /dev/pts ${'$'}mnt/dev/pts >/dev/null 2>&1;
                    mkdir -p ${'$'}mnt/dev/shm;
                    mount -t tmpfs -o size=512M tmpfs ${'$'}mnt/dev/shm >/dev/null 2>&1;
                    mkdir -p ${'$'}mnt/tmp;
                    mount --bind $termuxTmp ${'$'}mnt/tmp >/dev/null 2>&1;
                    echo "$scriptB64" | base64 -d > $termuxTmp/flux_feature.sh;
                    chmod +x $termuxTmp/flux_feature.sh;
                    BB="${'$'}{FLUX_BB:-}";
                    if [ -z "${'$'}BB" ] || ! [ -x "${'$'}BB" ] || ! "${'$'}BB" --list >/dev/null 2>&1; then
                      BB="";
                      for path in /data/adb/ksu/bin/busybox /data/adb/ap/bin/busybox /data/adb/magisk/busybox; do
                        if [ -x "${'$'}path" ] && "${'$'}path" --list >/dev/null 2>&1; then BB="${'$'}path"; break; fi;
                      done;
                    fi;
                    if [ -z "${'$'}BB" ]; then BB="${'$'}(command -v busybox)"; fi;
                    if [ -z "${'$'}BB" ] || ! "${'$'}BB" --list >/dev/null 2>&1; then
                      if [ -x /data/local/tmp/flux_busybox ] && /data/local/tmp/flux_busybox --list >/dev/null 2>&1; then
                        BB=/data/local/tmp/flux_busybox;
                      fi;
                    fi;
                    "${'$'}BB" chroot ${'$'}mnt /bin/su - root -c "bash /tmp/flux_feature.sh$scriptArg";
                    STATUS=${'$'}?;
                    rm -f $termuxTmp/flux_feature.sh;
                fi;
                exit ${'$'}STATUS;
                ';
                sleep 1;
                if [ ${'$'}? -eq 0 ]; then $successCallbackCmd; else $errorCallbackCmd; fi
            """.trimIndent().replace("\n", " ")

            return buildRunCommandIntent(innerCommand, runInBackground = false)
        }

        // Command to run inside Termux (Proot):
        // 1. Run script inside Proot (with optional "uninstall" arg), capture its
        //    exit code BEFORE the trailing rm (which would otherwise mask the
        //    script's status with its own always-zero exit), then propagate the
        //    captured status to the outer shell via `exit $RC`.
        val innerCommand = "echo \"$scriptB64\" | base64 -d > /tmp/flux_feature.sh && bash /tmp/flux_feature.sh$scriptArg; RC=${'$'}?; rm -f /tmp/flux_feature.sh; exit ${'$'}RC"
        // 2. Outer command: run proot, capture its exit, fire success/error callback
        val command = """
            proot-distro login $distroId --shared-tmp -- bash -c '$innerCommand'
            STATUS=${'$'}?
            if [ ${'$'}STATUS -eq 0 ]; then $successCallbackCmd; else $errorCallbackCmd; fi
        """.trimIndent()

        return buildRunCommandIntent(command, runInBackground = false) // Foreground to see progress
    }

    /**
     * Runs a script as Android Root (su).
     * Used for uninstalling/managing Chroot environments.
     */
    fun buildRunRootScriptIntent(scriptContent: String): Intent {
        val safeScript = if (!scriptContent.endsWith("\n")) "$scriptContent\n" else scriptContent
        val scriptB64 = android.util.Base64.encodeToString(safeScript.toByteArray(), android.util.Base64.NO_WRAP)
        
        // Write to tmp, execute, then remove.
        // We use /data/local/tmp as it is writable by shell and accessible by root.
        val command = """
            su -c '
            echo "$scriptB64" | base64 -d > /data/local/tmp/flux_root_task.sh
            chmod +x /data/local/tmp/flux_root_task.sh
            sh /data/local/tmp/flux_root_task.sh
            rm -f /data/local/tmp/flux_root_task.sh
            '
        """.trimIndent().replace("\n", " ")
        
        return buildRunCommandIntent(command, runInBackground = false)
    }

    /**
     * Generates a safe command string that detects if it's running as root,
     * and if not, prompts the user to type 'su'.
     * Used for Clipboard copy-paste interactions.
     */
    fun getSafeRootManualCommand(scriptContent: String, scriptName: String): String {
        val safeScript = if (!scriptContent.endsWith("\n")) "$scriptContent\n" else scriptContent
        val scriptB64 = android.util.Base64.encodeToString(safeScript.toByteArray(), android.util.Base64.DEFAULT)
        // Use Heredoc with wrapped Base64 to prevent terminal freeze (Line length limits)
        val chunkedEchos = "cat << 'EOF_B64' > \"\${S}.b64\"\n$scriptB64\nEOF_B64\n"

        return """
            S="/data/local/tmp/$scriptName"
            if [ "${'$'}(id -u)" != "0" ]; then S="${'$'}HOME/$scriptName"; fi
            $chunkedEchos
            base64 -d "${'$'}S.b64" > "${'$'}S"
            rm -f "${'$'}S.b64"
            chmod +x "${'$'}S"
            if [ "${'$'}(id -u)" = "0" ]; then
                sh "${'$'}S"
            else
                echo "⚠️ PLEASE RUN AS ROOT ⚠️"
                echo "Type su and press Enter."
                echo "Then paste this command again."
            fi
        """.trimIndent() + "\n"
    }
}
