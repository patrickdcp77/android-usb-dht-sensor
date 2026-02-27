package com.example.myapplication

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.hoho.android.usbserial.driver.UsbSerialPort
import com.hoho.android.usbserial.driver.UsbSerialProber
import com.hoho.android.usbserial.util.SerialInputOutputManager
import java.nio.charset.Charset
import java.util.concurrent.Executors

class MainActivity : ComponentActivity(), SerialInputOutputManager.Listener {

    private val ACTION_USB_PERMISSION = "com.example.myapplication.USB_PERMISSION"

    private var usbManager: UsbManager? = null
    private var port: UsbSerialPort? = null
    private var ioManager: SerialInputOutputManager? = null
    private val executor = Executors.newSingleThreadExecutor()
    private val lineBuffer = StringBuilder()

    // UI state (Compose)
    private val statusState = mutableStateOf("Idle")
    private val tempState = mutableStateOf<Double?>(null)
    private val humState = mutableStateOf<Double?>(null)
    private val rawState = mutableStateOf("")

    private val usbPermissionReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action != ACTION_USB_PERMISSION) return
            val device = intent.getParcelableExtra<UsbDevice>(UsbManager.EXTRA_DEVICE)
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
        registerReceiver(usbPermissionReceiver, IntentFilter(ACTION_USB_PERMISSION))

        setContent {
            MaterialTheme {
                SensorScreen(
                    status = statusState.value,
                    temp = tempState.value,
                    hum = humState.value,
                    raw = rawState.value
                )
            }
        }

        setStatus("Plug OTG + ESP8266, then reopen app if needed")
    }

    override fun onResume() {
        super.onResume()
        openFirstSerialDevice()
    }

    override fun onPause() {
        super.onPause()
        closeSerial()
    }

    override fun onDestroy() {
        super.onDestroy()
        try { unregisterReceiver(usbPermissionReceiver) } catch (_: Exception) {}
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

            val flags = if (Build.VERSION.SDK_INT >= 31)
                PendingIntent.FLAG_MUTABLE
            else
                0

            val permissionIntent = PendingIntent.getBroadcast(
                this, 0, Intent(ACTION_USB_PERMISSION), flags
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
            newPort.setParameters(
                115200,
                8,
                UsbSerialPort.STOPBITS_1,
                UsbSerialPort.PARITY_NONE
            )
            port = newPort

            setStatus("Connected: ${device.productName ?: "USB-Serial"}")
            startIoManager()

        } catch (e: Exception) {
            setStatus("Open failed: ${e.message}")
            try { newPort.close() } catch (_: Exception) {}
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
        try { port?.close() } catch (_: Exception) {}
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
}

@Composable
private fun SensorScreen(status: String, temp: Double?, hum: Double?, raw: String) {
    Column(modifier = Modifier.padding(16.dp)) {
        Text(text = "Status: $status")
        Spacer(modifier = Modifier.height(16.dp))
        Text(text = "Temp: ${temp?.let { String.format("%.1f", it) } ?: "--.-"} °C")
        Spacer(modifier = Modifier.height(8.dp))
        Text(text = "Hum:  ${hum?.let { String.format("%.1f", it) } ?: "--.-"} %")
        Spacer(modifier = Modifier.height(16.dp))
        Text(text = "RAW: $raw")
    }
}