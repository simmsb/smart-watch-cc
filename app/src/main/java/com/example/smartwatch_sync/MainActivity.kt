package com.example.smartwatch_sync

import android.Manifest
import android.content.ComponentName
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.example.smartwatch_sync.databinding.ActivityMainBinding
import com.example.smartwatchsync.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import no.nordicsemi.android.ble.ktx.BuildConfig

class MainActivity : AppCompatActivity() {

    private lateinit var pins: Map<Pins, Pin>
    private lateinit var binding: ActivityMainBinding

    private val defaultScope = CoroutineScope(Dispatchers.Default)

    private val mainHandler = Handler(Looper.getMainLooper())

    private var gattServiceConn: GattServiceConn? = null

    private var bleServiceData: BleService.DataPlane? = null

    private val notifications = Channel<Notification>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val requestPermissionLauncher =
            registerForActivityResult(
                ActivityResultContracts.RequestMultiplePermissions()
            ) { it ->
                Log.i("Permissions", it.toString())
                val all = it.all { it.value }
                if (all) {
                    startup()
                    Log.i("Permission: ", "Granted")
                } else {
                    Log.i("Permission: ", "Denied")
                }
            }


        val perms = arrayOf(
            Manifest.permission.BLUETOOTH_CONNECT,
            Manifest.permission.BLUETOOTH_ADMIN,
            Manifest.permission.ACCESS_FINE_LOCATION,
        )

        val missing = perms.filterNot {
            ContextCompat.checkSelfPermission(
                this,
                it,
            ) == PackageManager.PERMISSION_GRANTED
        }.toTypedArray()

        addRows()

        if (missing.isNotEmpty()) {
            Log.i("Requesting perms: ", missing.toString())
            requestPermissionLauncher.launch(missing)
        } else {
            startup()
        }

        val readButton = findViewById<Button>(R.id.read_button)
        readButton.setOnClickListener {
            pins.values.forEach {
                if (it.mode == PinOperation.AnalogueRead) {
                    val msg = message {
                        origin = 3387062
                        setPin = SetPin.newBuilder()
                            .setPin(it.pin)
                            .setOp(PinOperation.AnalogueRead)
                            .build()
                    }
                    sendMessage(msg)
                }
            }
        }
    }

    fun sendMessage(msg: Message) {
        Log.i("Sending message: ", msg.toString())
        bleServiceData?.sendMessage(msg)
    }

    private fun startup() {
        val gattCharacteristicValue = findViewById<TextView>(R.id.status_text)

        defaultScope.launch {
            for (newValue in notifications) {
                mainHandler.run {
                    newValue.pinReadOrNull?.let {
                        pins[it.pin]?.setResult(it.value)
                    }
                }
            }
        }

        startForegroundService(Intent(this, BleService::class.java))
    }

    override fun onStart() {
        super.onStart()

        val latestGattServiceConn = GattServiceConn()
        if (bindService(Intent(BleService.DATA_PLANE_ACTION, null, this, BleService::class.java), latestGattServiceConn, 0)) {
            gattServiceConn = latestGattServiceConn
        }
    }

    override fun onStop() {
        super.onStop()

        gattServiceConn?.let {
            unbindService(it)
        }
        gattServiceConn = null
    }

    override fun onDestroy() {
        super.onDestroy()

        stopService(Intent(this, BleService::class.java))
    }

    private inner class GattServiceConn : ServiceConnection {
        override fun onServiceDisconnected(name: ComponentName?) {
            if (BuildConfig.DEBUG && BleService::class.java.name != name?.className) {
                error("Disconnected from unknown service")
            } else {
                bleServiceData = null
            }
        }

        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            if (BuildConfig.DEBUG && BleService::class.java.name != name?.className)
                error("Connected to unknown service")
            else {
                bleServiceData = service as BleService.DataPlane

                bleServiceData?.setNotificationChangedChannel(notifications)

            }
        }
    }

    private fun addRows() {
        val table = findViewById<TableLayout>(R.id.pin_table)
        val pins = arrayOf(
            Pin(Pins.G26, "G26", this),
            Pin(Pins.G25, "G25", this),
            Pin(Pins.G0, "G0", this),
        )

        val operations = arrayOf(
            PinOperation.SetLow,
            PinOperation.SetHigh,
            PinOperation.AnalogueRead
        )

        this.pins = pins.associateBy { pin ->
            val tableRow = TableRow(table.context)
            val name = TextView(this)
            name.text = pin.name
            tableRow.addView(name)

            val radios = operations.map { operation ->
                val button = RadioButton(this)
                tableRow.addView(button)

                if (operation == PinOperation.SetLow) {
                    button.isChecked = true
                }

                button.setOnClickListener {
                    pin.onClickHandlerFor(button, operation)
                }

                button
            }

            pin.radios = radios
            val result = TextView(this)
            pin.result = result
            tableRow.addView(result)
            table.addView(tableRow)

            pin.pin
        }
    }
}