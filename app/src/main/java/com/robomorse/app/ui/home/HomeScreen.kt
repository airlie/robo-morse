package com.robomorse.app.ui.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
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
import com.robomorse.app.data.toLocalDate
import com.robomorse.app.ui.CharacterMap
import com.robomorse.app.ui.MorsePattern
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
import java.time.temporal.ChronoUnit

data class HomeState(
    val settings: Settings = Settings(),
    val tiers: Map<Char, MasteryTier> = emptyMap(),
    val minutesToday: Double = 0.0,
    val streak: StreakInfo = StreakInfo(0, 0, false),
    val daysThisWeek: Int = 0,
    val nextGoal: String = "",
    /** Whole days since the last session, or null if there has never been one. */
    val daysAway: Int? = null,
    val loaded: Boolean = false,
)

/** After this many days away, the home screen suggests a refresher. */
const val REFRESHER_AFTER_DAYS = 5

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
            daysAway = sessions.maxOfOrNull { it.endedAt }?.let { ChronoUnit.DAYS.between(it.toLocalDate(), today).toInt() },
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
    onCopy: () -> Unit,
    onHeadCopy: () -> Unit,
    onLadder: () -> Unit,
    onOnAir: () -> Unit,
    onVoice: () -> Unit,
    onRefresher: () -> Unit,
    onFreeSend: () -> Unit,
    onEcho: () -> Unit,
    onStats: () -> Unit,
    onSettings: () -> Unit,
    onCharacter: (Char) -> Unit,
) {
    val s by vm.state.collectAsStateWithLifecycle()
    val learnedCount = s.tiers.size
    val away = s.daysAway
    val suggestRefresher = s.loaded && away != null && away >= REFRESHER_AFTER_DAYS && learnedCount >= 2

    Column(
        Modifier.fillMaxSize().safeDrawingPadding().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Header(onStats, onSettings)
        TodayCard(s)

        if (suggestRefresher) WelcomeBackCard(away!!, onRefresher)

        SectionTitle("Listen", "Learn to hear each character as a whole sound")
        LessonCard(s, learnedCount, onPractice)
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            ModeTile(
                title = "Word copy",
                motif = "W",
                body = "Whole words, ham shorthand and callsigns.",
                onClick = onCopy,
                modifier = Modifier.weight(1f),
            )
            ModeTile(
                title = "Head copy",
                motif = "H",
                body = "Hear a word or phrase, hold it in your head, then pick it.",
                onClick = onHeadCopy,
                modifier = Modifier.weight(1f),
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            ModeTile(
                title = "ICR sprint",
                motif = "I",
                body = "Single characters. Answer as fast as you can.",
                onClick = onIcr,
                modifier = Modifier.weight(1f),
            )
            ModeTile(
                title = "Say it",
                motif = "S",
                body = "Hear it, then say it out loud. No keys to hunt for.",
                onClick = onVoice,
                modifier = Modifier.weight(1f),
            )
        }
        ModeTile(
            title = "Refresher",
            motif = "R",
            body = "Back after a break? Rebuild your characters two at a time.",
            onClick = onRefresher,
            modifier = Modifier.fillMaxWidth(),
            compact = true,
        )

        SectionTitle("On the air", "Callsigns and contacts, the way they sound on the band")
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            ModeTile(
                title = "Callsign ladder",
                motif = "C",
                body = "25 callsigns that speed up as you copy them. Beat your best.",
                onClick = onLadder,
                modifier = Modifier.weight(1f),
            )
            ModeTile(
                title = "On-air practice",
                motif = "Q",
                body = "A practice contact. Copy call, report, name and more.",
                onClick = onOnAir,
                modifier = Modifier.weight(1f),
            )
        }

        SectionTitle("Send", "Key it yourself on a straight key or paddle")
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            ModeTile(
                title = "Echo",
                motif = "E",
                body = "Hear it, then send it back. Letters up to callsigns.",
                onClick = onEcho,
                modifier = Modifier.weight(1f),
            )
            ModeTile(
                title = "Free sending",
                motif = "F",
                body = "Send anything and watch it decoded live.",
                onClick = onFreeSend,
                modifier = Modifier.weight(1f),
            )
        }

        SectionTitle("Your characters", null)
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Character map", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                    Text("$learnedCount / ${s.settings.kochOrder.length}", fontWeight = FontWeight.SemiBold)
                }
                CharacterMap(s.settings.kochOrder, s.tiers, onClick = onCharacter)
                TierLegend()
                Text("Next goal: ${s.nextGoal}", style = MaterialTheme.typography.bodyMedium)
                Text(
                    "Tap any character to hear it.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        Text(
            "${s.settings.charWpm} WPM characters · ${s.settings.effWpm} WPM effective",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 4.dp),
        )
    }
}

@Composable
private fun Header(onStats: () -> Unit, onSettings: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text("Robo Morse", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.SemiBold)
            // "RM" in Morse, as a quiet signature under the name.
            MorsePattern("RM", unit = 4.dp, color = MaterialTheme.colorScheme.secondary, modifier = Modifier.padding(top = 4.dp))
        }
        TextButton(onClick = onStats) { Text("Stats") }
        TextButton(onClick = onSettings) { Text("Settings") }
    }
}

@Composable
private fun TodayCard(s: HomeState) {
    Card(Modifier.fillMaxWidth()) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            val goal = s.settings.dailyGoalMinutes.toDouble()
            val progress = (s.minutesToday / goal).toFloat().coerceIn(0f, 1f)
            Box(contentAlignment = Alignment.Center) {
                CircularProgressIndicator(
                    progress = { progress },
                    modifier = Modifier.size(64.dp),
                    strokeWidth = 7.dp,
                    trackColor = MaterialTheme.colorScheme.surfaceVariant,
                )
                Text("${s.minutesToday.toInt()}/${goal.toInt()}", style = MaterialTheme.typography.labelLarge)
            }
            Column(Modifier.padding(start = 16.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    if (progress >= 1f) "Today's goal done" else "Today: ${s.minutesToday.toInt()} of ${goal.toInt()} min",
                    style = MaterialTheme.typography.titleMedium,
                )
                Text(
                    "Streak: ${s.streak.days} day${if (s.streak.days == 1) "" else "s"}" +
                        if (s.streak.freezesUsedThisMonth > 0) " (${s.streak.freezesUsedThisMonth} freeze used this month)" else "",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text("This week: ${s.daysThisWeek} of ${s.settings.weeklyGoalDays} days", style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

@Composable
private fun WelcomeBackCard(daysAway: Int, onRefresher: () -> Unit) {
    Card(
        Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.secondaryContainer,
            contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
        ),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Welcome back", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Text(
                "It's been $daysAway days. Some characters may have faded, and that's normal. A refresher " +
                    "starts with the first two and brings the rest back as they click.",
                style = MaterialTheme.typography.bodyMedium,
            )
            Button(onClick = onRefresher) { Text("Start a refresher") }
        }
    }
}

@Composable
private fun SectionTitle(title: String, subtitle: String?) {
    Column(Modifier.padding(top = 8.dp)) {
        Text(
            title.uppercase(),
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary,
        )
        subtitle?.let {
            Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** The main mode: a large card in the primary colour. */
@Composable
private fun LessonCard(s: HomeState, learnedCount: Int, onClick: () -> Unit) {
    val next = Curriculum.nextToUnlock(s.settings.kochOrder, s.settings.learnedCount)
    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primary,
            contentColor = MaterialTheme.colorScheme.onPrimary,
        ),
    ) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Lesson", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                MorsePattern("L", unit = 6.dp, color = MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.55f))
            }
            Text(
                "Groups of the characters you know, with extra practice on weak ones. " +
                    "A new character joins when you're ready.",
                style = MaterialTheme.typography.bodyMedium,
            )
            Spacer(Modifier.height(4.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    if (next != null) "$learnedCount characters · next up: $next" else "All $learnedCount characters learned",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.8f),
                    modifier = Modifier.weight(1f),
                )
                Button(
                    onClick = onClick,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.onPrimary,
                        contentColor = MaterialTheme.colorScheme.primary,
                    ),
                ) { Text("Start", fontWeight = FontWeight.SemiBold) }
            }
        }
    }
}

/** A secondary mode: title, a small Morse motif, and one line on what it trains. */
@Composable
private fun ModeTile(
    title: String,
    motif: String,
    body: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    compact: Boolean = false,
) {
    Card(
        onClick = onClick,
        modifier = if (compact) modifier else modifier.heightIn(min = 128.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            MorsePattern(motif, unit = 5.dp, color = MaterialTheme.colorScheme.secondary)
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Text(body, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
