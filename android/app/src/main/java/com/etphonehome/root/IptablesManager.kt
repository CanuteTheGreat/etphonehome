package com.etphonehome.root

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Manager for iptables-based traffic routing
 *
 * This provides kernel-level packet routing which is more efficient
 * than the VPN userspace approach.
 */
class IptablesManager {

    private val TAG = "IptablesManager"

    // Custom chain names for our rules (to avoid conflicts)
    private val CHAIN_OUTPUT = "ETPHONEHOME_OUTPUT"
    private val CHAIN_PREROUTING = "ETPHONEHOME_PREROUTING"
    private val CHAIN_POSTROUTING = "ETPHONEHOME_POSTROUTING"

    private var desktopIp: String? = null
    private var desktopPort: Int = 8888
    private var isSetup = false

    /**
     * Setup iptables rules for root-based routing
     *
     * @param desktopIp IP address of the desktop client
     * @param desktopPort Port the desktop is listening on
     * @param excludeApps Package names of apps to exclude from routing
     * @return true if setup was successful
     */
    suspend fun setupRootRouting(
        desktopIp: String,
        desktopPort: Int,
        excludeApps: List<String> = emptyList()
    ): Boolean = withContext(Dispatchers.IO) {
        this@IptablesManager.desktopIp = desktopIp
        this@IptablesManager.desktopPort = desktopPort

        // Clean up any existing rules first
        cleanupRules()

        try {
            Log.i(TAG, "Setting up iptables routing to $desktopIp:$desktopPort")

            // Create custom chains
            executeIptables("-t nat -N $CHAIN_OUTPUT")
            executeIptables("-t nat -N $CHAIN_PREROUTING")
            executeIptables("-t nat -N $CHAIN_POSTROUTING")

            // Insert jump to our chains
            executeIptables("-t nat -I OUTPUT -j $CHAIN_OUTPUT")
            executeIptables("-t nat -I PREROUTING -j $CHAIN_PREROUTING")
            executeIptables("-t nat -I POSTROUTING -j $CHAIN_POSTROUTING")

            // Don't redirect traffic to the desktop itself (prevent routing loop)
            executeIptables("-t nat -A $CHAIN_OUTPUT -d $desktopIp -j RETURN")

            // Don't redirect local/private network traffic
            executeIptables("-t nat -A $CHAIN_OUTPUT -d 127.0.0.0/8 -j RETURN")
            executeIptables("-t nat -A $CHAIN_OUTPUT -d 10.0.0.0/8 -j RETURN")
            executeIptables("-t nat -A $CHAIN_OUTPUT -d 172.16.0.0/12 -j RETURN")
            executeIptables("-t nat -A $CHAIN_OUTPUT -d 192.168.0.0/16 -j RETURN")
            executeIptables("-t nat -A $CHAIN_OUTPUT -d 169.254.0.0/16 -j RETURN")
            executeIptables("-t nat -A $CHAIN_OUTPUT -d 224.0.0.0/4 -j RETURN") // Multicast
            executeIptables("-t nat -A $CHAIN_OUTPUT -d 255.255.255.255 -j RETURN") // Broadcast

            // Exclude specific apps if requested (by UID)
            for (packageName in excludeApps) {
                val uid = getAppUid(packageName)
                if (uid != null) {
                    Log.d(TAG, "Excluding app $packageName (UID: $uid) from routing")
                    executeIptables("-t nat -A $CHAIN_OUTPUT -m owner --uid-owner $uid -j RETURN")
                }
            }

            // Redirect all TCP traffic to desktop
            executeIptables("-t nat -A $CHAIN_OUTPUT -p tcp -j DNAT --to-destination $desktopIp:$desktopPort")

            // Redirect all UDP traffic to desktop
            executeIptables("-t nat -A $CHAIN_OUTPUT -p udp -j DNAT --to-destination $desktopIp:$desktopPort")

            // Enable IP forwarding
            RootManager.executeCommand("echo 1 > /proc/sys/net/ipv4/ip_forward")

            // Masquerade outgoing traffic
            executeIptables("-t nat -A $CHAIN_POSTROUTING -j MASQUERADE")

            isSetup = true
            Log.i(TAG, "Root routing setup complete")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to setup root routing", e)
            cleanupRules()
            false
        }
    }

    /**
     * Clean up all iptables rules created by this manager
     */
    suspend fun cleanupRules() = withContext(Dispatchers.IO) {
        Log.i(TAG, "Cleaning up iptables rules")

        // Remove jumps to our chains (ignore errors if they don't exist)
        executeIptables("-t nat -D OUTPUT -j $CHAIN_OUTPUT", ignoreErrors = true)
        executeIptables("-t nat -D PREROUTING -j $CHAIN_PREROUTING", ignoreErrors = true)
        executeIptables("-t nat -D POSTROUTING -j $CHAIN_POSTROUTING", ignoreErrors = true)

        // Flush and delete our chains
        executeIptables("-t nat -F $CHAIN_OUTPUT", ignoreErrors = true)
        executeIptables("-t nat -F $CHAIN_PREROUTING", ignoreErrors = true)
        executeIptables("-t nat -F $CHAIN_POSTROUTING", ignoreErrors = true)
        executeIptables("-t nat -X $CHAIN_OUTPUT", ignoreErrors = true)
        executeIptables("-t nat -X $CHAIN_PREROUTING", ignoreErrors = true)
        executeIptables("-t nat -X $CHAIN_POSTROUTING", ignoreErrors = true)

        isSetup = false
        Log.i(TAG, "Cleanup complete")
    }

    /**
     * Check if routing is currently set up
     */
    fun isSetup(): Boolean = isSetup

    /**
     * Execute an iptables command
     */
    private suspend fun executeIptables(rule: String, ignoreErrors: Boolean = false): Boolean {
        val result = RootManager.executeCommand("iptables $rule")
        if (!result.success && !ignoreErrors) {
            Log.e(TAG, "iptables command failed: $rule - ${result.stderr}")
            throw RuntimeException("iptables command failed: $rule")
        }
        return result.success
    }

    /**
     * Get the UID of an app by package name
     *
     * Deliberately does *not* push packageName into a shell `grep` pattern
     * (the old approach was `pm list packages -U | grep $packageName`):
     * grep does a substring match, so excluding "com.app" would also match
     * "com.app.extra" and "com.notcom.app" -- excludeApps silently applied
     * to the wrong package(s), meaning either an app the user wanted exempt
     * from routing still got DNAT'd, or an unrelated app got exempted
     * instead. Fetching the full, unfiltered list and matching the exact
     * "package:<name> uid:<uid>" line ourselves removes the substring-match
     * hazard (and the shell-quoting hazard of interpolating an arbitrary
     * string into a root shell command) entirely.
     */
    private suspend fun getAppUid(packageName: String): Int? {
        val result = RootManager.executeCommand("pm list packages -U")
        if (!result.success) return null

        val prefix = "package:$packageName uid:"
        for (line in result.stdout) {
            if (line.startsWith(prefix)) {
                return line.substring(prefix.length).trim().toIntOrNull()
            }
        }
        return null
    }
}
