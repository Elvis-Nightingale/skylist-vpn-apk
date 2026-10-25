package com.example.vpn

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
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
import java.net.InetSocketAddress
import java.net.Socket
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
    private var tunRouter: TunPacketRouter? = null
    private val isRunning = AtomicBoolean(false)

    private var currentServer: VpnServer? = null
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
            // Initiate protected handshake connection to the selected VPN server
            serviceScope.launch {
                try {
                    val probeSocket = Socket()
                    protect(probeSocket)
                    probeSocket.connect(InetSocketAddress(server.host, server.port), 2000)
                    probeSocket.close()
                } catch (_: Exception) {}
            }

            // Configure full-tunnel VpnService builder
            val builder = Builder().apply {
                setSession("Skylist VPN: ${server.name}")
                addAddress("10.0.0.2", 24)
                addDnsServer("1.1.1.1")
                addDnsServer("8.8.8.8")
                addRoute("0.0.0.0", 0) // Full device VPN tunnel

                // Exclude the VPN app itself so it always has direct internet access to poll GitHub and measure pings
                try {
                    addDisallowedApplication(packageName)
                } catch (_: Exception) {}

                setMtu(1500)
                setBlocking(false)
            }

            val pfd = builder.establish()
            vpnInterface = pfd

            if (pfd == null) {
                VpnController.updateStatus(ConnectionStatus.DISCONNECTED, error = "Failed to establish VPN interface")
                stopSelf()
                return
            }

            // Start full TUN packet router
            tunRouter = TunPacketRouter(
                vpnService = this,
                pfd = pfd,
                activeServer = server,
                onTraffic = { bIn, bOut ->
                    synchronized(this) {
                        totalBytesIn += bIn
                        totalBytesOut += bOut
                    }
                }
            ).apply { start() }

            VpnController.updateStatus(ConnectionStatus.CONNECTED, server = server)
            updateNotification("Connected to ${server.name}", "V2Ray (${server.protocol.uppercase()}) tunnel active • Encrypted")

            startStatsTicker()
        } catch (e: Exception) {
            VpnController.updateStatus(ConnectionStatus.DISCONNECTED, error = e.localizedMessage ?: "Connection error")
            disconnect()
        }
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

        tunRouter?.stop()
        tunRouter = null

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
