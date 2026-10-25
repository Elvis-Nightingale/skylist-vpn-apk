package com.example.vpn

import android.net.VpnService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.atomic.AtomicBoolean

class LocalVpnProxy(
    private val vpnService: VpnService,
    private val onTraffic: (bytesIn: Long, bytesOut: Long) -> Unit
) {
    private var serverSocket: ServerSocket? = null
    private val isRunning = AtomicBoolean(false)
    private val proxyScope = CoroutineScope(Dispatchers.IO + Job())

    var listeningPort: Int = 10809
        private set

    fun start(): Boolean {
        return try {
            serverSocket = ServerSocket(0).apply {
                reuseAddress = true
            }
            listeningPort = serverSocket?.localPort ?: 10809
            isRunning.set(true)

            proxyScope.launch {
                while (isActive && isRunning.get()) {
                    try {
                        val client = serverSocket?.accept() ?: break
                        proxyScope.launch {
                            handleClient(client)
                        }
                    } catch (_: Exception) {
                        break
                    }
                }
            }
            true
        } catch (_: Exception) {
            false
        }
    }

    private fun handleClient(clientSocket: Socket) {
        try {
            clientSocket.soTimeout = 20000
            val input = clientSocket.getInputStream()
            val output = clientSocket.getOutputStream()

            // Read the initial request line
            val firstLine = readLine(input) ?: run {
                clientSocket.close()
                return
            }

            if (firstLine.startsWith("CONNECT ", ignoreCase = true)) {
                // HTTPS Tunneling (CONNECT target:443 HTTP/1.1)
                val parts = firstLine.split(" ")
                if (parts.size >= 2) {
                    val hostPort = parts[1].split(":")
                    val targetHost = hostPort[0]
                    val targetPort = hostPort.getOrNull(1)?.toIntOrNull() ?: 443

                    // Consume remaining HTTP headers
                    while (true) {
                        val line = readLine(input)
                        if (line.isNullOrEmpty()) break
                    }

                    val targetSocket = Socket()
                    vpnService.protect(targetSocket)
                    targetSocket.connect(InetSocketAddress(targetHost, targetPort), 7000)

                    // Reply 200 Connection Established
                    output.write("HTTP/1.1 200 Connection Established\r\n\r\n".toByteArray())
                    output.flush()

                    // Bidirectional pipe
                    pipeSockets(clientSocket, targetSocket)
                } else {
                    clientSocket.close()
                }
            } else {
                // Plain HTTP request
                val parts = firstLine.split(" ")
                if (parts.size >= 2) {
                    val rawUrl = parts[1]
                    val hostPort = extractHostPort(rawUrl)
                    val targetHost = hostPort.first
                    val targetPort = hostPort.second

                    val targetSocket = Socket()
                    vpnService.protect(targetSocket)
                    targetSocket.connect(InetSocketAddress(targetHost, targetPort), 7000)

                    val targetOutput = targetSocket.getOutputStream()
                    targetOutput.write((firstLine + "\r\n").toByteArray())
                    targetOutput.flush()

                    pipeSockets(clientSocket, targetSocket)
                } else {
                    clientSocket.close()
                }
            }
        } catch (_: Exception) {
            try { clientSocket.close() } catch (_: Exception) {}
        }
    }

    private fun pipeSockets(client: Socket, target: Socket) {
        val clientIn = client.getInputStream()
        val clientOut = client.getOutputStream()
        val targetIn = target.getInputStream()
        val targetOut = target.getOutputStream()

        val j1 = proxyScope.launch {
            val buf = ByteArray(16384)
            try {
                while (isRunning.get()) {
                    val n = clientIn.read(buf)
                    if (n <= 0) break
                    targetOut.write(buf, 0, n)
                    targetOut.flush()
                    onTraffic(0, n.toLong())
                }
            } catch (_: Exception) {}
            try { target.close() } catch (_: Exception) {}
        }

        val j2 = proxyScope.launch {
            val buf = ByteArray(16384)
            try {
                while (isRunning.get()) {
                    val n = targetIn.read(buf)
                    if (n <= 0) break
                    clientOut.write(buf, 0, n)
                    clientOut.flush()
                    onTraffic(n.toLong(), 0)
                }
            } catch (_: Exception) {}
            try { client.close() } catch (_: Exception) {}
        }

        proxyScope.launch {
            j1.join()
            j2.join()
            try { client.close() } catch (_: Exception) {}
            try { target.close() } catch (_: Exception) {}
        }
    }

    private fun readLine(input: InputStream): String? {
        val sb = StringBuilder()
        var b: Int
        while (input.read().also { b = it } != -1) {
            if (b == '\n'.code) break
            if (b != '\r'.code) {
                sb.append(b.toChar())
            }
        }
        return if (sb.isEmpty() && b == -1) null else sb.toString()
    }

    private fun extractHostPort(url: String): Pair<String, Int> {
        val clean = url.removePrefix("http://").removePrefix("https://")
        val hostPart = clean.substringBefore('/')
        return if (hostPart.contains(':')) {
            val h = hostPart.substringBefore(':')
            val p = hostPart.substringAfter(':').toIntOrNull() ?: 80
            Pair(h, p)
        } else {
            Pair(hostPart, 80)
        }
    }

    fun stop() {
        isRunning.set(false)
        try {
            serverSocket?.close()
        } catch (_: Exception) {}
        serverSocket = null
    }
}
