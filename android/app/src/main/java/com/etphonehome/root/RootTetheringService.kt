package com.etphonehome.root

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.etphonehome.MainActivity
import com.etphonehome.R
import com.etphonehome.protocol.*
import com.etphonehome.util.TokenManager
import kotlinx.coroutines.*
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.ServerSocket
import java.net.Socket

/**
 * Root-based tethering service
 *
 * This service uses iptables for kernel-level packet routing instead of
 * the VPN API. This provides better performance on rooted devices.
 *
 * Flow:
 * 1. Desktop connects to Android server socket
 * 2. Android sets up iptables NAT rules to redirect traffic
 * 3. Kernel handles packet forwarding (no userspace copying)
 * 4. Desktop acts as internet gateway
 */
class RootTetheringService : Service() {

    private val TAG = "RootTetheringService"
    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private var serverSocket: ServerSocket? = null
    private var clientSocket: Socket? = null
    private var iptablesManager: IptablesManager? = null
    private var isRunning = false

    private var bytesSent: Long = 0
    private var bytesReceived: Long = 0
    private var startTime: Long = 0

    companion object {
        private const val NOTIFICATION_ID = 2
        private const val CHANNEL_ID = "root_tethering_service"

        const val EXTRA_PORT = "port"
        const val EXTRA_DESKTOP_IP = "desktop_ip"
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        Log.d(TAG, "RootTetheringService created")
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent == null) {
            stopSelf()
            return START_NOT_STICKY
        }

        val port = intent.getIntExtra(EXTRA_PORT, 8888)
        val desktopIp = intent.getStringExtra(EXTRA_DESKTOP_IP)

        Log.d(TAG, "Starting root tethering on port $port")

        startForeground(NOTIFICATION_ID, createNotification())

        serviceScope.launch {
            try {
                // Check for root access first
                if (!RootManager.requestRoot()) {
                    Log.e(TAG, "Root access denied")
                    stopSelf()
                    return@launch
                }

                startRootTethering(port, desktopIp)
            } catch (e: Exception) {
                Log.e(TAG, "Error in root tethering", e)
                stopSelf()
            }
        }

        return START_STICKY
    }

    private suspend fun startRootTethering(port: Int, desktopIp: String?) {
        Log.d(TAG, "Starting root tethering server on port $port")

        // Create server socket for desktop connection
        serverSocket = ServerSocket(port)
        Log.d(TAG, "Server socket listening on port $port")

        // Wait for desktop connection
        Log.d(TAG, "Waiting for desktop connection...")
        clientSocket = serverSocket?.accept()
        val connectedIp = desktopIp ?: clientSocket?.inetAddress?.hostAddress
            ?: throw Exception("No desktop IP available")

        Log.d(TAG, "Desktop connected from $connectedIp")

        // Configure socket
        clientSocket?.tcpNoDelay = true
        clientSocket?.keepAlive = true

        // Perform handshake
        performHandshake()

        // Setup iptables routing
        iptablesManager = IptablesManager()

        // Check if SELinux might block us
        if (!SelinuxHelper.checkIptablesPermissions()) {
            Log.w(TAG, "iptables access may be blocked by SELinux")
            // Try with permissive mode
            SelinuxHelper.withPermissiveSelinux {
                if (!iptablesManager!!.setupRootRouting(connectedIp, port)) {
                    throw Exception("Failed to setup iptables routing")
                }
            }
        } else {
            if (!iptablesManager!!.setupRootRouting(connectedIp, port)) {
                throw Exception("Failed to setup iptables routing")
            }
        }

        isRunning = true
        startTime = System.currentTimeMillis()

        Log.i(TAG, "Root tethering active - traffic being routed via iptables")

        // Keep connection alive and handle control messages
        handleControlMessages()
    }

    private suspend fun performHandshake() = withContext(Dispatchers.IO) {
        val input = clientSocket?.getInputStream() ?: throw IOException("No input stream")
        val output = clientSocket?.getOutputStream() ?: throw IOException("No output stream")

        Log.d(TAG, "Starting handshake")

        // Read handshake request
        val request = readMessage(input) as? HandshakeRequest
            ?: throw ProtocolException("Expected HandshakeRequest")

        Log.d(TAG, "Received handshake: version=${request.version}, capabilities=${request.capabilities}, has_token=${request.token != null}")

        // Verify token before granting root-level routing. The server socket in
        // startRootTethering() is a plain `ServerSocket(port)`, which binds to ALL
        // interfaces (0.0.0.0) -- same as VpnService's CONNECTION_WIFI path, not the
        // loopback-only CONNECTION_USB path. This handshake previously accepted any
        // HandshakeRequest unconditionally and handed back CAP_ROOT, meaning any host
        // on the same WiFi network could connect to this port and get the device's
        // entire traffic routed through it via iptables DNAT, with zero authentication
        // -- the same bypass class already fixed for VpnService's WiFi/Bluetooth paths
        // and for TokenManager.verifyWifiToken/verifyBluetoothToken. Root tethering has
        // no connection-type concept of its own (it's always reached over the network,
        // never over USB loopback), so it reuses the WiFi token here.
        if (!TokenManager.verifyWifiToken(applicationContext, request.token)) {
            Log.e(TAG, "Root tethering token verification failed")
            val errorMsg = ErrorMessage(
                errorCode = Protocol.ERR_AUTH_FAILED,
                message = "Authentication failed: invalid token"
            )
            writeMessage(output, errorMsg)
            throw SecurityException("Root tethering authentication failed")
        }

        // Send response with ROOT capability flag
        // ip_address = 0 indicates root mode (no VPN interface)
        val capabilities = Protocol.CAP_USB or Protocol.CAP_WIFI or Protocol.CAP_ROOT
        val response = HandshakeResponse(
            version = Protocol.PROTOCOL_VERSION,
            capabilities = capabilities,
            ipAddress = 0,  // 0 indicates root mode
            dns = 0
        )

        writeMessage(output, response)
        Log.d(TAG, "Handshake complete (ROOT mode)")
    }

    private suspend fun handleControlMessages() = withContext(Dispatchers.IO) {
        val input = clientSocket?.getInputStream() ?: return@withContext
        val output = clientSocket?.getOutputStream() ?: return@withContext

        // Start keepalive sender
        val keepaliveJob = launch {
            while (isRunning) {
                delay(30000)
                try {
                    writeMessage(output, Keepalive)
                    Log.d(TAG, "Sent keepalive")
                } catch (e: Exception) {
                    Log.e(TAG, "Error sending keepalive", e)
                    break
                }
            }
        }

        // Start stats sender
        val statsJob = launch {
            while (isRunning) {
                delay(5000)
                try {
                    val uptime = ((System.currentTimeMillis() - startTime) / 1000).toInt()
                    // In root mode, we don't have exact byte counts
                    // Could potentially read from /proc/net/dev
                    val stats = StatsUpdate(bytesSent, bytesReceived, uptime)
                    writeMessage(output, stats)
                    Log.d(TAG, "Sent stats: uptime=${uptime}s")
                } catch (e: Exception) {
                    Log.e(TAG, "Error sending stats", e)
                    break
                }
            }
        }

        // Handle incoming control messages
        while (isRunning) {
            try {
                val message = readMessage(input)
                when (message) {
                    is Disconnect -> {
                        Log.d(TAG, "Desktop requested disconnect: ${message.reason}")
                        isRunning = false
                        break
                    }
                    is Keepalive -> {
                        // Desktop keepalive received, send response
                        writeMessage(output, Keepalive)
                    }
                    is ConfigUpdate -> {
                        Log.d(TAG, "Config update received: ${message.config}")
                        // Handle dynamic config updates if needed
                    }
                    else -> {
                        Log.w(TAG, "Unexpected message in root mode: $message")
                    }
                }
            } catch (e: EOFException) {
                Log.d(TAG, "Desktop closed connection")
                break
            } catch (e: Exception) {
                if (isRunning) Log.e(TAG, "Error reading control message", e)
                break
            }
        }

        keepaliveJob.cancel()
        statsJob.cancel()
        stopSelf()
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.d(TAG, "RootTetheringService destroyed")
        isRunning = false

        // Must complete synchronously and *before* serviceScope.cancel() below --
        // this used to be `serviceScope.launch { iptablesManager?.cleanupRules() ... }`
        // immediately followed by serviceScope.cancel(), which raced: cancelling the
        // scope cancels its just-launched children too, so the iptables NAT/routing
        // cleanup could be skipped or interrupted mid-run, leaving the device's
        // iptables rules stuck in root-tethering mode after the service stopped.
        // runBlocking(NonCancellable) guarantees cleanup actually finishes first.
        runBlocking(NonCancellable) {
            try {
                iptablesManager?.cleanupRules()
            } catch (e: Exception) {
                Log.e(TAG, "Error cleaning up iptables rules on destroy", e)
            }

            try {
                clientSocket?.close()
                serverSocket?.close()
            } catch (e: Exception) {
                Log.e(TAG, "Error closing sockets on destroy", e)
            }
        }

        serviceScope.cancel()
    }

    private fun readMessage(input: InputStream): Message {
        // Read header (5 bytes)
        val header = ByteArray(5)
        var offset = 0
        while (offset < 5) {
            val read = input.read(header, offset, 5 - offset)
            if (read < 0) throw EOFException("Stream closed")
            offset += read
        }

        // Parse header
        val magic = ((header[0].toInt() and 0xFF) shl 8) or (header[1].toInt() and 0xFF)
        if (magic != Protocol.MAGIC.toInt()) {
            throw ProtocolException("Invalid magic bytes: $magic")
        }

        val length = ((header[3].toInt() and 0xFF) shl 8) or (header[4].toInt() and 0xFF)

        // Read payload
        val payload = ByteArray(length)
        offset = 0
        while (offset < length) {
            val read = input.read(payload, offset, length - offset)
            if (read < 0) throw EOFException("Stream closed")
            offset += read
        }

        // Reconstruct full message
        val fullMessage = header + payload
        return Message.fromBytes(fullMessage)
    }

    private fun writeMessage(output: OutputStream, message: Message) {
        val data = message.toBytes()
        output.write(data)
        output.flush()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Root Tethering Service",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "ET Phone Home root tethering status"
            }

            val notificationManager = getSystemService(NotificationManager::class.java)
            notificationManager.createNotificationChannel(channel)
        }
    }

    private fun createNotification() =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Root Tethering Active")
            .setContentText("Using kernel-level routing for better performance")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentIntent(
                PendingIntent.getActivity(
                    this,
                    0,
                    Intent(this, MainActivity::class.java),
                    PendingIntent.FLAG_IMMUTABLE
                )
            )
            .build()
}
