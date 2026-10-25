package com.example.vpn

import android.net.VpnService
import android.os.ParcelFileDescriptor
import com.example.data.model.VpnServer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.ByteBuffer
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

class TunPacketRouter(
    private val vpnService: VpnService,
    private val pfd: ParcelFileDescriptor,
    private val activeServer: VpnServer?,
    private val onTraffic: (bytesIn: Long, bytesOut: Long) -> Unit
) {
    private val isRunning = AtomicBoolean(false)
    private val routerScope = CoroutineScope(Dispatchers.IO + Job())
    private val outputLock = Any()

    private val tcpConnections = ConcurrentHashMap<String, TcpSession>()
    private val dohClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(3, TimeUnit.SECONDS)
        .readTimeout(3, TimeUnit.SECONDS)
        .build()

    private class TcpSession(
        val socket: Socket,
        val input: InputStream,
        val output: OutputStream,
        var clientSeq: Long,
        var serverSeq: Long,
        val clientPort: Int,
        val remotePort: Int,
        val clientIp: ByteArray,
        val remoteIp: ByteArray,
        var isClosing: Boolean = false
    )

    fun start() {
        isRunning.set(true)

        // Main TUN packet reader thread
        Thread({
            val inputStream = FileInputStream(pfd.fileDescriptor)
            val outputStream = FileOutputStream(pfd.fileDescriptor)
            val buffer = ByteArray(32767)

            while (isRunning.get()) {
                try {
                    val length = inputStream.read(buffer)
                    if (length <= 0) {
                        Thread.sleep(5)
                        continue
                    }

                    onTraffic(0, length.toLong())

                    val version = (buffer[0].toInt() shr 4) and 0x0F
                    if (version != 4 || length < 20) {
                        continue
                    }

                    val ihl = (buffer[0].toInt() and 0x0F) * 4
                    if (length < ihl) continue

                    val protocol = buffer[9].toInt() and 0xFF

                    if (protocol == 17) {
                        // UDP (DNS, QUIC, VoIP, etc.)
                        handleUdpPacket(buffer, ihl, length, outputStream)
                    } else if (protocol == 6) {
                        // TCP (Web, TLS, Streams, Apps)
                        handleTcpPacket(buffer, ihl, length, outputStream)
                    }
                } catch (_: Exception) {
                    try { Thread.sleep(10) } catch (_: Exception) {}
                }
            }
        }, "TunPacketReader").start()
    }

    private fun handleUdpPacket(
        packet: ByteArray,
        ihl: Int,
        length: Int,
        outputStream: FileOutputStream
    ) {
        if (length < ihl + 8) return

        val srcPort = ((packet[ihl].toInt() and 0xFF) shl 8) or (packet[ihl + 1].toInt() and 0xFF)
        val dstPort = ((packet[ihl + 2].toInt() and 0xFF) shl 8) or (packet[ihl + 3].toInt() and 0xFF)
        val udpLength = ((packet[ihl + 4].toInt() and 0xFF) shl 8) or (packet[ihl + 5].toInt() and 0xFF)
        val payloadOffset = ihl + 8
        val payloadLength = (udpLength - 8).coerceAtLeast(0)

        if (payloadOffset + payloadLength > length) return

        val srcIp = packet.copyOfRange(12, 16)
        val dstIp = packet.copyOfRange(16, 20)
        val payload = packet.copyOfRange(payloadOffset, payloadOffset + payloadLength)

        if (dstPort == 53) {
            // DNS Query: resolve via high-speed Cloudflare DoH (RFC 8484) with UDP fallback
            routerScope.launch {
                resolveDnsAndReply(payload, srcIp, dstIp, srcPort, dstPort, outputStream)
            }
        } else {
            // Other UDP traffic: forward via protected DatagramSocket
            routerScope.launch {
                forwardUdpTraffic(payload, srcIp, dstIp, srcPort, dstPort, outputStream)
            }
        }
    }

    private fun resolveDnsAndReply(
        dnsQuery: ByteArray,
        clientIp: ByteArray,
        serverIp: ByteArray,
        clientPort: Int,
        serverPort: Int,
        outputStream: FileOutputStream
    ) {
        var replyPayload: ByteArray? = null

        // 1. Try Cloudflare DNS-over-HTTPS (DoH)
        try {
            val reqBody = dnsQuery.toRequestBody("application/dns-message".toMediaType())
            val request = Request.Builder()
                .url("https://1.1.1.1/dns-query")
                .post(reqBody)
                .header("Accept", "application/dns-message")
                .build()

            dohClient.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    replyPayload = response.body?.bytes()
                }
            }
        } catch (_: Exception) {}

        // 2. Fallback to protected UDP Datagram to 1.1.1.1
        if (replyPayload == null || replyPayload!!.isEmpty()) {
            try {
                val udpSocket = DatagramSocket()
                vpnService.protect(udpSocket)
                udpSocket.soTimeout = 1500

                val sendPacket = DatagramPacket(dnsQuery, dnsQuery.size, InetAddress.getByName("1.1.1.1"), 53)
                udpSocket.send(sendPacket)

                val recvBuf = ByteArray(2048)
                val recvPacket = DatagramPacket(recvBuf, recvBuf.size)
                udpSocket.receive(recvPacket)
                replyPayload = recvBuf.copyOf(recvPacket.length)
                udpSocket.close()
            } catch (_: Exception) {}
        }

        if (replyPayload != null && replyPayload!!.isNotEmpty()) {
            val replyPacket = buildIpv4UdpPacket(
                srcIp = serverIp,
                dstIp = clientIp,
                srcPort = serverPort,
                dstPort = clientPort,
                payload = replyPayload!!
            )
            writeToTun(replyPacket, outputStream)
        }
    }

    private fun forwardUdpTraffic(
        payload: ByteArray,
        clientIp: ByteArray,
        remoteIp: ByteArray,
        clientPort: Int,
        remotePort: Int,
        outputStream: FileOutputStream
    ) {
        try {
            val socket = DatagramSocket()
            vpnService.protect(socket)
            socket.soTimeout = 2500

            val destAddress = InetAddress.getByAddress(remoteIp)
            val sendPacket = DatagramPacket(payload, payload.size, destAddress, remotePort)
            socket.send(sendPacket)

            val recvBuf = ByteArray(4096)
            val recvPacket = DatagramPacket(recvBuf, recvBuf.size)
            socket.receive(recvPacket)

            val respPayload = recvBuf.copyOf(recvPacket.length)
            val replyPacket = buildIpv4UdpPacket(
                srcIp = remoteIp,
                dstIp = clientIp,
                srcPort = remotePort,
                dstPort = clientPort,
                payload = respPayload
            )
            writeToTun(replyPacket, outputStream)
            socket.close()
        } catch (_: Exception) {}
    }

    private fun handleTcpPacket(
        packet: ByteArray,
        ihl: Int,
        length: Int,
        outputStream: FileOutputStream
    ) {
        if (length < ihl + 20) return

        val srcPort = ((packet[ihl].toInt() and 0xFF) shl 8) or (packet[ihl + 1].toInt() and 0xFF)
        val dstPort = ((packet[ihl + 2].toInt() and 0xFF) shl 8) or (packet[ihl + 3].toInt() and 0xFF)

        val clientSeq = getUint32(packet, ihl + 4)
        val flags = packet[ihl + 13].toInt() and 0xFF
        val isSyn = (flags and 0x02) != 0
        val isAck = (flags and 0x10) != 0
        val isFin = (flags and 0x01) != 0
        val isRst = (flags and 0x04) != 0

        val dataOffset = ((packet[ihl + 12].toInt() shr 4) and 0x0F) * 4
        val payloadOffset = ihl + dataOffset
        val payloadLength = (length - payloadOffset).coerceAtLeast(0)

        val clientIp = packet.copyOfRange(12, 16)
        val dstIp = packet.copyOfRange(16, 20)
        val connKey = "$srcPort->${dstIp[0]}.${dstIp[1]}.${dstIp[2]}.${dstIp[3]}:$dstPort"

        if (isSyn && !isAck) {
            // New TCP connection request from client
            routerScope.launch {
                handleTcpSyn(connKey, clientIp, dstIp, srcPort, dstPort, clientSeq, outputStream)
            }
            return
        }

        val session = tcpConnections[connKey] ?: return

        if (isRst || isFin) {
            session.isClosing = true
            try { session.socket.close() } catch (_: Exception) {}
            tcpConnections.remove(connKey)
            if (isFin) {
                // Send FIN-ACK reply
                val finAckPacket = buildIpv4TcpPacket(
                    srcIp = session.remoteIp,
                    dstIp = session.clientIp,
                    srcPort = session.remotePort,
                    dstPort = session.clientPort,
                    seq = session.serverSeq,
                    ack = clientSeq + 1,
                    flags = 0x11, // FIN + ACK
                    payload = ByteArray(0)
                )
                writeToTun(finAckPacket, outputStream)
            }
            return
        }

        if (payloadLength > 0) {
            // Client data packet: forward payload to remote socket
            val data = packet.copyOfRange(payloadOffset, payloadOffset + payloadLength)
            session.clientSeq = (clientSeq + payloadLength) and 0xFFFFFFFFL

            routerScope.launch {
                try {
                    session.output.write(data)
                    session.output.flush()

                    // Send TCP ACK back to client for data receipt
                    val ackPacket = buildIpv4TcpPacket(
                        srcIp = session.remoteIp,
                        dstIp = session.clientIp,
                        srcPort = session.remotePort,
                        dstPort = session.clientPort,
                        seq = session.serverSeq,
                        ack = session.clientSeq,
                        flags = 0x10, // ACK
                        payload = ByteArray(0)
                    )
                    writeToTun(ackPacket, outputStream)
                } catch (_: Exception) {
                    tcpConnections.remove(connKey)
                }
            }
        }
    }

    private fun handleTcpSyn(
        connKey: String,
        clientIp: ByteArray,
        dstIp: ByteArray,
        srcPort: Int,
        dstPort: Int,
        clientSynSeq: Long,
        outputStream: FileOutputStream
    ) {
        try {
            val destAddr = InetAddress.getByAddress(dstIp)
            val socket = Socket()
            vpnService.protect(socket)
            socket.tcpNoDelay = true
            socket.soTimeout = 15000
            socket.connect(InetSocketAddress(destAddr, dstPort), 4000)

            val session = TcpSession(
                socket = socket,
                input = socket.getInputStream(),
                output = socket.getOutputStream(),
                clientSeq = (clientSynSeq + 1) and 0xFFFFFFFFL,
                serverSeq = 1000L,
                clientPort = srcPort,
                remotePort = dstPort,
                clientIp = clientIp,
                remoteIp = dstIp
            )
            tcpConnections[connKey] = session

            // Send TCP SYN-ACK back to client
            val synAck = buildIpv4TcpPacket(
                srcIp = dstIp,
                dstIp = clientIp,
                srcPort = dstPort,
                dstPort = srcPort,
                seq = session.serverSeq,
                ack = session.clientSeq,
                flags = 0x12, // SYN + ACK
                payload = ByteArray(0)
            )
            session.serverSeq = (session.serverSeq + 1) and 0xFFFFFFFFL
            writeToTun(synAck, outputStream)

            // Start reading remote server responses and streaming back into TUN
            routerScope.launch {
                val recvBuf = ByteArray(1460)
                try {
                    while (isRunning.get() && !session.isClosing) {
                        val n = session.input.read(recvBuf)
                        if (n <= 0) break

                        val chunk = recvBuf.copyOf(n)
                        val dataPacket = buildIpv4TcpPacket(
                            srcIp = session.remoteIp,
                            dstIp = session.clientIp,
                            srcPort = session.remotePort,
                            dstPort = session.clientPort,
                            seq = session.serverSeq,
                            ack = session.clientSeq,
                            flags = 0x18, // PSH + ACK
                            payload = chunk
                        )
                        session.serverSeq = (session.serverSeq + n) and 0xFFFFFFFFL
                        writeToTun(dataPacket, outputStream)
                    }
                } catch (_: Exception) {} finally {
                    try { session.socket.close() } catch (_: Exception) {}
                    tcpConnections.remove(connKey)
                }
            }
        } catch (_: Exception) {
            // Send RST if remote connection failed
            val rstPacket = buildIpv4TcpPacket(
                srcIp = dstIp,
                dstIp = clientIp,
                srcPort = dstPort,
                dstPort = srcPort,
                seq = 0L,
                ack = (clientSynSeq + 1) and 0xFFFFFFFFL,
                flags = 0x14, // RST + ACK
                payload = ByteArray(0)
            )
            writeToTun(rstPacket, outputStream)
        }
    }

    private fun writeToTun(packet: ByteArray, outputStream: FileOutputStream) {
        synchronized(outputLock) {
            try {
                outputStream.write(packet)
                outputStream.flush()
                onTraffic(packet.size.toLong(), 0)
            } catch (_: Exception) {}
        }
    }

    private fun buildIpv4UdpPacket(
        srcIp: ByteArray,
        dstIp: ByteArray,
        srcPort: Int,
        dstPort: Int,
        payload: ByteArray
    ): ByteArray {
        val totalLength = 20 + 8 + payload.size
        val packet = ByteArray(totalLength)

        // IP Header
        packet[0] = 0x45.toByte()
        packet[1] = 0x00.toByte()
        packet[2] = ((totalLength shr 8) and 0xFF).toByte()
        packet[3] = (totalLength and 0xFF).toByte()
        packet[4] = 0x00.toByte()
        packet[5] = 0x01.toByte()
        packet[6] = 0x40.toByte()
        packet[7] = 0x00.toByte()
        packet[8] = 64.toByte() // TTL
        packet[9] = 17.toByte() // UDP
        packet[10] = 0.toByte()
        packet[11] = 0.toByte()
        System.arraycopy(srcIp, 0, packet, 12, 4)
        System.arraycopy(dstIp, 0, packet, 16, 4)

        val ipCs = calcChecksum(packet, 0, 20)
        packet[10] = ((ipCs shr 8) and 0xFF).toByte()
        packet[11] = (ipCs and 0xFF).toByte()

        // UDP Header
        packet[20] = ((srcPort shr 8) and 0xFF).toByte()
        packet[21] = (srcPort and 0xFF).toByte()
        packet[22] = ((dstPort shr 8) and 0xFF).toByte()
        packet[23] = (dstPort and 0xFF).toByte()
        val udpLen = 8 + payload.size
        packet[24] = ((udpLen shr 8) and 0xFF).toByte()
        packet[25] = (udpLen and 0xFF).toByte()
        packet[26] = 0.toByte()
        packet[27] = 0.toByte()

        System.arraycopy(payload, 0, packet, 28, payload.size)
        return packet
    }

    private fun buildIpv4TcpPacket(
        srcIp: ByteArray,
        dstIp: ByteArray,
        srcPort: Int,
        dstPort: Int,
        seq: Long,
        ack: Long,
        flags: Int,
        payload: ByteArray
    ): ByteArray {
        val totalLength = 20 + 20 + payload.size
        val packet = ByteArray(totalLength)

        // IP Header
        packet[0] = 0x45.toByte()
        packet[1] = 0x00.toByte()
        packet[2] = ((totalLength shr 8) and 0xFF).toByte()
        packet[3] = (totalLength and 0xFF).toByte()
        packet[4] = 0x00.toByte()
        packet[5] = 0x02.toByte()
        packet[6] = 0x40.toByte()
        packet[7] = 0x00.toByte()
        packet[8] = 64.toByte() // TTL
        packet[9] = 6.toByte()  // TCP
        packet[10] = 0.toByte()
        packet[11] = 0.toByte()
        System.arraycopy(srcIp, 0, packet, 12, 4)
        System.arraycopy(dstIp, 0, packet, 16, 4)

        val ipCs = calcChecksum(packet, 0, 20)
        packet[10] = ((ipCs shr 8) and 0xFF).toByte()
        packet[11] = (ipCs and 0xFF).toByte()

        // TCP Header (20 bytes)
        val tcpOffset = 20
        packet[tcpOffset] = ((srcPort shr 8) and 0xFF).toByte()
        packet[tcpOffset + 1] = (srcPort and 0xFF).toByte()
        packet[tcpOffset + 2] = ((dstPort shr 8) and 0xFF).toByte()
        packet[tcpOffset + 3] = (dstPort and 0xFF).toByte()

        putUint32(packet, tcpOffset + 4, seq)
        putUint32(packet, tcpOffset + 8, ack)

        packet[tcpOffset + 12] = (5 shl 4).toByte() // Data offset = 5 (20 bytes)
        packet[tcpOffset + 13] = (flags and 0xFF).toByte()
        packet[tcpOffset + 14] = (0xFF).toByte() // Window size = 65535
        packet[tcpOffset + 15] = (0xFF).toByte()
        packet[tcpOffset + 16] = 0.toByte() // Checksum placeholder
        packet[tcpOffset + 17] = 0.toByte()
        packet[tcpOffset + 18] = 0.toByte() // Urgent pointer
        packet[tcpOffset + 19] = 0.toByte()

        if (payload.isNotEmpty()) {
            System.arraycopy(payload, 0, packet, tcpOffset + 20, payload.size)
        }

        // TCP Checksum with pseudo header
        val tcpLen = 20 + payload.size
        val tcpCs = calcTcpChecksum(srcIp, dstIp, packet, tcpOffset, tcpLen)
        packet[tcpOffset + 16] = ((tcpCs shr 8) and 0xFF).toByte()
        packet[tcpOffset + 17] = (tcpCs and 0xFF).toByte()

        return packet
    }

    private fun calcChecksum(data: ByteArray, offset: Int, length: Int): Int {
        var sum = 0
        var i = offset
        while (i < offset + length) {
            val b1 = data[i].toInt() and 0xFF
            val b2 = if (i + 1 < offset + length) data[i + 1].toInt() and 0xFF else 0
            sum += (b1 shl 8) or b2
            i += 2
        }
        while (sum shr 16 != 0) {
            sum = (sum and 0xFFFF) + (sum shr 16)
        }
        return (sum.inv()) and 0xFFFF
    }

    private fun calcTcpChecksum(
        srcIp: ByteArray,
        dstIp: ByteArray,
        tcpPacket: ByteArray,
        offset: Int,
        tcpLength: Int
    ): Int {
        var sum = 0

        // Pseudo header: Src IP (4), Dst IP (4), Zero (1), Protocol 6 (1), TCP Length (2)
        for (i in 0 until 4 step 2) {
            sum += ((srcIp[i].toInt() and 0xFF) shl 8) or (srcIp[i + 1].toInt() and 0xFF)
            sum += ((dstIp[i].toInt() and 0xFF) shl 8) or (dstIp[i + 1].toInt() and 0xFF)
        }
        sum += 6 // Protocol TCP
        sum += tcpLength

        // TCP segment
        var i = offset
        val end = offset + tcpLength
        while (i < end) {
            val b1 = tcpPacket[i].toInt() and 0xFF
            val b2 = if (i + 1 < end) tcpPacket[i + 1].toInt() and 0xFF else 0
            sum += (b1 shl 8) or b2
            i += 2
        }

        while (sum shr 16 != 0) {
            sum = (sum and 0xFFFF) + (sum shr 16)
        }
        return (sum.inv()) and 0xFFFF
    }

    private fun getUint32(data: ByteArray, offset: Int): Long {
        return (((data[offset].toLong() and 0xFF) shl 24) or
                ((data[offset + 1].toLong() and 0xFF) shl 16) or
                ((data[offset + 2].toLong() and 0xFF) shl 8) or
                (data[offset + 3].toLong() and 0xFF))
    }

    private fun putUint32(data: ByteArray, offset: Int, value: Long) {
        data[offset] = ((value shr 24) and 0xFF).toByte()
        data[offset + 1] = ((value shr 16) and 0xFF).toByte()
        data[offset + 2] = ((value shr 8) and 0xFF).toByte()
        data[offset + 3] = (value and 0xFF).toByte()
    }

    fun stop() {
        isRunning.set(false)
        for ((_, session) in tcpConnections) {
            try { session.socket.close() } catch (_: Exception) {}
        }
        tcpConnections.clear()
    }
}
