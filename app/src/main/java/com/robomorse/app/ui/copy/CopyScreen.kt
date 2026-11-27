package com.robomorse.app.ui.copy

import android.view.KeyEvent as AndroidKeyEvent
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.focusable
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
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
import com.robomorse.app.ui.MorsePattern
import com.robomorse.app.ui.WrongRed
import com.robomorse.app.ui.practice.Slot
import com.robomorse.app.ui.practice.formatMs
import com.robomorse.core.CopyContent

@Composable
fun CopyScreen(vm: CopyViewModel, onBack: () -> Unit) {
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
            title = "Word & callsign copy",
            subtitle = "${s.charWpm}/${s.effWpm} WPM · ${formatMs(s.elapsedMs)}" +
                if (s.items > 0) " · ${s.correct} of ${s.items} copied" else "",
            canPause = s.canPause,
            onPause = { vm.pause() },
            onEnd = { vm.finish(onBack) },
        )
        Options(s, vm)

        Box(Modifier.fillMaxWidth().weight(1f).padding(16.dp), contentAlignment = Alignment.Center) {
            when {
                s.phase == CopyPhase.DONE -> DoneCard(s, onBack)
                s.phase == CopyPhase.LOADING || s.phase == CopyPhase.SAVING -> CircularProgressIndicator()
                s.paused -> PausedCard(s.pauseReason, onResume = vm::resume, onEnd = { vm.finish(onBack) })
                s.phase == CopyPhase.READY -> ReadyCard(s, onStart = vm::start)
                s.phase == CopyPhase.RESULT -> ResultArea(s)
                else -> AnswerArea(s, onReplay = vm::replay, onSkip = vm::submitNow)
            }
        }

        if (s.phase != CopyPhase.DONE) {
            val accepting = s.phase == CopyPhase.LISTEN && !s.paused
            MorseKeyboard(
                // Copy material is only letters and digits, so punctuation keys would just take space.
                learned = s.chars.filter { it.isLetterOrDigit() }.toSet(),
                hardMode = false,
                haptics = s.haptics,
                showBackspace = true,
                onKey = { ch, _ -> vm.onKey(ch) },
                modifier = Modifier.padding(bottom = 8.dp).alpha(if (accepting) 1f else 0.4f),
            )
        }
    }
}

/** Title, a status line, and Pause and End buttons: shared by the drill screens. */
@Composable
internal fun DrillTopBar(title: String, subtitle: String, canPause: Boolean, onPause: () -> Unit, onEnd: () -> Unit) {
    Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleMedium)
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            OutlinedButton(onClick = onPause, enabled = canPause) { Text("Pause") }
            Spacer(Modifier.width(8.dp))
            OutlinedButton(onClick = onEnd) { Text("End") }
        }
    }
}

@Composable
private fun Options(s: CopyUiState, vm: CopyViewModel) {
    Column(Modifier.padding(horizontal = 16.dp)) {
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            s.levels.forEach { (level, available) ->
                FilterChip(
                    selected = s.level == level,
                    onClick = { vm.setLevel(level) },
                    enabled = available,
                    label = { Text(level.label) },
                )
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                if (s.allCharsForced) "Using all characters: too few learned to make words yet"
                else "Use all characters, not just learned ones",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            if (!s.allCharsForced) Switch(checked = s.allChars, onCheckedChange = vm::setAllChars)
        }
        s.notice?.let {
            Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary)
        }
    }
}

@Composable
private fun ReadyCard(s: CopyUiState, onStart: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            "Listen to a whole word or callsign, then type it. Try to hear each word as one sound, " +
                "the way you'll copy on the air. You can start typing while it plays.",
            style = MaterialTheme.typography.bodyLarge,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            "Anything you miss is played again and comes back a few words later. " +
                "The level moves up after 8 of your last 10 right.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(24.dp))
        Button(onClick = onStart) { Text("Start") }
    }
}

private fun slotWidth(length: Int) = if (length <= 6) 48.dp else 40.dp

@Composable
private fun AnswerArea(s: CopyUiState, onReplay: () -> Unit, onSkip: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
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
            OutlinedButton(onClick = onReplay, enabled = !s.playing) { Text("Replay") }
            OutlinedButton(onClick = onSkip) { Text("Skip") }
        }
    }
}

@Composable
private fun ResultArea(s: CopyUiState) {
    val r = s.result ?: return
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            when {
                s.reviewing -> "Listen: the correct sound"
                r.correct -> "Copied"
                else -> "Not quite"
            },
            style = MaterialTheme.typography.titleMedium,
            color = if (r.correct) CorrectGreen else MaterialTheme.colorScheme.onSurface,
        )
        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            r.target.forEachIndexed { i, ch ->
                val ok = r.perChar[i]
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Slot(
                        ch.toString(),
                        borderColor = if (ok) CorrectGreen else WrongRed,
                        textColor = if (ok) CorrectGreen else WrongRed,
                        width = slotWidth(r.target.length),
                    )
                    if (!ok) {
                        Text(
                            "you: ${r.typed.getOrNull(i) ?: "–"}",
                            style = MaterialTheme.typography.labelMedium,
                            color = WrongRed,
                        )
                        if (s.showPatterns) {
                            MorsePattern(
                                ch.toString(),
                                unit = 4.dp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                                modifier = Modifier.padding(top = 4.dp),
                            )
                        }
                    }
                }
            }
        }
        CopyContent.meanings[r.target]?.let {
            Spacer(Modifier.height(16.dp))
            Text(
                "${r.target}: $it",
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium,
                textAlign = TextAlign.Center,
            )
        }
    }
}

@Composable
internal fun PausedCard(reason: String?, onResume: () -> Unit, onEnd: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text("Paused", style = MaterialTheme.typography.headlineMedium)
        reason?.let {
            Spacer(Modifier.height(8.dp))
            Text(it, style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center)
        }
        Spacer(Modifier.height(20.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(onClick = onResume) { Text("Resume") }
            OutlinedButton(onClick = onEnd) { Text("End session") }
        }
    }
}

@Composable
private fun DoneCard(s: CopyUiState, onBack: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text("Session saved", style = MaterialTheme.typography.headlineMedium)
        Spacer(Modifier.height(8.dp))
        Text(
            "${s.correct} of ${s.items} copied (${if (s.items == 0) 0 else s.correct * 100 / s.items}%) in ${formatMs(s.elapsedMs)}",
            style = MaterialTheme.typography.bodyLarge,
        )
        Text("Level: ${s.level.label}", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(20.dp))
        Button(onClick = onBack) { Text("Done") }
    }
}
