package com.ivarna.fluxlinux.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Article
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.DesktopWindows
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ivarna.fluxlinux.core.data.DistroRepository
import com.ivarna.fluxlinux.core.desktop.DesktopSession
import com.ivarna.fluxlinux.ui.theme.FluxAccentCyan

@Composable
fun ActiveDesktopCard(
    session: DesktopSession,
    onOpen: () -> Unit,
    onStop: () -> Unit,
    onLogs: () -> Unit,
    modifier: Modifier = Modifier
) {
    val colors = MaterialTheme.colorScheme
    val cardShape = RoundedCornerShape(16.dp)
    val isRunning = session.phase == DesktopSession.Phase.Running
    val statusColor = if (isRunning) Color(0xFF4CAF50) else Color(0xFFFFA000)
    val deName = if (session.type == DesktopSession.Type.KDE) "KDE Plasma" else "XFCE4"
    val titleText = if (isRunning) {
        "${session.distroName} desktop is running"
    } else {
        "Starting ${session.distroName} desktop…"
    }
    val subtitleText = "$deName on :0 — stop this session before starting another desktop."
    val a11yDesc = "$titleText. $subtitleText"

    val distro = DistroRepository.supportedDistros.find { it.id == session.distroId }
    val iconRes = distro?.iconRes

    Box(
        modifier = modifier
            .fillMaxWidth()
            .clip(cardShape)
            .background(colors.surface.copy(alpha = 0.88f))
            .border(BorderStroke(1.dp, Color.White.copy(alpha = 0.20f)), cardShape)
            .semantics(mergeDescendants = true) {
                contentDescription = a11yDesc
            }
    ) {
        // Leading status indicator bar
        Box(
            modifier = Modifier
                .align(Alignment.CenterStart)
                .matchParentSize()
        ) {
            Box(
                modifier = Modifier
                    .align(Alignment.CenterStart)
                    .fillMaxHeight()
                    .width(4.dp)
                    .background(statusColor)
            )
        }

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 16.dp, top = 14.dp, end = 16.dp, bottom = 14.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(colors.surfaceVariant.copy(alpha = 0.6f))
                        .border(BorderStroke(1.dp, colors.outlineVariant.copy(alpha = 0.3f)), RoundedCornerShape(10.dp)),
                    contentAlignment = Alignment.Center
                ) {
                    if (iconRes != null) {
                        Image(
                            painter = painterResource(id = iconRes),
                            contentDescription = null,
                            modifier = Modifier.size(28.dp)
                        )
                    } else {
                        Icon(
                            imageVector = Icons.Filled.DesktopWindows,
                            contentDescription = null,
                            tint = colors.secondary,
                            modifier = Modifier.size(24.dp)
                        )
                    }
                }

                Spacer(modifier = Modifier.width(12.dp))

                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = titleText,
                        color = colors.onSurface,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 16.sp,
                        maxLines = 1
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = subtitleText,
                        color = colors.onSurfaceVariant.copy(alpha = 0.90f),
                        fontSize = 12.sp,
                        lineHeight = 16.sp
                    )
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                CompactAction(
                    label = "Open",
                    icon = Icons.AutoMirrored.Filled.OpenInNew,
                    onClick = onOpen,
                    containerColor = FluxAccentCyan,
                    contentColor = Color.Black,
                    modifier = Modifier.weight(1f)
                )
                CompactAction(
                    label = "Stop",
                    icon = Icons.Default.Stop,
                    onClick = onStop,
                    containerColor = com.ivarna.fluxlinux.ui.theme.FluxDanger,
                    contentColor = Color.White,
                    modifier = Modifier.weight(0.85f)
                )
                CompactAction(
                    label = "Logs",
                    icon = Icons.AutoMirrored.Filled.Article,
                    onClick = onLogs,
                    containerColor = colors.secondaryContainer,
                    contentColor = colors.onSecondaryContainer,
                    modifier = Modifier.weight(0.85f)
                )
            }
        }
    }
}
