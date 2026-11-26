package com.robomorse.app.ui.onboarding

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.robomorse.app.AppContainer
import com.robomorse.app.R
import com.robomorse.app.data.Settings
import com.robomorse.app.ui.settings.SliderRow
import com.robomorse.core.MorseTiming
import kotlinx.coroutines.launch

private val Navy = Color(0xFF1F3A5F)
private val Amber = Color(0xFFF2C14E)

@Composable
fun OnboardingScreen(c: AppContainer, onDone: () -> Unit) {
    val s by c.settings.settings.collectAsStateWithLifecycle(initialValue = Settings())
    val scope = rememberCoroutineScope()
    var page by remember { mutableIntStateOf(0) }
    var plan by remember { mutableStateOf("") }
    fun playSample(text: String) = scope.launch {
        c.player.play(text, MorseTiming(s.charWpm.toDouble(), s.effWpm.toDouble()), s.pitchHz, s.volume)
    }

    Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
            when (page) {
                0 -> Welcome(onHear = { playSample("CQ CQ") })
                1 -> PageBody("Your sound") {
                    Text("Pick a comfortable pitch. Most operators like something between 500 and 700 Hz.")
                    SliderRow("Pitch", "${s.pitchHz} Hz", s.pitchHz.toFloat(), 400f..1000f, 11) { v ->
                        scope.launch { c.settings.update { it.copy(pitchHz = (v / 50).toInt() * 50) } }
                    }
                    SliderRow("Volume", "${(s.volume * 100).toInt()}%", s.volume, 0.1f..1f, 0) { v ->
                        scope.launch { c.settings.update { it.copy(volume = v) } }
                    }
                    OutlinedButton(onClick = { playSample("KM KM") }) { Text("Play a sample") }
                    Text(
                        "That was ${s.charWpm} WPM characters at ${s.effWpm} WPM effective. It sounds fast. " +
                            "That's intended, and it is easier to learn than it sounds.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                else -> PageBody("Make it a habit") {
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
        }

        // Footer: page dots and navigation.
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.weight(1f)) {
                repeat(3) { i ->
                    Box(
                        Modifier
                            .size(if (i == page) 10.dp else 8.dp)
                            .clip(CircleShape)
                            .background(if (i == page) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant),
                    )
                }
            }
            if (page > 0) TextButton(onClick = { page-- }) { Text("Back") }
            Spacer(Modifier.width(8.dp))
            Button(onClick = {
                if (page < 2) {
                    page++
                } else {
                    scope.launch {
                        c.settings.update { it.copy(onboarded = true, practicePlan = plan.trim()) }
                        onDone()
                    }
                }
            }) { Text(if (page == 0) "Get started" else if (page < 2) "Next" else "Start learning") }
        }
    }
}

@Composable
private fun PageBody(title: String, content: @Composable () -> Unit) {
    Column(
        Modifier.padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(title, style = MaterialTheme.typography.headlineMedium)
        content()
    }
}

@Composable
private fun Welcome(onHear: () -> Unit) {
    Column {
        // Hero
        Column(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(bottomStart = 28.dp, bottomEnd = 28.dp))
                .background(Navy)
                .padding(start = 24.dp, end = 24.dp, top = 24.dp, bottom = 28.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Image(
                painter = painterResource(R.drawable.ic_launcher_foreground),
                contentDescription = null,
                modifier = Modifier.size(150.dp),
            )
            Text("Robo Morse", style = MaterialTheme.typography.headlineLarge, color = Color.White, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(6.dp))
            Text(
                "Learn CW by ear, the way the clubs teach it, adapted to you.",
                style = MaterialTheme.typography.bodyLarge,
                color = Color.White.copy(alpha = 0.85f),
            )
            Spacer(Modifier.height(18.dp))
            Button(
                onClick = onHear,
                colors = ButtonDefaults.buttonColors(containerColor = Amber, contentColor = Navy),
            ) { Text("♪  Hear “CQ CQ”", fontWeight = FontWeight.SemiBold) }
        }

        Column(Modifier.padding(horizontal = 20.dp, vertical = 20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("How it works", style = MaterialTheme.typography.titleLarge)
            Principle("1", "By sound, not charts", "Each character is one sound, the way you'll hear it on the air. No dots and dashes to translate.")
            Principle("2", "Full speed from day one", "Characters play at 20 WPM. Extra space between them gives you thinking time, and shrinks as you improve.")
            Principle("3", "Two letters to start", "A new character is added only when you're ready (the Koch method).")
            Principle("4", "Adapts to you", "Tracks accuracy and reaction time for every character, and drills what you find hard.")
            Principle("5", "Little and often", "Short daily sessions beat long ones. The app tells you when to stop.")
        }
    }
}

@Composable
private fun Principle(n: String, title: String, body: String) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(14.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Box(
            Modifier.size(32.dp).clip(CircleShape).background(Amber),
            contentAlignment = Alignment.Center,
        ) { Text(n, color = Navy, fontWeight = FontWeight.Bold) }
        Spacer(Modifier.width(12.dp))
        Column {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(body, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
