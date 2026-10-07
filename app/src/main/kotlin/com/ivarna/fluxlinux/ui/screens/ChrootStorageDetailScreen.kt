package com.ivarna.fluxlinux.ui.screens

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.rounded.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ivarna.fluxlinux.core.chroot.ChrootInfoStore
import com.ivarna.fluxlinux.core.chroot.ChrootKillCoordinator
import com.ivarna.fluxlinux.core.chroot.ChrootSettingsModel
import com.ivarna.fluxlinux.core.chroot.GuestStorageCatalog
import com.ivarna.fluxlinux.core.data.DistroRepository
import com.ivarna.fluxlinux.core.root.ChrootPaths
import com.ivarna.fluxlinux.ui.components.GlassSettingCard
import com.ivarna.fluxlinux.ui.screens.storage.MetaRow
import com.ivarna.fluxlinux.ui.screens.storage.StatusBadge
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Parameterized detail screen for a single chroot distribution or "__all_chroot__" aggregate.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChrootStorageDetailScreen(
    distroId: String,
    onBack: () -> Unit,
    onNavigateToInstall: (() -> Unit)? = null
) {
    val isUniversal = distroId == GuestStorageCatalog.ALL_CHROOT_ID
    val distro = if (!isUniversal) {
        DistroRepository.supportedDistros.firstOrNull { it.id == distroId }
    } else null

    val resolvedPath = if (!isUniversal) {
        GuestStorageCatalog.chrootPathOrNull(distroId)
    } else null

    // If target distro disappeared or invalid id, pop to list
    if (!isUniversal && (distro == null || resolvedPath == null)) {
        LaunchedEffect(distroId) {
            onBack()
        }
        return
    }

    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    val path = resolvedPath ?: ChrootPaths.CHROOT_PATH
    val allPaths = remember { GuestStorageCatalog.KNOWN_CHROOT_PATHS.toList().sorted() }
    val installableIds = remember { GuestStorageCatalog.installableChroots().map { it.id } }

    val initialPage = remember(distroId) {
        if (isUniversal) {
            val cachedBytes = ChrootInfoStore.cachedBytes(context, GuestStorageCatalog.ALL_CHROOT_ID)
            val cachedProc = ChrootInfoStore.cachedProcCount(context, GuestStorageCatalog.ALL_CHROOT_ID)
            ChrootSettingsModel.PageSnapshot(
                size = ChrootSettingsModel.SizeUi(
                    rootOk = true,
                    installed = true,
                    markerOk = true,
                    dirExists = true,
                    bytes = cachedBytes,
                    dimmedCache = cachedBytes != null,
                    hint = "Tap refresh to measure"
                ),
                proc = ChrootSettingsModel.ProcUi(
                    rootOk = true,
                    count = cachedProc,
                    processes = emptyList(),
                    hint = if (cachedProc >= 0) "Cached proc count" else "Tap scan for all chroot processes"
                )
            )
        } else {
            ChrootSettingsModel.loadCached(context, distroId = distroId, path = path)
        }
    }

    var sizeUi by remember { mutableStateOf(initialPage.size) }
    var procUi by remember { mutableStateOf(initialPage.proc) }
    var busy by remember { mutableStateOf(false) }
    var showKillConfirm by remember { mutableStateOf(false) }
    val killCoordinator = remember { ChrootKillCoordinator() }
    var killProgress by remember { mutableStateOf<Pair<Int, Int>?>(null) }

    fun refreshAll(force: Boolean = true) {
        if (busy || killCoordinator.isBusy) return
        busy = true
        sizeUi = sizeUi.copy(measuring = true, hint = "Checking root · measuring rootfs…")
        procUi = procUi.copy(scanning = true, hint = "Scanning /proc across roots…")
        scope.launch {
            try {
                val snap = withContext(Dispatchers.IO) {
                    if (isUniversal) {
                        val p = ChrootSettingsModel.refreshUniversal(context, installableIds)
                        val proc = ChrootSettingsModel.scanAllProcesses(context, allPaths)
                        ChrootSettingsModel.PageSnapshot(p.size, proc)
                    } else {
                        ChrootSettingsModel.refreshPage(context, distroId = distroId, path = path, force = force)
                    }
                }
                sizeUi = snap.size
                procUi = snap.proc
            } finally {
                busy = false
            }
        }
    }

    fun refreshSizeOnly() {
        if (busy || killCoordinator.isBusy) return
        busy = true
        sizeUi = sizeUi.copy(measuring = true, hint = "Checking root · measuring rootfs…")
        scope.launch {
            try {
                val s = withContext(Dispatchers.IO) {
                    if (isUniversal) {
                        ChrootSettingsModel.refreshUniversal(context, installableIds).size
                    } else {
                        ChrootSettingsModel.refreshSize(context, distroId = distroId, path = path, forceClearSu = true)
                    }
                }
                sizeUi = s
            } finally {
                busy = false
            }
        }
    }

    fun scanProcs() {
        if (busy || killCoordinator.isBusy) return
        busy = true
        procUi = procUi.copy(scanning = true, hint = "Scanning /proc for chroot root…")
        scope.launch {
            try {
                val p = withContext(Dispatchers.IO) {
                    if (isUniversal) {
                        ChrootSettingsModel.scanAllProcesses(context, allPaths)
                    } else {
                        ChrootSettingsModel.refreshProcesses(context, distroId = distroId, path = path, forceClearSu = true)
                    }
                }
                procUi = p
            } finally {
                busy = false
            }
        }
    }

    fun killAll() {
        val session = killCoordinator.startSession() ?: return
        busy = true
        killProgress = null
        procUi = procUi.copy(killing = true, scanning = true, hint = "Sending SIGKILL…")
        scope.launch {
            try {
                val p = withContext(Dispatchers.IO) {
                    if (isUniversal) {
                        ChrootSettingsModel.killAllProcesses(
                            context,
                            paths = allPaths,
                            isCancelled = { session.isCancelled },
                            onProgress = { cur, tot ->
                                killProgress = cur to tot
                            }
                        )
                    } else {
                        ChrootSettingsModel.killAllProcesses(context, path = path)
                    }
                }
                procUi = p
                Toast.makeText(
                    context,
                    p.statusLine ?: p.hint,
                    Toast.LENGTH_SHORT
                ).show()
            } finally {
                killCoordinator.endSession(session)
                busy = false
                killProgress = null
            }
        }
    }

    fun cancelKill() {
        if (killCoordinator.requestCancel()) {
            procUi = procUi.copy(hint = "Cancelling kill… waiting for current path")
        }
    }

    LaunchedEffect(distroId) {
        refreshAll(force = false)
    }

    val pageTitle = if (isUniversal) "All chroots" else distro!!.name
    val pageSubtitle = if (isUniversal) {
        "// Root-level Linux — outside app storage"
    } else {
        "// Root-level ${distro!!.name} — outside app storage"
    }

    if (showKillConfirm) {
        AlertDialog(
            onDismissRequest = { showKillConfirm = false },
            title = { Text(if (isUniversal) "Kill all chroot processes?" else "Kill ${distro?.name ?: "chroot"} processes?") },
            text = {
                if (isUniversal) {
                    Text(
                        ChrootSettingsModel.confirmKillCopy(allPaths) +
                            if (procUi.count > 0) "\n\nDetected: ${procUi.count} process(es)." else ""
                    )
                } else {
                    Text(
                        "Sends SIGKILL to every process whose root is $path.\n\n" +
                            "Open chroot shells and guest daemons will die. Host Android processes " +
                            "are not targeted.\n\nRootfs and mounts stay." +
                            if (procUi.count > 0) "\n\nDetected: ${procUi.count} process(es)." else ""
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showKillConfirm = false
                        killAll()
                    }
                ) { Text("Kill all", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { showKillConfirm = false }) { Text("Cancel") }
            }
        )
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            CenterAlignedTopAppBar(
                title = {
                    Text(
                        pageTitle,
                        style = MaterialTheme.typography.headlineSmall,
                        color = MaterialTheme.colorScheme.onBackground
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.Rounded.ArrowBack,
                            contentDescription = "Back",
                            tint = MaterialTheme.colorScheme.onBackground
                        )
                    }
                },
                colors = TopAppBarDefaults.centerAlignedTopAppBarColors(
                    containerColor = Color.Transparent
                )
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = 16.dp, vertical = 12.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            val accent = MaterialTheme.colorScheme.secondary
            val body = MaterialTheme.colorScheme.onBackground
            val muted = MaterialTheme.colorScheme.onSurfaceVariant
            val label = MaterialTheme.colorScheme.onSurfaceVariant

            Text(
                pageSubtitle,
                fontSize = 11.sp,
                fontFamily = FontFamily.Monospace,
                color = muted
            )

            // ── Storage & auto-detect ─────────────────────────────────────
            GlassSettingCard {
                Column(Modifier.padding(18.dp).fillMaxWidth()) {
                    Text(
                        "Storage & manage",
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold,
                        color = accent
                    )
                    Spacer(Modifier.height(8.dp))

                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(
                                Color(0x33FF9800),
                                RoundedCornerShape(8.dp)
                            )
                            .padding(12.dp)
                    ) {
                        Text(
                            "Rootfs is not removed when you uninstall the app. Free space here.",
                            fontSize = 12.sp,
                            color = Color(0xFFFFCC80)
                        )
                    }

                    Spacer(Modifier.height(12.dp))
                    MetaRow("Status") {
                        val installedCount = remember(sizeUi) {
                            GuestStorageCatalog.installableChroots().count {
                                ChrootInfoStore.cachedInstalled(context, it.id)
                            }
                        }
                        val statusText = if (isUniversal) {
                            "$installedCount INSTALLED"
                        } else {
                            ChrootSettingsModel.resolveStatus(
                                installed = sizeUi.installed,
                                dirExists = sizeUi.dirExists
                            )
                        }
                        StatusBadge(
                            text = statusText,
                            ok = if (isUniversal) installedCount > 0 else sizeUi.installed
                        )
                    }
                    Spacer(Modifier.height(8.dp))
                    if (sizeUi.rootOk || sizeUi.measuring) {
                        MetaRow("Root access") {
                            StatusBadge(
                                text = when {
                                    sizeUi.measuring -> "CHECKING"
                                    sizeUi.rootOk -> "GRANTED"
                                    else -> "DENIED"
                                },
                                ok = sizeUi.rootOk && !sizeUi.measuring
                            )
                        }
                        Spacer(Modifier.height(12.dp))
                    }

                    // Size panel
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(
                                MaterialTheme.colorScheme.surface.copy(alpha = 0.55f),
                                RoundedCornerShape(12.dp)
                            )
                            .padding(14.dp)
                    ) {
                        Row(
                            Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                "LINUX STORAGE",
                                fontSize = 11.sp,
                                fontFamily = FontFamily.Monospace,
                                fontWeight = FontWeight.SemiBold,
                                color = label,
                                modifier = Modifier.weight(1f)
                            )
                            IconButton(
                                onClick = { refreshSizeOnly() },
                                enabled = !busy
                            ) {
                                Icon(
                                    Icons.Default.Refresh,
                                    contentDescription = "Refresh size",
                                    tint = accent
                                )
                            }
                        }
                        if (sizeUi.measuring || busy && sizeUi.bytes == null) {
                            LinearProgressIndicator(
                                modifier = Modifier.fillMaxWidth(),
                                color = accent,
                                trackColor = accent.copy(alpha = 0.2f)
                            )
                            Spacer(Modifier.height(8.dp))
                        }
                        val (v, u) = ChrootInfoStore.formatStorageBytes(sizeUi.bytes)
                        Row(verticalAlignment = Alignment.Bottom) {
                            Text(
                                v,
                                fontSize = 32.sp,
                                fontWeight = FontWeight.Bold,
                                fontFamily = FontFamily.Monospace,
                                color = accent,
                                modifier = Modifier.alpha(if (sizeUi.dimmedCache) 0.85f else 1f)
                            )
                            if (u.isNotEmpty()) {
                                Text(
                                    " $u",
                                    fontSize = 14.sp,
                                    fontFamily = FontFamily.Monospace,
                                    color = body,
                                    modifier = Modifier.padding(bottom = 4.dp)
                                )
                            }
                        }
                        Text(
                            sizeUi.hint,
                            fontSize = 11.sp,
                            fontFamily = FontFamily.Monospace,
                            color = muted
                        )
                    }

                    Spacer(Modifier.height(12.dp))
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(
                                MaterialTheme.colorScheme.surface.copy(alpha = 0.45f),
                                RoundedCornerShape(10.dp)
                            )
                            .padding(12.dp)
                    ) {
                        Text(
                            if (isUniversal) "HOST PATHS (12 KNOWN)" else "HOST PATH",
                            fontSize = 10.sp,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.SemiBold,
                            color = label
                        )
                        if (isUniversal) {
                            allPaths.forEach { p ->
                                Text(
                                    "• $p",
                                    fontSize = 11.sp,
                                    fontFamily = FontFamily.Monospace,
                                    color = body
                                )
                            }
                        } else {
                            Text(
                                path,
                                fontSize = 12.sp,
                                fontFamily = FontFamily.Monospace,
                                color = body
                            )
                        }
                    }

                    if (!isUniversal && !sizeUi.installed && onNavigateToInstall != null) {
                        Spacer(Modifier.height(12.dp))
                        Button(
                            onClick = { onNavigateToInstall() },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = accent,
                                contentColor = MaterialTheme.colorScheme.onSecondary
                            ),
                            modifier = Modifier.fillMaxWidth().height(48.dp)
                        ) {
                            Text("Install chroot", fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }

            // ── Processes / kill services ─────────────────────────────────
            GlassSettingCard {
                Column(Modifier.padding(18.dp).fillMaxWidth()) {
                    Text(
                        "Processes",
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.error
                    )
                    Text(
                        if (isUniversal) {
                            "Orphans survive app close across all roots. Kill before uninstall if stuck."
                        } else {
                            "Orphans survive app close (unlike proot). Kill before uninstall if stuck."
                        },
                        fontSize = 12.sp,
                        color = muted,
                        modifier = Modifier.padding(top = 4.dp, bottom = 12.dp)
                    )

                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(
                                MaterialTheme.colorScheme.surface.copy(alpha = 0.55f),
                                RoundedCornerShape(12.dp)
                            )
                            .padding(14.dp)
                    ) {
                        Row(
                            Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                "CHROOT PROCESSES",
                                fontSize = 11.sp,
                                fontFamily = FontFamily.Monospace,
                                fontWeight = FontWeight.SemiBold,
                                color = label,
                                modifier = Modifier.weight(1f)
                            )
                            TextButton(onClick = { scanProcs() }, enabled = !busy) {
                                Text("Scan", color = accent, fontWeight = FontWeight.SemiBold)
                            }
                        }
                        if (procUi.scanning || procUi.killing) {
                            LinearProgressIndicator(
                                modifier = Modifier.fillMaxWidth(),
                                color = MaterialTheme.colorScheme.error,
                                trackColor = MaterialTheme.colorScheme.error.copy(alpha = 0.2f)
                            )
                            Spacer(Modifier.height(8.dp))
                        }
                        Row(verticalAlignment = Alignment.Bottom) {
                            Text(
                                if (procUi.count < 0) "—" else procUi.count.toString(),
                                fontSize = 32.sp,
                                fontWeight = FontWeight.Bold,
                                fontFamily = FontFamily.Monospace,
                                color = if (procUi.count > 0) {
                                    MaterialTheme.colorScheme.error
                                } else {
                                    accent
                                }
                            )
                            Text(
                                " running",
                                fontSize = 14.sp,
                                fontFamily = FontFamily.Monospace,
                                color = body,
                                modifier = Modifier.padding(bottom = 4.dp, start = 6.dp)
                            )
                        }
                        Text(
                            if (isUniversal) "roots=12 known · scan all catalog paths" else "root=$path",
                            fontSize = 10.sp,
                            fontFamily = FontFamily.Monospace,
                            color = muted
                        )
                        Text(
                            procUi.hint,
                            fontSize = 11.sp,
                            fontFamily = FontFamily.Monospace,
                            color = muted,
                            modifier = Modifier.padding(top = 4.dp)
                        )

                        if (procUi.processes.isNotEmpty()) {
                            Spacer(Modifier.height(8.dp))
                            Text(
                                "SAMPLE",
                                fontSize = 10.sp,
                                fontFamily = FontFamily.Monospace,
                                fontWeight = FontWeight.SemiBold,
                                color = label
                            )
                            procUi.processes.take(5).forEach { p ->
                                Text(
                                    "${p.pid}  ${p.comm}",
                                    fontSize = 11.sp,
                                    fontFamily = FontFamily.Monospace,
                                    color = body
                                )
                            }
                            val more = procUi.processes.size - 5
                            if (more > 0) {
                                Text(
                                    "+$more more",
                                    fontSize = 10.sp,
                                    fontFamily = FontFamily.Monospace,
                                    color = muted
                                )
                            }
                        }
                    }

                    Spacer(Modifier.height(12.dp))
                    val isOperationBusy = busy || killCoordinator.isBusy
                    val killEnabled = ChrootSettingsModel.isKillEnabled(
                        rootOk = procUi.rootOk,
                        busy = isOperationBusy
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Button(
                            onClick = { showKillConfirm = true },
                            enabled = killEnabled,
                            colors = ButtonDefaults.buttonColors(
                                containerColor = MaterialTheme.colorScheme.error,
                                contentColor = MaterialTheme.colorScheme.onError,
                                disabledContainerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                                disabledContentColor = muted
                            ),
                            modifier = Modifier.weight(1f).height(48.dp)
                        ) {
                            if (procUi.killing) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(18.dp),
                                    strokeWidth = 2.dp,
                                    color = Color.White
                                )
                                Spacer(Modifier.width(8.dp))
                            }
                            val killText = if (killProgress != null) {
                                "Killing (${killProgress!!.first}/${killProgress!!.second})…"
                            } else if (isUniversal) {
                                "Kill all across roots"
                            } else {
                                "Kill all chroot processes"
                            }
                            Text(killText, fontWeight = FontWeight.Bold)
                        }

                        if (procUi.killing && isUniversal) {
                            val isCancelling = killCoordinator.state == ChrootKillCoordinator.State.CANCEL_REQUESTED
                            Button(
                                onClick = { cancelKill() },
                                enabled = !isCancelling,
                                colors = ButtonDefaults.outlinedButtonColors(
                                    contentColor = MaterialTheme.colorScheme.error
                                ),
                                modifier = Modifier.height(48.dp)
                            ) {
                                Text(if (isCancelling) "Cancelling…" else "Cancel")
                            }
                        }
                    }

                    procUi.statusLine?.let {
                        Text(
                            it,
                            fontSize = 11.sp,
                            fontFamily = FontFamily.Monospace,
                            color = muted,
                            modifier = Modifier.padding(top = 8.dp)
                        )
                    }
                }
            }

            Button(
                onClick = { refreshAll(force = true) },
                enabled = !busy,
                colors = ButtonDefaults.buttonColors(
                    containerColor = accent,
                    contentColor = MaterialTheme.colorScheme.onSecondary,
                    disabledContainerColor = com.ivarna.fluxlinux.ui.theme.FluxSwitchUncheckedTrack,
                    disabledContentColor = com.ivarna.fluxlinux.ui.theme.FluxBodyMuted
                ),
                modifier = Modifier.fillMaxWidth().height(48.dp)
            ) {
                Text("Refresh all", fontWeight = FontWeight.Bold)
            }

            Spacer(Modifier.height(48.dp))
        }
    }
}
