package com.robomorse.app.ui.settings

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.robomorse.app.AppContainer
import com.robomorse.app.data.Settings
import com.robomorse.app.data.practiceDataShareIntent
import com.robomorse.app.reminder.Reminders
import com.robomorse.core.MorseTiming
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

@Composable
fun SettingsScreen(c: AppContainer, onBack: () -> Unit) {
    val s by c.settings.settings.collectAsStateWithLifecycle(initialValue = Settings())
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    var confirmReset by remember { mutableStateOf(false) }
    fun update(f: (Settings) -> Settings) = scope.launch { c.settings.update(f) }
    fun reschedule(next: Settings) = Reminders.schedule(context, next.reminderEnabled, next.reminderHour, next.reminderMinute)

    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        scope.launch {
            c.settings.update { it.copy(reminderEnabled = granted) }
            reschedule(c.settings.current())
        }
    }

    Column(
        Modifier.fillMaxSize().safeDrawingPadding().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Settings", style = MaterialTheme.typography.headlineMedium, modifier = Modifier.weight(1f))
            TextButton(onClick = onBack) { Text("Back") }
        }

        Section("Sound")
        SliderRow("Character speed", "${s.charWpm} WPM", s.charWpm.toFloat(), 15f..35f, 19) { v ->
            update { it.copy(charWpm = v.toInt(), effWpm = minOf(it.effWpm, v.toInt())) }
        }
        Text(
            "Characters stay fast so you learn their sound, not the dits and dahs. 20 WPM or more is recommended.",
            style = MaterialTheme.typography.bodySmall,
        )
        SliderRow("Effective speed", "${s.effWpm} WPM", s.effWpm.toFloat(), 5f..s.charWpm.toFloat(), (s.charWpm - 6).coerceAtLeast(0)) { v ->
            update { it.copy(effWpm = v.toInt()) }
        }
        Text("Adjusted automatically as you improve. Extra space goes between characters.", style = MaterialTheme.typography.bodySmall)
        SliderRow("Pitch", "${s.pitchHz} Hz", s.pitchHz.toFloat(), 400f..1000f, 11) { v ->
            update { it.copy(pitchHz = (v / 50).toInt() * 50) }
        }
        SliderRow("Volume", "${(s.volume * 100).toInt()}%", s.volume, 0.1f..1f, 0) { v -> update { it.copy(volume = v) } }
        OutlinedButton(onClick = {
            scope.launch { c.player.play("PARIS", MorseTiming(s.charWpm.toDouble(), s.effWpm.toDouble()), s.pitchHz, s.volume) }
        }) { Text("Test sound") }

        HorizontalDivider()
        Section("Goals and reminders")
        SliderRow("Daily goal", "${s.dailyGoalMinutes} min", s.dailyGoalMinutes.toFloat(), 5f..40f, 6) { v ->
            update { it.copy(dailyGoalMinutes = v.toInt()) }
        }
        SliderRow("Weekly goal", "${s.weeklyGoalDays} days", s.weeklyGoalDays.toFloat(), 1f..7f, 5) { v ->
            update { it.copy(weeklyGoalDays = v.toInt()) }
        }
        ToggleRow("Daily reminder", s.reminderEnabled) { on ->
            if (on && Build.VERSION.SDK_INT >= 33 && !Reminders.canNotify(context)) {
                permission.launch(Manifest.permission.POST_NOTIFICATIONS)
            } else {
                scope.launch {
                    c.settings.update { it.copy(reminderEnabled = on) }
                    reschedule(c.settings.current())
                }
            }
        }
        if (s.reminderEnabled) {
            SliderRow(
                "Reminder time", "%02d:%02d".format(s.reminderHour, s.reminderMinute),
                (s.reminderHour * 4 + s.reminderMinute / 15).toFloat(), 0f..95f, 94,
            ) { v ->
                scope.launch {
                    c.settings.update { it.copy(reminderHour = v.toInt() / 4, reminderMinute = (v.toInt() % 4) * 15) }
                    reschedule(c.settings.current())
                }
            }
        }
        var plan by remember(s.practicePlan) { mutableStateOf(s.practicePlan) }
        OutlinedTextField(
            value = plan,
            onValueChange = { plan = it; update { cur -> cur.copy(practicePlan = it) } },
            label = { Text("My plan: when and where I'll practice") },
            modifier = Modifier.fillMaxWidth(),
        )
        var myCall by remember(s.myCall) { mutableStateOf(s.myCall) }
        OutlinedTextField(
            value = myCall,
            onValueChange = { v ->
                val clean = v.uppercase().filter { it.isLetterOrDigit() || it == '/' }.take(12)
                myCall = clean
                update { cur -> cur.copy(myCall = clean) }
            },
            label = { Text("Your callsign (for on-air practice)") },
            supportingText = { Text("Leave blank and one is made up each time.") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )

        HorizontalDivider()
        Section("Pacing")
        SliderRow(
            "Pause between groups", "%.1f s".format(s.groupGapMs / 1000f),
            s.groupGapMs / 1000f, 0.5f..4f, 6,
        ) { v -> update { it.copy(groupGapMs = (v * 2).roundToInt() * 500) } }
        SliderRow(
            "Breather", if (s.breatherEvery == 0) "Off" else "about every ${s.breatherEvery * 4} characters",
            s.breatherEvery.toFloat(), 0f..20f, 3,
        ) { v -> update { it.copy(breatherEvery = (v / 5).roundToInt() * 5) } }
        Text("Replay missed characters", style = MaterialTheme.typography.bodyLarge)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("NEW" to "Newest only", "ALWAYS" to "Always", "NEVER" to "Never").forEach { (key, label) ->
                FilterChip(
                    selected = s.replayMissed == key,
                    onClick = { update { it.copy(replayMissed = key) } },
                    label = { Text(label) },
                )
            }
        }
        Text(
            "Hearing the right sound helps most with a character you've just been introduced to.",
            style = MaterialTheme.typography.bodySmall,
        )

        HorizontalDivider()
        Section("Answering")
        ToggleRow("Hard mode (every key active)", s.hardMode) { on -> update { it.copy(hardMode = on) } }
        ToggleRow("Key vibration", s.haptics) { on -> update { it.copy(haptics = on) } }
        ToggleRow("Show the pattern of missed characters", s.showPatternOnMiss) { on -> update { it.copy(showPatternOnMiss = on) } }
        ToggleRow("Right/wrong sounds in lessons and ICR", s.answerSounds) { on -> update { it.copy(answerSounds = on) } }

        HorizontalDivider()
        Section("Progress")
        OutlinedButton(onClick = {
            scope.launch { context.startActivity(practiceDataShareIntent(context, c.repository)) }
        }) { Text("Share practice data") }
        Text(
            "Saves every session and answer to a file (no callsign or account details) and opens the share menu, " +
                "so you can send it to the developer to help tune the app. Nothing is sent unless you choose where.",
            style = MaterialTheme.typography.bodySmall,
        )
        Button(onClick = { confirmReset = true }) { Text("Reset all progress") }
    }

    if (confirmReset) {
        AlertDialog(
            onDismissRequest = { confirmReset = false },
            title = { Text("Reset all progress?") },
            text = { Text("This permanently deletes every session, statistic and learned character. It cannot be undone.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmReset = false
                    scope.launch { c.repository.resetProgress() }
                }) { Text("Delete everything") }
            },
            dismissButton = { TextButton(onClick = { confirmReset = false }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun Section(title: String) = Text(title, style = MaterialTheme.typography.titleMedium)

@Composable
fun SliderRow(
    label: String,
    value: String,
    current: Float,
    range: ClosedFloatingPointRange<Float>,
    steps: Int,
    onChange: (Float) -> Unit,
) {
    // The setting is saved when the finger lifts, not on every movement.
    val state = remember(current, range, steps) { SliderState(current.coerceIn(range), steps, range) }
    Column {
        Row {
            Text(label, Modifier.weight(1f))
            Text(value)
        }
        Slider(
            state = state,
            onValueChange = { state.value = it },
            onValueChangeFinished = { onChange(state.value) },
        )
    }
}

@Composable
fun ToggleRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}
