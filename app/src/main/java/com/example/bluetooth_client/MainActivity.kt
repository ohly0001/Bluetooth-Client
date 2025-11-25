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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.tooling.preview.Preview
import com.example.bluetooth_client.ui.theme.Bluetooth_ClientTheme
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.codescanner.GmsBarcodeScannerOptions
import com.google.mlkit.vision.codescanner.GmsBarcodeScanning
import java.util.UUID

class MainActivity : ComponentActivity() {

    companion object {
        const val TAG = "BLUETOOTH_Client"
    }
    private var bluetoothManager:BluetoothManager? = null
    private var bluetoothAdapter: BluetoothAdapter? = null
    private var isScanning = false

    private var gattServer : BluetoothGatt? = null

    private val connectedCallback = object : BluetoothGattCallback() {

        //This gets called when the client either connects or disconnects from a Server:
        @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {

            when(newState)
            {
                BluetoothProfile.STATE_CONNECTED -> {
                    Log.d(TAG, "Connected to GATT server.")

                    //Step 8:
                    gattServer?.discoverServices() //this will call onServicesDiscovered() in GattCallbacks
                }
                BluetoothProfile.STATE_CONNECTING -> {
                    Log.d(TAG, "Connecting to GATT server.")
                }
                BluetoothProfile.STATE_DISCONNECTED -> {
                    Log.d(TAG, "Disconnected from GATT server.")
                }
            }
        }


        //This gets called whenever you request to read a Characteristic on the server:
        // Step 11a:
        override fun onCharacteristicRead(
            gatt: BluetoothGatt?,
            characteristic: BluetoothGattCharacteristic?,
            status: Int
        ) {
            super.onCharacteristicRead(gatt, characteristic, status)
            if(status == BluetoothGatt.GATT_SUCCESS)
                Log.d(TAG, "The current value is: ${String(characteristic!!.value)}")

            //Step 12: writing to the server
            characteristic!!.setValue("Eric says hello!")
            gatt!!.writeCharacteristic(characteristic)
            //end of step 12
        }
        //////////////end of step 11a:


        //This gets called whenever you request to write to a Characteristic on the server:
        override fun onCharacteristicWrite(
            gatt: BluetoothGatt?,
            characteristic: BluetoothGattCharacteristic?,
            status: Int
        ) {
            super.onCharacteristicWrite(gatt, characteristic, status)

            val str = String(characteristic!!.value)
            Log.d(TAG, "Characteristic written: $str")
        }

        //step 13a:
        //This gets called whenever someone else changes a Characteristic on the server:
        @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
        override fun onCharacteristicChanged(
            gatt: BluetoothGatt?,
            characteristic: BluetoothGattCharacteristic?
        ) {
            super.onCharacteristicChanged(gatt, characteristic)

            val str = String(characteristic!!.value)
            Log.d(TAG, "Characteristic changed: $str")

            //Step 14
            gatt?.disconnect()
            //end of step 14
        }
        ////////////////end of step 13a


        //Part of Step 8, called when discoverServices() has finished:
        @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
        override fun onServicesDiscovered(
            gatt: BluetoothGatt?,
            status: Int
        ) {
            super.onServicesDiscovered(gatt, status)

            //Step 9:
            val service = gatt?.getService(UUID.fromString("0000180D-0000-1000-8000-00805F9B34FB"))
            if(status == BluetoothGatt.GATT_SUCCESS)
            {

                val serviceUUID = service!!.uuid


                var characteristic = service.getCharacteristic(UUID.fromString("0000180D-0000-1000-8000-00805F9B34FB"))

                //Step 11: Tell server to notify you if characteristic changes
                gatt.setCharacteristicNotification(characteristic, true)


                gatt.readCharacteristic(characteristic) //this triggers onCharacteristicRead( ) when finished
                //end of step 11

                Log.d(TAG, "Service UUID: $serviceUUID, characteristic UUID:${characteristic.uuid}")

                //  }
            }
        }
    }

    @RequiresApi(Build.VERSION_CODES.R)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        bluetoothManager = getSystemService(BLUETOOTH_SERVICE) as? BluetoothManager
        if(bluetoothManager != null){
            bluetoothAdapter = bluetoothManager?.adapter
        }

        //Step 4
        val scanner = bluetoothAdapter?.bluetoothLeScanner
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY) // Fast scanning mode
            .setReportDelay(0) // Notify devices immediately
            .build()

        val filters = listOf(
            ScanFilter.Builder()
                //      .setDeviceName("MyBLEDevice") // Optional: Filter by device name
                .setServiceUuid(ParcelUuid(UUID.fromString("1010281D-1010-1010-8010-10805F9B34FB"))) // Filter by specific service, this should match the advertising UUID from the Server
                .build()
        )

        val scanCallback = object : ScanCallback() {

            //This gets called for every device found:
            @RequiresPermission(Manifest.permission.BLUETOOTH_SCAN)
            override fun onScanResult(callbackType: Int, result: ScanResult?) {
                super.onScanResult(callbackType, result)

                if(isScanning){
                    scanner?.stopScan(object: ScanCallback() {
                        override fun onScanResult(callbackType: Int, result: ScanResult?) {
                            super.onScanResult(callbackType, result)
                        }
                        override fun onScanFailed(errorCode: Int) {
                            super.onScanFailed(errorCode)
                        }
                    })
                    isScanning = false

                    //If result is not null, you've found a device advertising on that UUID, so connect to it:
                    result?.let {
                        val device = result.device
                        val deviceName = device.name
                        val deviceAddress = device.address
                        Log.d("BLE", "Device found: $deviceName ($deviceAddress)")

                        //Step 5
                        gattServer = device.connectGatt(this@MainActivity, false,
                            connectedCallback, BluetoothDevice.TRANSPORT_LE)
                        /////////////end of Step 5
                    }
                }
            }
            override fun onScanFailed(errorCode: Int) {
                super.onScanFailed(errorCode)
                Log.d("BLE", "Stopped bluetooth scan")

            }
        }
        ///end of step 4
        setContent {
            Bluetooth_ClientTheme {
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    Greeting(
                        modifier = Modifier.padding(innerPadding)
                    )
                }
            }
        }

        val requestPermissionLauncher =
            registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
                isGranted ->
                if (isGranted.values.all { it }) {

                    ///Step 4
                    isScanning = true
                    scanner?.startScan(filters, settings, scanCallback)

                    ///////////////end of Step 4
                } else {
                    // Explain to the user that the feature is unavailable because the
                    // feature requires a permission that the user has denied. At the
                    // same time, respect the user's decision. Don't link to system
                    // settings in an effort to convince the user to change their
                    // decision.
                }
            }

        if(Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) //SDK 31 or more
        {
            requestPermissionLauncher.launch(arrayOf(Manifest.permission.BLUETOOTH_CONNECT,
                // Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.BLUETOOTH_SCAN))
        }
        else //SDK 30 or less
        {
            requestPermissionLauncher.launch(arrayOf(Manifest.permission.BLUETOOTH))
        }
    }
}

@RequiresApi(Build.VERSION_CODES.R)
@Composable
fun Greeting(modifier: Modifier = Modifier) {
    val txtMsg = remember { mutableStateOf("") }
    val foundUUID = remember { mutableStateOf("")}
    val ctx = LocalContext.current
    val view = LocalView.current
    val messages = remember { mutableListOf<String>() } //ref lab 7
    //TODO send msg to server, get automatic response back

    Column(modifier = modifier) {
        //This button opens the QR scanner:
        Button(onClick = {
            val options = GmsBarcodeScannerOptions.Builder()
                .setBarcodeFormats(
                    Barcode.FORMAT_QR_CODE
                )
                .build()

            val scanner = GmsBarcodeScanning.getClient(ctx, options)

            scanner.startScan()
                .addOnSuccessListener { barcode ->
                    val rawValue = barcode.rawValue
                    if (!rawValue.isNullOrEmpty()) {
                        foundUUID.value = rawValue
                        view.performHapticFeedback(HapticFeedbackConstants.CONFIRM)
                    }
                }
                .addOnCanceledListener {
                    // Task canceled, nothing found
                    view.performHapticFeedback(HapticFeedbackConstants.REJECT)
                }
                .addOnFailureListener { exception ->
                    Log.e("BarcodeScan", "Scan failed", exception)
                    view.performHapticFeedback(HapticFeedbackConstants.REJECT)
                }
        }) {
            Text("Scan QR Code")
        }

        //when the UUID string is not empty, show the UUID:
        if(foundUUID.value.isNotEmpty()) {
            Text("Scanned UUID: ${foundUUID.value}")
            LazyColumn {
                items(messages.size) { index ->
                    Text(text = "Item: ${items[index]}")
                }
            }
            Row {
                TextField(
                    value = txtMsg.value,
                    onValueChange = {
                        txtMsg.value = it
                    },
                    placeholder = { Text("Type Here...") }
                )
                Button(onClick = {
                    // Send message somehow
                }) {
                    Text("Send")
                }
            }
        }
    }
}

@RequiresApi(Build.VERSION_CODES.R)
@Preview(showBackground = true)
@Composable
fun GreetingPreview() {
    Bluetooth_ClientTheme() {
        Greeting()
    }
}