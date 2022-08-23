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
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import com.google.android.material.bottomnavigation.BottomNavigationView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.navigation.findNavController
import androidx.navigation.ui.AppBarConfiguration
import androidx.navigation.ui.setupActionBarWithNavController
import androidx.navigation.ui.setupWithNavController
import com.example.smartwatch_sync.databinding.ActivityMainBinding
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

    private val defaultScope = CoroutineScope(Dispatchers.Default)

    private val mainHandler = Handler(Looper.getMainLooper())

    private var gattServiceConn: GattServiceConn? = null

    private var bleServiceData: BleService.DataPlane? = null

    private val myCharacteristicValueChangeNotifications = Channel<String>()

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

        if (missing.isNotEmpty()) {
            Log.i("Requesting perms: ", missing.toString())
            requestPermissionLauncher.launch(missing)
        } else {
            startup()
        }
    }

    private fun startup() {
        val gattCharacteristicValue = findViewById<TextView>(R.id.status_text)

        defaultScope.launch {
            for (newValue in myCharacteristicValueChangeNotifications) {
                mainHandler.run {
                    gattCharacteristicValue.text = newValue
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

                bleServiceData?.setMyCharacteristicChangedChannel(myCharacteristicValueChangeNotifications)
            }
        }
    }
}