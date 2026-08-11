package tech.gonxt.kate

import android.Manifest
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import tech.gonxt.kate.core.BluetoothCarMonitor
import tech.gonxt.kate.ui.KateApp
import tech.gonxt.kate.ui.theme.KateTheme

class MainActivity : ComponentActivity() {

    private val vm: KateViewModel by viewModels()
    private var carMonitor: BluetoothCarMonitor? = null

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
            vm.onPermissionsGranted()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        carMonitor = BluetoothCarMonitor(this) { connected ->
            if (vm.settings.value.drivingModeAuto) vm.setDrivingMode(connected)
        }
        permissionLauncher.launch(
            arrayOf(
                Manifest.permission.RECORD_AUDIO,
                Manifest.permission.POST_NOTIFICATIONS,
                Manifest.permission.BLUETOOTH_CONNECT,
            ),
        )
        setContent {
            KateTheme {
                KateApp(vm)
            }
        }
    }

    override fun onStart() {
        super.onStart()
        carMonitor?.start()
    }

    override fun onStop() {
        carMonitor?.stop()
        super.onStop()
    }
}
