package com.example.vpn

import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import com.example.data.model.VpnServer
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

object VpnController {

    private val _uiState = MutableStateFlow(VpnUiState())
    val uiState: StateFlow<VpnUiState> = _uiState.asStateFlow()

    fun updateStatus(status: ConnectionStatus, server: VpnServer? = null, error: String? = null) {
        _uiState.update { current ->
            current.copy(
                status = status,
                activeServer = server ?: if (status == ConnectionStatus.DISCONNECTED) null else current.activeServer,
                errorMessage = error
            )
        }
    }

    fun updateStats(durationSeconds: Long, bytesIn: Long, bytesOut: Long) {
        _uiState.update { current ->
            current.copy(
                stats = VpnSessionStats(
                    durationSeconds = durationSeconds,
                    bytesIn = bytesIn,
                    bytesOut = bytesOut
                )
            )
        }
    }

    fun startService(context: Context, server: VpnServer) {
        updateStatus(ConnectionStatus.CONNECTING, server = server)
        val intent = Intent(context, SkylistVpnService::class.java).apply {
            action = SkylistVpnService.ACTION_CONNECT
            putExtra(SkylistVpnService.EXTRA_SERVER_NAME, server.name)
            putExtra(SkylistVpnService.EXTRA_SERVER_HOST, server.host)
            putExtra(SkylistVpnService.EXTRA_SERVER_PORT, server.port)
            putExtra(SkylistVpnService.EXTRA_SERVER_PROTOCOL, server.protocol)
            putExtra(SkylistVpnService.EXTRA_SERVER_RAW, server.rawConfig)
        }
        ContextCompat.startForegroundService(context, intent)
    }

    fun stopService(context: Context) {
        updateStatus(ConnectionStatus.DISCONNECTING)
        val intent = Intent(context, SkylistVpnService::class.java).apply {
            action = SkylistVpnService.ACTION_DISCONNECT
        }
        ContextCompat.startForegroundService(context, intent)
    }
}
