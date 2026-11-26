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
import com.robomorse.app.ui.MorsePattern
import com.robomorse.app.ui.MorseKeyboard
import com.robomorse.app.ui.WrongRed
import com.robomorse.core.PracticeMode

@Composable
fun PracticeScreen(vm: PracticeViewModel, onSwitch: (PracticeMode) -> Unit, onFinished: (Boolean) -> Unit) {
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
        s.banner?.let {
            Banner(
                it,
                s.bannerAction,
                onDismiss = vm::dismissBanner,
                onStop = { vm.finish(onFinished) },
                onRefresh = { vm.switchTo(PracticeMode.REFRESH, onSwitch) },
            )
        }

        Box(Modifier.fillMaxWidth().weight(1f).padding(16.dp), contentAlignment = Alignment.Center) {
            when {
                s.paused -> PausedCard(s, onResume = vm::resume, onEnd = { vm.finish(onFinished) })
                s.phase == Phase.LOADING || s.phase == Phase.SAVING || s.phase == Phase.FINISHED -> CircularProgressIndicator()
                s.phase == Phase.READY -> ReadyCard(s, onStart = vm::start)
                s.phase == Phase.INTRO -> IntroCard(s.introChar, returning = s.mode == PracticeMode.REFRESH)
                s.phase == Phase.REFRESH_DONE -> RefreshDoneCard(
                    s,
                    onLesson = { vm.switchTo(PracticeMode.KOCH, onSwitch) },
                    onFinish = { vm.finish(onFinished) },
                )
                else -> AnswerArea(s, onReplay = vm::replay, onSkip = vm::submitNow)
            }
        }

        MorseKeyboard(
            learned = s.learned,
            hardMode = s.hardMode,
            haptics = s.haptics,
            showBackspace = s.mode != PracticeMode.ICR,
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
                    when (s.mode) {
                        PracticeMode.KOCH -> "Koch lesson"
                        PracticeMode.ICR -> "ICR sprint"
                        PracticeMode.REFRESH -> "Refresher"
                    },
                    style = MaterialTheme.typography.titleMedium,
                )
                Text(
                    if (s.mode == PracticeMode.REFRESH) {
                        "${s.activeCount} of ${s.totalLearned} characters back · ${s.charWpm}/${s.effWpm} WPM"
                    } else {
                        "${s.charWpm}/${s.effWpm} WPM · ${s.learned.size} characters"
                    },
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
private fun Banner(text: String, action: BannerAction, onDismiss: () -> Unit, onStop: () -> Unit, onRefresh: () -> Unit) {
    Card(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
        Column(Modifier.padding(12.dp)) {
            Text(text, style = MaterialTheme.typography.bodyMedium)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = onDismiss) { Text("Keep going") }
                when (action) {
                    BannerAction.FINISH -> TextButton(onClick = onStop) { Text("Finish") }
                    BannerAction.REFRESH -> TextButton(onClick = onRefresh) { Text("Start a refresher") }
                }
            }
        }
    }
}

@Composable
private fun PausedCard(s: PracticeUiState, onResume: () -> Unit, onEnd: () -> Unit) {
    if (s.breather) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text("Take a breath", style = MaterialTheme.typography.headlineMedium)
            Spacer(Modifier.height(8.dp))
            Text(
                "${s.groupsDone} groups done · recent accuracy ${(s.rollingAccuracy * 100).toInt()}%",
                style = MaterialTheme.typography.bodyLarge,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                "Relax your shoulders and hands. Carry on whenever you're ready; the clock is stopped.",
                style = MaterialTheme.typography.bodySmall,
                textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(20.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(onClick = onResume) { Text("Continue") }
                OutlinedButton(onClick = onEnd) { Text("End session") }
            }
        }
        return
    }
    val reason = s.pauseReason
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
            when (s.mode) {
                PracticeMode.KOCH ->
                    "Listen to each group and type the characters as you hear them. Don't count dits and dahs; " +
                        "let each sound map straight to its letter. If you don't know one, skip it and move on."
                PracticeMode.ICR ->
                    "One character at a time. Answer as fast as you can. Speed of recognition is what this drill trains."
                PracticeMode.REFRESH ->
                    "Back after a break? Let's warm up. You'll start with ${s.learned.joinToString(" and ")}, " +
                        "hearing each one with its letter shown. " +
                        (if (s.totalLearned > s.activeCount) {
                            "More come back, two at a time, as they click again, until all ${s.totalLearned} are back."
                        } else {
                            "Then short groups rebuild them until they click again."
                        }) +
                        "\n\nGroups are short and a little slower. Anything you miss is replayed and its pattern shown."
            },
            style = MaterialTheme.typography.bodyLarge,
        )
        Spacer(Modifier.height(24.dp))
        Button(onClick = onStart) { Text("Start") }
    }
}

@Composable
private fun IntroCard(ch: Char?, returning: Boolean) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(if (returning) "Coming back" else "New character", style = MaterialTheme.typography.titleMedium)
        Text(ch?.toString() ?: "", fontSize = 96.sp, fontWeight = FontWeight.Bold)
        if (returning && ch != null) {
            // While relearning, the pattern helps reconnect sound and letter.
            MorsePattern(
                ch.toString(),
                unit = 8.dp,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                modifier = Modifier.padding(bottom = 12.dp),
            )
        }
        Text("Listen to its sound", style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun RefreshDoneCard(s: PracticeUiState, onLesson: () -> Unit, onFinish: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text("You're back", style = MaterialTheme.typography.headlineMedium, color = CorrectGreen)
        Spacer(Modifier.height(8.dp))
        Text(
            "All ${s.totalLearned} characters are back in play, at ${(s.rollingAccuracy * 100).toInt()}% recent accuracy.",
            style = MaterialTheme.typography.bodyLarge,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            "Your lesson picks up where you left off, and it gives extra practice to anything that came back shaky.",
            style = MaterialTheme.typography.bodySmall,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(20.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(onClick = onLesson) { Text("Continue with a lesson") }
            OutlinedButton(onClick = onFinish) { Text("Finish") }
        }
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
                        if (!r.correct && s.showPatterns) {
                            // A quiet hint for when the sound itself isn't clicking yet.
                            MorsePattern(
                                r.expected.toString(),
                                unit = 4.dp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                                modifier = Modifier.padding(top = 4.dp),
                            )
                        }
                    }
                }
            }
            Spacer(Modifier.height(12.dp))
            Text("Next group in a moment", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
