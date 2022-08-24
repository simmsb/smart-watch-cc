package com.example.smartwatch_sync

import android.app.Service
import android.content.Intent
import android.os.IBinder
import android.app.NotificationChannel
import android.app.NotificationManager
import android.bluetooth.*
import android.content.BroadcastReceiver
import android.content.Context
import android.content.IntentFilter
import android.os.*
import android.util.Log
import androidx.core.app.NotificationCompat
import com.example.smartwatchsync.Message
import com.example.smartwatchsync.Notification
import com.example.smartwatchsync.message
import com.example.smartwatchsync.syncClock
import com.google.protobuf.timestamp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.SendChannel
import kotlinx.coroutines.launch
import no.nordicsemi.android.ble.BleManager
import java.time.Instant
import java.util.*


/**
 * Connects with a Bluetooth LE GATT service and takes care of its notifications. The service
 * runs as a foreground service, which is generally required so that it can run even
 * while the containing app has no UI. It is also possible to have the service
 * started up as part of the OS boot sequence using code similar to the following:
 *
 * <pre>
 *     class OsNotificationReceiver : BroadcastReceiver() {
 *          override fun onReceive(context: Context?, intent: Intent?) {
 *              when (intent?.action) {
 *                  // Start our Gatt service as a result of the system booting up
 *                  Intent.ACTION_BOOT_COMPLETED -> {
 *                     context?.startForegroundService(Intent(context, GattService::class.java))
 *                  }
 *              }
 *          }
 *      }
 * </pre>
 */
class BleService : Service() {

    private val defaultScope = CoroutineScope(Dispatchers.Default)

    private lateinit var bluetoothObserver: BroadcastReceiver

    private var deviceNotificationChannel: SendChannel<Notification>? = null

    private val clientManagers = mutableMapOf<String, ClientManager>()

    override fun onCreate() {
        super.onCreate()

        // Setup as a foreground service

        val notificationChannel = NotificationChannel(
            BleService::class.java.simpleName,
            resources.getString(R.string.gatt_service_name),
            NotificationManager.IMPORTANCE_DEFAULT
        )
        val notificationService =
            getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationService.createNotificationChannel(notificationChannel)

        val notification = NotificationCompat.Builder(this, BleService::class.java.simpleName)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(resources.getString(R.string.gatt_service_name))
            .setContentText(resources.getString(R.string.gatt_service_running_notification))
            .setAutoCancel(true)

        startForeground(1, notification.build())

        // Observe OS state changes in BLE

        bluetoothObserver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                when (intent?.action) {
                    BluetoothAdapter.ACTION_STATE_CHANGED -> {
                        val bluetoothState = intent.getIntExtra(
                            BluetoothAdapter.EXTRA_STATE,
                            -1
                        )
                        when (bluetoothState) {
                            BluetoothAdapter.STATE_ON -> enableBleServices()
                            BluetoothAdapter.STATE_OFF -> disableBleServices()
                        }
                    }
                    BluetoothDevice.ACTION_BOND_STATE_CHANGED -> {
                        val device =
                            intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
                        Log.d(TAG, "Bond state changed for device ${device?.address}: ${device?.bondState}")
                        when (device?.bondState) {
                            BluetoothDevice.BOND_BONDED -> addDevice(device)
                            BluetoothDevice.BOND_NONE -> removeDevice(device)
                        }
                    }

                }
            }
        }
        registerReceiver(bluetoothObserver, IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED))
        registerReceiver(bluetoothObserver, IntentFilter(BluetoothDevice.ACTION_BOND_STATE_CHANGED))

        // Startup BLE if we have it

        val bluetoothManager = getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        if (bluetoothManager.adapter?.isEnabled == true) enableBleServices()
    }

    override fun onDestroy() {
        super.onDestroy()
        unregisterReceiver(bluetoothObserver)
        disableBleServices()
    }

    override fun onBind(intent: Intent?): IBinder? =
        when (intent?.action) {
            DATA_PLANE_ACTION -> {
                DataPlane()
            }
            else -> null
        }

    override fun onUnbind(intent: Intent?): Boolean =
        when (intent?.action) {
            DATA_PLANE_ACTION -> {
                deviceNotificationChannel = null
                true
            }
            else -> false
        }

    /**
     * A binding to be used to interact with data of the service
     */
    inner class DataPlane : Binder() {
        fun setNotificationChangedChannel(sendChannel: SendChannel<Notification>) {
            deviceNotificationChannel = sendChannel
        }

        fun sendMessage(msg: Message) {
            val data = msg.toByteArray()

            clientManagers.values.forEach {
                it.sendToMessageChar(data)
            }
        }
    }

    private fun enableBleServices() {
        val bluetoothManager = getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        if (bluetoothManager.adapter?.isEnabled == true) {
            Log.i(TAG, "Enabling BLE services")
            bluetoothManager.adapter.bondedDevices.forEach { device -> addDevice(device) }
        } else {
            Log.w(TAG, "Cannot enable BLE services as either there is no Bluetooth adapter or it is disabled")
        }
    }

    private fun disableBleServices() {
        clientManagers.values.forEach { clientManager ->
            clientManager.close()
        }
        clientManagers.clear()
    }

    private fun addDevice(device: BluetoothDevice) {
        if (!clientManagers.containsKey(device.address)) {
            val clientManager = ClientManager()
            clientManager.connect(device).useAutoConnect(true).enqueue()
            clientManagers[device.address] = clientManager
        }
    }

    private fun removeDevice(device: BluetoothDevice) {
        clientManagers.remove(device.address)?.close()
    }

    /*
     * Manages the entire GATT service, declaring the services and characteristics on offer
     */
    companion object {
        /**
         * A binding action to return a binding that can be used in relation to the service's data
         */
        const val DATA_PLANE_ACTION = "data-plane"

        private const val TAG = "gatt-service"
    }

    private inner class ClientManager : BleManager(this@BleService) {
        override fun getGattCallback(): BleManagerGattCallback = GattCallback()

        private var notificationChar: BluetoothGattCharacteristic? = null

        private var messageWriteChar: BluetoothGattCharacteristic? = null

        override fun log(priority: Int, message: String) {
            if (BuildConfig.DEBUG || priority == Log.ERROR) {
                Log.println(priority, TAG, message)
            }
        }

        fun sendToMessageChar(value: ByteArray) {
            writeCharacteristic(messageWriteChar, value, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT)
                .enqueue()
        }

        private inner class GattCallback : BleManagerGattCallback() {

            override fun isRequiredServiceSupported(gatt: BluetoothGatt): Boolean {
                val service = gatt.getService(MyServiceProfile.MY_SERVICE_UUID)
                messageWriteChar =
                    service?.getCharacteristic(MyServiceProfile.MESSAGE_WRITE_UUID)
                notificationChar =
                    service?.getCharacteristic(MyServiceProfile.NOTIFICATION_UUID)
                val messageWriteProperties = messageWriteChar?.properties ?: 0
                val notificationProperties = notificationChar?.properties ?: 0
                return (messageWriteProperties and BluetoothGattCharacteristic.PROPERTY_WRITE != 0) &&
                        (notificationProperties and BluetoothGattCharacteristic.PROPERTY_NOTIFY != 0)
            }

            override fun initialize() {
                Log.i("Service: ", "initializing")

                requestMtu(517).enqueue();

                val now = Instant.now()
                val sync = syncClock {
                    timestamp = timestamp {
                        seconds = now.epochSecond
                        nanos = now.nano
                    }
                }
                val message = message {
                    origin = 3387062
                    syncClock = sync
                }

                writeCharacteristic(messageWriteChar, message.toByteArray(), BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT)
                    .enqueue()

                setNotificationCallback(notificationChar).with { _, data ->
                    if (data.value != null) {
                        val msg = Notification.parseFrom(data.value!!)
                        defaultScope.launch {
                            deviceNotificationChannel?.send(msg)
                        }
                    }
                }

                beginAtomicRequestQueue()
                    .add(enableNotifications(notificationChar)
                        .fail { _: BluetoothDevice?, status: Int ->
                            log(Log.ERROR, "Could not subscribe: $status")
                            disconnect().enqueue()
                        }
                    )
                    .done {
                        log(Log.INFO, "Target initialized")
                    }
                    .enqueue()
            }

            override fun onServicesInvalidated() {
                messageWriteChar = null
                notificationChar = null
            }
        }
    }

    object MyServiceProfile {
        val MY_SERVICE_UUID: UUID = UUID.fromString("98200001-2160-4474-82b4-1a25cef92156")
        val MESSAGE_WRITE_UUID: UUID = UUID.fromString("98200002-2160-4474-82b4-1a25cef92156")
        val NOTIFICATION_UUID: UUID = UUID.fromString("98200003-2160-4474-82b4-1a25cef92156")
    }
}