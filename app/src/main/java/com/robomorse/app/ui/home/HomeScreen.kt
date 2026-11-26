package com.robomorse.app.ui.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.robomorse.app.AppContainer
import com.robomorse.app.data.Settings
import com.robomorse.app.data.minutesByDay
import com.robomorse.app.data.practiceDays
import com.robomorse.app.ui.CharacterMap
import com.robomorse.app.ui.TierLegend
import com.robomorse.core.Curriculum
import com.robomorse.core.LearnerModel
import com.robomorse.core.MasteryTier
import com.robomorse.core.SpeedScale
import com.robomorse.core.StreakInfo
import com.robomorse.core.Streaks
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import java.time.LocalDate

data class HomeState(
    val settings: Settings = Settings(),
    val tiers: Map<Char, MasteryTier> = emptyMap(),
    val minutesToday: Double = 0.0,
    val streak: StreakInfo = StreakInfo(0, 0, false),
    val daysThisWeek: Int = 0,
    val nextGoal: String = "",
    val loaded: Boolean = false,
)

class HomeViewModel(c: AppContainer) : ViewModel() {
    val state: StateFlow<HomeState> = combine(c.settings.settings, c.repository.stats, c.repository.sessions) { s, stats, sessions ->
        val today = LocalDate.now()
        val days = practiceDays(sessions)
        val scale = SpeedScale.from(stats.values)
        val learned = Curriculum.learned(s.kochOrder, s.learnedCount)
        val mastery = learned.associateWith { ch -> stats[ch]?.let { LearnerModel.mastery(it, scale) } ?: 0.0 }
        HomeState(
            settings = s,
            tiers = mastery.mapValues { MasteryTier.of(it.value) },
            minutesToday = minutesByDay(sessions)[today] ?: 0.0,
            streak = Streaks.compute(days, today),
            daysThisWeek = Streaks.daysThisWeek(days, today),
            nextGoal = nearestGoal(mastery, s),
            loaded = true,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), HomeState())
}

/** The goal closest to being reached: the "almost there" nudge. */
fun nearestGoal(mastery: Map<Char, Double>, s: Settings): String {
    val candidates = mastery.mapNotNull { (ch, m) ->
        val next = MasteryTier.entries.firstOrNull { it.min > m } ?: return@mapNotNull null
        Triple(ch, next, next.min - m)
    }
    val best = candidates.minByOrNull { it.third }
    val next = Curriculum.nextToUnlock(s.kochOrder, s.learnedCount)
    return when {
        best != null && best.third < 0.15 ->
            "${best.first} is ${(best.third * 100).toInt().coerceAtLeast(1)}% from ${best.second.label}"
        next != null -> "Keep accuracy at 90%+ to unlock “$next”"
        else -> "All characters learned! Next: raise your effective speed toward ${s.charWpm} WPM"
    }
}

@Composable
fun HomeScreen(
    vm: HomeViewModel,
    onPractice: () -> Unit,
    onIcr: () -> Unit,
    onFreeSend: () -> Unit,
    onEcho: () -> Unit,
    onStats: () -> Unit,
    onSettings: () -> Unit,
    onCharacter: (Char) -> Unit,
) {
    val s by vm.state.collectAsStateWithLifecycle()
    Column(
        Modifier.fillMaxSize().safeDrawingPadding().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Robo Morse", style = MaterialTheme.typography.headlineMedium, modifier = Modifier.weight(1f))
            TextButton(onClick = onStats) { Text("Stats") }
            TextButton(onClick = onSettings) { Text("Settings") }
        }

        Card(Modifier.fillMaxWidth()) {
            Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                val goal = s.settings.dailyGoalMinutes.toDouble()
                val progress = (s.minutesToday / goal).toFloat().coerceIn(0f, 1f)
                androidx.compose.foundation.layout.Box(contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(progress = { progress }, modifier = Modifier.size(72.dp), strokeWidth = 8.dp)
                    Text("${s.minutesToday.toInt()}/${goal.toInt()}", style = MaterialTheme.typography.labelLarge)
                }
                Column(Modifier.padding(start = 16.dp)) {
                    Text(
                        if (progress >= 1f) "Today's goal done" else "Today: ${s.minutesToday.toInt()} of ${goal.toInt()} min",
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Text("Streak: ${s.streak.days} day${if (s.streak.days == 1) "" else "s"}" +
                        if (s.streak.freezesUsedThisMonth > 0) " (${s.streak.freezesUsedThisMonth} freeze used this month)" else "")
                    Text("This week: ${s.daysThisWeek} of ${s.settings.weeklyGoalDays} days")
                }
            }
        }

        Button(onClick = onPractice, modifier = Modifier.fillMaxWidth().height(56.dp)) {
            Text("Start practice", style = MaterialTheme.typography.titleMedium)
        }
        OutlinedButton(onClick = onIcr, modifier = Modifier.fillMaxWidth()) { Text("ICR sprint (speed of recognition)") }

        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Sending", style = MaterialTheme.typography.titleMedium)
                Text(
                    "Build your rhythm with an on-screen straight key or iambic paddle.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedButton(onClick = onEcho, modifier = Modifier.weight(1f)) { Text("Echo") }
                    OutlinedButton(onClick = onFreeSend, modifier = Modifier.weight(1f)) { Text("Free sending") }
                }
            }
        }

        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row {
                    Text("Character map", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                    Text("${s.tiers.size} / ${s.settings.kochOrder.length}", fontWeight = FontWeight.SemiBold)
                }
                CharacterMap(s.settings.kochOrder, s.tiers, onClick = onCharacter)
                TierLegend()
                Spacer(Modifier.height(2.dp))
                Text("Next goal: ${s.nextGoal}", style = MaterialTheme.typography.bodyMedium)
            }
        }

        Text(
            "${s.settings.charWpm} WPM characters · ${s.settings.effWpm} WPM effective",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
