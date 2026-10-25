package com.example.ui.components

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.ui.theme.BluePrimary
import com.example.ui.theme.CyanAccent
import com.example.ui.theme.NavySurface
import com.example.ui.theme.PingGood
import com.example.vpn.ConnectionStatus

@Composable
fun VpnPowerButton(
    status: ConnectionStatus,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val isConnected = status == ConnectionStatus.CONNECTED
    val isConnecting = status == ConnectionStatus.CONNECTING || status == ConnectionStatus.DISCONNECTING

    val infiniteTransition = rememberInfiniteTransition(label = "pulse")
    val pulseScale by infiniteTransition.animateFloat(
        initialValue = 1f,
        targetValue = if (isConnected || isConnecting) 1.14f else 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(1400, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulseScale"
    )
    val pulseAlpha by infiniteTransition.animateFloat(
        initialValue = 0.5f,
        targetValue = if (isConnected || isConnecting) 0.1f else 0f,
        animationSpec = infiniteRepeatable(
            animation = tween(1400, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulseAlpha"
    )

    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(
            modifier = Modifier.size(190.dp),
            contentAlignment = Alignment.Center
        ) {
            // Outer glowing animated pulse ring
            if (isConnected || isConnecting) {
                Box(
                    modifier = Modifier
                        .size(180.dp)
                        .scale(pulseScale)
                        .clip(CircleShape)
                        .background(
                            if (isConnected) CyanAccent.copy(alpha = pulseAlpha)
                            else BluePrimary.copy(alpha = pulseAlpha)
                        )
                )
            }

            // Middle ring
            Box(
                modifier = Modifier
                    .size(156.dp)
                    .clip(CircleShape)
                    .background(
                        Brush.radialGradient(
                            colors = listOf(
                                if (isConnected) CyanAccent.copy(alpha = 0.25f) else Color(0xFF162942),
                                Color(0xFF0C1829)
                            )
                        )
                    )
                    .border(
                        width = 2.dp,
                        brush = Brush.linearGradient(
                            colors = if (isConnected) {
                                listOf(CyanAccent, BluePrimary)
                            } else {
                                listOf(Color(0xFF264166), Color(0xFF112238))
                            }
                        ),
                        shape = CircleShape
                    )
            )

            // Inner main power button
            Box(
                modifier = Modifier
                    .size(126.dp)
                    .shadow(
                        elevation = if (isConnected) 16.dp else 4.dp,
                        shape = CircleShape,
                        spotColor = if (isConnected) CyanAccent else Color.Black
                    )
                    .clip(CircleShape)
                    .background(
                        if (isConnected) {
                            Brush.verticalGradient(
                                listOf(CyanAccent, BluePrimary)
                            )
                        } else {
                            Brush.verticalGradient(
                                listOf(Color(0xFF1C314C), NavySurface)
                            )
                        }
                    )
                    .border(
                        width = 1.5.dp,
                        color = if (isConnected) Color.White.copy(alpha = 0.5f) else Color(0xFF2B4466),
                        shape = CircleShape
                    )
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = ripple(bounded = true, radius = 64.dp),
                        onClick = onClick
                    )
                    .testTag("connect_vpn_button"),
                contentAlignment = Alignment.Center
            ) {
                if (isConnecting) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(48.dp),
                        color = Color.White,
                        strokeWidth = 3.dp
                    )
                } else {
                    Icon(
                        imageVector = Icons.Default.PowerSettingsNew,
                        contentDescription = "Power VPN Button",
                        tint = if (isConnected) Color(0xFF001F3F) else BluePrimary,
                        modifier = Modifier.size(52.dp)
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(14.dp))

        val statusText = when (status) {
            ConnectionStatus.DISCONNECTED -> "Tap to Connect"
            ConnectionStatus.CONNECTING -> "Connecting to VPN..."
            ConnectionStatus.CONNECTED -> "VPN Connected • Protected"
            ConnectionStatus.DISCONNECTING -> "Disconnecting..."
        }

        val statusColor = when (status) {
            ConnectionStatus.CONNECTED -> PingGood
            ConnectionStatus.CONNECTING, ConnectionStatus.DISCONNECTING -> CyanAccent
            ConnectionStatus.DISCONNECTED -> MaterialTheme.colorScheme.onSurfaceVariant
        }

        Text(
            text = statusText,
            color = statusColor,
            fontSize = 15.sp,
            fontWeight = FontWeight.SemiBold
        )
    }
}
