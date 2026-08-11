package tech.gonxt.kate.core

import android.Manifest
import android.bluetooth.BluetoothClass
import android.bluetooth.BluetoothDevice
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat

/**
 * Driving Mode v1 auto-trigger (spec M1.1): flips driving mode when a car-audio
 * Bluetooth device connects. Runtime-registered, so it works while the app is
 * alive; the M1.3 foreground service will keep it armed permanently.
 */
class BluetoothCarMonitor(
    private val context: Context,
    private val onCarConnected: (Boolean) -> Unit,
) {
    private var registered = false

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context, intent: Intent) {
            val device: BluetoothDevice? =
                intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
            if (device == null || !hasPermission()) return
            val isCar = try {
                device.bluetoothClass?.deviceClass == BluetoothClass.Device.AUDIO_VIDEO_CAR_AUDIO
            } catch (_: SecurityException) {
                false
            }
            if (!isCar) return
            when (intent.action) {
                BluetoothDevice.ACTION_ACL_CONNECTED -> onCarConnected(true)
                BluetoothDevice.ACTION_ACL_DISCONNECTED -> onCarConnected(false)
            }
        }
    }

    fun hasPermission(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) ==
            PackageManager.PERMISSION_GRANTED

    fun start() {
        if (registered || !hasPermission()) return
        val filter = IntentFilter().apply {
            addAction(BluetoothDevice.ACTION_ACL_CONNECTED)
            addAction(BluetoothDevice.ACTION_ACL_DISCONNECTED)
        }
        ContextCompat.registerReceiver(context, receiver, filter, ContextCompat.RECEIVER_EXPORTED)
        registered = true
    }

    fun stop() {
        if (!registered) return
        context.unregisterReceiver(receiver)
        registered = false
    }
}
