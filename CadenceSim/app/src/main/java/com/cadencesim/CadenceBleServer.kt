package com.cadencesim

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothGattServer
import android.bluetooth.BluetoothGattServerCallback
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.bluetooth.le.BluetoothLeAdvertiser
import android.content.Context
import android.os.Build
import android.os.ParcelUuid
import android.util.Log
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * Implements a BLE peripheral that emulates a Cycling Speed and Cadence sensor.
 *
 * Exposes the standard CSC service (0x1816) with:
 *  - CSC Measurement (0x2A5B) — Notify
 *  - CSC Feature     (0x2A5C) — Read   (bit 1 set: crank revolution data supported)
 *
 * Each subscribed device receives a 1 Hz notification packet containing the
 * cumulative crank revolutions and the last crank event time (1/1024 s).
 * Receivers compute instantaneous cadence from consecutive packet deltas.
 */
class CadenceBleServer(
    private val context: Context,
    private val adapter: BluetoothAdapter,
    private val onStatus: (String) -> Unit
) {

    companion object {
        private const val TAG = "CadenceBleServer"
        // Standard SIG UUIDs (16-bit)
        val CSC_SERVICE: java.util.UUID = java.util.UUID.fromString("00001816-0000-1000-8000-00805F9B34FB")
        val CSC_MEASUREMENT_CHAR: java.util.UUID = java.util.UUID.fromString("00002A5B-0000-1000-8000-00805F9B34FB")
        val CSC_FEATURE_CHAR: java.util.UUID = java.util.UUID.fromString("00002A5C-0000-1000-8000-00805F9B34FB")
        val CLIENT_CONFIG_DESC: java.util.UUID = java.util.UUID.fromString("00002902-0000-1000-8000-00805F9B34FB")

        // CSC Feature: bit 1 = crank revolution data supported
        private const val CSC_FEATURE_CRANK_SUPPORTED = 0x02

        private const val NOTIFY_INTERVAL_MS = 1000L
    }

    @Volatile
    var targetCadenceRpm: Float = 80f

    private val isRunningFlag = AtomicBoolean(false)
    val isRunning: Boolean get() = isRunningFlag.get()

    private var gattServer: BluetoothGattServer? = null
    private var advertiser: BluetoothLeAdvertiser? = null
    private var measurementChar: BluetoothGattCharacteristic? = null
    private var scheduler: ScheduledExecutorService? = null
    private var notifyTask: ScheduledFuture<*>? = null

    // Devices that have enabled notifications on CSC Measurement.
    private val subscribers = ConcurrentHashMap.newKeySet<BluetoothDevice>()

    // Monotonic counters driven by [targetCadenceRpm] and elapsed time.
    private val cumulativeCrankRevs = AtomicReference(0L)
    private val sessionStartNanos = AtomicReference(0L)

    private val advertiseCallback = object : AdvertiseCallback() {
        override fun onStartSuccess(settingsInEffect: AdvertiseSettings?) {
            Log.i(TAG, "Advertise start success: $settingsInEffect")
            onStatus("广播中，等待设备连接…")
        }

        override fun onStartFailure(errorCode: Int) {
            Log.e(TAG, "Advertise start failure: $errorCode")
            onStatus("广播失败 (code=$errorCode)，请重试")
            stopInternal()
        }
    }

    private val gattCallback = object : BluetoothGattServerCallback() {
        override fun onConnectionStateChange(device: BluetoothDevice, status: Int, newState: Int) {
            val name = safeName(device)
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    Log.i(TAG, "Connected: $name")
                    onStatus("已连接: $name")
                }
                BluetoothProfile.STATE_DISCONNECTED -> {
                    Log.i(TAG, "Disconnected: $name")
                    subscribers.remove(device)
                    onStatus("已断开: $name")
                }
            }
        }

        override fun onServiceAdded(status: Int, service: BluetoothGattService?) {
            Log.i(TAG, "onServiceAdded status=$status uuid=${service?.uuid}")
        }

        override fun onCharacteristicReadRequest(
            device: BluetoothDevice,
            requestId: Int,
            offset: Int,
            characteristic: BluetoothGattCharacteristic
        ) {
            val server = gattServer ?: return
            when (characteristic.uuid) {
                CSC_FEATURE_CHAR -> {
                    val value = if (offset <= 0) {
                        byteArrayOf(0, 0, CSC_FEATURE_CRANK_SUPPORTED.toByte())
                    } else {
                        // 3 bytes total; subsequent offsets return 0
                        ByteArray(0)
                    }
                    server.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, value)
                }
                CSC_MEASUREMENT_CHAR -> {
                    val payload = buildMeasurementPayload()
                    if (offset in 0..(payload.size - 1).coerceAtLeast(0)) {
                        val slice = payload.copyOfRange(offset, payload.size)
                        server.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, slice)
                    } else {
                        server.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, ByteArray(0))
                    }
                }
                else -> {
                    server.sendResponse(device, requestId, BluetoothGatt.GATT_FAILURE, 0, null)
                }
            }
        }

        override fun onDescriptorReadRequest(
            device: BluetoothDevice,
            requestId: Int,
            offset: Int,
            descriptor: BluetoothGattDescriptor
        ) {
            val server = gattServer ?: return
            if (descriptor.uuid == CLIENT_CONFIG_DESC) {
                val isSubscribed = subscribers.contains(device)
                val value = if (isSubscribed)
                    BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                else
                    BluetoothGattDescriptor.DISABLE_NOTIFICATION_VALUE
                server.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, 0, value)
            } else {
                server.sendResponse(device, requestId, BluetoothGatt.GATT_FAILURE, 0, null)
            }
        }

        override fun onDescriptorWriteRequest(
            device: BluetoothDevice,
            requestId: Int,
            descriptor: BluetoothGattDescriptor,
            preparedWrite: Boolean,
            responseNeeded: Boolean,
            offset: Int,
            value: ByteArray
        ) {
            val server = gattServer ?: return
            if (descriptor.uuid != CLIENT_CONFIG_DESC) {
                server.sendResponse(device, requestId, BluetoothGatt.GATT_FAILURE, 0, null)
                return
            }
            val enabled = value.contentEquals(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE) ||
                value.contentEquals(BluetoothGattDescriptor.ENABLE_INDICATION_VALUE)
            if (enabled) {
                subscribers.add(device)
                onStatus("${safeName(device)} 已订阅踏频")
            } else {
                subscribers.remove(device)
                onStatus("${safeName(device)} 已取消订阅")
            }
            if (responseNeeded) {
                server.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, 0, value)
            }
        }
    }

    fun start() {
        if (isRunningFlag.getAndSet(true)) return

        val mgr = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
        if (mgr == null) {
            onStatus("系统不支持 BluetoothManager")
            isRunningFlag.set(false)
            return
        }

        gattServer = mgr.openGattServer(context, gattCallback)
        if (gattServer == null) {
            onStatus("无法打开 GATT Server")
            isRunningFlag.set(false)
            return
        }

        val service = BluetoothGattService(CSC_SERVICE, BluetoothGattService.SERVICE_TYPE_PRIMARY).apply {
            val feature = BluetoothGattCharacteristic(
                CSC_FEATURE_CHAR,
                BluetoothGattCharacteristic.PROPERTY_READ,
                BluetoothGattCharacteristic.PERMISSION_READ
            )
            feature.value = byteArrayOf(0, 0, CSC_FEATURE_CRANK_SUPPORTED.toByte())
            addCharacteristic(feature)

            val measurement = BluetoothGattCharacteristic(
                CSC_MEASUREMENT_CHAR,
                BluetoothGattCharacteristic.PROPERTY_READ or BluetoothGattCharacteristic.PROPERTY_NOTIFY,
                BluetoothGattCharacteristic.PERMISSION_READ
            )
            val cccd = BluetoothGattDescriptor(
                CLIENT_CONFIG_DESC,
                BluetoothGattDescriptor.PERMISSION_READ or BluetoothGattDescriptor.PERMISSION_WRITE
            )
            measurement.addDescriptor(cccd)
            addCharacteristic(measurement)
            measurementChar = measurement
        }
        val added = gattServer?.addService(service) ?: false
        if (!added) {
            onStatus("注册 CSC 服务失败")
            stopInternal()
            return
        }

        advertiser = adapter.bluetoothLeAdvertiser
        if (advertiser == null) {
            onStatus("设备不支持 BLE 广播")
            stopInternal()
            return
        }

        val settings = AdvertiseSettings.Builder()
            .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
            .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_MEDIUM)
            .setConnectable(true)
            .setTimeout(0)
            .build()

        val data = AdvertiseData.Builder()
            .setIncludeDeviceName(true)
            .setIncludeTxPowerLevel(false)
            .addServiceUuid(ParcelUuid(CSC_SERVICE))
            .build()

        try {
            advertiser?.startAdvertising(settings, data, advertiseCallback)
        } catch (e: SecurityException) {
            onStatus("缺少 BLUETOOTH_ADVERTISE 权限")
            stopInternal()
            return
        } catch (e: Throwable) {
            onStatus("启动广播异常: ${e.message}")
            stopInternal()
            return
        }

        // Start the notification loop.
        sessionStartNanos.set(System.nanoTime())
        cumulativeCrankRevs.set(0L)
        scheduler = Executors.newSingleThreadScheduledExecutor { r ->
            Thread(r, "cadence-tick").apply { isDaemon = true }
        }
        notifyTask = scheduler!!.scheduleAtFixedRate(
            { tick() },
            NOTIFY_INTERVAL_MS,
            NOTIFY_INTERVAL_MS,
            TimeUnit.MILLISECONDS
        )
        onStatus("初始化完成…")
    }

    fun stop() {
        stopInternal()
    }

    private fun stopInternal() {
        if (!isRunningFlag.getAndSet(false)) return

        try {
            notifyTask?.cancel(false)
        } catch (_: Throwable) {
        }
        notifyTask = null

        try {
            scheduler?.shutdownNow()
        } catch (_: Throwable) {
        }
        scheduler = null

        try {
            advertiser?.stopAdvertising(advertiseCallback)
        } catch (_: SecurityException) {
        } catch (_: Throwable) {
        }
        advertiser = null

        try {
            gattServer?.close()
        } catch (_: Throwable) {
        }
        gattServer = null
        subscribers.clear()
        measurementChar = null
    }

    private fun tick() {
        val server = gattServer ?: return
        val char = measurementChar ?: return
        if (subscribers.isEmpty()) return

        val payload = buildMeasurementPayload()
        try {
            // Set value on the characteristic then notify each subscriber.
            char.value = payload
            subscribers.toList().forEach { device ->
                try {
                    server.notifyCharacteristicChanged(device, char, /* confirm */ false)
                } catch (se: SecurityException) {
                    Log.w(TAG, "notify: SecurityException, drop subscriber ${safeName(device)}")
                    subscribers.remove(device)
                } catch (t: Throwable) {
                    Log.w(TAG, "notify failed: ${t.message}")
                }
            }
        } catch (_: SecurityException) {
            // No CONNECT permission
        } catch (_: Throwable) {
        }
    }

    /**
     * Build the CSC Measurement packet:
     *   flags (1 byte): bit 1 = crank revolution data present
     *   cumulative crank revolutions (uint16, little-endian)
     *   last crank event time (uint16, 1/1024 s, little-endian)
     */
    private fun buildMeasurementPayload(): ByteArray {
        val nowNs = System.nanoTime()
        val startNs = sessionStartNanos.get()
        val elapsedSec = ((nowNs - startNs).coerceAtLeast(0L)) / 1_000_000_000.0

        val targetRpm = targetCadenceRpm.coerceIn(0f, 200f)
        // Cumulative crank revolutions = integral(target/60 dt). For a piecewise-constant
        // target with 1 s tick resolution, this is exact.
        val revs = (targetRpm / 60.0 * elapsedSec)
        val cumRevs = revs.toLong().coerceAtMost(0xFFFFL)
        cumulativeCrankRevs.set(cumRevs)

        val crankEventTime = (elapsedSec * 1024.0).toLong() and 0xFFFFL

        val buf = ByteBuffer.allocate(5).order(ByteOrder.LITTLE_ENDIAN)
        buf.put(0x02) // flags: only crank data present
        buf.putShort(cumRevs.toInt().toShort())
        buf.putShort(crankEventTime.toInt().toShort())
        return buf.array()
    }

    private fun safeName(device: BluetoothDevice): String {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                device.alias ?: device.address
            } else {
                @Suppress("DEPRECATION")
                device.name ?: device.address
            }
        } catch (_: SecurityException) {
            device.address
        } catch (_: Throwable) {
            device.address
        }
    }
}
