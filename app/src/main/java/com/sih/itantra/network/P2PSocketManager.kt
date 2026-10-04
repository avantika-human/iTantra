package com.sih.itantra.network

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.nio.charset.StandardCharsets

/**
 * Low-latency transport for the iTantra walkie-talkie link.
 *
 * Design notes:
 *  - Pure java.net ServerSocket/Socket: works on any Wi-Fi hotspot LAN with
 *    zero infrastructure and no internet access.
 *  - Every blocking call is confined to Dispatchers.IO via a private
 *    CoroutineScope, so the UI thread is never touched.
 *  - TCP_NODELAY is set on every socket so small transcript packets are not
 *    buffered by Nagle's algorithm - this is what keeps mouth-to-ear delay low.
 *  - Payloads are single UTF-8 text lines. Emergency traffic is flagged with
 *    the [EMERGENCY] prefix on the wire and re-parsed on the receiving side.
 */
class P2PSocketManager(
    private val onPacketReceived: (packet: Packet) -> Unit,
    private val onLog: (message: String) -> Unit,
    private val onConnectionChanged: (connected: Boolean) -> Unit
) {

    /** A parsed wire packet: payload text plus its emergency flag. */
    data class Packet(val payload: String, val isEmergency: Boolean)

    companion object {
        /** Single well-known port on the hotspot LAN. */
        const val PORT = 50005

        /**
         * Classic Android hotspot gateway address (the device running the
         * hotspot). Newer Android builds may use 192.168.49.1 instead.
         */
        const val DEFAULT_HOST = "192.168.43.1"

        /** Prefix that marks a packet as priority emergency traffic. */
        const val EMERGENCY_PREFIX = "[EMERGENCY]"

        const val CONNECT_TIMEOUT_MS = 3_000
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** Serializes writes so two coroutines never interleave into one stream. */
    private val sendMutex = Mutex()

    @Volatile private var serverSocket: ServerSocket? = null
    @Volatile private var activeSocket: Socket? = null
    @Volatile private var isShuttingDown = false

    // ------------------------------------------------------------------
    // Host (Receiver) side
    // ------------------------------------------------------------------

    /** Bind a ServerSocket on [PORT] and accept peers in a background loop. */
    fun startServer() {
        if (serverSocket?.isClosed == false) {
            onLog("Server already listening on port $PORT")
            return
        }
        scope.launch {
            try {
                ServerSocket().apply {
                    reuseAddress = true
                    bind(InetSocketAddress(PORT))
                    serverSocket = this
                }
                onLog("ServerSocket bound on 0.0.0.0:$PORT -> waiting for peers")
                onConnectionChanged(true)
                while (isActive && serverSocket != null) {
                    val client = serverSocket?.accept() ?: break
                    client.tcpNoDelay = true
                    onLog("Peer connected: ${client.inetAddress?.hostAddress}:${client.port}")
                    attachSocket(client)
                }
            } catch (e: SocketException) {
                if (!isShuttingDown) onLog("Server closed unexpectedly: ${e.message}")
            } catch (e: Exception) {
                if (!isShuttingDown) onLog("Server error: ${e.message}")
            }
        }
    }

    // ------------------------------------------------------------------
    // Client (Transmitter) side
    // ------------------------------------------------------------------

    /** Dial the peer (default: hotspot gateway) and start the read loop. */
    fun connectToPeer(host: String = DEFAULT_HOST, port: Int = PORT) {
        scope.launch {
            try {
                onLog("Dialing peer socket $host:$port ...")
                val socket = Socket()
                socket.tcpNoDelay = true
                socket.connect(InetSocketAddress(host, port), CONNECT_TIMEOUT_MS)
                onLog("Connected to peer $host:$port")
                attachSocket(socket)
            } catch (e: Exception) {
                onConnectionChanged(false)
                onLog("Peer connection failed: ${e.message}")
            }
        }
    }

    // ------------------------------------------------------------------
    // Transmission
    // ------------------------------------------------------------------

    /**
     * Send one raw text packet (a single flushed line - minimal latency).
     * A fresh writer per packet keeps socket lifecycle simple; walkie-talkie
     * traffic is tiny so the allocation cost is irrelevant.
     */
    suspend fun sendPacket(payload: String, isEmergency: Boolean = false): Boolean =
        withContext(Dispatchers.IO) {
            val socket = activeSocket
            if (socket == null || socket.isClosed) {
                onLog("TX failed -> no active peer socket (connect first)")
                false
            } else {
                sendMutex.withLock {
                    try {
                        val wire = if (isEmergency) "$EMERGENCY_PREFIX $payload" else payload
                        val writer = BufferedWriter(
                            OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8)
                        )
                        writer.write(wire)
                        writer.newLine()
                        writer.flush()
                        onLog("TX -> $wire")
                        true
                    } catch (e: Exception) {
                        onLog("TX error: ${e.message}")
                        false
                    }
                }
            }
        }

    // ------------------------------------------------------------------
    // Lifecycle
    // ------------------------------------------------------------------

    /** Tear down sockets and cancel all I/O coroutines. Safe to call once. */
    fun shutdown() {
        isShuttingDown = true
        try {
            activeSocket?.close()
        } catch (e: Exception) {
            // best effort
        }
        try {
            serverSocket?.close()
        } catch (e: Exception) {
            // best effort
        }
        activeSocket = null
        serverSocket = null
        onConnectionChanged(false)
        scope.cancel()
        onLog("P2P socket manager shut down")
    }

    // ------------------------------------------------------------------
    // Internals
    // ------------------------------------------------------------------

    /** Promote a socket to the active link and start its reader loop. */
    private fun attachSocket(socket: Socket) {
        activeSocket?.takeIf { !it.isClosed }?.close()
        activeSocket = socket
        onConnectionChanged(true)
        scope.launch {
            try {
                val reader = BufferedReader(
                    InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8)
                )
                while (isActive && !socket.isClosed) {
                    val line = reader.readLine() ?: break
                    if (line.isBlank()) continue
                    onPacketReceived(parsePacket(line))
                }
            } catch (e: Exception) {
                if (!isShuttingDown) onLog("Read loop terminated: ${e.message}")
            } finally {
                if (activeSocket === socket) {
                    activeSocket = null
                }
                if (!isShuttingDown) onLog("Peer disconnected")
            }
        }
    }

    /** Strip (and detect) the emergency prefix from a raw wire line. */
    fun parsePacket(raw: String): Packet {
        val trimmed = raw.trim()
        val isEmergency = trimmed.startsWith(EMERGENCY_PREFIX)
        val payload =
            if (isEmergency) trimmed.removePrefix(EMERGENCY_PREFIX).trim()
            else trimmed
        return Packet(payload = payload, isEmergency = isEmergency)
    }
}
