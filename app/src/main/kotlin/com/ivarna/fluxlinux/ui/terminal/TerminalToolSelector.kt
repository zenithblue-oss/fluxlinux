package com.ivarna.fluxlinux.ui.terminal

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AdminPanelSettings
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ivarna.fluxlinux.core.root.RootShell
import com.ivarna.fluxlinux.core.terminal.TerminalShellCardUi
import com.ivarna.fluxlinux.core.terminal.TerminalShellCatalog
import com.ivarna.fluxlinux.ui.components.MethodChip
import com.ivarna.fluxlinux.ui.components.MethodTab
import com.ivarna.fluxlinux.ui.components.MethodTabs
import com.ivarna.fluxlinux.ui.theme.FluxAccentCyan

private val MethodProot = Color(0xFF00B8D4)
private val MethodChroot = Color(0xFFE040FB)
private val MethodHost = Color(0xFFF5E6CA)
private val RowShape = RoundedCornerShape(14.dp)

private data class TerminalMethodGroup(
    val title: String,
    val subtitle: String,
    val method: String,
    val rows: List<TerminalDistroRow>
)

private data class TerminalDistroRow(
    val method: String,
    val distroId: String?,
    val iconRes: Int,
    val name: String,
    val user: TerminalShellCardUi?,
    val root: TerminalShellCardUi?
)

/**
 * Terminal empty-state selector.
 *
 * Catalog cards stay 1:1 with [TerminalShellCatalog] (proot / chroot × user / root).
 * The UI groups them into compact method sections so User and Root stay on the
 * same row and nothing is clipped under the bottom nav.
 *
 * @param onOpen (type, title, method, distroId) — opens the session (host prepare by caller)
 */
@Composable
fun TerminalToolSelector(
    onOpen: (type: String, title: String, method: String, distroId: String?) -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    var rootAvailable by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        RootShell.probeRootAvailable { ok -> rootAvailable = ok }
    }

    val sections = remember(context, rootAvailable) {
        TerminalShellCatalog.sections(
            context,
            TerminalShellCatalog.availability(context, rootAvailable)
        )
    }
    val groups = remember(sections) { groupCatalogByMethod(sections.flatMap { it.cards }) }
    var methodTab by remember { mutableStateOf(MethodTab.PROOT) }
    val activeMethod = if (methodTab == MethodTab.CHROOT) "chroot" else "proot"
    val activeGroup = groups.firstOrNull { it.method == activeMethod }
    val hostGroup = groups.firstOrNull { it.method == "host" }
    val prootCount = groups.firstOrNull { it.method == "proot" }?.rows?.size ?: 0
    val chrootCount = groups.firstOrNull { it.method == "chroot" }?.rows?.size ?: 0
    // Root granted (probe above) + chroot installed: warm helper + BusyBox off main
    // so the first chroot open skips su staging/probes. Never prompts non-root users.
    val warmChroot = rootAvailable && chrootCount > 0
    LaunchedEffect(warmChroot) {
        if (warmChroot) {
            val app = context.applicationContext
            Thread { RootShell.ensureChrootHelper(app); RootShell.resolveBusyBox() }.start()
        }
    }

    Column(modifier = modifier.fillMaxSize()) {
        Spacer(Modifier.height(4.dp))
        MethodTabs(
            selected = methodTab,
            onSelected = { methodTab = it },
            prootCount = prootCount,
            chrootCount = chrootCount
        )
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            if (activeGroup != null) {
                items(
                    activeGroup.rows,
                    key = { "row-${it.method}-${it.distroId ?: it.name}" }
                ) { row ->
                    TerminalDistroRowCard(
                        row = row,
                        onOpen = onOpen
                    )
                }
            }
            if (hostGroup != null) {
                item(key = "header-host") {
                    TerminalSectionHeader(hostGroup)
                }
                items(
                    hostGroup.rows,
                    key = { "row-host-${it.name}" }
                ) { row ->
                    TerminalDistroRowCard(
                        row = row,
                        onOpen = onOpen
                    )
                }
            }
        }
    }
}

private fun groupCatalogByMethod(cards: List<TerminalShellCardUi>): List<TerminalMethodGroup> {
    fun rowsFor(method: String): List<TerminalDistroRow> {
        return cards
            .filter { it.def.method == method }
            .groupBy { it.def.distroId ?: it.def.label }
            .map { (_, group) ->
                val sample = group.first()
                TerminalDistroRow(
                    method = sample.def.method,
                    distroId = sample.def.distroId,
                    iconRes = sample.def.iconRes,
                    name = displayName(sample),
                    user = group.find { it.def.type == "shell" || it.def.type == "host" },
                    root = group.find { it.def.type == "shell-root" }
                )
            }
    }
    return listOf(
        TerminalMethodGroup("PROOT", "User-space guests", "proot", rowsFor("proot")),
        TerminalMethodGroup("CHROOT", "Requires device root", "chroot", rowsFor("chroot")),
        TerminalMethodGroup("HOST", "Embedded Termux", "host", rowsFor("host"))
    ).filter { it.rows.isNotEmpty() }
}

private fun displayName(card: TerminalShellCardUi): String {
    return card.def.label
        .replace(" Shell Rooted", "")
        .replace(" Rooted", "")
        .replace(" Chroot Shell", "")
        .replace(" Chroot", "")
        .replace(" Shell", "")
        .trim()
        .ifBlank { card.def.label }
}

@Composable
private fun TerminalSectionHeader(group: TerminalMethodGroup, modifier: Modifier = Modifier) {
    val accent = when (group.method) {
        "chroot" -> MethodChroot
        "host" -> MethodHost
        else -> MethodProot
    }
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(top = 8.dp, bottom = 2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .width(3.dp)
                .height(14.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(accent)
        )
        Spacer(Modifier.width(8.dp))
        Text(
            text = group.title,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Bold,
            color = accent,
            letterSpacing = 1.2.sp
        )
        Spacer(Modifier.weight(1f))
        Text(
            text = group.subtitle,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun TerminalDistroRowCard(
    row: TerminalDistroRow,
    onOpen: (type: String, title: String, method: String, distroId: String?) -> Unit,
    modifier: Modifier = Modifier
) {
    val methodColor = when (row.method) {
        "chroot" -> MethodChroot
        "host" -> MethodHost
        else -> MethodProot
    }
    val enabled = (row.user?.enabled == true) || (row.root?.enabled == true)
    val reason = row.user?.disabledReason ?: row.root?.disabledReason
    val colors = MaterialTheme.colorScheme

    Box(
        modifier = modifier
            .fillMaxWidth()
            .clip(RowShape)
            .background(
                if (enabled) colors.surface.copy(alpha = 0.78f)
                else colors.surfaceVariant.copy(alpha = 0.38f)
            )
            .border(
                BorderStroke(
                    1.dp,
                    if (enabled) methodColor.copy(alpha = 0.35f) else colors.outlineVariant
                ),
                RowShape
            )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(IntrinsicSize.Min)
        ) {
            Box(
                modifier = Modifier
                    .width(3.dp)
                    .fillMaxHeight()
                    .background(if (enabled) methodColor else methodColor.copy(alpha = 0.3f))
            )
            Row(
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = 10.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Image(
                    painter = painterResource(id = row.iconRes),
                    contentDescription = row.name,
                    modifier = Modifier
                        .size(36.dp)
                        .alpha(if (enabled) 1f else 0.42f)
                )
                Spacer(Modifier.width(10.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = row.name,
                            color = if (enabled) colors.onSurface else colors.onSurfaceVariant,
                            fontSize = 15.sp,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f, fill = false)
                        )
                        Spacer(Modifier.width(6.dp))
                        MethodChip(
                            label = row.method.uppercase(),
                            color = methodColor
                        )
                    }
                    if (!enabled && reason != null) {
                        Text(
                            text = reason,
                            color = colors.error,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Medium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
                Spacer(Modifier.width(8.dp))
                if (row.root != null) {
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        if (row.user != null) {
                            SessionAction(
                                label = "User",
                                icon = Icons.Default.Person,
                                enabled = row.user.enabled,
                                containerColor = FluxAccentCyan,
                                contentColor = Color.Black,
                                onClick = { openCard(row.user, onOpen) }
                            )
                        }
                        SessionAction(
                            label = "Root",
                            icon = Icons.Default.AdminPanelSettings,
                            enabled = row.root.enabled,
                            containerColor = Color(0xFFB71C1C),
                            contentColor = Color.White,
                            onClick = { openCard(row.root, onOpen) }
                        )
                    }
                } else if (row.user != null) {
                    SessionAction(
                        label = "Open",
                        icon = Icons.Default.Terminal,
                        enabled = row.user.enabled,
                        containerColor = MethodHost,
                        contentColor = Color(0xFF1A1C1E),
                        onClick = { openCard(row.user, onOpen) }
                    )
                }
            }
        }
    }
}

private fun openCard(
    card: TerminalShellCardUi,
    onOpen: (type: String, title: String, method: String, distroId: String?) -> Unit
) {
    if (!card.enabled) return
    onOpen(card.def.type, card.def.label, card.def.method, card.def.distroId)
}

@Composable
private fun SessionAction(
    label: String,
    icon: ImageVector,
    enabled: Boolean,
    containerColor: Color,
    contentColor: Color,
    onClick: () -> Unit
) {
    Button(
        onClick = onClick,
        enabled = enabled,
        colors = ButtonDefaults.buttonColors(
            containerColor = containerColor,
            contentColor = contentColor,
            disabledContainerColor = containerColor.copy(alpha = 0.28f),
            disabledContentColor = contentColor.copy(alpha = 0.45f)
        ),
        shape = RoundedCornerShape(10.dp),
        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 0.dp),
        modifier = Modifier.height(36.dp)
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            modifier = Modifier.size(15.dp)
        )
        Spacer(Modifier.width(4.dp))
        Text(
            text = label,
            fontWeight = FontWeight.Bold,
            fontSize = 12.sp,
            maxLines = 1
        )
    }
}
