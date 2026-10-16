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

        // Always trust the socket's own observed remote address for where to route
        // traffic, never the caller-supplied EXTRA_DESKTOP_IP intent extra. The whole
        // point of setupRootRouting()'s "don't redirect traffic back to the desktop"
        // RETURN rule (see IptablesManager) is to exempt the one address packets
        // should actually keep flowing to -- if that address doesn't match who the
        // kernel actually accepted a TCP connection from, every packet (including the
        // control channel/stats/keepalive after this point) gets DNAT'd into a black
        // hole on the wrong host instead of exempted, instantly losing device
        // connectivity (and routing to an attacker-chosen host, if the extra were
        // ever wired up to something less trustworthy than the OS-resolved peer
        // address). The EXTRA_DESKTOP_IP value isn't used for routing at all, only
        // logged if it disagrees with the real peer, since no current caller sets it
        // but a future one might expect it to at least be visible for debugging.
        val socketIp = clientSocket?.inetAddress?.hostAddress
            ?: throw Exception("No desktop IP available")
        if (desktopIp != null && desktopIp != socketIp) {
            Log.w(TAG, "EXTRA_DESKTOP_IP ($desktopIp) does not match actual peer " +
                "address ($socketIp); using the real socket peer for routing")
        }
        val connectedIp = socketIp

        Log.d(TAG, "Desktop connected from $connectedIp")

        // Configure socket
        clientSocket?.tcpNoDelay = true
        clientSocket?.keepAlive = true

        // Perform handshake
        val socket = clientSocket ?: throw IOException("No desktop connection")
        val stream = MessageStream(socket.getInputStream(), socket.getOutputStream())
        performHandshake(stream)

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
        handleControlMessages(stream)
    }

    private suspend fun performHandshake(stream: MessageStream) = withContext(Dispatchers.IO) {
        Log.d(TAG, "Starting handshake")
        // Root tethering is reached over the network (the server socket binds all
        // interfaces), never over USB loopback, so it always requires the WiFi
        // token before handing out CAP_ROOT routing -- see ServerHandshake for the
        // shared version/token checks. ip_address = 0 tells the desktop this is
        // root mode (no VPN interface).
        ServerHandshake.perform(
            stream,
            capabilities = Protocol.CAP_USB or Protocol.CAP_WIFI or Protocol.CAP_ROOT,
            ipAddress = 0,
            dns = 0,
            tokenVerifier = { token -> TokenManager.verifyWifiToken(applicationContext, token) }
        )
        Log.d(TAG, "Handshake complete (ROOT mode)")
    }

    private suspend fun handleControlMessages(stream: MessageStream) = withContext(Dispatchers.IO) {

        // Start keepalive sender
        val keepaliveJob = launch {
            while (isRunning) {
                delay(30000)
                try {
                    stream.write(Keepalive)
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
                    stream.write(stats)
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
                val message = stream.read()
                when (message) {
                    is Disconnect -> {
                        Log.d(TAG, "Desktop requested disconnect: ${message.reason}")
                        isRunning = false
                        break
                    }
                    is Keepalive -> {
                        // Desktop keepalive received, send response
                        stream.write(Keepalive)
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
