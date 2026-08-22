package com.cadencesim

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.cadencesim.databinding.ActivityMainBinding

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private var server: CadenceBleServer? = null

    private val requiredPermissions: Array<String> by lazy {
        buildList {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                add(Manifest.permission.BLUETOOTH_ADVERTISE)
                add(Manifest.permission.BLUETOOTH_CONNECT)
            } else {
                add(Manifest.permission.BLUETOOTH)
                add(Manifest.permission.BLUETOOTH_ADMIN)
            }
            add(Manifest.permission.ACCESS_FINE_LOCATION)
        }.toTypedArray()
    }

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        val denied = result.filterValues { !it }.keys
        if (denied.isEmpty()) {
            tryStartServer()
        } else {
            Toast.makeText(
                this,
                "缺少权限: ${denied.joinToString()}",
                Toast.LENGTH_LONG
            ).show()
        }
    }

    private val btStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            updateBluetoothState()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.sliderCadence.valueFrom = 0f
        binding.sliderCadence.valueTo = 200f
        binding.sliderCadence.stepSize = 1f
        binding.sliderCadence.value = 80f
        updateRpmLabel(80f)

        binding.sliderCadence.addOnChangeListener { _, value, _ ->
            updateRpmLabel(value)
            server?.targetCadenceRpm = value
        }

        binding.btnToggle.setOnClickListener {
            val s = server
            if (s == null || !s.isRunning) {
                if (hasAllPermissions()) {
                    tryStartServer()
                } else {
                    permissionLauncher.launch(requiredPermissions)
                }
            } else {
                stopServer()
            }
        }

        val filter = IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(btStateReceiver, filter, RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            registerReceiver(btStateReceiver, filter)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        try {
            unregisterReceiver(btStateReceiver)
        } catch (_: Throwable) {
        }
        stopServer()
    }

    private fun hasAllPermissions(): Boolean = requiredPermissions.all {
        ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
    }

    private fun tryStartServer() {
        val mgr = getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
        val adapter = mgr?.adapter
        if (adapter == null || !adapter.isEnabled) {
            Toast.makeText(this, "请先打开蓝牙", Toast.LENGTH_LONG).show()
            return
        }
        if (!packageManager.hasSystemFeature(PackageManager.FEATURE_BLUETOOTH_LE)) {
            Toast.makeText(this, "设备不支持 BLE", Toast.LENGTH_LONG).show()
            return
        }

        val s = CadenceBleServer(this, adapter) { status ->
            runOnUiThread {
                binding.txtStatus.text = status
                updateToggleUi(this@MainActivity.server?.isRunning == true)
            }
        }
        s.targetCadenceRpm = binding.sliderCadence.value
        server = s
        s.start()
    }

    private fun stopServer() {
        server?.stop()
        server = null
        binding.txtStatus.text = "已停止"
        updateToggleUi(false)
    }

    private fun updateRpmLabel(value: Float) {
        binding.txtRpm.text = "${value.toInt()} RPM"
    }

    private fun updateToggleUi(running: Boolean) {
        binding.btnToggle.text = if (running) "停止模拟" else "开始模拟"
        binding.sliderCadence.isEnabled = true // always adjustable
    }

    private fun updateBluetoothState() {
        val mgr = getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
        val enabled = mgr?.adapter?.isEnabled == true
        if (!enabled && server?.isRunning == true) {
            stopServer()
            Toast.makeText(this, "蓝牙已关闭", Toast.LENGTH_SHORT).show()
        }
    }
}
