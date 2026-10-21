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
        val usb = usb ?: return null
        while (!stopped && running()) {
            val acc = usb.accessoryList?.firstOrNull(::isOurs)
            if (acc == null) {
                askedFor = null
                pause(1500)
                continue
            }
            if (!usb.hasPermission(acc)) {
                // Normally granted with "Open ETPH ...?"; if that was dismissed, ask once per plug-in.
                if (askedFor != acc) {
                    askedFor = acc
                    Log.i(tag, "USB accessory ${acc.description}: asking for permission")
                    val pi = PendingIntent.getBroadcast(
                        context, 0, Intent(ACTION_PERMISSION).setPackage(context.packageName), PendingIntent.FLAG_IMMUTABLE,
                    )
                    usb.requestPermission(acc, pi)
                }
                pause(1000)
                continue
            }
            val pfd = try {
                usb.openAccessory(acc)
            } catch (e: Exception) {
                Log.w(tag, "Cannot open the USB accessory yet: ${e.message}")
                null
            }
            if (pfd == null) {
                pause(1000)
                continue
            }
            opening = pfd
            try {
                val input = AccessoryInputStream(FileInputStream(pfd.fileDescriptor))
                val output = FileOutputStream(pfd.fileDescriptor)
                Log.i(tag, "USB accessory open (${acc.description}): waiting for the computer/router")
                val stream = AccessoryPreamble.accept(input, output) {
                    Log.i(tag, "USB accessory: the computer/router said hello")
                }
                opening = null
                return Connection(pfd, stream, output, acc.description ?: acc.model ?: "USB accessory")
            } catch (e: IOException) {
                opening = null
                Log.i(tag, "USB accessory closed before a session started: ${e.message}")
                try {
                    pfd.close()
                } catch (_: IOException) {
                }
                pause(1000)
            }
        }
        return null
    }

    companion object {
        /** Keep in sync with res/xml/accessory_filter.xml and desktop/common/src/aoa/mod.rs. */
        const val MANUFACTURER = "Errant Solutions"
        const val MODEL = "ETPH"
        private const val ACTION_PERMISSION = "solutions.errant.etph.USB_ACCESSORY_PERMISSION"

        fun isOurs(a: UsbAccessory): Boolean = a.manufacturer == MANUFACTURER && a.model == MODEL
    }
}
