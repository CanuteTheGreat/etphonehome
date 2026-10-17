package com.etphonehome

import android.Manifest
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.RadioGroup
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.etphonehome.service.StatusText
import com.etphonehome.service.TetherService
import com.etphonehome.service.TetherState
import com.etphonehome.service.TetherStatus
import com.etphonehome.util.Settings
import com.etphonehome.util.TokenManager
import kotlinx.coroutines.launch

/**
 * Main screen: pick USB / WiFi / Bluetooth, start or stop sharing, and see
 * whether a desktop is connected and how much it has used.
 *
 * Sharing needs no VPN permission and no root: the phone keeps its own
 * internet while the desktop uses it.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var statusText: TextView
    private lateinit var startButton: Button
    private lateinit var stopButton: Button
    private lateinit var statsText: TextView
    private lateinit var connectionModeGroup: RadioGroup
    private lateinit var wifiIpText: TextView
    private lateinit var bluetoothInfoText: TextView
    private lateinit var requireEncryptionSwitch: SwitchCompat

    private var selectedConnectionMode = TetherService.CONNECTION_USB

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { results ->
        if (selectedConnectionMode == TetherService.CONNECTION_BLUETOOTH &&
            results[Manifest.permission.BLUETOOTH_CONNECT] == false
        ) {
            Toast.makeText(this, "Bluetooth permission is needed for Bluetooth mode", Toast.LENGTH_LONG).show()
        } else {
            startTethering()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        statusText = findViewById(R.id.statusText)
        startButton = findViewById(R.id.startButton)
        stopButton = findViewById(R.id.stopButton)
        statsText = findViewById(R.id.statsText)
        connectionModeGroup = findViewById(R.id.connectionModeGroup)
        wifiIpText = findViewById(R.id.wifiIpText)
        bluetoothInfoText = findViewById(R.id.bluetoothInfoText)
        requireEncryptionSwitch = findViewById(R.id.requireEncryptionSwitch)

        selectedConnectionMode = Settings.connectionMode(this, TetherService.CONNECTION_USB)
        connectionModeGroup.check(
            when (selectedConnectionMode) {
                TetherService.CONNECTION_WIFI -> R.id.radioWifi
                TetherService.CONNECTION_BLUETOOTH -> R.id.radioBluetooth
                else -> R.id.radioUsb
            },
        )
        showModeInfo()
        requireEncryptionSwitch.isChecked = Settings.requireEncryption(this)
        requireEncryptionSwitch.setOnCheckedChangeListener { _, checked ->
            Settings.setRequireEncryption(this, checked)
        }

        startButton.setOnClickListener { requestPermissionsAndStart() }
        stopButton.setOnClickListener {
            startService(Intent(this, TetherService::class.java).setAction(TetherService.ACTION_STOP))
        }
        connectionModeGroup.setOnCheckedChangeListener { _, checkedId ->
            selectedConnectionMode = when (checkedId) {
                R.id.radioWifi -> TetherService.CONNECTION_WIFI
                R.id.radioBluetooth -> TetherService.CONNECTION_BLUETOOTH
                else -> TetherService.CONNECTION_USB
            }
            Settings.setConnectionMode(this, selectedConnectionMode)
            showModeInfo()
        }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                TetherStatus.state.collect { render(it) }
            }
        }
    }

    private fun render(state: TetherState) {
        statusText.text = StatusText.status(state)
        statsText.text = StatusText.details(state)
        val running = state !is TetherState.Stopped
        startButton.isEnabled = !running
        stopButton.isEnabled = running
        for (i in 0 until connectionModeGroup.childCount) connectionModeGroup.getChildAt(i).isEnabled = !running
        requireEncryptionSwitch.isEnabled = !running
    }

    private fun requestPermissionsAndStart() {
        val needed = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            !granted(Manifest.permission.POST_NOTIFICATIONS)
        ) {
            needed += Manifest.permission.POST_NOTIFICATIONS
        }
        if (selectedConnectionMode == TetherService.CONNECTION_BLUETOOTH &&
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            !granted(Manifest.permission.BLUETOOTH_CONNECT)
        ) {
            needed += Manifest.permission.BLUETOOTH_CONNECT
        }
        if (needed.isEmpty()) startTethering() else permissionLauncher.launch(needed.toTypedArray())
    }

    private fun granted(permission: String) =
        ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED

    private fun startTethering() {
        val intent = Intent(this, TetherService::class.java).apply {
            putExtra(TetherService.EXTRA_CONNECTION_TYPE, selectedConnectionMode)
            putExtra(TetherService.EXTRA_PORT, TetherService.DEFAULT_PORT)
        }
        ContextCompat.startForegroundService(this, intent)
    }

    private fun showModeInfo() {
        wifiIpText.visibility = View.GONE
        bluetoothInfoText.visibility = View.GONE
        requireEncryptionSwitch.visibility =
            if (selectedConnectionMode == TetherService.CONNECTION_WIFI) View.VISIBLE else View.GONE
        when (selectedConnectionMode) {
            TetherService.CONNECTION_WIFI -> showWifiInfo()
            TetherService.CONNECTION_BLUETOOTH -> showBluetoothInfo()
        }
    }

    private fun showWifiInfo() {
        val wifiIp = wifiIpAddress()
        val token = TokenManager.getWifiToken(this)
        wifiIpText.text = if (wifiIp != null) {
            buildString {
                appendLine("WiFi IP: $wifiIp:${TetherService.DEFAULT_PORT}")
                appendLine("Auth Token: $token")
                appendLine()
                appendLine("Desktop connection:")
                append("  sudo etphonehome wifi --host $wifiIp --token $token")
            }
        } else {
            "WiFi not connected. Please connect to WiFi first."
        }
        wifiIpText.visibility = View.VISIBLE
    }

    @Suppress("MissingPermission") // BLUETOOTH_CONNECT is checked first (granted())
    private fun showBluetoothInfo() {
        bluetoothInfoText.visibility = View.VISIBLE
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !granted(Manifest.permission.BLUETOOTH_CONNECT)) {
            bluetoothInfoText.text = "Bluetooth permission not granted yet (asked when you start)."
            return
        }
        val token = TokenManager.getBluetoothToken(this)
        val adapter = (getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter
        bluetoothInfoText.text = if (adapter?.isEnabled == true) {
            buildString {
                appendLine("Bluetooth Address: ${adapter.address}")
                appendLine("Device Name: ${adapter.name}")
                appendLine("Auth Token: $token")
                appendLine()
                appendLine("Desktop connection (Linux):")
                append("  sudo etphonehome bluetooth --address ${adapter.address} --token $token")
            }
        } else {
            "Bluetooth is disabled. Please enable Bluetooth first."
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
