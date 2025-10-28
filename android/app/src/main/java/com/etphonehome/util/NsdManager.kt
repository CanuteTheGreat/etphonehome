package com.etphonehome.util

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.util.Log

/**
 * Manager for mDNS/NSD service advertisement
 *
 * Advertises the ET Phone Home service on the local network
 * so desktop clients can discover the Android device automatically.
 */
class NsdAdvertiser(private val context: Context) {

    private val tag = "NsdAdvertiser"
    private val nsdManager: NsdManager = context.getSystemService(Context.NSD_SERVICE) as NsdManager

    private var registrationListener: NsdManager.RegistrationListener? = null
    private var serviceInfo: NsdServiceInfo? = null

    companion object {
        private const val SERVICE_TYPE = "_etphonehome._tcp"
        private const val SERVICE_NAME = "ET Phone Home"
    }

    /**
     * Register the service for discovery
     */
    fun registerService(port: Int) {
        if (registrationListener != null) {
            Log.w(tag, "Service already registered")
            return
        }

        // Create service info
        serviceInfo = NsdServiceInfo().apply {
            serviceName = SERVICE_NAME
            serviceType = SERVICE_TYPE
            setPort(port)
        }

        // Create registration listener
        registrationListener = object : NsdManager.RegistrationListener {
            override fun onRegistrationFailed(serviceInfo: NsdServiceInfo?, errorCode: Int) {
                Log.e(tag, "Service registration failed: errorCode=$errorCode")
            }

            override fun onUnregistrationFailed(serviceInfo: NsdServiceInfo?, errorCode: Int) {
                Log.e(tag, "Service unregistration failed: errorCode=$errorCode")
            }

            override fun onServiceRegistered(serviceInfo: NsdServiceInfo?) {
                val actualName = serviceInfo?.serviceName
                Log.i(tag, "Service registered successfully as: $actualName")
                Log.i(tag, "Desktop clients can now discover this device via mDNS")
            }

            override fun onServiceUnregistered(serviceInfo: NsdServiceInfo?) {
                Log.i(tag, "Service unregistered: ${serviceInfo?.serviceName}")
            }
        }

        // Register the service
        try {
            nsdManager.registerService(
                serviceInfo,
                NsdManager.PROTOCOL_DNS_SD,
                registrationListener
            )
            Log.d(tag, "Registering NSD service: $SERVICE_NAME on port $port")
        } catch (e: Exception) {
            Log.e(tag, "Failed to register NSD service", e)
            registrationListener = null
        }
    }

    /**
     * Unregister the service
     */
    fun unregisterService() {
        registrationListener?.let { listener ->
            try {
                nsdManager.unregisterService(listener)
                Log.d(tag, "Unregistering NSD service")
            } catch (e: Exception) {
                Log.e(tag, "Failed to unregister NSD service", e)
            } finally {
                registrationListener = null
                serviceInfo = null
            }
        }
    }

    /**
     * Check if service is registered
     */
    fun isRegistered(): Boolean = registrationListener != null
}
