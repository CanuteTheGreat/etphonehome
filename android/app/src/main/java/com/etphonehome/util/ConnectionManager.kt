package com.etphonehome.util

import android.content.Context
import android.content.Intent
import android.net.VpnService
import androidx.core.content.ContextCompat
import com.etphonehome.service.VpnService as EtVpnService

/**
 * Manager for tethering connections
 */
object ConnectionManager {

    /**
     * Connection state
     */
    enum class ConnectionState {
        DISCONNECTED,
        CONNECTING,
        CONNECTED,
        DISCONNECTING,
        ERROR
    }

    /**
     * Connection type
     */
    enum class ConnectionType(val value: String) {
        USB(EtVpnService.CONNECTION_USB),
        WIFI(EtVpnService.CONNECTION_WIFI),
        BLUETOOTH(EtVpnService.CONNECTION_BLUETOOTH)
    }

    /**
     * Connection configuration
     */
    data class ConnectionConfig(
        val type: ConnectionType,
        val host: String = "localhost",
        val port: Int = 8888
    )

    private var currentState: ConnectionState = ConnectionState.DISCONNECTED
    private var currentConfig: ConnectionConfig? = null

    /**
     * Get VPN permission intent
     */
    fun getVpnPermissionIntent(context: Context): Intent? {
        return VpnService.prepare(context)
    }

    /**
     * Check if VPN permission is granted
     */
    fun hasVpnPermission(context: Context): Boolean {
        return VpnService.prepare(context) == null
    }

    /**
     * Start tethering connection
     */
    fun startConnection(context: Context, config: ConnectionConfig) {
        if (currentState != ConnectionState.DISCONNECTED) {
            throw IllegalStateException("Connection already active")
        }

        currentConfig = config
        currentState = ConnectionState.CONNECTING

        val intent = Intent(context, EtVpnService::class.java).apply {
            putExtra(EtVpnService.EXTRA_CONNECTION_TYPE, config.type.value)
            putExtra(EtVpnService.EXTRA_HOST, config.host)
            putExtra(EtVpnService.EXTRA_PORT, config.port)
        }

        ContextCompat.startForegroundService(context, intent)
        currentState = ConnectionState.CONNECTED
    }

    /**
     * Stop tethering connection
     */
    fun stopConnection(context: Context) {
        if (currentState == ConnectionState.DISCONNECTED) {
            return
        }

        currentState = ConnectionState.DISCONNECTING

        val intent = Intent(context, EtVpnService::class.java)
        context.stopService(intent)

        currentState = ConnectionState.DISCONNECTED
        currentConfig = null
    }

    /**
     * Get current connection state
     */
    fun getState(): ConnectionState = currentState

    /**
     * Get current connection config
     */
    fun getConfig(): ConnectionConfig? = currentConfig

    /**
     * Check if connected
     */
    fun isConnected(): Boolean = currentState == ConnectionState.CONNECTED
}
