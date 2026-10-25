package com.example.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.model.PingStatus
import com.example.data.model.VpnServer
import com.example.ui.theme.CyanAccent
import com.example.ui.theme.NavySurface
import com.example.ui.theme.NavySurfaceHighlight
import com.example.ui.theme.NavySurfaceVariant
import com.example.ui.theme.PingBad
import com.example.ui.theme.PingGood
import com.example.ui.theme.PingMedium
import com.example.ui.theme.PingSlow

@Composable
fun ServerItemCard(
    server: VpnServer,
    isSelected: Boolean,
    onSelect: () -> Unit,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 58.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(if (isSelected) NavySurfaceVariant else NavySurface)
            .border(
                width = if (isSelected) 1.5.dp else 1.dp,
                color = if (isSelected) CyanAccent else Color(0xFF1B304B),
                shape = RoundedCornerShape(16.dp)
            )
            .clickable(onClick = onSelect)
            .testTag("server_item_${server.id}")
            .padding(horizontal = 16.dp, vertical = 12.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            // Radio circle indicator + Server Name
            Row(
                modifier = Modifier.weight(1f),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(22.dp)
                        .clip(CircleShape)
                        .background(if (isSelected) CyanAccent else NavySurfaceHighlight)
                        .border(
                            width = 1.dp,
                            color = if (isSelected) CyanAccent else Color(0xFF334E68),
                            shape = CircleShape
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    if (isSelected) {
                        Icon(
                            imageVector = Icons.Default.Check,
                            contentDescription = "Selected",
                            tint = Color(0xFF001F3F),
                            modifier = Modifier.size(14.dp)
                        )
                    }
                }

                Spacer(modifier = Modifier.width(14.dp))

                // Server Name (Strictly from after # in config)
                Text(
                    text = server.name,
                    fontSize = 15.sp,
                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                    color = if (isSelected) Color.White else MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            Spacer(modifier = Modifier.width(10.dp))

            // Automatically updating network ping badge
            PingBadge(
                status = server.pingStatus,
                pingMs = server.pingMs
            )
        }
    }
}

@Composable
fun PingBadge(
    status: PingStatus,
    pingMs: Long?,
    modifier: Modifier = Modifier
) {
    val (label, bgColor, textColor) = when (status) {
        PingStatus.PINGING -> Triple("Pinging", Color(0xFF102A43), CyanAccent)
        PingStatus.SUCCESS -> {
            val ms = pingMs ?: 0L
            when {
                ms < 150 -> Triple("$ms ms", Color(0xFF063323), PingGood)
                ms < 350 -> Triple("$ms ms", Color(0xFF3B2904), PingMedium)
                else -> Triple("$ms ms", Color(0xFF3D1B06), PingSlow)
            }
        }
        PingStatus.TIMEOUT -> Triple("Timeout", Color(0xFF3A0D15), PingBad)
        PingStatus.ERROR -> Triple("Offline", Color(0xFF3A0D15), PingBad)
        PingStatus.IDLE -> Triple("Ready", Color(0xFF162942), MaterialTheme.colorScheme.onSurfaceVariant)
    }

    Box(
        modifier = modifier
            .clip(RoundedCornerShape(8.dp))
            .background(bgColor)
            .border(width = 0.5.dp, color = textColor.copy(alpha = 0.4f), shape = RoundedCornerShape(8.dp))
            .padding(horizontal = 10.dp, vertical = 5.dp),
        contentAlignment = Alignment.Center
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (status == PingStatus.PINGING) {
                CircularProgressIndicator(
                    modifier = Modifier.size(10.dp),
                    color = textColor,
                    strokeWidth = 1.5.dp
                )
                Spacer(modifier = Modifier.width(6.dp))
            } else {
                Box(
                    modifier = Modifier
                        .size(6.dp)
                        .clip(CircleShape)
                        .background(textColor)
                )
                Spacer(modifier = Modifier.width(6.dp))
            }

            Text(
                text = label,
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
                color = textColor
            )
        }
    }
}
