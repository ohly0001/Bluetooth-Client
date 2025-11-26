package com.example.bluetooth_client

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.ParcelUuid
import android.util.Log
import android.view.HapticFeedbackConstants
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.RequiresApi
import androidx.annotation.RequiresPermission
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.core.app.ActivityCompat
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.codescanner.GmsBarcodeScannerOptions
import com.google.mlkit.vision.codescanner.GmsBarcodeScanning
import java.util.UUID

class MainActivity : ComponentActivity() {
    companion object {
        const val TAG = "BLE_CLIENT"
    }

    // BLE
    private var bluetoothManager: BluetoothManager? = null
    private var bluetoothAdapter: BluetoothAdapter? = null
    private var gatt: BluetoothGatt? = null

    // Dynamic UUID scanned from QR
    val foundUUID = mutableStateOf("")

    // Track scanning
    private var isScanning = false

    private val messages = mutableListOf<String>()
    fun getMessages() = messages

    // ============
    // GATT CALLBACK
    // ============
    private val gattCallback = object : BluetoothGattCallback() {

        @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {

            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    Log.d(TAG, "Connected. Discovering services...")
                    gatt.discoverServices()
                }
                BluetoothProfile.STATE_DISCONNECTED -> {
                    Log.d(TAG, "Disconnected from server")
                }
            }
        }

        @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) return

            val serviceUUID = getScannedUUID() ?: return
            val service = gatt.getService(serviceUUID)

            if (service == null) {
                Log.e(TAG, "Service $serviceUUID not found on server")
                return
            }

            val characteristic = service.getCharacteristic(serviceUUID)
            if (characteristic == null) {
                Log.e(TAG, "Characteristic $serviceUUID not found")
                return
            }

            // Enable notifications
            gatt.setCharacteristicNotification(characteristic, true)

            Log.d(TAG, "Notifications enabled on $serviceUUID")
        }

        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic
        ) {
            val text = String(characteristic.value)

            Log.d(TAG, "Received from server: $text")

            messages.add("Server: $text")
        }

        override fun onCharacteristicWrite(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            status: Int
        ) {
            Log.d(TAG, "Write complete: ${String(characteristic.value)}")
        }
    }

    // ============
    // SCANNING
    // ============
    @RequiresApi(Build.VERSION_CODES.R)
    private fun startScan() {
        val uuid = getScannedUUID() ?: return

        val scanner = bluetoothAdapter?.bluetoothLeScanner ?: return

        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()

        val filters = listOf(
            ScanFilter.Builder()
                .setServiceUuid(ParcelUuid(uuid))
                .build()
        )

        val scanCallback = object : ScanCallback() {

            @RequiresPermission(Manifest.permission.BLUETOOTH_SCAN)
            override fun onScanResult(type: Int, result: ScanResult) {
                if (!isScanning) return

                isScanning = false
                scanner.stopScan(this)

                Log.d(TAG, "Found device: ${result.device.address}")

                if (ActivityCompat.checkSelfPermission(
                        this@MainActivity, // <- explicitly refer to the Activity
                        Manifest.permission.BLUETOOTH_CONNECT
                    ) != PackageManager.PERMISSION_GRANTED
                ) {
                    // handle permission request
                    return
                }

                gatt = result.device.connectGatt(
                    this@MainActivity,
                    false,
                    gattCallback,
                    BluetoothDevice.TRANSPORT_LE
                )
            }
        }

        isScanning = true
        scanner.startScan(filters, settings, scanCallback)
    }

    // ============
    // WRITE TO SERVER (called from UI)
    // ============
    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    fun sendMessage(text: String) {
        val uuid = getScannedUUID() ?: return
        val service = gatt?.getService(uuid) ?: return
        val characteristic = service.getCharacteristic(uuid) ?: return

        characteristic.value = text.toByteArray()
        gatt?.writeCharacteristic(characteristic)
    }

    private fun getScannedUUID(): UUID? {
        return try { UUID.fromString(foundUUID.value) }
        catch (ex: Exception) { null }
    }

    // ============
    // ACTIVITY
    // ============
    @RequiresApi(Build.VERSION_CODES.R)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        bluetoothManager = getSystemService(BLUETOOTH_SERVICE) as BluetoothManager
        bluetoothAdapter = bluetoothManager?.adapter

        setContent {
            ClientUI(
                foundUUID = foundUUID,
                messages = messages,
                onScan = { startScan() },
                onSend = { sendMessage(it) }
            )
        }

        val permissions = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
            arrayOf(
                Manifest.permission.BLUETOOTH_SCAN,
                Manifest.permission.BLUETOOTH_CONNECT
            )
        else
            arrayOf(Manifest.permission.BLUETOOTH)

        requestPermissionsLauncher.launch(permissions)
    }

    private val requestPermissionsLauncher =
    registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { granted ->
        if (!granted.values.all { it }) {
            Log.e(TAG, "Permissions denied")
        }
    }
}

@RequiresApi(Build.VERSION_CODES.R)
@Composable
fun ClientUI(
    foundUUID: MutableState<String>,
    messages: List<String>,
    onScan: () -> Unit,
    onSend: (String) -> Unit
) {
    val ctx = LocalContext.current
    val view = LocalView.current
    val typedMessage = remember { mutableStateOf("") }

    Column(Modifier.fillMaxSize().padding(16.dp)) {

        // SCAN QR BUTTON
        Button(onClick = {
            val options = GmsBarcodeScannerOptions.Builder()
                .setBarcodeFormats(Barcode.FORMAT_QR_CODE)
                .build()

            val scanner = GmsBarcodeScanning.getClient(ctx, options)

            scanner.startScan()
                .addOnSuccessListener { barcode ->
                    val raw = barcode.rawValue
                    if (!raw.isNullOrEmpty()) {
                        foundUUID.value = raw
                        view.performHapticFeedback(HapticFeedbackConstants.CONFIRM)
                    }
                }
                .addOnCanceledListener {
                    view.performHapticFeedback(HapticFeedbackConstants.REJECT)
                }
                .addOnFailureListener { e ->
                    Log.e("BarcodeScan", "Scan failed", e)
                    view.performHapticFeedback(HapticFeedbackConstants.REJECT)
                }
        }) {
            Text("Scan QR Code")
        }

        Spacer(Modifier.padding(8.dp))

        // SHOW UUID + CONNECT BUTTON
        if (foundUUID.value.isNotEmpty()) {
            Text("UUID: ${foundUUID.value}")

            Spacer(Modifier.padding(8.dp))

            Button(onClick = onScan) {
                Text("Connect to Device")
            }

            Spacer(Modifier.padding(12.dp))

            Text("Messages:")
            LazyColumn(
                modifier = Modifier
                    .weight(1f)
                    .padding(vertical = 8.dp)
            ) {
                items(messages.size) { i ->
                    Text(messages[i])
                }
            }

            Row {
                TextField(
                    modifier = Modifier.weight(1f),
                    value = typedMessage.value,
                    onValueChange = { typedMessage.value = it },
                    placeholder = { Text("Type a message") }
                )

                Spacer(Modifier.padding(4.dp))

                Button(onClick = {
                    val txt = typedMessage.value.trim()
                    if (txt.isNotEmpty()) {
                        onSend(txt)
                        typedMessage.value = ""
                    }
                }) {
                    Text("Send")
                }
            }
        }
    }
}