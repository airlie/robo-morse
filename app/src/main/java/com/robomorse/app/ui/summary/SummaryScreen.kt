package com.robomorse.app.ui.summary

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.robomorse.app.PracticeResult
import com.robomorse.app.content.Facts
import com.robomorse.app.data.Settings
import com.robomorse.app.ui.home.nearestGoal
import com.robomorse.app.ui.practice.formatMs
import com.robomorse.core.LearnerModel
import com.robomorse.core.SpeedScale
import com.robomorse.core.chanceCorrected

@Composable
fun SummaryScreen(result: PracticeResult?, settings: Settings, onDone: () -> Unit, onAgain: () -> Unit) {
    Column(
        Modifier.fillMaxSize().safeDrawingPadding().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Text("Done for today?", style = MaterialTheme.typography.headlineMedium)
        if (result == null) {
            Text("No session to show.")
            Button(onClick = onDone) { Text("Home") }
            return@Column
        }
        val sm = result.summary
        val acc = sm.accuracy
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                StatRow("Accuracy", "${(acc * 100).toInt()}%")
                StatRow("Better than guessing", "${(chanceCorrected(acc, result.learnedCount) * 100).toInt()}%")
                StatRow("Characters answered", sm.attempts.toString())
                StatRow("Time", formatMs(result.durationMs))
                val rts = sm.perChar.keys.mapNotNull { sm.finalStats[it]?.rtMs }
                if (rts.isNotEmpty()) StatRow("Typical response time", "${rts.average().toInt()} ms")
            }
        }

        val highlights = buildList {
            sm.unlocked.forEach { add("New character learned: $it") }
            sm.tierChanges.forEach { (c, ch) -> add("$c: ${ch.first.label} → ${ch.second.label}") }
            result.save.newEffWpm?.let { add("Effective speed ${if (it > result.save.oldEffWpm) "up" else "down"} to $it WPM") }
        }
        if (highlights.isNotEmpty()) {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("Progress", style = MaterialTheme.typography.titleMedium)
                    highlights.forEach { Text("• $it") }
                }
            }
        }

        val worst = sm.perChar.filter { it.value.attempts >= 3 }.minByOrNull { it.value.accuracy }
        val topConfusion = sm.confusions.maxByOrNull { it.value }
        if ((worst != null && worst.value.accuracy < 0.9) || topConfusion != null) {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("Working on", style = MaterialTheme.typography.titleMedium)
                    if (worst != null && worst.value.accuracy < 0.9) {
                        Text("${worst.key}: ${(worst.value.accuracy * 100).toInt()}% this session. It will come up more often next time.")
                    }
                    topConfusion?.let { (pair, n) ->
                        Text("Heard ${pair.first}, typed ${pair.second} ($n×). These two will be drilled side by side.")
                    }
                }
            }
        }

        val scale = SpeedScale.from(sm.finalStats.values)
        val mastery = sm.finalStats.filterKeys { it in settings.kochOrder.take(result.learnedCount) }
            .mapValues { LearnerModel.mastery(it.value, scale) }
        Text("Next goal: ${nearestGoal(mastery, settings.copy(learnedCount = result.learnedCount))}", fontWeight = FontWeight.SemiBold)

        if (result.fatigue) {
            Text("Accuracy dipped late in the session. Tomorrow's session will do more for you than extra minutes today.")
        } else {
            Text("Short daily sessions beat long ones. Coming back tomorrow does more than keeping going now.")
        }

        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp)) {
                Text("Did you know?", style = MaterialTheme.typography.titleSmall)
                Text(Facts.forSession(result.save.sessionId), style = MaterialTheme.typography.bodyMedium)
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(onClick = onDone) { Text("Done") }
            OutlinedButton(onClick = onAgain) { Text("Keep practicing") }
        }
    }
}

@Composable
private fun StatRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth()) {
        Text(label, Modifier.padding(end = 12.dp))
        Text(value, Modifier.weight(1f), fontWeight = FontWeight.SemiBold, textAlign = TextAlign.End)
    }
}
