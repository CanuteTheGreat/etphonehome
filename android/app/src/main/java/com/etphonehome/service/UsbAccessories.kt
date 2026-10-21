package com.etphonehome.service

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.hardware.usb.UsbAccessory
import android.hardware.usb.UsbManager
import android.os.ParcelFileDescriptor
import com.etphonehome.log.Log
import com.etphonehome.session.AccessoryInputStream
import com.etphonehome.session.AccessoryPreamble
import com.etphonehome.session.CoalescingOutputStream
import java.io.Closeable
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

/**
 * The phone's end of the USB accessory link (Android Open Accessory). A computer or router with
 * ETPH switches the phone into accessory mode with our strings (manufacturer "Errant Solutions",
 * model "ETPH", see res/xml/accessory_filter.xml); Android then offers "Open ETPH ...?"
 * (AccessoryActivity) and grants us the accessory. This class finds it, opens it, and waits for
 * the host's preamble ([AccessoryPreamble]).
 *
 * Android's accessory file can only be open once, and a read blocked in it is not woken by
 * close(): it returns when the host sends something (it repeats HELLO every second while it
 * tries to connect) or the cable goes. So an open that fails is simply retried.
 */
class UsbAccessories(private val context: Context) {
    class Connection(
        private val pfd: ParcelFileDescriptor,
        val input: InputStream,
        val output: OutputStream,
        val description: String,
    ) : Closeable {
        override fun close() {
            try {
                output.close()
            } catch (_: IOException) {
            }
            try {
                pfd.close()
            } catch (_: IOException) {
            }
        }
    }

    private val tag = "ETPhoneHome"
    private val usb = context.getSystemService(Context.USB_SERVICE) as? UsbManager
    private val lock = Object()
    @Volatile private var stopped = false
    @Volatile private var opening: ParcelFileDescriptor? = null
    private var askedFor: UsbAccessory? = null
    private var firstSeenMs = 0L

    fun wake() = synchronized(lock) { lock.notifyAll() }

    fun stop() {
        stopped = true
        try {
            opening?.close()
        } catch (_: IOException) {
        }
        wake()
    }

    private fun pause(ms: Long) = synchronized(lock) { if (!stopped) lock.wait(ms) }

    /** Blocks until a host has said START on our accessory; null once stopped. */
    fun accept(running: () -> Boolean): Connection? {
        while (!stopped && running()) {
            val c = try {
                tryOnce()
            } catch (e: SecurityException) {
                // Samsung's UsbManager can throw instead of answering hasPermission(): no permission yet.
                Log.i(tag, "USB accessory: no permission yet (${e.message?.take(80)})")
                null
            }
            if (c != null) return c
        }
        return null
    }

    /** One look: a connection, or null after a pause (nothing there, no permission, busy). */
    private fun tryOnce(): Connection? {
        val usb = usb ?: run { pause(5000); return null }
        val acc = usb.accessoryList?.firstOrNull(::isOurs)
        if (acc == null) {
            askedFor = null
            firstSeenMs = 0
            pause(1500)
            return null
        }
        if (!usb.hasPermission(acc)) {
            // Android asks "Open ETPH to handle ...?" by itself when the accessory attaches (OK
            // grants it; AccessoryActivity then wakes us). Only if that didn't happen (dismissed, or
            // the accessory was already there), ask once per plug-in.
            val now = System.currentTimeMillis()
            if (firstSeenMs == 0L) firstSeenMs = now
            if (askedFor != acc && now - firstSeenMs > ASK_AFTER_MS) {
                askedFor = acc
                Log.i(tag, "USB accessory ${acc.description}: asking for permission")
                val pi = PendingIntent.getBroadcast(
                    context, 0, Intent(ACTION_PERMISSION).setPackage(context.packageName), PendingIntent.FLAG_IMMUTABLE,
                )
                usb.requestPermission(acc, pi)
            }
            pause(1000)
            return null
        }
        val pfd = try {
            usb.openAccessory(acc)
        } catch (e: Exception) {
            Log.w(tag, "Cannot open the USB accessory yet: ${e.message}")
            null
        }
        if (pfd == null) {
            pause(1000)
            return null
        }
        opening = pfd
        var output: CoalescingOutputStream? = null
        try {
            val input = AccessoryInputStream(FileInputStream(pfd.fileDescriptor))
            // Frames are coalesced into 16 KiB USB transfers (see CoalescingOutputStream); the
            // descriptor itself is closed with pfd.
            val out = CoalescingOutputStream(FileOutputStream(pfd.fileDescriptor), closeOut = false)
            output = out
            Log.i(tag, "USB accessory open (${acc.description}): waiting for the computer/router")
            val stream = AccessoryPreamble.accept(input, out) {
                Log.i(tag, "USB accessory: the computer/router said hello")
            }
            opening = null
            return Connection(pfd, stream, out, acc.description ?: acc.model ?: "USB accessory")
        } catch (e: IOException) {
            opening = null
            output?.close()
            Log.i(tag, "USB accessory closed before a session started: ${e.message}")
            try {
                pfd.close()
            } catch (_: IOException) {
            }
            pause(1000)
            return null
        }
    }

    companion object {
        /** Keep in sync with res/xml/accessory_filter.xml and desktop/common/src/aoa/mod.rs. */
        const val MANUFACTURER = "Errant Solutions"
        const val MODEL = "ETPH"
        private const val ACTION_PERMISSION = "solutions.errant.etph.USB_ACCESSORY_PERMISSION"
        /** Give Android's own "Open ETPH ...?" this long before asking ourselves. */
        private const val ASK_AFTER_MS = 8000L

        fun isOurs(a: UsbAccessory): Boolean = a.manufacturer == MANUFACTURER && a.model == MODEL
    }
}
