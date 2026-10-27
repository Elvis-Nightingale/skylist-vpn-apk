package com.example.ui.viewmodel

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.net.VpnService
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.model.PingStatus
import com.example.data.model.VpnServer
import com.example.data.repository.ServerRepository
import com.example.vpn.ConnectionStatus
import com.example.vpn.VpnController
import com.example.vpn.VpnUiState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class VpnViewModel(
    private val repository: ServerRepository = ServerRepository()
) : ViewModel() {

    companion object {
        const val YOUTUBE_CHANNEL_URL = "https://www.youtube.com/@SkylerHeyes"
        private const val AUTO_UPDATE_INTERVAL_MS = 8000L // Auto-check GitHub every 8 seconds with cache-busting
        private const val AUTO_PING_INTERVAL_MS = 10000L  // Auto-ping servers every 10 seconds
    }

    val vpnUiState: StateFlow<VpnUiState> = VpnController.uiState

    private val _servers = MutableStateFlow<List<VpnServer>>(emptyList())
    val servers: StateFlow<List<VpnServer>> = _servers.asStateFlow()

    private val _selectedServer = MutableStateFlow<VpnServer?>(null)
    val selectedServer: StateFlow<VpnServer?> = _selectedServer.asStateFlow()

    private val _isLoadingServers = MutableStateFlow(false)
    val isLoadingServers: StateFlow<Boolean> = _isLoadingServers.asStateFlow()

    private val _isPinging = MutableStateFlow(false)
    val isPinging: StateFlow<Boolean> = _isPinging.asStateFlow()

    private val _fetchError = MutableStateFlow<String?>(null)
    val fetchError: StateFlow<String?> = _fetchError.asStateFlow()

    private val _lastSyncTimestamp = MutableStateFlow(System.currentTimeMillis())
    val lastSyncTimestamp: StateFlow<Long> = _lastSyncTimestamp.asStateFlow()

    private var autoUpdateJob: Job? = null
    private var autoPingJob: Job? = null

    init {
        loadServers(showLoading = true)
        startAutoUpdateLoop()
        startAutoPingLoop()
    }

    fun loadServers(showLoading: Boolean = false) {
        viewModelScope.launch(Dispatchers.IO) {
            if (showLoading) {
                _isLoadingServers.value = true
            }
            val result = repository.fetchServersFromGitHub()
            result.onSuccess { fetchedList ->
                _isLoadingServers.value = false
                _fetchError.value = null
                _lastSyncTimestamp.value = System.currentTimeMillis()

                val currentRawConfigs = _servers.value.map { it.rawConfig }
                val newRawConfigs = fetchedList.map { it.rawConfig }

                // Check if list changed or if this is the initial load
                if (_servers.value.isEmpty() || currentRawConfigs != newRawConfigs) {
                    _servers.value = fetchedList

                    // Keep selected server or select first
                    val currentSelected = _selectedServer.value
                    if (currentSelected == null && fetchedList.isNotEmpty()) {
                        _selectedServer.value = fetchedList.first()
                    } else if (currentSelected != null) {
                        val matching = fetchedList.find { it.host == currentSelected.host && it.port == currentSelected.port }
                        _selectedServer.value = matching ?: fetchedList.firstOrNull()
                    }

                    // Trigger ping immediately on newly fetched list
                    triggerPingAll()
                }
            }.onFailure { error ->
                _isLoadingServers.value = false
                if (_servers.value.isEmpty()) {
                    _fetchError.value = error.localizedMessage ?: "Failed to load servers from GitHub"
                }
            }
        }
    }

    private fun startAutoUpdateLoop() {
        autoUpdateJob?.cancel()
        autoUpdateJob = viewModelScope.launch(Dispatchers.IO) {
            while (isActive) {
                delay(AUTO_UPDATE_INTERVAL_MS)
                loadServers(showLoading = false)
            }
        }
    }

    private fun startAutoPingLoop() {
        autoPingJob?.cancel()
        autoPingJob = viewModelScope.launch(Dispatchers.IO) {
            while (isActive) {
                delay(AUTO_PING_INTERVAL_MS)
                if (_servers.value.isNotEmpty() && !_isPinging.value) {
                    triggerPingAll()
                }
            }
        }
    }

    fun selectServer(server: VpnServer) {
        _selectedServer.value = server
    }

    fun triggerPingAll() {
        val currentList = _servers.value
        if (currentList.isEmpty()) return

        viewModelScope.launch(Dispatchers.IO) {
            _isPinging.value = true

            _servers.update { list ->
                list.map { it.copy(pingStatus = PingStatus.PINGING) }
            }

            val updatedServers = repository.pingAllServers(currentList)
            _servers.value = updatedServers

            _selectedServer.value?.let { selected ->
                updatedServers.find { it.id == selected.id }?.let { updated ->
                    _selectedServer.value = updated
                }
            }

            _isPinging.value = false
        }
    }

    fun onConnectButtonClicked(context: Context, prepareLauncher: (Intent) -> Unit) {
        val currentStatus = vpnUiState.value.status

        if (currentStatus == ConnectionStatus.CONNECTED || currentStatus == ConnectionStatus.CONNECTING) {
            VpnController.stopService(context)
            return
        }

        val serverToConnect = _selectedServer.value
        if (serverToConnect == null) {
            _fetchError.value = "Please select a server to connect"
            return
        }

        val prepareIntent = VpnService.prepare(context)
        if (prepareIntent != null) {
            prepareLauncher(prepareIntent)
        } else {
            VpnController.startService(context, serverToConnect)
        }
    }

    fun onVpnPermissionResult(isGranted: Boolean, context: Context) {
        if (isGranted) {
            val server = _selectedServer.value
            if (server != null) {
                VpnController.startService(context, server)
            }
        } else {
            _fetchError.value = "VPN permission was denied"
        }
    }

    fun openYouTubeChannel(context: Context) {
        try {
            val appIntent = Intent(Intent.ACTION_VIEW, Uri.parse(YOUTUBE_CHANNEL_URL)).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            context.startActivity(appIntent)
        } catch (_: Exception) {
            try {
                val browserIntent = Intent(Intent.ACTION_VIEW, Uri.parse(YOUTUBE_CHANNEL_URL)).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                }
                context.startActivity(browserIntent)
            } catch (_: Exception) {}
        }
    }

    override fun onCleared() {
        autoUpdateJob?.cancel()
        autoPingJob?.cancel()
        super.onCleared()
    }
}
