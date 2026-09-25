package com.etphonehome

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.RadioGroup
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.etphonehome.service.VpnService as EtVpnService
import com.etphonehome.util.TokenManager

/**
 * Main activity for ET Phone Home
 */
class MainActivity : AppCompatActivity() {

    private lateinit var statusText: TextView
    private lateinit var startButton: Button
    private lateinit var stopButton: Button
    private lateinit var statsText: TextView
    private lateinit var connectionModeGroup: RadioGroup
    private lateinit var wifiIpText: TextView
    private lateinit var bluetoothInfoText: TextView

    private var isVpnRunning = false
    private var selectedConnectionMode = EtVpnService.CONNECTION_USB

    private val vpnPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            startVpnService()
        } else {
            Toast.makeText(this, R.string.error_vpn_permission, Toast.LENGTH_LONG).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        initViews()
        setupListeners()
        updateUI()
    }

    private fun initViews() {
        statusText = findViewById(R.id.statusText)
        startButton = findViewById(R.id.startButton)
        stopButton = findViewById(R.id.stopButton)
        statsText = findViewById(R.id.statsText)
        connectionModeGroup = findViewById(R.id.connectionModeGroup)
        wifiIpText = findViewById(R.id.wifiIpText)
        bluetoothInfoText = findViewById(R.id.bluetoothInfoText)
    }

    private fun setupListeners() {
        startButton.setOnClickListener {
            requestVpnPermission()
        }

        stopButton.setOnClickListener {
            stopVpnService()
        }

        connectionModeGroup.setOnCheckedChangeListener { _, checkedId ->
            when (checkedId) {
                R.id.radioUsb -> {
                    selectedConnectionMode = EtVpnService.CONNECTION_USB
                    wifiIpText.visibility = View.GONE
                    bluetoothInfoText.visibility = View.GONE
                }
                R.id.radioWifi -> {
                    selectedConnectionMode = EtVpnService.CONNECTION_WIFI
                    bluetoothInfoText.visibility = View.GONE
                    showWifiIpAddress()
                }
                R.id.radioBluetooth -> {
                    selectedConnectionMode = EtVpnService.CONNECTION_BLUETOOTH
                    wifiIpText.visibility = View.GONE
                    showBluetoothInfo()
                }
            }
        }
    }

    private fun requestVpnPermission() {
        val intent = VpnService.prepare(this)
        if (intent != null) {
            vpnPermissionLauncher.launch(intent)
        } else {
            // Permission already granted
            startVpnService()
        }
    }

    private fun startVpnService() {
        val intent = Intent(this, EtVpnService::class.java).apply {
            putExtra(EtVpnService.EXTRA_CONNECTION_TYPE, selectedConnectionMode)
            putExtra(EtVpnService.EXTRA_HOST, "localhost")
            putExtra(EtVpnService.EXTRA_PORT, 8888)
        }

        ContextCompat.startForegroundService(this, intent)
        isVpnRunning = true
        updateUI()

        val modeText = when (selectedConnectionMode) {
            EtVpnService.CONNECTION_USB -> "USB"
            EtVpnService.CONNECTION_WIFI -> "WiFi"
            EtVpnService.CONNECTION_BLUETOOTH -> "Bluetooth"
            else -> "Unknown"
        }
        Toast.makeText(this, "Tethering started ($modeText mode)", Toast.LENGTH_SHORT).show()
    }

    private fun stopVpnService() {
        val intent = Intent(this, EtVpnService::class.java)
        stopService(intent)
        isVpnRunning = false
        updateUI()

        Toast.makeText(this, "Tethering stopped", Toast.LENGTH_SHORT).show()
    }

    private fun updateUI() {
        if (isVpnRunning) {
            statusText.text = getString(R.string.status_connected)
            startButton.isEnabled = false
            stopButton.isEnabled = true
            connectionModeGroup.isEnabled = false
        } else {
            statusText.text = getString(R.string.status_disconnected)
            startButton.isEnabled = true
            stopButton.isEnabled = false
            connectionModeGroup.isEnabled = true
        }
    }

    private fun showWifiIpAddress() {
        val wifiIp = getWifiIpAddress()
        val wifiToken = TokenManager.getWifiToken(this)

        if (wifiIp != null) {
            wifiIpText.text = buildString {
                appendLine("WiFi IP: $wifiIp:8888")
                appendLine("Auth Token: $wifiToken")
                appendLine()
                appendLine("Desktop connection:")
                append("  sudo etphonehome wifi --host $wifiIp --token $wifiToken")
            }
            wifiIpText.visibility = View.VISIBLE
        } else {
            wifiIpText.text = "WiFi not connected. Please connect to WiFi first."
            wifiIpText.visibility = View.VISIBLE
        }
    }

    private fun showBluetoothInfo() {
        if (ContextCompat.checkSelfPermission(this, android.Manifest.permission.BLUETOOTH_CONNECT)
            != android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            bluetoothInfoText.text = "Bluetooth permission not granted."
            return
        }

        val bluetoothToken = TokenManager.getBluetoothToken(this)
        val bluetoothManager = getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        val bluetoothAdapter = bluetoothManager.adapter

        if (bluetoothAdapter?.isEnabled == true) {
            val btAddress = bluetoothAdapter.address
            val btName = bluetoothAdapter.name

            bluetoothInfoText.text = buildString {
                appendLine("Bluetooth Address: $btAddress")
                appendLine("Device Name: $btName")
                appendLine("Auth Token: $bluetoothToken")
                appendLine()
                appendLine("Desktop connection:")
                append("  sudo etphonehome bluetooth --address $btAddress --token $bluetoothToken")
            }
            bluetoothInfoText.visibility = View.VISIBLE
        } else {
            bluetoothInfoText.text = "Bluetooth is disabled. Please enable Bluetooth first."
            bluetoothInfoText.visibility = View.VISIBLE
        }
    }

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
            e.printStackTrace()
        }
        return null
    }
}
