package com.example.data.model

enum class PingStatus {
    IDLE,
    PINGING,
    SUCCESS,
    TIMEOUT,
    ERROR
}

data class VpnServer(
    val id: String,
    val name: String,
    val rawConfig: String,
    val protocol: String,
    val host: String,
    val port: Int,
    val auth: String = "",
    val sni: String = "",
    val alpn: String = "",
    val pingMs: Long? = null,
    val pingStatus: PingStatus = PingStatus.IDLE,
    val lastPingTimestamp: Long = 0L
) {
    val displayProtocol: String
        get() = protocol.uppercase()

    val isOnline: Boolean
        get() = pingStatus == PingStatus.SUCCESS && (pingMs ?: -1) > 0
}
