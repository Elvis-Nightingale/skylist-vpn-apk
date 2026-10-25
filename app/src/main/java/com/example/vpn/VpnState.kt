package com.example.vpn

import com.example.data.model.VpnServer

enum class ConnectionStatus {
    DISCONNECTED,
    CONNECTING,
    CONNECTED,
    DISCONNECTING
}

data class VpnSessionStats(
    val durationSeconds: Long = 0L,
    val bytesIn: Long = 0L,
    val bytesOut: Long = 0L
) {
    val formattedDuration: String
        get() {
            val hours = durationSeconds / 3600
            val minutes = (durationSeconds % 3600) / 60
            val seconds = durationSeconds % 60
            return if (hours > 0) {
                String.format("%02d:%02d:%02d", hours, minutes, seconds)
            } else {
                String.format("%02d:%02d", minutes, seconds)
            }
        }

    val formattedDownload: String
        get() = formatBytes(bytesIn)

    val formattedUpload: String
        get() = formatBytes(bytesOut)

    private fun formatBytes(bytes: Long): String {
        return when {
            bytes >= 1024 * 1024 * 1024 -> String.format("%.2f GB", bytes.toDouble() / (1024 * 1024 * 1024))
            bytes >= 1024 * 1024 -> String.format("%.1f MB", bytes.toDouble() / (1024 * 1024))
            bytes >= 1024 -> String.format("%.0f KB", bytes.toDouble() / 1024)
            else -> "$bytes B"
        }
    }
}

data class VpnUiState(
    val status: ConnectionStatus = ConnectionStatus.DISCONNECTED,
    val activeServer: VpnServer? = null,
    val stats: VpnSessionStats = VpnSessionStats(),
    val errorMessage: String? = null
)
