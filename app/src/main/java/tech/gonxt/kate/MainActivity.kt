package tech.gonxt.kate

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import tech.gonxt.kate.core.BluetoothCarMonitor
import tech.gonxt.kate.ui.KateApp
import tech.gonxt.kate.ui.theme.KateTheme

class MainActivity : ComponentActivity() {

    private val vm: KateViewModel by viewModels()
    private var carMonitor: BluetoothCarMonitor? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        carMonitor = BluetoothCarMonitor(this) { connected ->
            if (vm.settings.value.drivingModeAuto) vm.setDrivingMode(connected)
        }
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
