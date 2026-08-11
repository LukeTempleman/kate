package tech.gonxt.kate.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import tech.gonxt.kate.KateViewModel
import tech.gonxt.kate.ui.screens.DrivingScreen
import tech.gonxt.kate.ui.screens.HomeScreen
import tech.gonxt.kate.ui.screens.SettingsScreen

object Routes {
    const val HOME = "home"
    const val SETTINGS = "settings"
    const val DRIVING = "driving"
}

@Composable
fun KateApp(vm: KateViewModel) {
    val nav = rememberNavController()
    val driving by vm.drivingMode.collectAsStateWithLifecycle()

    // Car Bluetooth auto-trigger: monitor flips the flag, nav follows it.
    LaunchedEffect(driving) {
        val current = nav.currentBackStackEntry?.destination?.route
        if (driving && current != Routes.DRIVING) nav.navigate(Routes.DRIVING)
        if (!driving && current == Routes.DRIVING) nav.popBackStack()
    }

    NavHost(navController = nav, startDestination = Routes.HOME) {
        composable(Routes.HOME) {
            HomeScreen(
                vm = vm,
                onOpenSettings = { nav.navigate(Routes.SETTINGS) },
                onOpenDriving = { vm.setDrivingMode(true) },
            )
        }
        composable(Routes.SETTINGS) {
            SettingsScreen(vm = vm, onBack = { nav.popBackStack() })
        }
        composable(Routes.DRIVING) {
            DrivingScreen(vm = vm, onExit = { vm.setDrivingMode(false) })
        }
    }
}
