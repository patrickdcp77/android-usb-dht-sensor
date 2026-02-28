package com.example.myapplication

import android.Manifest
import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.location.Location
import android.location.LocationManager
import android.os.Build
import android.os.Build.VERSION_CODES.TIRAMISU
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.google.android.gms.location.*
import com.google.android.gms.tasks.CancellationTokenSource
import com.hoho.android.usbserial.driver.UsbSerialPort
import com.hoho.android.usbserial.driver.UsbSerialProber
import com.hoho.android.usbserial.util.SerialInputOutputManager
import java.nio.charset.Charset
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import androidx.core.content.ContextCompat

class MainActivity : ComponentActivity(), SerialInputOutputManager.Listener {

    private val actionUsbPermission = "com.example.myapplication.USB_PERMISSION"

    // Permission interne pour marquer ce broadcast comme protégé
    private val usbPermissionBroadcastPermission = "com.example.myapplication.permission.USB_PERMISSION"

    private var usbManager: UsbManager? = null
    private var port: UsbSerialPort? = null
    private var ioManager: SerialInputOutputManager? = null
    private val executor = Executors.newSingleThreadExecutor()
    private val lineBuffer = StringBuilder()

    private val statusState = mutableStateOf("Idle")
    private val tempState = mutableStateOf<Double?>(null)
    private val humState = mutableStateOf<Double?>(null)
    private val rawState = mutableStateOf("")

    private val locationState = mutableStateOf<Location?>(null)
    private val locationStatusState = mutableStateOf("Location: idle")

    private lateinit var fusedLocationClient: FusedLocationProviderClient

    private val locationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { _ ->
            fetchLocationOnce()
        }

    private val usbPermissionReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action != actionUsbPermission) return

            val device = if (Build.VERSION.SDK_INT >= TIRAMISU) {
                intent.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
            } else {
                @Suppress("DEPRECATION")
                intent.getParcelableExtra(UsbManager.EXTRA_DEVICE) as? UsbDevice
            }

            val granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
            if (granted && device != null) {
                setStatus("USB permission granted, opening…")
                openFirstSerialDevice()
            } else {
                setStatus("USB permission denied")
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        usbManager = getSystemService(Context.USB_SERVICE) as UsbManager
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)

        val filter = IntentFilter(actionUsbPermission)
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(
                usbPermissionReceiver,
                filter,
                usbPermissionBroadcastPermission,
                null,
                Context.RECEIVER_NOT_EXPORTED
            )
        } else {
            @Suppress("DEPRECATION")
            @SuppressLint("UnspecifiedRegisterReceiverFlag")
            registerReceiver(
                usbPermissionReceiver,
                filter,
                usbPermissionBroadcastPermission,
                null
            )
        }

        setContent {
            MaterialTheme {
                SensorScreen(
                    status = statusState.value,
                    temp = tempState.value,
                    hum = humState.value,
                    raw = rawState.value,
                    location = locationState.value,
                    locationStatus = locationStatusState.value
                )
            }
        }

        ensureLocationPermissionThenFetch()
        setStatus("Plug OTG + ESP8266, then reopen app if needed")
    }

    override fun onResume() {
        super.onResume()
        openFirstSerialDevice()
        fetchLocationOnce()
    }

    override fun onPause() {
        super.onPause()
        closeSerial()
    }

    override fun onDestroy() {
        super.onDestroy()
        try {
            unregisterReceiver(usbPermissionReceiver)
        } catch (_: Exception) {
        }
        executor.shutdownNow()
    }

    private fun setStatus(s: String) {
        statusState.value = s
    }

    private fun openFirstSerialDevice() {
        val manager = usbManager ?: return

        val availableDrivers = UsbSerialProber.getDefaultProber().findAllDrivers(manager)
        if (availableDrivers.isEmpty()) {
            setStatus("No USB-Serial device found")
            return
        }

        val driver = availableDrivers[0]
        val device = driver.device

        if (!manager.hasPermission(device)) {
            setStatus("Requesting USB permission…")

            val flags = if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0
            val permissionIntent = PendingIntent.getBroadcast(
                this,
                0,
                Intent(actionUsbPermission),
                flags
            )
            manager.requestPermission(device, permissionIntent)
            return
        }

        val connection = manager.openDevice(device)
        if (connection == null) {
            setStatus("Cannot open USB device (connection null)")
            return
        }

        val newPort = driver.ports.firstOrNull()
        if (newPort == null) {
            setStatus("No serial port on device")
            return
        }

        try {
            newPort.open(connection)
            newPort.setParameters(115200, 8, UsbSerialPort.STOPBITS_1, UsbSerialPort.PARITY_NONE)
            port = newPort

            setStatus("Connected: ${device.productName ?: "USB-Serial"}")
            startIoManager()
        } catch (e: Exception) {
            setStatus("Open failed: ${e.message}")
            try {
                newPort.close()
            } catch (_: Exception) {
            }
        }
    }

    private fun startIoManager() {
        val p = port ?: return
        stopIoManager()
        ioManager = SerialInputOutputManager(p, this)
        executor.submit(ioManager)
    }

    private fun stopIoManager() {
        ioManager?.stop()
        ioManager = null
    }

    private fun closeSerial() {
        stopIoManager()
        try {
            port?.close()
        } catch (_: Exception) {
        }
        port = null
        lineBuffer.clear()
    }

    override fun onNewData(data: ByteArray) {
        val text = data.toString(Charset.forName("UTF-8"))
        for (ch in text) {
            if (ch == '\n') {
                val line = lineBuffer.toString().trim()
                lineBuffer.clear()
                if (line.isNotEmpty()) handleLine(line)
            } else if (ch != '\r') {
                lineBuffer.append(ch)
            }
        }
    }

    override fun onRunError(e: Exception) {
        setStatus("IO error: ${e.message}")
    }

    private fun handleLine(line: String) {
        rawState.value = line

        val map = parseKeyValueLine(line)
        val t = map["T"]?.toDoubleOrNull()
        val h = map["H"]?.toDoubleOrNull()

        if (t != null) tempState.value = t
        if (h != null) humState.value = h

        if (line.startsWith("ERR=")) setStatus("Sensor error: $line")
    }

    private fun parseKeyValueLine(line: String): Map<String, String> {
        val out = mutableMapOf<String, String>()
        val parts = line.split(";")
        for (p in parts) {
            val kv = p.split("=", limit = 2)
            if (kv.size == 2) out[kv[0].trim()] = kv[1].trim()
        }
        return out
    }

    private fun ensureLocationPermissionThenFetch() {
        val fine = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
        val coarse = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION)

        if (fine == PackageManager.PERMISSION_GRANTED || coarse == PackageManager.PERMISSION_GRANTED) {
            fetchLocationOnce()
        } else {
            locationStatusState.value = "Location: requesting permission…"
            locationPermissionLauncher.launch(
                arrayOf(
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION
                )
            )
        }
    }

    private fun isLocationEnabled(): Boolean {
        val lm = getSystemService(Context.LOCATION_SERVICE) as LocationManager
        return if (Build.VERSION.SDK_INT >= 28) {
            lm.isLocationEnabled
        } else {
            @Suppress("DEPRECATION")
            lm.isProviderEnabled(LocationManager.GPS_PROVIDER) || lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER)
        }
    }

    @SuppressLint("MissingPermission")
    private fun fetchLocationOnce() {
        val fineGranted = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        val coarseGranted = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
        if (!fineGranted && !coarseGranted) {
            locationStatusState.value = "Location: permission missing"
            return
        }

        if (!isLocationEnabled()) {
            locationStatusState.value = "Location: désactivée dans les réglages (active GPS/Localisation)"
            locationState.value = null
            return
        }

        val priority = if (fineGranted) Priority.PRIORITY_HIGH_ACCURACY else Priority.PRIORITY_BALANCED_POWER_ACCURACY
        locationStatusState.value = "Location: fetching…"

        val cts = CancellationTokenSource()
        fusedLocationClient.getCurrentLocation(priority, cts.token)
            .addOnSuccessListener { loc: Location? ->
                if (loc != null) {
                    locationState.value = loc
                    locationStatusState.value = "Location: OK"
                } else {
                    requestSingleUpdate(priority)
                }
            }
            .addOnFailureListener { e: Exception ->
                locationStatusState.value = "Location error: ${e.message}"
            }
    }

    @SuppressLint("MissingPermission")
    private fun requestSingleUpdate(priority: Int) {
        val fineGranted = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        val coarseGranted = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
        if (!fineGranted && !coarseGranted) {
            locationStatusState.value = "Location: permission missing"
            return
        }

        val request = LocationRequest.Builder(priority, TimeUnit.SECONDS.toMillis(1))
            .setMinUpdateIntervalMillis(TimeUnit.SECONDS.toMillis(1))
            .setMaxUpdateDelayMillis(TimeUnit.SECONDS.toMillis(2))
            .setMaxUpdates(1)
            .build()

        val callback = object : LocationCallback() {
            override fun onLocationResult(result: LocationResult) {
                val loc = result.lastLocation
                locationState.value = loc
                locationStatusState.value = if (loc != null) "Location: OK" else "Location: null"
                fusedLocationClient.removeLocationUpdates(this)
            }

            override fun onLocationAvailability(availability: LocationAvailability) {
                if (!availability.isLocationAvailable) {
                    locationStatusState.value = "Location: en attente de fix…"
                }
            }
        }

        locationStatusState.value = "Location: waiting for fix…"
        fusedLocationClient.requestLocationUpdates(request, callback, mainLooper)
            .addOnFailureListener { e ->
                locationStatusState.value = "Location updates error: ${e.message}"
            }
    }
}

@Composable
private fun SensorScreen(
    status: String,
    temp: Double?,
    hum: Double?,
    raw: String,
    location: Location?,
    locationStatus: String
) {
    Column(modifier = Modifier.padding(16.dp)) {
        Text(text = "Status: $status")
        Spacer(modifier = Modifier.height(16.dp))
        Text(text = "Temp: ${temp?.let { String.format(Locale.US, "%.1f", it) } ?: "--.-"} °C")
        Spacer(modifier = Modifier.height(8.dp))
        Text(text = "Hum:  ${hum?.let { String.format(Locale.US, "%.1f", it) } ?: "--.-"} %")
        Spacer(modifier = Modifier.height(16.dp))
        Text(text = locationStatus)
        Text(text = "Lat:  ${location?.latitude?.toString() ?: "--"}")
        Text(text = "Lon:  ${location?.longitude?.toString() ?: "--"}")
        Spacer(modifier = Modifier.height(16.dp))
        Text(text = "RAW: $raw")
    }
}
