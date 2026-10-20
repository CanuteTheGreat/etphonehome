package com.etphonehome.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothServerSocket
import android.bluetooth.BluetoothSocket
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.etphonehome.MainActivity
import com.etphonehome.R
import com.etphonehome.filter.FilterConfig
import com.etphonehome.log.Log
import com.etphonehome.protocol.MessageStream
import com.etphonehome.protocol.Protocol
import com.etphonehome.relay.RelayConfig
import com.etphonehome.session.SessionInfo
import com.etphonehome.session.SessionListener
import com.etphonehome.session.SessionLoop
import com.etphonehome.session.SessionOptions
import com.etphonehome.session.SessionSnapshot
import com.etphonehome.session.TcpLink
import com.etphonehome.session.TetherSession
import com.etphonehome.util.BluetoothText
import com.etphonehome.util.NsdAdvertiser
import com.etphonehome.util.Settings
import com.etphonehome.util.TokenManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.io.Closeable
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.UUID

/**
 * Shares the phone's internet with a desktop, without root and without a
 * phone VPN.
 *
 * The service listens for the desktop (USB: loopback port reached through
 * `adb forward`; WiFi: LAN port, token + TLS; Bluetooth: RFCOMM + token)
 * and runs a [TetherSession] per desktop: its userspace relay opens
 * ordinary sockets for the desktop's connections, so they use the phone's
 * network like any app, and the phone's own traffic is never touched (it
 * keeps its internet the whole time). Earlier versions captured all of the
 * phone's traffic with a VpnService and never got packets to the internet.
 */
class TetherService : Service() {

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val tag = "ETPhoneHome"

    @Volatile private var serverSocket: ServerSocket? = null
    @Volatile private var bluetoothServerSocket: BluetoothServerSocket? = null
    @Volatile private var activeConnection: Closeable? = null
    @Volatile private var sessionLoop: SessionLoop<*>? = null
    private var nsdAdvertiser: NsdAdvertiser? = null
    private var upstream: UpstreamNetworks? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var mode = CONNECTION_USB
    private var running = false

    companion object {
        private const val NOTIFICATION_ID = 1
        private const val CHANNEL_ID = "tethering_service"
        const val ACTION_STOP = "com.etphonehome.action.STOP"
        const val EXTRA_CONNECTION_TYPE = "connection_type"
        const val EXTRA_PORT = "port"
        const val DEFAULT_PORT = 8888

        const val CONNECTION_USB = "usb"
        const val CONNECTION_WIFI = "wifi"
        const val CONNECTION_BLUETOOTH = "bluetooth"

        private const val BLUETOOTH_SERVICE_NAME = "ETPhoneHome"
        // ETPhoneHome's own service UUID (not the generic Serial Port one, which
        // other apps on the phone may also listen on). The desktop
        // (runner::BLUETOOTH_SERVICE_UUID) asks BlueZ to find its RFCOMM
        // channel over SDP: Android assigns channels dynamically.
        private val BLUETOOTH_UUID: UUID = UUID.fromString("6b1c0f53-4e3a-4d7e-9c5b-455450484f4e")

        fun modeLabel(mode: String) = when (mode) {
            CONNECTION_USB -> "USB"
            CONNECTION_WIFI -> "WiFi"
            CONNECTION_BLUETOOTH -> "Bluetooth"
            else -> mode
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent == null || intent.action == ACTION_STOP) {
            shutdown(null)
            return START_NOT_STICKY
        }
        if (running) {
            Log.i(tag, "Tethering already running ($mode)")
            return START_NOT_STICKY
        }
        running = true
        mode = intent.getStringExtra(EXTRA_CONNECTION_TYPE) ?: CONNECTION_USB
        val port = intent.getIntExtra(EXTRA_PORT, DEFAULT_PORT)
        Log.i(tag, "Starting tethering: mode=$mode port=$port")
        TetherStatus.sessionsServed = 0
        TetherStatus.set(TetherState.Waiting(modeLabel(mode), "Starting…"))
        startInForeground()
        acquireWakeLock()

        scope.launch {
            try {
                when (mode) {
                    CONNECTION_USB -> serveUsb(port)
                    CONNECTION_WIFI -> serveWifi(port)
                    CONNECTION_BLUETOOTH -> serveBluetooth()
                    else -> throw IllegalArgumentException("Unsupported connection type: $mode")
                }
            } catch (e: Exception) {
                if (running) {
                    Log.e(tag, "Tethering failed", e)
                    shutdown(e.message ?: e.javaClass.simpleName)
                }
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        shutdown(null)
        scope.cancel()
        Log.d(tag, "TetherService destroyed")
        super.onDestroy()
    }

    private fun shutdown(error: String?) {
        if (!running && error == null) {
            stopSelf()
            return
        }
        Log.i(tag, "Stopping tethering")
        running = false
        sessionLoop?.stop()
        for (c in listOf(activeConnection, serverSocket, bluetoothServerSocket)) {
            try {
                c?.close()
            } catch (e: Exception) {
                Log.w(tag, "Error closing socket", e)
            }
        }
        activeConnection = null
        serverSocket = null
        bluetoothServerSocket = null
        nsdAdvertiser?.unregisterService()
        nsdAdvertiser = null
        upstream?.stop()
        upstream = null
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
        TetherStatus.set(TetherState.Stopped(error))
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    // ---------------------------------------------------------------
    // Transports
    // ---------------------------------------------------------------

    private suspend fun serveUsb(port: Int) {
        // Loopback only: reached exclusively through `adb forward` over the
        // cable, which is why USB needs no token. Binding to all interfaces
        // would let anyone on the phone's WiFi in without one.
        //
        // IPv4 127.0.0.1, not InetAddress.getLoopbackAddress(): on Android
        // that is ::1, and adbd before Android 11 connects `adb forward`
        // streams to 127.0.0.1 only -- on Android 8 (our minSdk) every USB
        // connection was refused ("adbd: failed to connect to socket
        // 'tcp:8888': Connection refused", found by the API 26 emulator
        // test). Newer adbd tries IPv4 first too.
        val server = ServerSocket(port, 50, InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1)))
        serverSocket = server
        Log.i(tag, "USB server listening on loopback port $port")
        waiting("Connect the phone by USB and run on the computer:\n  sudo etphonehome usb")
        serveTcp(server, token = null, requireTls = false)
    }

    private suspend fun serveWifi(port: Int) {
        val server = ServerSocket(port, 50, null)
        serverSocket = server
        Log.i(tag, "WiFi server listening on port $port")
        nsdAdvertiser = NsdAdvertiser(this).also { it.registerService(port) }
        upstream = UpstreamNetworks(this).also { it.start() }
        val token = TokenManager.getWifiToken(this)
        val ip = wifiIpAddress()
        val requireTls = Settings.requireEncryption(this)
        waiting(
            if (ip == null) {
                "WiFi is not connected."
            } else {
                "On the computer run:\n  sudo etphonehome wifi --host $ip --token $token" +
                    (if (requireTls) "" else "\n(encryption not required: --no-tls also works)") +
                    "\nRouter (OpenWrt/GL.iNet): LuCI > Network > Phone uplink, WiFi, phone $ip, token $token"
            },
        )
        serveTcp(server, token, requireTls)
    }

    private suspend fun serveTcp(server: ServerSocket, token: String?, requireTls: Boolean) {
        val loop = SessionLoop<Socket>(
            accept = { server.accept() },
            session = { sock ->
                activeConnection = sock
                Log.i(tag, "Desktop connected from ${sock.inetAddress}")
                val link = TcpLink.accept(sock, token)
                activeConnection = link
                val verifier: ((String?) -> Boolean)? = token?.let {
                    { presented -> TokenManager.verifyWifiToken(this, presented) }
                }
                // A router whose internet is this phone, reached over the
                // router's own Wi-Fi: pin relay sockets to another network
                // (UpstreamPolicy), or they would loop back through it.
                val up = upstream
                val downlink = if (token != null) sock.localAddress else null
                val peer = if (token != null) sock.inetAddress else null
                val relayConfig = if (up != null) {
                    RelayConfig(socketPreparer = up.preparerFor(downlink, peer), icmpEchoer = up.echoerFor(downlink, peer))
                } else {
                    RelayConfig()
                }
                runSession(
                    link.stream,
                    SessionOptions(
                        // USB arrives through adb's loopback forward: the
                        // address (::1) says nothing, so name the transport.
                        peer = if (token == null) "computer over USB" else sock.inetAddress.hostAddress ?: "desktop",
                        encrypted = link.encrypted,
                        tokenVerifier = verifier,
                        refuseReason = if (requireTls && !link.encrypted) {
                            "This phone requires an encrypted connection. Use `etphonehome wifi` " +
                                "(TLS is the default) or turn off \"Require encryption\" in the app."
                        } else {
                            null
                        },
                        capabilities = Protocol.CAP_USB or Protocol.CAP_WIFI or
                            (if (token != null) Protocol.CAP_TLS else 0),
                        abortLink = { link.abort() },
                        relayConfig = relayConfig,
                        deviceName = deviceName(),
                    ),
                )
            },
            endSession = { endSession() },
            onSessionError = { e -> Log.w(tag, "Desktop session failed: ${e.message}") },
        )
        sessionLoop = loop
        loop.run()
    }

    @Suppress("MissingPermission") // checked in MainActivity before starting this mode
    private suspend fun serveBluetooth() {
        val manager = getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        val adapter = manager.adapter ?: throw IllegalStateException("Bluetooth not available on this device")
        if (!adapter.isEnabled) throw IllegalStateException("Bluetooth is off")
        val server = adapter.listenUsingRfcommWithServiceRecord(BLUETOOTH_SERVICE_NAME, BLUETOOTH_UUID)
        bluetoothServerSocket = server
        val token = TokenManager.getBluetoothToken(this)
        Log.i(tag, "Bluetooth server listening as ${adapter.name}")
        waiting(
            "Pair the computer with this phone, then run:\n" +
                "  ${BluetoothText.desktopCommand(adapter.name, token)}",
        )
        val loop = SessionLoop<BluetoothSocket>(
            accept = { server.accept() },
            session = { sock ->
                activeConnection = sock
                Log.i(tag, "Desktop connected over Bluetooth: ${sock.remoteDevice.address}")
                runSession(
                    MessageStream(sock.inputStream, sock.outputStream),
                    SessionOptions(
                        peer = sock.remoteDevice.name ?: sock.remoteDevice.address,
                        // RFCOMM between paired devices is link-encrypted by Bluetooth.
                        encrypted = true,
                        tokenVerifier = { presented -> TokenManager.verifyBluetoothToken(this, presented) },
                        capabilities = Protocol.CAP_BLUETOOTH,
                        abortLink = {
                            try {
                                sock.close()
                            } catch (_: java.io.IOException) {
                            }
                        },
                        deviceName = deviceName(),
                    ),
                )
            },
            endSession = { endSession() },
            onSessionError = { e -> Log.w(tag, "Bluetooth session failed: ${e.message}") },
        )
        sessionLoop = loop
        loop.run()
    }

    private suspend fun runSession(stream: MessageStream, options: SessionOptions) {
        val label = modeLabel(mode)
        TetherSession(
            stream,
            options,
            object : SessionListener {
                override fun onConnected(info: SessionInfo) {
                    TetherStatus.sessionsServed++
                    TetherStatus.set(TetherState.Connected(label, info))
                    refreshNotification()
                }

                override fun onStats(stats: SessionSnapshot) {
                    TetherStatus.update { s -> if (s is TetherState.Connected) s.copy(stats = stats) else s }
                    refreshNotification()
                }

                override fun onFilterChanged(config: FilterConfig) {
                    TetherStatus.update { s -> if (s is TetherState.Connected) s.copy(filter = config) else s }
                }
            },
        ).run()
    }

    private var waitingHint = ""

    private fun waiting(hint: String) {
        waitingHint = hint
        TetherStatus.set(TetherState.Waiting(modeLabel(mode), hint))
        refreshNotification()
    }

    /** "samsung SM-N986U": how the computer/router UI names this phone. */
    private fun deviceName(): String =
        listOf(Build.MANUFACTURER, Build.MODEL).filter { !it.isNullOrBlank() }.joinToString(" ").ifBlank { "Android phone" }

    /** One desktop left; keep listening for the next. */
    private fun endSession() {
        activeConnection = null
        upstream?.releaseCellular()
        if (running) {
            Log.i(tag, "Session ended, waiting for the next desktop connection")
            waiting(waitingHint)
        }
    }

    // ---------------------------------------------------------------
    // Notification / wake lock
    // ---------------------------------------------------------------

    private fun startInForeground() {
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
        } else {
            0
        }
        ServiceCompat.startForeground(this, NOTIFICATION_ID, buildNotification(), type)
    }

    private var lastNotificationText: String? = null

    private fun refreshNotification() {
        if (!running) return
        val text = StatusText.notification(TetherStatus.state.value)
        if (text == lastNotificationText) return
        lastNotificationText = text
        getSystemService(NotificationManager::class.java)?.notify(NOTIFICATION_ID, buildNotification())
    }

    private fun buildNotification(): Notification {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE,
        )
        val stop = PendingIntent.getService(
            this, 1, Intent(this, TetherService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.notification_title))
            .setContentText(StatusText.notification(TetherStatus.state.value))
            .setSmallIcon(android.R.drawable.stat_sys_upload)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(open)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, getString(R.string.btn_stop), stop)
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.notification_channel_name),
                NotificationManager.IMPORTANCE_LOW,
            ).apply { description = getString(R.string.notification_channel_description) }
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    private fun acquireWakeLock() {
        // Keep the CPU awake while tethering: the relay must keep moving
        // the desktop's traffic with the screen off.
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "etphonehome:tether").also {
            it.setReferenceCounted(false)
            it.acquire()
        }
    }

    private fun wifiIpAddress(): String? = try {
        java.net.NetworkInterface.getNetworkInterfaces().asSequence()
            .filter { it.name.startsWith("wlan") }
            .flatMap { it.inetAddresses.asSequence() }
            .firstOrNull { !it.isLoopbackAddress && it is java.net.Inet4Address }
            ?.hostAddress
    } catch (e: Exception) {
        null
    }
}
