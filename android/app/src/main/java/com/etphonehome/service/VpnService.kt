package com.etphonehome.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.net.VpnService as AndroidVpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import android.util.Log
import androidx.core.app.NotificationCompat
import com.etphonehome.MainActivity
import com.etphonehome.R
import com.etphonehome.protocol.*
import com.etphonehome.util.NsdAdvertiser
import com.etphonehome.util.TokenManager
import kotlinx.coroutines.*
import java.io.*
import java.net.ServerSocket
import java.net.Socket
import java.nio.ByteBuffer
import java.util.UUID
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothServerSocket

/**
 * VPN Service for non-root tethering
 *
 * This service creates a local VPN that captures all network traffic
 * and forwards it to the desktop companion app through the active connection.
 */
class VpnService : AndroidVpnService() {

    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val tag = "ETPhoneHome"

    private var vpnInterface: ParcelFileDescriptor? = null
    private var serverSocket: ServerSocket? = null
    private var bluetoothServerSocket: BluetoothServerSocket? = null
    private var clientSocket: Socket? = null
    private var nsdAdvertiser: NsdAdvertiser? = null
    private var currentConnectionType: String = CONNECTION_USB

    private var bytesSent: Long = 0
    private var bytesReceived: Long = 0
    private var startTime: Long = 0

    private var isRunning = false

    companion object {
        private const val NOTIFICATION_ID = 1
        private const val CHANNEL_ID = "tethering_service"

        // VPN Configuration
        private const val VPN_ADDRESS = "10.0.0.2"
        private const val VPN_ROUTE = "0.0.0.0"
        private const val VPN_DNS = "8.8.8.8"
        private const val VPN_MTU = 1500

        // Bluetooth Configuration
        private const val BLUETOOTH_SERVICE_NAME = "ETPhoneHome"
        private val BLUETOOTH_UUID: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB") // SPP UUID

        // Connection
        const val EXTRA_CONNECTION_TYPE = "connection_type"
        const val EXTRA_HOST = "host"
        const val EXTRA_PORT = "port"

        const val CONNECTION_USB = "usb"
        const val CONNECTION_WIFI = "wifi"
        const val CONNECTION_BLUETOOTH = "bluetooth"
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        Log.d(tag, "VpnService created")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent == null) {
            stopSelf()
            return START_NOT_STICKY
        }

        val connectionType = intent.getStringExtra(EXTRA_CONNECTION_TYPE) ?: CONNECTION_USB
        val host = intent.getStringExtra(EXTRA_HOST) ?: "localhost"
        val port = intent.getIntExtra(EXTRA_PORT, 8888)

        Log.d(tag, "Starting VPN service: type=$connectionType, host=$host, port=$port")

        startForeground(NOTIFICATION_ID, createNotification(connectionType))

        currentConnectionType = connectionType

        serviceScope.launch {
            try {
                when (connectionType) {
                    CONNECTION_USB -> startUsbTethering(port)
                    CONNECTION_WIFI -> startWifiTethering(host, port)
                    CONNECTION_BLUETOOTH -> startBluetoothTethering()
                    else -> {
                        Log.e(tag, "Unsupported connection type: $connectionType")
                        stopSelf()
                    }
                }
            } catch (e: Exception) {
                Log.e(tag, "Error starting VPN", e)
                stopSelf()
            }
        }

        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.d(tag, "VpnService destroyed")
        isRunning = false

        try {
            // Unregister NSD service
            nsdAdvertiser?.unregisterService()
            nsdAdvertiser = null

            clientSocket?.close()
            serverSocket?.close()
            bluetoothServerSocket?.close()
            vpnInterface?.close()
        } catch (e: Exception) {
            Log.e(tag, "Error closing connections", e)
        }

        serviceScope.cancel()
    }

    /**
     * Start USB tethering mode - act as server waiting for desktop connection
     */
    private suspend fun startUsbTethering(port: Int) = withContext(Dispatchers.IO) {
        Log.d(tag, "Starting USB tethering server on port $port")

        // Create server socket
        serverSocket = ServerSocket(port)
        Log.d(tag, "Server socket listening on port $port")

        // Wait for desktop connection
        Log.d(tag, "Waiting for desktop connection...")
        clientSocket = serverSocket?.accept()
        Log.d(tag, "Desktop connected from ${clientSocket?.inetAddress}")

        // Configure socket
        clientSocket?.tcpNoDelay = true
        clientSocket?.soTimeout = 0  // No timeout - let keepalive handle dead connections
        clientSocket?.keepAlive = true

        // Start VPN and packet forwarding
        startVpnAndForwarding()
    }

    /**
     * Start WiFi tethering mode - act as server waiting for desktop connection
     */
    private suspend fun startWifiTethering(@Suppress("UNUSED_PARAMETER") host: String, port: Int) = withContext(Dispatchers.IO) {
        Log.d(tag, "Starting WiFi tethering server on port $port")

        // Create server socket bound to all interfaces
        serverSocket = ServerSocket(port, 50, null)
        Log.d(tag, "WiFi server socket listening on port $port")

        // Register mDNS/NSD service for automatic discovery
        nsdAdvertiser = NsdAdvertiser(this@VpnService)
        nsdAdvertiser?.registerService(port)

        // Log WiFi IP address for user
        val wifiIp = getWifiIpAddress()
        if (wifiIp != null) {
            Log.i(tag, "WiFi IP Address: $wifiIp")
            Log.i(tag, "Desktop should connect to: $wifiIp:$port")
        }

        // Wait for desktop connection
        Log.d(tag, "Waiting for desktop connection on WiFi...")
        clientSocket = serverSocket?.accept()
        Log.d(tag, "Desktop connected from ${clientSocket?.inetAddress}")

        // Configure socket
        clientSocket?.tcpNoDelay = true
        clientSocket?.soTimeout = 0  // No timeout - let keepalive handle dead connections
        clientSocket?.keepAlive = true

        // Start VPN and packet forwarding
        startVpnAndForwarding()
    }

    /**
     * Start Bluetooth tethering mode - act as server waiting for desktop connection
     */
    @Suppress("MissingPermission") // Permissions checked at runtime via MainActivity
    private suspend fun startBluetoothTethering() = withContext(Dispatchers.IO) {
        Log.d(tag, "Starting Bluetooth tethering server")

        // Get Bluetooth adapter
        val bluetoothManager = getSystemService(android.content.Context.BLUETOOTH_SERVICE) as BluetoothManager
        val bluetoothAdapter = bluetoothManager.adapter
            ?: throw Exception("Bluetooth not available on this device")

        if (!bluetoothAdapter.isEnabled) {
            throw Exception("Bluetooth is not enabled")
        }

        // Create Bluetooth server socket
        bluetoothServerSocket = bluetoothAdapter.listenUsingRfcommWithServiceRecord(
            BLUETOOTH_SERVICE_NAME,
            BLUETOOTH_UUID
        )
        Log.d(tag, "Bluetooth server socket listening")
        Log.i(tag, "Bluetooth device name: ${bluetoothAdapter.name}")
        Log.i(tag, "Bluetooth address: ${bluetoothAdapter.address}")
        Log.i(tag, "Desktop should pair and connect to this device")

        // Wait for desktop connection
        Log.d(tag, "Waiting for desktop connection via Bluetooth...")
        val bluetoothSocket = bluetoothServerSocket?.accept()
            ?: throw Exception("Failed to accept Bluetooth connection")
        Log.d(tag, "Desktop connected from ${bluetoothSocket.remoteDevice.name} (${bluetoothSocket.remoteDevice.address})")

        // Note: BluetoothSocket doesn't expose a regular Socket, but we can use its streams
        // We'll need to adapt our code slightly to work with BluetoothSocket
        // For now, we'll store it as a regular Socket won't work, so we need a different approach

        // Actually, let's wrap the Bluetooth streams in a way compatible with our existing code
        // We can't directly use clientSocket for Bluetooth, so we'll need to refactor slightly
        // For the prototype, we'll just use the input/output streams directly

        // Configure socket (BluetoothSocket doesn't have all TCP socket options)
        // Just verify it's connected
        if (!bluetoothSocket.isConnected) {
            throw Exception("Bluetooth socket not connected")
        }

        // We need to handle this differently - Bluetooth uses a different socket type
        // Let's create a wrapper or adapt the forwarding logic
        // For now, store it in a compatible way
        clientSocket = null // We'll use Bluetooth socket directly in forwarding

        // Start VPN and packet forwarding with Bluetooth socket
        startVpnAndForwardingBluetooth(bluetoothSocket)
    }

    /**
     * Start VPN interface and packet forwarding for Bluetooth
     */
    private suspend fun startVpnAndForwardingBluetooth(bluetoothSocket: android.bluetooth.BluetoothSocket) = withContext(Dispatchers.IO) {
        // Establish VPN interface
        Log.d(tag, "Establishing VPN interface")

        val builder = Builder()
            .setSession("ET Phone Home")
            .addAddress(VPN_ADDRESS, 24)
            .addRoute(VPN_ROUTE, 0)
            .addDnsServer(VPN_DNS)
            .setMtu(VPN_MTU)
            .setBlocking(false)

        vpnInterface = builder.establish() ?: throw Exception("Failed to establish VPN")
        Log.d(tag, "VPN interface established")

        // Perform handshake using Bluetooth streams
        val input = bluetoothSocket.inputStream
        val output = bluetoothSocket.outputStream

        // Handshake
        Log.d(tag, "Starting handshake")
        val request = readMessage(input) as? HandshakeRequest
            ?: throw ProtocolException("Expected HandshakeRequest")

        Log.d(tag, "Received handshake request: version=${request.version}, capabilities=${request.capabilities}")

        val capabilities = Protocol.CAP_USB or Protocol.CAP_WIFI or Protocol.CAP_BLUETOOTH
        val response = HandshakeResponse(
            version = Protocol.PROTOCOL_VERSION,
            capabilities = capabilities,
            ipAddress = ipToInt(VPN_ADDRESS),
            dns = ipToInt(VPN_DNS)
        )

        writeMessage(output, response)
        Log.d(tag, "Handshake complete")

        isRunning = true
        startTime = System.currentTimeMillis()

        Log.d(tag, "Starting packet forwarding")

        // Start packet forwarding with Bluetooth streams
        coroutineScope {
            launch { forwardFromVpnToDesktopBluetooth(output) }
            launch { forwardFromDesktopToVpnBluetooth(input) }
            launch { sendPeriodicStatsBluetooth(output) }
            launch { sendPeriodicKeepaliveBluetooth(output) }
        }
    }

    /**
     * Forward packets from VPN to desktop via Bluetooth
     */
    private suspend fun forwardFromVpnToDesktopBluetooth(output: OutputStream) = withContext(Dispatchers.IO) {
        val vpnInput = FileInputStream(vpnInterface!!.fileDescriptor)
        val buffer = ByteArray(VPN_MTU)

        Log.d(tag, "Started VPN -> Desktop (Bluetooth) forwarding")

        while (isRunning) {
            try {
                val length = vpnInput.read(buffer)
                if (length > 0) {
                    val packet = buffer.copyOf(length)
                    val message = IpPacket(packet)
                    writeMessage(output, message)
                    bytesSent += length
                }
            } catch (e: Exception) {
                if (isRunning) {
                    Log.e(tag, "Error forwarding VPN -> Desktop (Bluetooth)", e)
                }
                break
            }
        }

        Log.d(tag, "Stopped VPN -> Desktop (Bluetooth) forwarding")
    }

    /**
     * Forward packets from desktop to VPN via Bluetooth
     */
    private suspend fun forwardFromDesktopToVpnBluetooth(input: InputStream) = withContext(Dispatchers.IO) {
        val vpnOutput = FileOutputStream(vpnInterface!!.fileDescriptor)

        Log.d(tag, "Started Desktop (Bluetooth) -> VPN forwarding")

        while (isRunning) {
            try {
                val message = readMessage(input)

                when (message) {
                    is IpPacket -> {
                        vpnOutput.write(message.packetData)
                        vpnOutput.flush()
                        bytesReceived += message.packetData.size
                    }
                    is Disconnect -> {
                        Log.d(tag, "Desktop requested disconnect: ${message.reason}")
                        isRunning = false
                        break
                    }
                    is Keepalive -> {
                        // Just a keepalive, ignore
                    }
                    is ConfigUpdate -> {
                        Log.d(tag, "Received config update: ${message.config}")
                    }
                    else -> {
                        Log.w(tag, "Unexpected message type: $message")
                    }
                }
            } catch (e: EOFException) {
                Log.d(tag, "Desktop closed connection")
                break
            } catch (e: Exception) {
                if (isRunning) {
                    Log.e(tag, "Error forwarding Desktop (Bluetooth) -> VPN", e)
                }
                break
            }
        }

        Log.d(tag, "Stopped Desktop (Bluetooth) -> VPN forwarding")
        stopSelf()
    }

    /**
     * Send periodic statistics via Bluetooth
     */
    private suspend fun sendPeriodicStatsBluetooth(output: OutputStream) = withContext(Dispatchers.IO) {
        while (isRunning) {
            delay(5000)

            val uptime = ((System.currentTimeMillis() - startTime) / 1000).toInt()
            val stats = StatsUpdate(bytesSent, bytesReceived, uptime)

            try {
                writeMessage(output, stats)
                Log.d(tag, "Sent stats (Bluetooth): sent=$bytesSent, received=$bytesReceived, uptime=${uptime}s")
            } catch (e: Exception) {
                Log.e(tag, "Error sending stats (Bluetooth)", e)
                break
            }
        }
    }

    /**
     * Send periodic keepalive via Bluetooth
     */
    private suspend fun sendPeriodicKeepaliveBluetooth(output: OutputStream) = withContext(Dispatchers.IO) {
        while (isRunning) {
            delay(30000)

            try {
                writeMessage(output, Keepalive)
                Log.d(tag, "Sent keepalive (Bluetooth)")
            } catch (e: Exception) {
                Log.e(tag, "Error sending keepalive (Bluetooth)", e)
                break
            }
        }
    }

    /**
     * Start VPN interface and packet forwarding
     */
    private suspend fun startVpnAndForwarding() = withContext(Dispatchers.IO) {
        // Establish VPN interface
        Log.d(tag, "Establishing VPN interface")

        val builder = Builder()
            .setSession("ET Phone Home")
            .addAddress(VPN_ADDRESS, 24)
            .addRoute(VPN_ROUTE, 0)
            .addDnsServer(VPN_DNS)
            .setMtu(VPN_MTU)
            .setBlocking(false)

        vpnInterface = builder.establish() ?: throw Exception("Failed to establish VPN")
        Log.d(tag, "VPN interface established")

        // Perform handshake
        performHandshake()

        isRunning = true
        startTime = System.currentTimeMillis()

        Log.d(tag, "Starting packet forwarding")

        // Start packet forwarding
        coroutineScope {
            launch { forwardFromVpnToDesktop() }
            launch { forwardFromDesktopToVpn() }
            launch { sendPeriodicStats() }
            launch { sendPeriodicKeepalive() }
        }
    }

    /**
     * Perform handshake with desktop
     */
    private suspend fun performHandshake() = withContext(Dispatchers.IO) {
        Log.d(tag, "Starting handshake")

        val input = clientSocket?.getInputStream() ?: throw IOException("No input stream")
        val output = clientSocket?.getOutputStream() ?: throw IOException("No output stream")

        // Wait for handshake request from desktop
        val request = readMessage(input) as? HandshakeRequest
            ?: throw ProtocolException("Expected HandshakeRequest")

        Log.d(tag, "Received handshake request: version=${request.version}, capabilities=${request.capabilities}, has_token=${request.token != null}")

        // Verify token for WiFi connections
        if (currentConnectionType == CONNECTION_WIFI) {
            if (!TokenManager.verifyWifiToken(this@VpnService, request.token)) {
                Log.e(tag, "WiFi token verification failed")
                val errorMsg = ErrorMessage(
                    errorCode = Protocol.ERR_AUTH_FAILED,
                    message = "Authentication failed: invalid token"
                )
                writeMessage(output, errorMsg)
                throw SecurityException("WiFi authentication failed")
            }
        }

        // Send handshake response
        val capabilities = Protocol.CAP_USB or Protocol.CAP_WIFI
        val response = HandshakeResponse(
            version = Protocol.PROTOCOL_VERSION,
            capabilities = capabilities,
            ipAddress = ipToInt(VPN_ADDRESS),
            dns = ipToInt(VPN_DNS)
        )

        writeMessage(output, response)
        Log.d(tag, "Handshake complete")
    }

    /**
     * Forward packets from VPN interface to desktop
     */
    private suspend fun forwardFromVpnToDesktop() = withContext(Dispatchers.IO) {
        val vpnInput = FileInputStream(vpnInterface!!.fileDescriptor)
        val output = clientSocket?.getOutputStream() ?: return@withContext
        val buffer = ByteArray(VPN_MTU)

        Log.d(tag, "Started VPN -> Desktop forwarding")

        while (isRunning) {
            try {
                val length = vpnInput.read(buffer)
                if (length > 0) {
                    // Read IP packet from VPN
                    val packet = buffer.copyOf(length)

                    // Wrap in protocol message and send to desktop
                    val message = IpPacket(packet)
                    writeMessage(output, message)

                    bytesSent += length
                }
            } catch (e: Exception) {
                if (isRunning) {
                    Log.e(tag, "Error forwarding VPN -> Desktop", e)
                }
                break
            }
        }

        Log.d(tag, "Stopped VPN -> Desktop forwarding")
    }

    /**
     * Forward packets from desktop to VPN interface
     */
    private suspend fun forwardFromDesktopToVpn() = withContext(Dispatchers.IO) {
        val vpnOutput = FileOutputStream(vpnInterface!!.fileDescriptor)
        val input = clientSocket?.getInputStream() ?: return@withContext

        Log.d(tag, "Started Desktop -> VPN forwarding")

        while (isRunning) {
            try {
                val message = readMessage(input)

                when (message) {
                    is IpPacket -> {
                        // Write IP packet to VPN interface
                        vpnOutput.write(message.packetData)
                        vpnOutput.flush()
                        bytesReceived += message.packetData.size
                    }
                    is Disconnect -> {
                        Log.d(tag, "Desktop requested disconnect: ${message.reason}")
                        isRunning = false
                        break
                    }
                    is Keepalive -> {
                        // Just a keepalive, ignore
                    }
                    is ConfigUpdate -> {
                        Log.d(tag, "Received config update: ${message.config}")
                    }
                    else -> {
                        Log.w(tag, "Unexpected message type: $message")
                    }
                }
            } catch (e: EOFException) {
                Log.d(tag, "Desktop closed connection")
                break
            } catch (e: Exception) {
                if (isRunning) {
                    Log.e(tag, "Error forwarding Desktop -> VPN", e)
                }
                break
            }
        }

        Log.d(tag, "Stopped Desktop -> VPN forwarding")
        stopSelf()
    }

    /**
     * Send periodic statistics to desktop
     */
    private suspend fun sendPeriodicStats() = withContext(Dispatchers.IO) {
        val output = clientSocket?.getOutputStream() ?: return@withContext

        while (isRunning) {
            delay(5000) // Send stats every 5 seconds

            val uptime = ((System.currentTimeMillis() - startTime) / 1000).toInt()
            val stats = StatsUpdate(bytesSent, bytesReceived, uptime)

            try {
                writeMessage(output, stats)
                Log.d(tag, "Sent stats: sent=$bytesSent, received=$bytesReceived, uptime=${uptime}s")
            } catch (e: Exception) {
                Log.e(tag, "Error sending stats", e)
                break
            }
        }
    }

    /**
     * Send periodic keepalive messages
     */
    private suspend fun sendPeriodicKeepalive() = withContext(Dispatchers.IO) {
        val output = clientSocket?.getOutputStream() ?: return@withContext

        while (isRunning) {
            delay(30000) // Send keepalive every 30 seconds

            try {
                writeMessage(output, Keepalive)
                Log.d(tag, "Sent keepalive")
            } catch (e: Exception) {
                Log.e(tag, "Error sending keepalive", e)
                break
            }
        }
    }

    /**
     * Read a message from input stream
     */
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

    /**
     * Write a message to output stream
     */
    private fun writeMessage(output: OutputStream, message: Message) {
        val data = message.toBytes()
        output.write(data)
        output.flush()
    }

    /**
     * Get the WiFi IP address of this device
     */
    private fun getWifiIpAddress(): String? {
        try {
            val networkInterfaces = java.net.NetworkInterface.getNetworkInterfaces()
            for (networkInterface in networkInterfaces) {
                // Look for wlan0 interface (WiFi)
                if (networkInterface.name.startsWith("wlan")) {
                    val addresses = networkInterface.inetAddresses
                    for (address in addresses) {
                        if (!address.isLoopbackAddress && address is java.net.Inet4Address) {
                            return address.hostAddress
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(tag, "Error getting WiFi IP", e)
        }
        return null
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.notification_channel_name),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = getString(R.string.notification_channel_description)
            }

            val notificationManager = getSystemService(NotificationManager::class.java)
            notificationManager.createNotificationChannel(channel)
        }
    }

    private fun createNotification(connectionType: String) =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.notification_title))
            .setContentText(getString(R.string.notification_text, connectionType))
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

    private fun ipToInt(ip: String): Int {
        val parts = ip.split(".")
        return ((parts[0].toInt() and 0xFF) shl 24) or
               ((parts[1].toInt() and 0xFF) shl 16) or
               ((parts[2].toInt() and 0xFF) shl 8) or
               (parts[3].toInt() and 0xFF)
    }
}
