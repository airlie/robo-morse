package com.robomorse.app.ui.headcopy

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.robomorse.app.ui.CorrectGreen
import com.robomorse.app.ui.WrongRed
import com.robomorse.app.ui.copy.DrillTopBar
import com.robomorse.app.ui.copy.PausedCard
import com.robomorse.app.ui.practice.formatMs
import com.robomorse.core.CopyContent

@Composable
fun HeadCopyScreen(vm: HeadCopyViewModel, onBack: () -> Unit) {
    val s by vm.state.collectAsStateWithLifecycle()
    BackHandler { vm.finish(onBack) }
    LifecycleEventEffect(Lifecycle.Event.ON_PAUSE) { vm.pause("Paused while the app was in the background.") }

    Column(Modifier.fillMaxSize().safeDrawingPadding()) {
        DrillTopBar(
            title = "Head copy",
            subtitle = "${s.charWpm}/${s.effWpm} WPM · ${formatMs(s.elapsedMs)}" +
                if (s.items > 0) " · ${s.correct} of ${s.items} heard" else "",
            canPause = s.canPause,
            onPause = { vm.pause() },
            onEnd = { vm.finish(onBack) },
        )
        if (s.phase != HeadPhase.DONE) Options(s, vm)

        Box(Modifier.fillMaxWidth().weight(1f).padding(16.dp), contentAlignment = Alignment.Center) {
            when {
                s.phase == HeadPhase.DONE -> DoneCard(s, onBack)
                s.phase == HeadPhase.LOADING || s.phase == HeadPhase.SAVING -> CircularProgressIndicator()
                s.paused -> PausedCard(s.pauseReason, onResume = vm::resume, onEnd = { vm.finish(onBack) })
                s.phase == HeadPhase.READY -> ReadyCard(onStart = vm::start)
                else -> PlayArea(s, onChoose = vm::choose, onReplay = vm::replay)
            }
        }
    }
}

@Composable
private fun Options(s: HeadCopyUiState, vm: HeadCopyViewModel) {
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
private fun ReadyCard(onStart: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            "Copy in your head: a word or phrase plays and there's nothing to type. Let it build up as " +
                "you listen, then pick what you heard from four choices.",
            style = MaterialTheme.typography.bodyLarge,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            "The choices look alike on purpose, so you have to really hear it. Misses are played again and " +
                "come back a few items later. The level moves up after 8 of your last 10 right.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(24.dp))
        Button(onClick = onStart) { Text("Start") }
    }
}

@Composable
private fun PlayArea(s: HeadCopyUiState, onChoose: (String) -> Unit, onReplay: () -> Unit) {
    val item = s.item ?: return
    val answered = s.phase == HeadPhase.RESULT
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
        Text(
            when {
                s.reviewing -> "Listen again: ${item.target}"
                answered && s.picked == item.target -> "Heard it"
                answered -> "It was ${item.target}"
                s.phase == HeadPhase.LISTEN -> "♪ Listen…"
                else -> "What did you hear?"
            },
            style = MaterialTheme.typography.titleLarge,
            color = if (answered && s.picked == item.target) CorrectGreen else MaterialTheme.colorScheme.onSurface,
        )
        Spacer(Modifier.height(20.dp))
        if (s.phase != HeadPhase.LISTEN || answered) {
            item.choices.forEach { choice ->
                val colour: Color? = when {
                    !answered -> null
                    choice == item.target -> CorrectGreen
                    choice == s.picked -> WrongRed
                    else -> null
                }
                OutlinedButton(
                    onClick = { onChoose(choice) },
                    enabled = !answered,
                    border = colour?.let { BorderStroke(2.dp, it) } ?: ButtonDefaults.outlinedButtonBorder(enabled = !answered),
                    colors = ButtonDefaults.outlinedButtonColors(disabledContentColor = colour ?: MaterialTheme.colorScheme.onSurfaceVariant),
                    modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(vertical = 4.dp),
                ) {
                    Text(choice, style = MaterialTheme.typography.titleLarge, fontFamily = FontFamily.Monospace)
                }
            }
            Spacer(Modifier.height(8.dp))
            if (!answered) {
                OutlinedButton(onClick = onReplay, enabled = !s.playing) { Text("Replay") }
            } else {
                val meanings = item.target.split(' ').mapNotNull { w -> CopyContent.meanings[w]?.let { "$w: $it" } }
                meanings.forEach {
                    Text(it, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium, textAlign = TextAlign.Center)
                }
            }
        } else {
            Text(
                "Hold it in your head. The choices appear when it finishes.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
    }
}

@Composable
private fun DoneCard(s: HeadCopyUiState, onBack: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text("Session saved", style = MaterialTheme.typography.headlineMedium)
        Spacer(Modifier.height(8.dp))
        Text(
            "${s.correct} of ${s.items} heard (${if (s.items == 0) 0 else s.correct * 100 / s.items}%) in ${formatMs(s.elapsedMs)}",
            style = MaterialTheme.typography.bodyLarge,
        )
        Text("Level: ${s.level.label}", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(20.dp))
        Button(onClick = onBack) { Text("Done") }
    }
}
