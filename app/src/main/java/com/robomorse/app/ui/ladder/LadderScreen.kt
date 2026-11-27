package com.robomorse.app.ui.ladder

import android.view.KeyEvent as AndroidKeyEvent
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.focusable
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.robomorse.app.ui.BACKSPACE
import com.robomorse.app.ui.CorrectGreen
import com.robomorse.app.ui.MorseKeyboard
import com.robomorse.app.ui.WrongRed
import com.robomorse.app.ui.copy.DrillTopBar
import com.robomorse.app.ui.copy.PausedCard
import com.robomorse.app.ui.practice.Slot
import com.robomorse.app.ui.practice.formatMs
import com.robomorse.core.LADDER_STEP

private val CALLSIGN_KEYS = (('A'..'Z') + ('0'..'9')).toSet()

@Composable
fun LadderScreen(vm: LadderViewModel, onBack: () -> Unit) {
    val s by vm.state.collectAsStateWithLifecycle()
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    BackHandler { vm.finish(onBack) }
    LifecycleEventEffect(Lifecycle.Event.ON_PAUSE) { vm.pause("Paused while the app was in the background.") }

    Column(
        Modifier
            .fillMaxSize()
            .safeDrawingPadding()
            .focusRequester(focus)
            .focusable()
            .onPreviewKeyEvent { e ->
                if (e.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                val native = e.nativeKeyEvent
                when (native.keyCode) {
                    AndroidKeyEvent.KEYCODE_DEL -> vm.onKey(BACKSPACE)
                    AndroidKeyEvent.KEYCODE_ENTER -> vm.submitNow()
                    AndroidKeyEvent.KEYCODE_ESCAPE -> if (s.paused) vm.resume() else vm.pause()
                    else -> {
                        val ch = native.unicodeChar.toChar()
                        if (ch.code == 0) return@onPreviewKeyEvent false
                        vm.onKey(ch)
                    }
                }
                true
            },
    ) {
        DrillTopBar(
            title = "Callsign ladder",
            subtitle = if (s.phase == LadderPhase.READY || s.phase == LadderPhase.LOADING) (if (s.best > 0) "Best: ${s.best} points" else "No runs yet")
            else "${s.wpm} WPM · ${s.done} of ${s.calls} · ${s.score} points · ${formatMs(s.elapsedMs)}",
            canPause = s.canPause,
            onPause = { vm.pause() },
            onEnd = { vm.finish(onBack) },
        )
        if (s.phase == LadderPhase.LISTEN || s.phase == LadderPhase.RESULT) {
            LinearProgressIndicator(
                progress = { s.done / s.calls.toFloat() },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            )
        }

        Box(Modifier.fillMaxWidth().weight(1f).padding(16.dp), contentAlignment = Alignment.Center) {
            when {
                s.phase == LadderPhase.DONE -> DoneCard(s, onAgain = vm::again, onBack = onBack)
                s.phase == LadderPhase.LOADING || s.phase == LadderPhase.SAVING -> CircularProgressIndicator()
                s.paused -> PausedCard(s.pauseReason, onResume = vm::resume, onEnd = { vm.finish(onBack) })
                s.phase == LadderPhase.READY -> ReadyCard(s, onChange = vm::changeStart, onStart = vm::start)
                s.phase == LadderPhase.RESULT -> ResultArea(s)
                else -> AnswerArea(s, onReplay = vm::replay, onSkip = vm::submitNow)
            }
        }

        if (s.phase == LadderPhase.LISTEN || s.phase == LadderPhase.RESULT) {
            MorseKeyboard(
                learned = CALLSIGN_KEYS,
                hardMode = false,
                haptics = s.haptics,
                showBackspace = true,
                onKey = { ch, _ -> vm.onKey(ch) },
                modifier = Modifier.padding(bottom = 8.dp).alpha(if (s.phase == LadderPhase.LISTEN && !s.paused) 1f else 0.4f),
            )
        }
    }
}

@Composable
private fun ReadyCard(s: LadderUiState, onChange: (Int) -> Unit, onStart: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            "${s.calls} callsigns, sent at full speed with no extra spacing. Each one you copy makes the " +
                "next $LADDER_STEP WPM faster; each miss makes it slower. Find the speed where you top out.",
            style = MaterialTheme.typography.bodyLarge,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            "Points are the callsign's length times its speed. Replaying one halves its points. " +
                "Best once you know most letters and numbers.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(20.dp))
        Text("Starting speed", style = MaterialTheme.typography.labelLarge)
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedButton(onClick = { onChange(-1) }) { Text("−") }
            Text(
                "${s.startWpm} WPM",
                style = MaterialTheme.typography.headlineSmall,
                modifier = Modifier.padding(horizontal = 16.dp),
            )
            OutlinedButton(onClick = { onChange(1) }) { Text("+") }
        }
        if (s.best > 0) {
            Spacer(Modifier.height(12.dp))
            Text(
                "Best run: ${s.best} points, top speed ${s.bestPeak} WPM",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.height(20.dp))
        Button(onClick = onStart) { Text("Start") }
    }
}

private fun slotWidth(length: Int) = if (length <= 6) 44.dp else 38.dp

@Composable
private fun AnswerArea(s: LadderUiState, onReplay: () -> Unit, onSkip: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text("${s.wpm} WPM", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
        Text(if (s.playing) "♪ Listening…" else "Your copy", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            repeat(s.length) { i ->
                Slot(
                    s.typed.getOrNull(i)?.toString() ?: "",
                    borderColor = if (i == s.typed.length) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,
                    textColor = MaterialTheme.colorScheme.onSurface,
                    width = slotWidth(s.length),
                )
            }
        }
        Spacer(Modifier.height(16.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedButton(onClick = onReplay, enabled = !s.playing) { Text(if (s.replayed) "Replay" else "Replay (½ points)") }
            OutlinedButton(onClick = onSkip) { Text("Skip") }
        }
    }
}

@Composable
private fun ResultArea(s: LadderUiState) {
    val r = s.result ?: return
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            when {
                s.reviewing -> "Listen: ${r.target} at ${r.wpm} WPM"
                r.correct -> "+${r.points} points"
                else -> "Missed"
            },
            style = MaterialTheme.typography.titleMedium,
            color = if (r.correct) CorrectGreen else MaterialTheme.colorScheme.onSurface,
        )
        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            r.target.forEachIndexed { i, ch ->
                val ok = r.typed.getOrNull(i) == ch
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Slot(
                        ch.toString(),
                        borderColor = if (ok) CorrectGreen else WrongRed,
                        textColor = if (ok) CorrectGreen else WrongRed,
                        width = slotWidth(r.target.length),
                    )
                    if (!ok) Text("you: ${r.typed.getOrNull(i) ?: "–"}", style = MaterialTheme.typography.labelMedium, color = WrongRed)
                }
            }
        }
        Spacer(Modifier.height(12.dp))
        Text(
            if (s.done >= s.calls) "Last one!" else "Next: ${s.wpm} WPM",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun DoneCard(s: LadderUiState, onAgain: () -> Unit, onBack: () -> Unit) {
    Column(
        Modifier.verticalScroll(rememberScrollState()),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            when {
                s.newBest -> "New best!"
                s.complete -> "Run complete"
                else -> "Run ended"
            },
            style = MaterialTheme.typography.headlineMedium,
            color = if (s.newBest) CorrectGreen else MaterialTheme.colorScheme.onSurface,
        )
        Spacer(Modifier.height(8.dp))
        Text("${s.score} points", style = MaterialTheme.typography.displaySmall, fontWeight = FontWeight.SemiBold)
        Text(
            "${s.correct} of ${s.done} copied · top speed ${if (s.peakWpm > 0) "${s.peakWpm} WPM" else "–"} · ${formatMs(s.elapsedMs)}",
            style = MaterialTheme.typography.bodyLarge,
            textAlign = TextAlign.Center,
        )
        if (s.complete) {
            Text(
                "Best: ${s.best} points, top speed ${s.bestPeak} WPM",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            Text(
                "Only complete runs count toward your best.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        val misses = s.results.filter { !it.correct }
        if (misses.isNotEmpty()) {
            Spacer(Modifier.height(12.dp))
            Text("Missed", style = MaterialTheme.typography.titleSmall)
            misses.forEach {
                Text(
                    "${it.target} at ${it.wpm} WPM" + if (it.typed.isNotEmpty()) " (you: ${it.typed})" else "",
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }
        Spacer(Modifier.height(20.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(onClick = onAgain) { Text("Run again") }
            OutlinedButton(onClick = onBack) { Text("Done") }
        }
        Spacer(Modifier.width(1.dp))
    }
}
