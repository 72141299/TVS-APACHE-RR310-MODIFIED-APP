package com.rr310.navbridge.ble

import android.annotation.SuppressLint
import android.bluetooth.*
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.ParcelUuid
import android.util.Log
import com.rr310.navbridge.protocol.TvsProtocol
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

@SuppressLint("MissingPermission")
class BleManager(private val context: Context) {

    companion object {
        private const val TAG = "RR310_BleManager"
    }

    enum class ConnectionState {
        DISCONNECTED,
        SCANNING,
        CONNECTING,
        CONNECTED
    }

    private val bluetoothManager: BluetoothManager? =
        context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
    private val bluetoothAdapter: BluetoothAdapter? = bluetoothManager?.adapter

    private var bluetoothGatt: BluetoothGatt? = null
    private var writeCharacteristic: BluetoothGattCharacteristic? = null

    private val _connectionState = MutableStateFlow(ConnectionState.DISCONNECTED)
    val connectionState: StateFlow<ConnectionState> = _connectionState

    private val _lastLog = MutableStateFlow("Ready to connect")
    val lastLog: StateFlow<String> = _lastLog

    private val handler = Handler(Looper.getMainLooper())
    private var targetDeviceAddress: String? = null

    fun setTargetDevice(address: String) {
        targetDeviceAddress = address
    }

    fun startScanAndConnect(targetAddress: String? = null) {
        if (targetAddress != null) {
            targetDeviceAddress = targetAddress
        }

        val adapter = bluetoothAdapter
        if (adapter == null || !adapter.isEnabled) {
            _lastLog.value = "Bluetooth is disabled"
            return
        }

        // If target address is known, connect directly
        if (!targetDeviceAddress.isNullOrEmpty()) {
            val device = adapter.getRemoteDevice(targetDeviceAddress)
            if (device != null) {
                connectToDevice(device)
                return
            }
        }

        val scanner = adapter.bluetoothLeScanner
        if (scanner == null) {
            _lastLog.value = "BLE Scanner unavailable"
            return
        }

        _connectionState.value = ConnectionState.SCANNING
        _lastLog.value = "Scanning for TVS SmartXonnect / RR310..."

        val scanFilter = ScanFilter.Builder()
            .setServiceUuid(ParcelUuid(TvsProtocol.SERVICE_UUID))
            .build()

        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()

        scanner.startScan(listOf(scanFilter), settings, scanCallback)

        // Stop scan after 15 seconds
        handler.postDelayed({
            if (_connectionState.value == ConnectionState.SCANNING) {
                scanner.stopScan(scanCallback)
                _connectionState.value = ConnectionState.DISCONNECTED
                _lastLog.value = "Scan timed out. Make sure motorcycle ignition is ON."
            }
        }, 15000)
    }

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult?) {
            val device = result?.device ?: return
            Log.d(TAG, "Found device: ${device.name} [${device.address}]")
            _lastLog.value = "Found ${device.name ?: "Unknown"} [${device.address}], connecting..."

            bluetoothAdapter?.bluetoothLeScanner?.stopScan(this)
            targetDeviceAddress = device.address
            connectToDevice(device)
        }

        override fun onScanFailed(errorCode: Int) {
            Log.e(TAG, "Scan failed with error: $errorCode")
            _connectionState.value = ConnectionState.DISCONNECTED
            _lastLog.value = "Scan failed (code $errorCode)"
        }
    }

    fun connectToDevice(device: BluetoothDevice) {
        _connectionState.value = ConnectionState.CONNECTING
        _lastLog.value = "Connecting to ${device.name ?: device.address}..."

        bluetoothGatt?.close()
        bluetoothGatt = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            device.connectGatt(context, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
        } else {
            device.connectGatt(context, false, gattCallback)
        }
    }

    private val gattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(gatt: BluetoothGatt?, status: Int, newState: Int) {
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    Log.d(TAG, "Connected to GATT server.")
                    _lastLog.value = "Connected to cluster. Discovering services..."
                    gatt?.discoverServices()
                }
                BluetoothProfile.STATE_DISCONNECTED -> {
                    Log.w(TAG, "Disconnected from GATT server.")
                    _connectionState.value = ConnectionState.DISCONNECTED
                    writeCharacteristic = null
                    _lastLog.value = "Disconnected from cluster."
                }
            }
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt?, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS && gatt != null) {
                val service = gatt.getService(TvsProtocol.SERVICE_UUID)
                if (service != null) {
                    writeCharacteristic = service.getCharacteristic(TvsProtocol.CHAR_WRITE_UUID)
                    if (writeCharacteristic != null) {
                        writeCharacteristic?.writeType = BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
                        _connectionState.value = ConnectionState.CONNECTED
                        _lastLog.value = "Ready! Connected to Apache RR 310 cluster."
                        Log.i(TAG, "Write characteristic 0x5352 ready!")
                        return
                    }
                }
                _lastLog.value = "Error: TVS SmartXonnect GATT service not found."
            } else {
                _lastLog.value = "Service discovery failed: $status"
            }
        }
    }

    fun sendPacket(packet: ByteArray): Boolean {
        val gatt = bluetoothGatt ?: return false
        val char = writeCharacteristic ?: return false

        if (_connectionState.value != ConnectionState.CONNECTED) return false

        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val res = gatt.writeCharacteristic(char, packet, BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE)
            res == BluetoothStatusCodes.SUCCESS
        } else {
            char.value = packet
            char.writeType = BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
            gatt.writeCharacteristic(char)
        }
    }

    fun disconnect() {
        bluetoothGatt?.disconnect()
        bluetoothGatt?.close()
        bluetoothGatt = null
        writeCharacteristic = null
        _connectionState.value = ConnectionState.DISCONNECTED
        _lastLog.value = "Disconnected."
    }
}
