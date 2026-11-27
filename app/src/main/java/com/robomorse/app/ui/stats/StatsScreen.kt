package com.robomorse.app.ui.stats

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.robomorse.app.AppContainer
import com.robomorse.app.data.CharSessionPoint
import com.robomorse.app.data.ConfusionEntity
import com.robomorse.app.data.WHOLE_ITEM_MODES
import com.robomorse.app.data.SessionEntity
import com.robomorse.app.data.Settings
import com.robomorse.app.data.toLocalDate
import com.robomorse.core.Curriculum
import com.robomorse.core.MorseCode
import com.robomorse.app.ui.MorsePattern
import com.robomorse.core.MorseTiming
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.runtime.DisposableEffect
import com.robomorse.core.ItemStats
import com.robomorse.core.LearnerModel
import com.robomorse.core.MasteryTier
import com.robomorse.core.SpeedScale
import com.robomorse.app.ui.tierColor
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import java.time.LocalDate
import java.time.format.DateTimeFormatter

data class StatsState(
    val settings: Settings = Settings(),
    val stats: Map<Char, ItemStats> = emptyMap(),
    val sessions: List<SessionEntity> = emptyList(),
    val confusions: List<ConfusionEntity> = emptyList(),
)

class StatsViewModel(private val c: AppContainer) : ViewModel() {
    val state: StateFlow<StatsState> =
        combine(c.settings.settings, c.repository.stats, c.repository.sessions, c.repository.confusions) { s, st, se, co ->
            StatsState(s, st, se, co)
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), StatsState())

    suspend fun history(ch: Char): List<CharSessionPoint> = c.repository.charHistory(ch)

    private val _repeating = MutableStateFlow(false)
    val repeating: StateFlow<Boolean> = _repeating
    private var playJob: Job? = null

    /** Plays [ch] once, or over and over (with a word space between) when [repeat] is set. */
    fun play(ch: Char, repeat: Boolean) {
        playJob?.cancel()
        _repeating.value = repeat
        playJob = viewModelScope.launch {
            try {
                val s = c.settings.current()
                val timing = MorseTiming(s.charWpm.toDouble(), s.effWpm.toDouble())
                do {
                    c.player.play(ch.toString(), timing, s.pitchHz, s.volume)
                    // About the normal gap between letters: clearly separate, but not a long wait.
                    if (repeat) delay(timing.charGapMs.toLong().coerceIn(350, 900))
                } while (repeat)
            } finally {
                _repeating.value = false
            }
        }
    }

    fun stop() {
        playJob?.cancel()
        c.player.stop()
    }

    override fun onCleared() = stop()
}

@Composable
fun StatsScreen(vm: StatsViewModel, onBack: () -> Unit, onCharacter: (Char) -> Unit) {
    val s by vm.state.collectAsStateWithLifecycle()
    val learned = Curriculum.learned(s.settings.kochOrder, s.settings.learnedCount)
    val scale = SpeedScale.from(s.stats.values)
    Column(
        Modifier.fillMaxSize().safeDrawingPadding().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Stats", style = MaterialTheme.typography.headlineMedium, modifier = Modifier.weight(1f))
            TextButton(onClick = onBack) { Text("Back") }
        }
        Overview(s, learned, scale)
        SpeedHistory(s.sessions)
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp)) {
                Text("Characters", style = MaterialTheme.typography.titleMedium)
                Text("Tap a character for its history.", style = MaterialTheme.typography.bodySmall)
                Spacer(Modifier.height(8.dp))
                Row {
                    Text("", Modifier.width(36.dp))
                    Header("Mastery"); Header("Accuracy"); Header("Response"); Header("Seen")
                }
                learned.forEach { ch ->
                    val st = s.stats[ch]
                    val m = st?.let { LearnerModel.mastery(it, scale) } ?: 0.0
                    HorizontalDivider()
                    Row(
                        Modifier.fillMaxWidth().clickable { onCharacter(ch) }.padding(vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(
                            Modifier.size(28.dp).background(tierColor(MasteryTier.of(m))),
                            contentAlignment = Alignment.Center,
                        ) { Text(ch.toString(), fontWeight = FontWeight.Bold) }
                        Spacer(Modifier.width(8.dp))
                        Cell(MasteryTier.of(m).label)
                        Cell(st?.let { "${(it.acc * 100).toInt()}%" } ?: "–")
                        Cell(st?.rtMs?.let { "${it.toInt()} ms" } ?: "–")
                        Cell(st?.attempts?.toString() ?: "0")
                    }
                }
            }
        }
        ConfusionHeatmap(learned, s.confusions)
    }
}

@Composable
private fun androidx.compose.foundation.layout.RowScope.Header(t: String) =
    Text(t, Modifier.weight(1f), style = MaterialTheme.typography.labelSmall)

@Composable
private fun androidx.compose.foundation.layout.RowScope.Cell(t: String) =
    Text(t, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)

@Composable
private fun Overview(s: StatsState, learned: List<Char>, scale: SpeedScale) {
    val totalMin = s.sessions.sumOf { (it.endedAt - it.startedAt) / 60_000.0 }
    val weekAgo = LocalDate.now().minusDays(7)
    // Accuracy is per character, so copy sessions (scored per word) are left out.
    val recent = s.sessions.filter { it.startedAt.toLocalDate().isAfter(weekAgo) && it.mode !in WHOLE_ITEM_MODES }
    val recentAcc = recent.sumOf { it.correct }.toDouble() / recent.sumOf { it.attempts }.coerceAtLeast(1)
    val solid = learned.count { ch -> s.stats[ch]?.let { LearnerModel.mastery(it, scale) >= MasteryTier.SOLID.min } == true }
    val fastest = learned.mapNotNull { ch -> s.stats[ch]?.bestRtMs?.let { ch to it } }.minByOrNull { it.second }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Overview", style = MaterialTheme.typography.titleMedium)
            Line("Total practice", "%.0f min over %d session%s".format(totalMin, s.sessions.size, if (s.sessions.size == 1) "" else "s"))
            Line("Characters learned", "${learned.size} of ${s.settings.kochOrder.length}")
            Line("Solid or better", "$solid")
            Line("Speed", "${s.settings.charWpm} WPM characters, ${s.settings.effWpm} WPM effective")
            Line("Accuracy, last 7 days", if (recent.isEmpty()) "–" else "${(recentAcc * 100).toInt()}%")
            fastest?.let { Line("Fastest character", "${it.first} at ${it.second.toInt()} ms") }
        }
    }
}

@Composable
private fun Line(label: String, value: String) {
    Row(Modifier.fillMaxWidth()) {
        Text(label, Modifier.padding(end = 12.dp))
        Text(value, Modifier.weight(1f), fontWeight = FontWeight.SemiBold, textAlign = TextAlign.End)
    }
}

/** Effective speed and accuracy per session over time. */
@Composable
private fun SpeedHistory(sessions: List<SessionEntity>) {
    val points = sessions.filter { it.mode !in WHOLE_ITEM_MODES }.sortedBy { it.startedAt }.takeLast(60)
    if (points.size < 2) return
    val line = MaterialTheme.colorScheme.primary
    val acc = MaterialTheme.colorScheme.tertiary
    val grid = MaterialTheme.colorScheme.outlineVariant
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Text("Sessions", style = MaterialTheme.typography.titleMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Legend(line, "Effective WPM"); Legend(acc, "Accuracy")
            }
            Spacer(Modifier.height(8.dp))
            val maxWpm = (points.maxOf { it.effWpm }.coerceAtLeast(10) + 2).toFloat()
            Canvas(Modifier.fillMaxWidth().height(140.dp)) {
                for (i in 0..4) {
                    val y = size.height * i / 4
                    drawLine(grid, Offset(0f, y), Offset(size.width, y), 1f)
                }
                fun x(i: Int) = size.width * i / (points.size - 1)
                val wpmPath = Path()
                val accPath = Path()
                points.forEachIndexed { i, p ->
                    val yW = size.height * (1 - p.effWpm / maxWpm)
                    val a = if (p.attempts == 0) 0f else p.correct.toFloat() / p.attempts
                    val yA = size.height * (1 - a)
                    if (i == 0) { wpmPath.moveTo(x(i), yW); accPath.moveTo(x(i), yA) } else { wpmPath.lineTo(x(i), yW); accPath.lineTo(x(i), yA) }
                }
                drawPath(wpmPath, line, style = Stroke(width = 2.dp.toPx()))
                drawPath(accPath, acc, style = Stroke(width = 2.dp.toPx()))
            }
            val fmt = DateTimeFormatter.ofPattern("MMM d")
            Row(Modifier.fillMaxWidth()) {
                Text(points.first().startedAt.toLocalDate().format(fmt), style = MaterialTheme.typography.labelSmall, modifier = Modifier.weight(1f))
                Text(points.last().startedAt.toLocalDate().format(fmt), style = MaterialTheme.typography.labelSmall)
            }
        }
    }
}

@Composable
private fun Legend(color: Color, label: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(10.dp).background(color))
        Spacer(Modifier.width(4.dp))
        Text(label, style = MaterialTheme.typography.labelSmall)
    }
}

/** Rows: what was sent. Columns: what was typed. Darker = more often confused. */
@Composable
private fun ConfusionHeatmap(learned: List<Char>, confusions: List<ConfusionEntity>) {
    val relevant = confusions.filter { it.expected.first() in learned && it.answered.first() in learned }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Text("Confusions", style = MaterialTheme.typography.titleMedium)
            if (relevant.isEmpty()) {
                Text("No mix-ups recorded yet.", style = MaterialTheme.typography.bodySmall)
                return@Column
            }
            Text("Row = character sent, column = what you typed.", style = MaterialTheme.typography.bodySmall)
            val top = relevant.sortedByDescending { it.count }.take(5)
            top.forEach { Text("${it.expected} → ${it.answered}: ${it.count}×", style = MaterialTheme.typography.bodyMedium) }
            Spacer(Modifier.height(8.dp))
            val chars = (relevant.map { it.expected.first() } + relevant.map { it.answered.first() }).distinct()
                .sortedBy { learned.indexOf(it) }
            val max = relevant.maxOf { it.count }.toFloat()
            val counts = relevant.associate { (it.expected.first() to it.answered.first()) to it.count }
            val base = MaterialTheme.colorScheme.error
            val cell = 26.dp
            Column(Modifier.horizontalScroll(rememberScrollState())) {
                Row {
                    Spacer(Modifier.width(cell))
                    chars.forEach { Text(it.toString(), Modifier.width(cell), fontFamily = FontFamily.Monospace, fontSize = 12.sp) }
                }
                chars.forEach { r ->
                    Row {
                        Text(r.toString(), Modifier.width(cell), fontFamily = FontFamily.Monospace, fontSize = 12.sp)
                        chars.forEach { col ->
                            val n = counts[r to col] ?: 0
                            Box(
                                Modifier.size(cell).padding(1.dp)
                                    .background(if (n == 0) MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f) else base.copy(alpha = 0.2f + 0.8f * n / max)),
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun CharacterDetailScreen(vm: StatsViewModel, ch: Char, onBack: () -> Unit) {
    val s by vm.state.collectAsStateWithLifecycle()
    var history by remember { mutableStateOf<List<CharSessionPoint>>(emptyList()) }
    LaunchedEffect(ch) { history = vm.history(ch) }
    val st = s.stats[ch]
    val scale = SpeedScale.from(s.stats.values)
    Column(
        Modifier.fillMaxSize().safeDrawingPadding().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        val repeating by vm.repeating.collectAsStateWithLifecycle()
        DisposableEffect(ch) { onDispose { vm.stop() } }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(ch.toString(), fontSize = 48.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            TextButton(onClick = onBack) { Text("Back") }
        }
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                MorsePattern(ch.toString(), unit = 14.dp, color = MaterialTheme.colorScheme.primary)
                Text(
                    spoken(ch),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Button(onClick = { vm.play(ch, repeat = false) }) { Text("▶  Play") }
                    if (repeating) {
                        OutlinedButton(onClick = vm::stop) { Text("■  Stop") }
                    } else {
                        OutlinedButton(onClick = { vm.play(ch, repeat = true) }) { Text("↻  Repeat") }
                    }
                }
            }
        }
        if (st == null) {
            Text("Not practiced yet.", style = MaterialTheme.typography.bodyMedium)
            return@Column
        }
        val m = LearnerModel.mastery(st, scale)
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Line("Mastery", "${MasteryTier.of(m).label} (${(m * 100).toInt()}%)")
                Line("Recent accuracy", "${(st.acc * 100).toInt()}%")
                Line("Response time", st.rtMs?.let { "${it.toInt()} ms" } ?: "–")
                Line("Best response time", st.bestRtMs?.let { "${it.toInt()} ms" } ?: "–")
                Line("Times heard", "${st.attempts} (${st.correct} correct)")
                Line(
                    "Next review",
                    if (st.srsStabilityDays <= 0) "–" else st.srsDueMs.toLocalDate().format(DateTimeFormatter.ofPattern("MMM d")),
                )
            }
        }
        val mixups = s.confusions.filter { it.expected.first() == ch }.sortedByDescending { it.count }.take(5)
        if (mixups.isNotEmpty()) {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    Text("Most often mistaken for", style = MaterialTheme.typography.titleMedium)
                    mixups.forEach { Text("${it.answered}: ${it.count}×") }
                }
            }
        }
        if (history.size >= 2) {
            val accColor = MaterialTheme.colorScheme.tertiary
            val rtColor = MaterialTheme.colorScheme.primary
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    Text("Per session", style = MaterialTheme.typography.titleMedium)
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Legend(accColor, "Accuracy"); Legend(rtColor, "Response time")
                    }
                    val maxRt = (history.mapNotNull { it.avgRtMs }.maxOrNull() ?: 1000.0).toFloat()
                    Canvas(Modifier.fillMaxWidth().height(140.dp)) {
                        fun x(i: Int) = size.width * i / (history.size - 1)
                        val a = Path(); val r = Path()
                        var rStarted = false
                        history.forEachIndexed { i, p ->
                            val ya = size.height * (1 - p.correct.toFloat() / p.attempts)
                            if (i == 0) a.moveTo(x(i), ya) else a.lineTo(x(i), ya)
                            p.avgRtMs?.let { rt ->
                                val yr = size.height * (1 - rt.toFloat() / maxRt)
                                if (!rStarted) { r.moveTo(x(i), yr); rStarted = true } else r.lineTo(x(i), yr)
                            }
                        }
                        drawPath(a, accColor, style = Stroke(2.dp.toPx()))
                        drawPath(r, rtColor, style = Stroke(2.dp.toPx()))
                    }
                }
            }
        }
    }
}

/** How operators say a character: "di" inside, "dit" at the end, "dah" for dashes. */
private fun spoken(ch: Char): String {
    val p = MorseCode.patternOf(ch) ?: return ""
    return p.mapIndexed { i, el ->
        when {
            el == '-' -> "dah"
            i == p.lastIndex -> "dit"
            else -> "di"
        }
    }.joinToString(" ")
}
