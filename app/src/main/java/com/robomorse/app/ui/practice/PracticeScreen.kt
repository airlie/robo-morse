package com.robomorse.app.ui.practice

import android.view.KeyEvent as AndroidKeyEvent
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.border
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.robomorse.app.ui.BACKSPACE
import com.robomorse.app.ui.CorrectGreen
import com.robomorse.app.ui.MorseKeyboard
import com.robomorse.app.ui.WrongRed
import com.robomorse.core.PracticeMode

@Composable
fun PracticeScreen(vm: PracticeViewModel, onFinished: (Boolean) -> Unit) {
    val s by vm.state.collectAsStateWithLifecycle()
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    BackHandler { vm.finish(onFinished) }
    // Interrupted (phone call, switched app, screen off): pause rather than run on.
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
                    AndroidKeyEvent.KEYCODE_DEL -> vm.onKey(BACKSPACE, native.eventTime, hardware = true)
                    AndroidKeyEvent.KEYCODE_ENTER, AndroidKeyEvent.KEYCODE_SPACE -> vm.submitNow()
                    AndroidKeyEvent.KEYCODE_ESCAPE ->
                        if (s.paused) vm.resume() else vm.pause()
                    else -> {
                        val ch = native.unicodeChar.toChar()
                        if (ch.code == 0) return@onPreviewKeyEvent false
                        vm.onKey(ch, native.eventTime, hardware = true)
                    }
                }
                true
            },
    ) {
        TopBar(s, onPause = { vm.pause() }, onEnd = { vm.finish(onFinished) })
        s.banner?.let { Banner(it, onDismiss = vm::dismissBanner, onStop = { vm.finish(onFinished) }) }

        Box(Modifier.fillMaxWidth().weight(1f).padding(16.dp), contentAlignment = Alignment.Center) {
            when {
                s.paused -> PausedCard(s.pauseReason, onResume = vm::resume, onEnd = { vm.finish(onFinished) })
                s.phase == Phase.LOADING || s.phase == Phase.SAVING || s.phase == Phase.FINISHED -> CircularProgressIndicator()
                s.phase == Phase.READY -> ReadyCard(s, onStart = vm::start)
                s.phase == Phase.INTRO -> IntroCard(s.introChar)
                else -> AnswerArea(s, onReplay = vm::replay, onSkip = vm::submitNow)
            }
        }

        MorseKeyboard(
            learned = s.learned,
            hardMode = s.hardMode,
            haptics = s.haptics,
            showBackspace = s.mode == PracticeMode.KOCH,
            onKey = { ch, t -> vm.onKey(ch, t) },
            // Dimmed whenever key presses are not being used as answers.
            modifier = Modifier.padding(bottom = 8.dp).alpha(if (s.acceptingInput) 1f else 0.4f),
        )
    }

    if (s.phase == Phase.UNLOCK_OFFER && s.unlockChar != null && !s.paused) {
        AlertDialog(
            onDismissRequest = {},
            title = { Text("Ready for a new character") },
            text = {
                Text(
                    "You've been accurate and quick on everything so far. Next up is " +
                        "“${s.unlockChar}”. You'll hear it three times with its letter shown, then it joins your drills.",
                )
            },
            confirmButton = { Button(onClick = vm::acceptUnlock) { Text("Add ${s.unlockChar}") } },
            dismissButton = { TextButton(onClick = vm::declineUnlock) { Text("Not this session") } },
        )
    }
}

@Composable
private fun TopBar(s: PracticeUiState, onPause: () -> Unit, onEnd: () -> Unit) {
    Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(
                    if (s.mode == PracticeMode.KOCH) "Koch lesson" else "ICR sprint",
                    style = MaterialTheme.typography.titleMedium,
                )
                Text(
                    "${s.charWpm}/${s.effWpm} WPM · ${s.learned.size} characters",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            OutlinedButton(onClick = onPause, enabled = s.canPause) { Text("Pause") }
            Spacer(Modifier.width(8.dp))
            OutlinedButton(onClick = onEnd) { Text("End") }
        }
        Spacer(Modifier.height(8.dp))
        val goalProgress = if (s.goalRemainingMs <= 0) 1f else (s.elapsedMs.toFloat() / s.goalRemainingMs).coerceIn(0f, 1f)
        LinearProgressIndicator(progress = { goalProgress }, modifier = Modifier.fillMaxWidth())
        Spacer(Modifier.height(4.dp))
        Row {
            Text(formatMs(s.elapsedMs), style = MaterialTheme.typography.labelMedium, modifier = Modifier.weight(1f))
            if (s.attempts > 0) {
                Text(
                    "${s.attempts} answered · recent accuracy ${(s.rollingAccuracy * 100).toInt()}%",
                    style = MaterialTheme.typography.labelMedium,
                )
            }
        }
    }
}

@Composable
private fun Banner(text: String, onDismiss: () -> Unit, onStop: () -> Unit) {
    Card(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
        Column(Modifier.padding(12.dp)) {
            Text(text, style = MaterialTheme.typography.bodyMedium)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = onDismiss) { Text("Keep going") }
                TextButton(onClick = onStop) { Text("Finish") }
            }
        }
    }
}

@Composable
private fun PausedCard(reason: String?, onResume: () -> Unit, onEnd: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text("Paused", style = MaterialTheme.typography.headlineMedium)
        reason?.let {
            Spacer(Modifier.height(8.dp))
            Text(it, style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center)
        }
        Spacer(Modifier.height(8.dp))
        Text(
            "Anything you hadn't answered yet isn't scored. Paused time doesn't count toward your practice time.",
            style = MaterialTheme.typography.bodySmall,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(20.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(onClick = onResume) { Text("Resume") }
            OutlinedButton(onClick = onEnd) { Text("End session") }
        }
    }
}

@Composable
private fun ReadyCard(s: PracticeUiState, onStart: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            if (s.mode == PracticeMode.KOCH) {
                "Listen to each group and type the characters as you hear them. Don't count dits and dahs; " +
                    "let each sound map straight to its letter. If you don't know one, skip it and move on."
            } else {
                "One character at a time. Answer as fast as you can. Speed of recognition is what this drill trains."
            },
            style = MaterialTheme.typography.bodyLarge,
        )
        Spacer(Modifier.height(24.dp))
        Button(onClick = onStart) { Text("Start") }
    }
}

@Composable
private fun IntroCard(ch: Char?) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text("New character", style = MaterialTheme.typography.titleMedium)
        Text(ch?.toString() ?: "", fontSize = 96.sp, fontWeight = FontWeight.Bold)
        Text("Listen to its sound", style = MaterialTheme.typography.bodyMedium)
    }
}

/**
 * One answer slot: an empty rounded box until filled. Boxes rather than dashes or
 * underscores, which look like dahs.
 */
@Composable
private fun Slot(text: String, borderColor: Color, textColor: Color, emphasized: Boolean = false) {
    Box(
        Modifier
            .size(width = 48.dp, height = 60.dp)
            .border(if (emphasized) 3.dp else 2.dp, borderColor, RoundedCornerShape(10.dp)),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, fontSize = 34.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, color = textColor)
    }
}

@Composable
private fun AnswerArea(s: PracticeUiState, onReplay: () -> Unit, onSkip: () -> Unit) {
    val outline = MaterialTheme.colorScheme.outlineVariant
    val onSurface = MaterialTheme.colorScheme.onSurface
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        if (s.phase == Phase.FEEDBACK || s.phase == Phase.UNLOCK_OFFER) {
            Text(
                when {
                    s.reviewIndex != null -> "Listen: the correct sound"
                    s.feedback.all { it.correct } -> "Correct"
                    else -> "Not quite"
                },
                style = MaterialTheme.typography.titleMedium,
                color = if (s.feedback.all { it.correct }) CorrectGreen else onSurface,
            )
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                s.feedback.forEachIndexed { i, r ->
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        val color = if (r.correct) CorrectGreen else WrongRed
                        Slot(
                            r.expected.toString(),
                            borderColor = if (s.reviewIndex == i) MaterialTheme.colorScheme.primary else color,
                            textColor = color,
                            emphasized = s.reviewIndex == i,
                        )
                        Text(
                            if (r.correct) r.rtMs?.let { "${it.toInt()} ms" } ?: "✓" else "you: ${r.answered ?: "–"}",
                            style = MaterialTheme.typography.labelMedium,
                            color = if (r.correct) MaterialTheme.colorScheme.onSurfaceVariant else WrongRed,
                        )
                    }
                }
            }
            Spacer(Modifier.height(12.dp))
            Text("Keyboard is off until the next group plays", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else {
            Text(
                if (s.playing) "♪ Listening…" else "Your answer",
                style = MaterialTheme.typography.titleMedium,
            )
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                repeat(s.promptLength) { i ->
                    val next = i == s.typed.length
                    Slot(
                        s.typed.getOrNull(i)?.toString() ?: "",
                        borderColor = if (next) MaterialTheme.colorScheme.primary else outline,
                        textColor = onSurface,
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
}

fun formatMs(ms: Long): String {
    val total = ms / 1000
    return "%d:%02d".format(total / 60, total % 60)
}
