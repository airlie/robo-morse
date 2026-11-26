package com.robomorse.app.ui.onboarding

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.robomorse.app.AppContainer
import com.robomorse.app.data.Settings
import com.robomorse.app.ui.settings.SliderRow
import com.robomorse.core.MorseTiming
import kotlinx.coroutines.launch

@Composable
fun OnboardingScreen(c: AppContainer, onDone: () -> Unit) {
    val s by c.settings.settings.collectAsStateWithLifecycle(initialValue = Settings())
    val scope = rememberCoroutineScope()
    var page by remember { mutableIntStateOf(0) }
    var plan by remember { mutableStateOf("") }

    Column(
        Modifier.fillMaxSize().safeDrawingPadding().verticalScroll(rememberScrollState()).padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        when (page) {
            0 -> {
                Text("Learn Morse by ear", style = MaterialTheme.typography.headlineMedium)
                Text("This app follows the methods that serious CW training groups have converged on:")
                Bullet("Sound only. No dot-dash charts. You learn each character as a single sound, the way you'll hear it on the air.")
                Bullet("Full-speed characters from day one (20 WPM). Extra space between characters gives you time to think, and it shrinks as you improve.")
                Bullet("Two characters to start, and a new one only when you're ready (Koch method).")
                Bullet("Speed of recognition matters, so response time is tracked as well as accuracy.")
                Bullet("The app focuses on what you find hard and spends less time on what you've mastered.")
                Bullet("Short daily sessions beat long ones. The app will tell you when to stop.")
            }
            1 -> {
                Text("Your sound", style = MaterialTheme.typography.headlineMedium)
                Text("Pick a comfortable pitch. Most operators like something between 500 and 700 Hz.")
                SliderRow("Pitch", "${s.pitchHz} Hz", s.pitchHz.toFloat(), 400f..1000f, 11) { v ->
                    scope.launch { c.settings.update { it.copy(pitchHz = (v / 50).toInt() * 50) } }
                }
                SliderRow("Volume", "${(s.volume * 100).toInt()}%", s.volume, 0.1f..1f, 0) { v ->
                    scope.launch { c.settings.update { it.copy(volume = v) } }
                }
                OutlinedButton(onClick = {
                    scope.launch { c.player.play("KM KM", MorseTiming(s.charWpm.toDouble(), s.effWpm.toDouble()), s.pitchHz, s.volume) }
                }) { Text("Play a sample") }
                Text(
                    "That was ${s.charWpm} WPM characters at ${s.effWpm} WPM effective. It sounds fast. That's intended, and it is easier to learn than it sounds.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            else -> {
                Text("Make it a habit", style = MaterialTheme.typography.headlineMedium)
                Text("People who decide when and where they'll practice are much more likely to follow through.")
                SliderRow("Daily goal", "${s.dailyGoalMinutes} min", s.dailyGoalMinutes.toFloat(), 5f..40f, 6) { v ->
                    scope.launch { c.settings.update { it.copy(dailyGoalMinutes = v.toInt()) } }
                }
                OutlinedTextField(
                    value = plan,
                    onValueChange = { plan = it },
                    label = { Text("When and where? e.g. “After breakfast, at the kitchen table”") },
                    modifier = Modifier.fillMaxWidth(),
                )
                Text("You can turn on a daily reminder in Settings.", style = MaterialTheme.typography.bodySmall)
            }
        }
        Spacer(Modifier.padding(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            if (page > 0) TextButton(onClick = { page-- }) { Text("Back") }
            Button(onClick = {
                if (page < 2) {
                    page++
                } else {
                    scope.launch {
                        c.settings.update { it.copy(onboarded = true, practicePlan = plan.trim()) }
                        onDone()
                    }
                }
            }) { Text(if (page < 2) "Next" else "Start learning") }
        }
    }
}

@Composable
private fun Bullet(text: String) = Text("•  $text", style = MaterialTheme.typography.bodyLarge)
