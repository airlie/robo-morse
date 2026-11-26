package com.robomorse.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.robomorse.app.data.Settings
import com.robomorse.app.ui.copy.CopyScreen
import com.robomorse.app.ui.copy.CopyViewModel
import com.robomorse.app.ui.RoboMorseTheme
import com.robomorse.app.ui.home.HomeScreen
import com.robomorse.app.ui.home.HomeViewModel
import com.robomorse.app.ui.onboarding.OnboardingScreen
import com.robomorse.app.ui.practice.PracticeScreen
import com.robomorse.app.ui.practice.PracticeViewModel
import com.robomorse.app.ui.settings.SettingsScreen
import com.robomorse.app.ui.stats.CharacterDetailScreen
import com.robomorse.app.ui.stats.StatsScreen
import com.robomorse.app.ui.stats.StatsViewModel
import com.robomorse.app.ui.summary.SummaryScreen
import com.robomorse.core.PracticeMode
import com.robomorse.app.ui.send.SendMode
import com.robomorse.app.ui.send.SendScreen
import com.robomorse.app.ui.send.SendViewModel

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val container = (application as RoboMorseApp).container
        setContent {
            RoboMorseTheme {
                Surface(Modifier.fillMaxSize()) { AppNav(container) }
            }
        }
    }
}

@Composable
private fun AppNav(c: AppContainer) {
    // Wait for the first real settings value so we know whether to onboard.
    val initial by produceState<Settings?>(null) { value = c.settings.current() }
    val start = initial ?: run { Box(Modifier.fillMaxSize()); return }
    val nav = rememberNavController()
    val settings by c.settings.settings.collectAsStateWithLifecycle(initialValue = start)

    NavHost(nav, startDestination = if (start.onboarded) "home" else "onboarding") {
        composable("onboarding") {
            OnboardingScreen(c) { nav.navigate("home") { popUpTo("onboarding") { inclusive = true } } }
        }
        composable("home") {
            HomeScreen(
                vm = viewModel { HomeViewModel(c) },
                onPractice = { nav.navigate("practice/KOCH") },
                onIcr = { nav.navigate("practice/ICR") },
                onRefresher = { nav.navigate("practice/REFRESH") },
                onCopy = { nav.navigate("copy") },
                onFreeSend = { nav.navigate("send/FREE") },
                onEcho = { nav.navigate("send/ECHO") },
                onStats = { nav.navigate("stats") },
                onSettings = { nav.navigate("settings") },
                onCharacter = { ch -> nav.navigate("char/${ch.code}") },
            )
        }
        composable("practice/{mode}", arguments = listOf(navArgument("mode") { type = NavType.StringType })) { entry ->
            val mode = PracticeMode.valueOf(entry.arguments?.getString("mode") ?: "KOCH")
            PracticeScreen(
                viewModel { PracticeViewModel(c, mode) },
                onSwitch = { next -> nav.navigate("practice/${next.name}") { popUpTo("home") } },
            ) { hasSummary ->
                if (hasSummary) {
                    nav.navigate("summary") { popUpTo("home") }
                } else {
                    nav.popBackStack()
                }
            }
        }
        composable("summary") {
            val result = c.lastResult
            SummaryScreen(
                result = result,
                settings = settings,
                onDone = { nav.popBackStack("home", inclusive = false) },
                onAgain = {
                    val mode = result?.mode ?: PracticeMode.KOCH
                    nav.navigate("practice/${mode.name}") { popUpTo("home") }
                },
            )
        }
        composable("copy") { CopyScreen(viewModel { CopyViewModel(c) }, onBack = { nav.popBackStack() }) }
        composable("stats") {
            StatsScreen(viewModel { StatsViewModel(c) }, onBack = { nav.popBackStack() }) { ch -> nav.navigate("char/${ch.code}") }
        }
        composable("char/{ch}") { entry ->
            val ch = entry.arguments?.getString("ch")?.toIntOrNull()?.toChar() ?: 'K'
            CharacterDetailScreen(viewModel { StatsViewModel(c) }, ch, onBack = { nav.popBackStack() })
        }
        composable("settings") { SettingsScreen(c, onBack = { nav.popBackStack() }) }
        composable("send/{mode}", arguments = listOf(navArgument("mode") { type = NavType.StringType })) { entry ->
            val mode = SendMode.valueOf(entry.arguments?.getString("mode") ?: "FREE")
            SendScreen(viewModel { SendViewModel(c, mode, c.sidetone) }, c.sidetone, onBack = { nav.popBackStack() })
        }
    }
}
