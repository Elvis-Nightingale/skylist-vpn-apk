package com.example.data.repository

import com.example.data.model.PingStatus
import com.example.data.model.VpnServer
import com.example.data.parser.V2RayConfigParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import okhttp3.CacheControl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.net.ConnectException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.TimeUnit

class ServerRepository(
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(8, TimeUnit.SECONDS)
        .build()
) {
    companion object {
        const val GITHUB_CONFIG_URL =
            "https://raw.githubusercontent.com/SuperHackerYT/vpntest/refs/heads/main/vless%20vmess%20ss%20hysteria2%20trojan%20configs.txt"
    }

    suspend fun fetchServersFromGitHub(): Result<List<VpnServer>> = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder()
                .url(GITHUB_CONFIG_URL)
                .cacheControl(CacheControl.Builder().noCache().noStore().build())
                .header("User-Agent", "SkylistVPN-Android/1.0")
                .header("Cache-Control", "no-cache, no-store, must-revalidate")
                .header("Pragma", "no-cache")
                .build()

            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    return@withContext Result.failure(IOException("Server error HTTP ${response.code}"))
                }
                val body = response.body?.string() ?: ""
                val parsed = V2RayConfigParser.parseConfigList(body)
                Result.success(parsed)
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun measurePing(server: VpnServer): VpnServer = withContext(Dispatchers.IO) {
        var pingSuccess = false
        var measuredLatency = -1L

        // Primary stage: Probe specified server.host:server.port
        // If Socket connects OR throws ConnectException (TCP RST from the remote host),
        // the host is actively reachable on the IP network and the RTT is measured!
        try {
            val socket = Socket()
            val t0 = System.currentTimeMillis()
            try {
                socket.connect(InetSocketAddress(server.host, server.port), 1500)
                measuredLatency = (System.currentTimeMillis() - t0).coerceAtLeast(1L)
                pingSuccess = true
            } catch (e: ConnectException) {
                measuredLatency = (System.currentTimeMillis() - t0).coerceAtLeast(1L)
                pingSuccess = true
            } finally {
                try { socket.close() } catch (_: Exception) {}
            }
        } catch (_: Exception) {}

        // Secondary stage: If server.port timed out (typical for UDP-only Hysteria2 QUIC servers on port 443),
        // probe fallback ports 80/8080 on the same host to determine true network round-trip time.
        if (!pingSuccess) {
            val fallbackPorts = intArrayOf(80, 8080)
            for (port in fallbackPorts) {
                try {
                    val socket = Socket()
                    val t0 = System.currentTimeMillis()
                    try {
                        socket.connect(InetSocketAddress(server.host, port), 1200)
                        measuredLatency = (System.currentTimeMillis() - t0).coerceAtLeast(1L)
                        pingSuccess = true
                        break
                    } catch (e: ConnectException) {
                        measuredLatency = (System.currentTimeMillis() - t0).coerceAtLeast(1L)
                        pingSuccess = true
                        break
                    } finally {
                        try { socket.close() } catch (_: Exception) {}
                    }
                } catch (_: Exception) {}
            }
        }

        // Tertiary stage: Host ICMP reachability probe
        if (!pingSuccess) {
            try {
                val t0 = System.currentTimeMillis()
                val addr = InetAddress.getByName(server.host)
                if (addr.isReachable(1000)) {
                    measuredLatency = (System.currentTimeMillis() - t0).coerceAtLeast(1L)
                    pingSuccess = true
                }
            } catch (_: Exception) {}
        }

        if (pingSuccess && measuredLatency > 0) {
            server.copy(
                pingMs = measuredLatency,
                pingStatus = PingStatus.SUCCESS,
                lastPingTimestamp = System.currentTimeMillis()
            )
        } else {
            server.copy(
                pingMs = null,
                pingStatus = PingStatus.TIMEOUT,
                lastPingTimestamp = System.currentTimeMillis()
            )
        }
    }

    suspend fun pingAllServers(servers: List<VpnServer>): List<VpnServer> = coroutineScope {
        servers.map { server ->
            async(Dispatchers.IO) {
                measurePing(server)
            }
        }.awaitAll()
    }
}
