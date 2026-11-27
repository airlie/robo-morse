package com.robomorse.app.ui.onair

import android.view.KeyEvent as AndroidKeyEvent
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
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
import com.robomorse.app.ui.practice.formatMs

private val LOG_KEYS = (('A'..'Z') + ('0'..'9')).toSet()

@Composable
fun OnAirScreen(vm: OnAirViewModel, onBack: () -> Unit) {
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
                    AndroidKeyEvent.KEYCODE_TAB -> vm.nextField()
                    AndroidKeyEvent.KEYCODE_ENTER -> if (s.phase == OnAirPhase.OVER) vm.check() else vm.continueContact()
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
            title = "On-air practice",
            subtitle = "${s.charWpm}/${s.effWpm} WPM · ${formatMs(s.elapsedMs)}" +
                if (s.fields > 0) " · ${s.correct}/${s.fields} right" else "",
            canPause = s.canPause,
            onPause = { vm.pause() },
            onEnd = { vm.finish(onBack) },
        )

        Box(Modifier.fillMaxWidth().weight(1f).padding(horizontal = 16.dp), contentAlignment = Alignment.TopCenter) {
            when {
                s.phase == OnAirPhase.DONE -> Centered { DoneCard(s, onBack) }
                s.phase == OnAirPhase.LOADING || s.phase == OnAirPhase.SAVING -> Centered { CircularProgressIndicator() }
                s.paused -> Centered { PausedCard(s.pauseReason, onResume = vm::resume, onEnd = { vm.finish(onBack) }) }
                s.phase == OnAirPhase.READY -> Centered { ReadyCard(s, onStart = vm::newContact) }
                s.phase == OnAirPhase.LOGGED -> LoggedCard(s, onNext = vm::newContact, onFinish = { vm.finish(onBack) })
                else -> OverArea(s, vm)
            }
        }

        if (s.phase == OnAirPhase.OVER && !s.paused) {
            MorseKeyboard(
                learned = LOG_KEYS,
                hardMode = false,
                haptics = s.haptics,
                showBackspace = true,
                onKey = { ch, _ -> vm.onKey(ch) },
                modifier = Modifier.padding(bottom = 8.dp),
            )
        }
    }
}

@Composable
private fun Centered(content: @Composable () -> Unit) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { content() }
}

@Composable
private fun ReadyCard(s: OnAirUiState, onStart: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            "A practice contact, one over at a time. A station calls CQ, you answer, and they send the " +
                "usual exchange. Copy the parts that go in your log as they come.",
            style = MaterialTheme.typography.bodyLarge,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            "Tap a box to choose where your typing goes. Replay an over as often as you like, then Check. " +
                "Afterwards you'll see everything they sent, with the shorthand explained.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(16.dp))
        Text("Your callsign: ${s.myCall}", style = MaterialTheme.typography.titleMedium, fontFamily = FontFamily.Monospace)
        if (s.myCallMadeUp) {
            Text(
                "Made up for practice. Set your own in Settings.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Text(
            "Best once you know most letters and numbers.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(24.dp))
        Button(onClick = onStart) { Text("Start a contact") }
    }
}

@Composable
private fun OverArea(s: OnAirUiState, vm: OnAirViewModel) {
    val over = s.over ?: return
    val contact = s.contact ?: return
    val checked = s.phase == OnAirPhase.CHECKED
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(
            "Over ${s.overIndex + 1} of ${contact.overs.size}" + if (s.playing) " · ♪ receiving…" else "",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
        )
        Text(over.situation, style = MaterialTheme.typography.bodyMedium)

        over.fields.forEachIndexed { i, f ->
            FieldRow(
                label = f.label,
                value = s.values.getOrElse(i) { "" },
                active = !checked && i == s.activeField,
                mark = s.marks.getOrNull(i),
                answer = f.answer,
                onClick = { vm.selectField(i) },
            )
        }

        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedButton(onClick = vm::replay, enabled = !s.playing) { Text("Replay") }
            if (!checked) {
                Button(onClick = vm::check) { Text("Check") }
            } else {
                Button(onClick = vm::continueContact) { Text(if (s.lastOver) "See the log" else "Next over") }
            }
        }

        if (checked) {
            OutlinedCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("They sent", style = MaterialTheme.typography.labelLarge)
                    Text(over.text, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodyLarge)
                    over.shorthand.forEach { (w, m) ->
                        Text("$w: $m", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    over.reply?.let {
                        Spacer(Modifier.height(4.dp))
                        Text("You'd reply", style = MaterialTheme.typography.labelLarge)
                        Text(it, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
        }
        Spacer(Modifier.height(4.dp))
    }
}

@Composable
private fun FieldRow(label: String, value: String, active: Boolean, mark: Boolean?, answer: String, onClick: () -> Unit) {
    val border = when (mark) {
        true -> CorrectGreen
        false -> WrongRed
        null -> if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant
    }
    Row(
        Modifier
            .fillMaxWidth()
            .border(BorderStroke(if (active || mark != null) 2.dp else 1.dp, border), RoundedCornerShape(10.dp))
            .background(if (active) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f) else MaterialTheme.colorScheme.surface, RoundedCornerShape(10.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.width(120.dp))
        Text(
            value + if (active) "▏" else "",
            fontFamily = FontFamily.Monospace,
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.weight(1f),
            color = if (mark == false) WrongRed else MaterialTheme.colorScheme.onSurface,
        )
        if (mark == false) {
            Text(answer, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.titleMedium, color = CorrectGreen)
        } else if (mark == true) {
            Text("✓", style = MaterialTheme.typography.titleMedium, color = CorrectGreen)
        }
    }
}

@Composable
private fun LoggedCard(s: OnAirUiState, onNext: () -> Unit, onFinish: () -> Unit) {
    val contact = s.contact ?: return
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("Contact logged", style = MaterialTheme.typography.headlineSmall)
        val right = s.log.count { it.ok }
        Text("${contact.theirCall}: $right of ${s.log.size} log entries right", style = MaterialTheme.typography.bodyLarge)
        Card(
            Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
        ) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                s.log.forEach { line ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(line.label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.width(120.dp))
                        Text(
                            line.answer,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Medium,
                            color = if (line.ok) CorrectGreen else MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.weight(1f),
                        )
                        if (!line.ok) {
                            Text(
                                "you: ${line.typed.ifEmpty { "–" }}",
                                style = MaterialTheme.typography.labelMedium,
                                color = WrongRed,
                                modifier = Modifier.alpha(0.9f),
                            )
                        }
                    }
                }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(onClick = onNext) { Text("Next contact") }
            OutlinedButton(onClick = onFinish) { Text("Finish") }
        }
    }
}

@Composable
private fun DoneCard(s: OnAirUiState, onBack: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text("Session saved", style = MaterialTheme.typography.headlineMedium)
        Spacer(Modifier.height(8.dp))
        Text(
            "${s.contacts} contact${if (s.contacts == 1) "" else "s"} · ${s.correct} of ${s.fields} log entries right in ${formatMs(s.elapsedMs)}",
            style = MaterialTheme.typography.bodyLarge,
        )
        Spacer(Modifier.height(20.dp))
        Button(onClick = onBack) { Text("Done") }
    }
}
