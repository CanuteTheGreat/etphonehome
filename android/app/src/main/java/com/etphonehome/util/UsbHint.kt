package com.etphonehome.util

/**
 * What the phone can tell about its own USB link, for a hint under the status when a computer or
 * router can't reach ETPH over the cable. Plain Kotlin (UsbHintTest); MainActivity fills it from
 * the sticky android.hardware.usb.action.USB_STATE broadcast and Settings.Global.ADB_ENABLED.
 *
 * Samsung's Auto Blocker (One UI 6+, Android 14+; Settings > Security and privacy > Auto Blocker)
 * blocks "commands by USB cable": USB debugging stays switched on in Developer options but the
 * phone no longer offers it on the cable. Apps can't read Auto Blocker's switch, so it is inferred
 * from exactly that combination (found on an owner's Galaxy SM-F976U1 on a GL.iNet router).
 */
data class UsbLinkState(
    /** The cable carries data to a computer/router (USB_STATE "connected" + "configured"). */
    val connected: Boolean,
    /** USB functions the phone offers right now. */
    val adbFunction: Boolean,
    val accessoryFunction: Boolean,
    /** Developer options > USB debugging switch. */
    val adbEnabled: Boolean,
    val samsung: Boolean,
    val sdk: Int,
)

object UsbHint {
    const val AUTO_BLOCKER_PATH = "Settings > Security and privacy > Auto Blocker"

    /** True when Samsung's Auto Blocker is (almost certainly) hiding USB debugging. */
    fun autoBlockerSuspected(s: UsbLinkState): Boolean =
        s.samsung && s.sdk >= 34 && s.connected && s.adbEnabled && !s.adbFunction

    /**
     * The hint, or null when nothing needs saying. [waitingForUsb]: sharing is on and no computer
     * or router is connected yet.
     */
    fun text(s: UsbLinkState, waitingForUsb: Boolean): String? {
        if (!waitingForUsb || !s.connected) return null
        if (s.accessoryFunction) return null // a router/computer is talking to ETPH as an accessory
        if (autoBlockerSuspected(s)) {
            return "Samsung Auto Blocker is blocking USB debugging on this cable. Up-to-date ETPH routers " +
                "and computers don't need it (they ask \"Open ETPH ...?\": tap OK). For older ones, turn " +
                "Auto Blocker off: $AUTO_BLOCKER_PATH."
        }
        if (!s.adbFunction && s.samsung && s.sdk >= 34) {
            return "Plugged in. When the router or computer asks \"Open ETPH ...?\", tap OK. Nothing " +
                "happens? Use a data cable, and turn off Samsung Auto Blocker ($AUTO_BLOCKER_PATH): it " +
                "can block USB connections."
        }
        if (!s.adbFunction) {
            return "Plugged in. When the router or computer asks \"Open ETPH ...?\", tap OK. Nothing happens? " +
                "Use a data cable (not a charge-only one)."
        }
        return null
    }
}
