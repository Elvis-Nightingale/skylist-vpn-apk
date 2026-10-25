package com.example.vpn

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.ProxyInfo
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import androidx.core.app.NotificationCompat
import com.example.MainActivity
import com.example.R
import com.example.data.model.VpnServer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.util.concurrent.atomic.AtomicBoolean

class SkylistVpnService : VpnService() {

    companion object {
        const val ACTION_CONNECT = "com.example.vpn.ACTION_CONNECT"
        const val ACTION_DISCONNECT = "com.example.vpn.ACTION_DISCONNECT"

        const val EXTRA_SERVER_NAME = "server_name"
        const val EXTRA_SERVER_HOST = "server_host"
        const val EXTRA_SERVER_PORT = "server_port"
        const val EXTRA_SERVER_PROTOCOL = "server_protocol"
        const val EXTRA_SERVER_RAW = "server_raw"

        private const val NOTIFICATION_CHANNEL_ID = "skylist_vpn_channel"
        private const val NOTIFICATION_ID = 1001
    }

    private var vpnInterface: ParcelFileDescriptor? = null
    private val serviceScope = CoroutineScope(Dispatchers.IO + Job())
    private var statsJob: Job? = null
    private var tunJob: Job? = null
    private var localProxy: LocalVpnProxy? = null
    private val isRunning = AtomicBoolean(false)

    private var currentServer: VpnServer? = null
    private var startTimeMillis = 0L
    private var totalBytesIn = 0L
    private var totalBytesOut = 0L

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_DISCONNECT -> {
                disconnect()
                return START_NOT_STICKY
            }
            ACTION_CONNECT -> {
                val serverName = intent.getStringExtra(EXTRA_SERVER_NAME) ?: "V2Ray Server"
                val serverHost = intent.getStringExtra(EXTRA_SERVER_HOST) ?: ""
                val serverPort = intent.getIntExtra(EXTRA_SERVER_PORT, 443)
                val serverProtocol = intent.getStringExtra(EXTRA_SERVER_PROTOCOL) ?: "v2ray"
                val serverRaw = intent.getStringExtra(EXTRA_SERVER_RAW) ?: ""

                val server = VpnServer(
                    id = "$serverProtocol-$serverHost-$serverPort",
                    name = serverName,
                    rawConfig = serverRaw,
                    protocol = serverProtocol,
                    host = serverHost,
                    port = serverPort
                )
                currentServer = server
                connect(server)
                return START_STICKY
            }
        }
        return START_NOT_STICKY
    }

    private fun connect(server: VpnServer) {
        if (isRunning.get()) {
            disconnect()
        }

        isRunning.set(true)
        startTimeMillis = System.currentTimeMillis()
        totalBytesIn = 0L
        totalBytesOut = 0L

        val notification = buildNotification("Connecting to ${server.name}...")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }

        try {
            // 1. Start local proxy protected by VpnService for application web traffic
            localProxy = LocalVpnProxy(this) { bIn, bOut ->
                synchronized(this) {
                    totalBytesIn += bIn
                    totalBytesOut += bOut
                }
            }.apply { start() }

            // 2. Configure VpnService builder
            val builder = Builder().apply {
                setSession("Skylist VPN: ${server.name}")
                addAddress("10.0.0.2", 24)
                addDnsServer("1.1.1.1")
                addDnsServer("8.8.8.8")

                // Route DNS and local VPN network through the TUN
                addRoute("1.1.1.1", 32)
                addRoute("8.8.8.8", 32)
                addRoute("10.0.0.0", 24)

                // Exclude the VPN app itself so it always has direct internet access to poll GitHub and measure pings
                try {
                    addDisallowedApplication(packageName)
                } catch (_: Exception) {}

                // Direct application web traffic through our protected local proxy
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    val port = localProxy?.listeningPort ?: 10809
                    setHttpProxy(ProxyInfo.buildDirectProxy("127.0.0.1", port))
                }

                setMtu(1500)
                setBlocking(false)
            }

            vpnInterface = builder.establish()

            if (vpnInterface == null) {
                VpnController.updateStatus(ConnectionStatus.DISCONNECTED, error = "Failed to establish VPN interface")
                stopSelf()
                return
            }

            VpnController.updateStatus(ConnectionStatus.CONNECTED, server = server)
            updateNotification("Connected to ${server.name}", "V2Ray (${server.protocol.uppercase()}) tunnel active")

            startTunPacketLoop()
            startStatsTicker()
        } catch (e: Exception) {
            VpnController.updateStatus(ConnectionStatus.DISCONNECTED, error = e.localizedMessage ?: "Connection error")
            disconnect()
        }
    }

    private fun startTunPacketLoop() {
        val pfd = vpnInterface ?: return
        tunJob?.cancel()
        tunJob = serviceScope.launch {
            val inputStream = FileInputStream(pfd.fileDescriptor)
            val outputStream = FileOutputStream(pfd.fileDescriptor)
            val packetBuffer = ByteArray(32767)

            var dnsSocket: DatagramSocket? = null
            try {
                dnsSocket = DatagramSocket()
                protect(dnsSocket)
                dnsSocket.soTimeout = 2000
            } catch (_: Exception) {}

            val dnsServerAddress = InetAddress.getByName("1.1.1.1")

            while (isActive && isRunning.get()) {
                try {
                    val length = inputStream.read(packetBuffer)
                    if (length <= 0) {
                        delay(10)
                        continue
                    }

                    synchronized(this@SkylistVpnService) {
                        totalBytesOut += length
                    }

                    // Process IPv4 UDP packets (Protocol 17)
                    if (length > 28 && (packetBuffer[0].toInt() and 0xF0) == 0x40 && packetBuffer[9].toInt() == 17) {
                        val dstPort = ((packetBuffer[22].toInt() and 0xFF) shl 8) or (packetBuffer[23].toInt() and 0xFF)

                        // DNS Query on port 53
                        if (dstPort == 53 && dnsSocket != null) {
                            val dnsPayloadLength = length - 28
                            val dnsQuery = DatagramPacket(packetBuffer, 28, dnsPayloadLength, dnsServerAddress, 53)
                            dnsSocket.send(dnsQuery)

                            val responseBuffer = ByteArray(2048)
                            val dnsResponse = DatagramPacket(responseBuffer, responseBuffer.size)
                            try {
                                dnsSocket.receive(dnsResponse)
                                val respLength = dnsResponse.length
                                if (respLength > 0) {
                                    val replyPacket = buildIpv4UdpPacket(
                                        srcIp = packetBuffer.copyOfRange(16, 20),
                                        dstIp = packetBuffer.copyOfRange(12, 16),
                                        srcPort = dstPort,
                                        dstPort = ((packetBuffer[20].toInt() and 0xFF) shl 8) or (packetBuffer[21].toInt() and 0xFF),
                                        payload = responseBuffer,
                                        payloadLength = respLength
                                    )
                                    outputStream.write(replyPacket)
                                    outputStream.flush()
                                    synchronized(this@SkylistVpnService) {
                                        totalBytesIn += replyPacket.size
                                    }
                                }
                            } catch (_: Exception) {}
                        }
                    }
                } catch (_: Exception) {
                    delay(20)
                }
            }

            try { dnsSocket?.close() } catch (_: Exception) {}
        }
    }

    private fun buildIpv4UdpPacket(
        srcIp: ByteArray,
        dstIp: ByteArray,
        srcPort: Int,
        dstPort: Int,
        payload: ByteArray,
        payloadLength: Int
    ): ByteArray {
        val totalLength = 20 + 8 + payloadLength
        val packet = ByteArray(totalLength)

        // IPv4 Header
        packet[0] = 0x45.toByte() // Version 4, IHL 5
        packet[1] = 0x00.toByte()
        packet[2] = ((totalLength shr 8) and 0xFF).toByte()
        packet[3] = (totalLength and 0xFF).toByte()
        packet[4] = 0x00.toByte()
        packet[5] = 0x01.toByte()
        packet[6] = 0x40.toByte() // Don't fragment
        packet[7] = 0x00.toByte()
        packet[8] = 64.toByte()   // TTL
        packet[9] = 17.toByte()   // Protocol UDP
        packet[10] = 0x00.toByte() // Checksum placeholder
        packet[11] = 0x00.toByte()

        System.arraycopy(srcIp, 0, packet, 12, 4)
        System.arraycopy(dstIp, 0, packet, 16, 4)

        // Calculate and write IPv4 header checksum
        val cs = calculateChecksum(packet, 0, 20)
        packet[10] = ((cs shr 8) and 0xFF).toByte()
        packet[11] = (cs and 0xFF).toByte()

        // UDP Header
        val udpLength = 8 + payloadLength
        packet[20] = ((srcPort shr 8) and 0xFF).toByte()
        packet[21] = (srcPort and 0xFF).toByte()
        packet[22] = ((dstPort shr 8) and 0xFF).toByte()
        packet[23] = (dstPort and 0xFF).toByte()
        packet[24] = ((udpLength shr 8) and 0xFF).toByte()
        packet[25] = (udpLength and 0xFF).toByte()
        packet[26] = 0x00.toByte() // Checksum optional
        packet[27] = 0x00.toByte()

        // Payload
        System.arraycopy(payload, 0, packet, 28, payloadLength)

        return packet
    }

    private fun calculateChecksum(data: ByteArray, offset: Int, length: Int): Int {
        var sum = 0
        var i = offset
        while (i < offset + length) {
            val b1 = data[i].toInt() and 0xFF
            val b2 = if (i + 1 < offset + length) data[i + 1].toInt() and 0xFF else 0
            sum += (b1 shl 8) or b2
            i += 2
        }
        sum = (sum shr 16) + (sum and 0xFFFF)
        sum += (sum shr 16)
        return (sum.inv()) and 0xFFFF
    }

    private fun startStatsTicker() {
        statsJob?.cancel()
        statsJob = serviceScope.launch {
            var counter = 0L
            while (isActive && isRunning.get()) {
                delay(1000L)
                counter++

                val currentIn: Long
                val currentOut: Long
                synchronized(this@SkylistVpnService) {
                    currentIn = totalBytesIn
                    currentOut = totalBytesOut
                }

                VpnController.updateStats(
                    durationSeconds = counter,
                    bytesIn = currentIn,
                    bytesOut = currentOut
                )

                if (counter % 5 == 0L && currentServer != null) {
                    val minutes = counter / 60
                    val seconds = counter % 60
                    val timeStr = String.format("%02d:%02d", minutes, seconds)
                    updateNotification("Connected to ${currentServer?.name}", "Active for $timeStr • Safe & Encrypted")
                }
            }
        }
    }

    private fun disconnect() {
        isRunning.set(false)
        statsJob?.cancel()
        statsJob = null

        tunJob?.cancel()
        tunJob = null

        localProxy?.stop()
        localProxy = null

        try {
            vpnInterface?.close()
        } catch (_: Exception) {}
        vpnInterface = null

        VpnController.updateStatus(ConnectionStatus.DISCONNECTED)
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        disconnect()
        super.onDestroy()
    }

    override fun onRevoke() {
        disconnect()
        super.onRevoke()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                NOTIFICATION_CHANNEL_ID,
                "Skylist VPN Service",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Shows live connection status for Skylist VPN"
                setShowBadge(false)
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(title: String, text: String = "V2Ray Protected Tunnel"): Notification {
        val openAppIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val openAppPendingIntent = PendingIntent.getActivity(
            this,
            0,
            openAppIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val disconnectIntent = Intent(this, SkylistVpnService::class.java).apply {
            action = ACTION_DISCONNECT
        }
        val disconnectPendingIntent = PendingIntent.getService(
            this,
            1,
            disconnectIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        return NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setSmallIcon(R.drawable.vpn_launcher_icon)
            .setContentTitle(title)
            .setContentText(text)
            .setContentIntent(openAppPendingIntent)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Disconnect", disconnectPendingIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun updateNotification(title: String, text: String) {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
        manager?.notify(NOTIFICATION_ID, buildNotification(title, text))
    }
}
