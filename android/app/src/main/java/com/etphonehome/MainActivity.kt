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
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.etphonehome.billing.Pro
import com.etphonehome.pro.ProGate
import com.etphonehome.service.StatusText
import com.etphonehome.service.TetherService
import com.etphonehome.service.TetherState
import com.etphonehome.service.TetherStatus
import com.etphonehome.util.BluetoothText
import com.etphonehome.util.LegacyApp
import com.etphonehome.util.ProText
import com.etphonehome.util.Settings
import com.etphonehome.util.TokenManager
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import java.time.LocalDate

/**
 * Main screen: pick USB / WiFi / Bluetooth, start or stop sharing, and see
 * whether a desktop is connected and how much it has used.
 *
 * Sharing needs no VPN permission and no root: the phone keeps its own
 * internet while the desktop uses it.
 */

/** How often the data usage view re-reads the ledger while a session is running. */
private const val USAGE_REFRESH_MS = 5_000L

class MainActivity : AppCompatActivity() {

    private lateinit var statusText: TextView
    private lateinit var startButton: Button
    private lateinit var stopButton: Button
    private lateinit var statsText: TextView
    private lateinit var connectionModeGroup: RadioGroup
    private lateinit var wifiIpText: TextView
    private lateinit var bluetoothInfoText: TextView
    private lateinit var requireEncryptionSwitch: SwitchCompat
    private lateinit var legacyAppBanner: View
    private lateinit var proStatusText: TextView
    private lateinit var upgradeButton: Button
    private lateinit var proRequiredPanel: View
    private lateinit var proRequiredText: TextView
    private lateinit var autoStartSwitch: SwitchCompat
    private lateinit var pairedHint: TextView
    private lateinit var pairedList: android.widget.LinearLayout
    private lateinit var usageText: TextView

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
        // Android 15+ (targetSdk 35+) always draws apps edge to edge; do it on every version
        // and keep the content clear of the status/navigation bars, cutouts and keyboard.
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.mainScroll)) { v, insets ->
            val bars = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout() or
                    WindowInsetsCompat.Type.ime(),
            )
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            WindowInsetsCompat.CONSUMED
        }
        statusText = findViewById(R.id.statusText)
        startButton = findViewById(R.id.startButton)
        stopButton = findViewById(R.id.stopButton)
        statsText = findViewById(R.id.statsText)
        connectionModeGroup = findViewById(R.id.connectionModeGroup)
        wifiIpText = findViewById(R.id.wifiIpText)
        bluetoothInfoText = findViewById(R.id.bluetoothInfoText)
        requireEncryptionSwitch = findViewById(R.id.requireEncryptionSwitch)
        legacyAppBanner = findViewById(R.id.legacyAppBanner)
        findViewById<Button>(R.id.legacyAppButton).setOnClickListener {
            startActivity(LegacyApp.settingsIntent())
        }
        proStatusText = findViewById(R.id.proStatusText)
        upgradeButton = findViewById(R.id.upgradeButton)
        proRequiredPanel = findViewById(R.id.proRequiredPanel)
        proRequiredText = findViewById(R.id.proRequiredText)
        autoStartSwitch = findViewById(R.id.autoStartSwitch)
        pairedHint = findViewById(R.id.pairedHint)
        pairedList = findViewById(R.id.pairedList)
        usageText = findViewById(R.id.usageText)
        upgradeButton.setOnClickListener { openUpgrade() }
        findViewById<Button>(R.id.proRequiredButton).setOnClickListener { openUpgrade() }
        autoStartSwitch.isChecked = Pro.store.autoStart
        autoStartSwitch.setOnCheckedChangeListener { v, checked ->
            if (Pro.isPro) {
                Pro.store.autoStart = checked
            } else if (checked) {
                v.isChecked = false
                openUpgrade()
            }
        }

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
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                combine(Pro.state, Pro.store.paired) { e, _ -> e }.collect { renderPro() }
            }
        }
        // Usage changes with every stats tick of a session (each second): re-read it at most every
        // USAGE_REFRESH_MS and only touch the view when the text changed. Re-rendering the whole
        // Pro section every tick kept the window from ever going idle (uiautomator, accessibility
        // services and the battery all noticed: found by the emulator e2e).
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                Pro.store.usageVersion.collect { // a StateFlow: conflated already
                    renderUsage()
                    delay(USAGE_REFRESH_MS)
                }
            }
        }
    }

    private fun openUpgrade() {
        startActivity(Intent(this, UpgradeActivity::class.java))
    }

    /** Free/Pro line, the Pro note for Wi-Fi/Bluetooth, auto-start, paired devices, usage. */
    private fun renderPro() {
        val e = Pro.state.value
        val pro = e.isPro
        proStatusText.text = ProText.statusLine(e)
        upgradeButton.visibility = if (pro) View.GONE else View.VISIBLE
        showModeInfo()
        autoStartSwitch.text = getString(if (pro) R.string.auto_start else R.string.auto_start_pro)
        autoStartSwitch.isChecked = pro && Pro.store.autoStart
        val paired = Pro.store.paired.value
        pairedHint.text = ProText.pairedHint(pro, paired)
        pairedList.removeAllViews()
        val now = System.currentTimeMillis()
        for (d in paired) {
            val row = layoutInflater.inflate(R.layout.row_paired, pairedList, false)
            row.findViewById<TextView>(R.id.pairedName).text = ProText.pairedRow(d, pro, paired, now)
            row.findViewById<Button>(R.id.pairedForget).setOnClickListener { Pro.store.forget(d.id) }
            pairedList.addView(row)
        }
        renderUsage()
    }

    private fun renderUsage() {
        val t = Pro.store.usage { ProText.usage(Pro.isPro, it, Pro.store.paired.value, LocalDate.now()) }
        if (usageText.text.toString() != t) usageText.text = t
    }

    override fun onResume() {
        super.onResume()
        // Re-ask Google Play whenever the app comes back (refunds, a purchase made elsewhere).
        Pro.refreshAsync()
        // Re-checked on every return, so the banner goes away once the old app is uninstalled.
        legacyAppBanner.visibility =
            if (LegacyApp.shouldWarn(packageName) { LegacyApp.isInstalled(packageManager, it) }) View.VISIBLE else View.GONE
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
        if (ProGate.transportRefusal(Pro.isPro, selectedConnectionMode) != null) {
            openUpgrade()
            return
        }
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
        val refusal = ProGate.transportRefusal(Pro.isPro, selectedConnectionMode)
        proRequiredPanel.visibility = if (refusal != null) View.VISIBLE else View.GONE
        proRequiredText.text = refusal ?: ""
        requireEncryptionSwitch.visibility =
            if (selectedConnectionMode == TetherService.CONNECTION_WIFI && refusal == null) View.VISIBLE else View.GONE
        if (refusal != null) return
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
            BluetoothText.info(adapter.name, token)
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
