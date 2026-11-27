package com.robomorse.app.ui.voice

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.robomorse.app.ui.CorrectGreen
import com.robomorse.app.ui.MorsePattern
import com.robomorse.app.ui.WrongRed
import com.robomorse.app.ui.copy.DrillTopBar
import com.robomorse.app.ui.copy.PausedCard
import com.robomorse.app.ui.practice.formatMs
import com.robomorse.core.CopyContent
import com.robomorse.core.VoiceAnswers
import com.robomorse.core.VoiceLevel

@Composable
fun VoiceScreen(vm: VoiceViewModel, onBack: () -> Unit) {
    val s by vm.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) vm.start() else vm.permissionDenied()
    }
    val onStart = {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            vm.start()
        } else {
            permission.launch(Manifest.permission.RECORD_AUDIO)
        }
    }
    BackHandler { vm.finish(onBack) }
    LifecycleEventEffect(Lifecycle.Event.ON_PAUSE) { vm.pause("Paused while the app was in the background.") }

    Column(Modifier.fillMaxSize().safeDrawingPadding()) {
        DrillTopBar(
            title = "Say it",
            subtitle = "${s.charWpm}/${s.effWpm} WPM · ${formatMs(s.elapsedMs)}" +
                if (s.items > 0) " · ${s.correct} of ${s.items} right" else "",
            canPause = s.canPause,
            onPause = { vm.pause() },
            onEnd = { vm.finish(onBack) },
        )
        if (s.phase != VoicePhase.DONE && s.phase != VoicePhase.UNAVAILABLE) Options(s, vm)

        Box(Modifier.fillMaxWidth().weight(1f).padding(16.dp), contentAlignment = Alignment.Center) {
            when {
                s.phase == VoicePhase.DONE -> DoneCard(s, onBack)
                s.phase == VoicePhase.LOADING || s.phase == VoicePhase.SAVING -> CircularProgressIndicator()
                s.phase == VoicePhase.UNAVAILABLE -> Text(
                    "Speech recognition isn't available on this phone, so Say it can't run. " +
                        "Installing or enabling the Google app usually provides it.",
                    style = MaterialTheme.typography.bodyLarge,
                    textAlign = TextAlign.Center,
                )
                s.paused -> PausedCard(s.pauseReason, onResume = vm::resume, onEnd = { vm.finish(onBack) })
                s.phase == VoicePhase.READY -> ReadyCard(s, onStart)
                s.phase == VoicePhase.RESULT -> ResultArea(s)
                else -> SpeakArea(s, onReplay = vm::replay, onShowMe = vm::showMe)
            }
        }

        if (s.phase in setOf(VoicePhase.LISTEN, VoicePhase.SPEAK, VoicePhase.RESULT) && s.chars.size <= 12) {
            PhoneticStrip(s.chars)
        }
    }
}

@Composable
private fun Options(s: VoiceUiState, vm: VoiceViewModel) {
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
                "Use all characters, not just learned ones",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            Switch(checked = s.allChars, onCheckedChange = vm::setAllChars)
        }
        s.notice?.let {
            Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary)
        }
    }
}

@Composable
private fun ReadyCard(s: VoiceUiState, onStart: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.verticalScroll(rememberScrollState())) {
        Text(
            "Listen, then say what you heard out loud. Saying it ties the sound straight to the letter, " +
                "with no keys to hunt for.",
            style = MaterialTheme.typography.bodyLarge,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            "Phonetic words are understood best: \"Kilo\" for K, \"Mike\" for M. Letter names work too, " +
                "but B, D, E, P, T and V sound alike to a phone. For a group, say each one (\"Kilo Mike\"); " +
                "for a word, just say the word.",
            style = MaterialTheme.typography.bodyMedium,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            if (s.onDevice) "Speech is recognized on this phone."
            else "Speech is recognized by your phone's speech service (Google), which may send audio to Google " +
                "unless its offline English pack is installed.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            "Headphones help: the microphone opens after each sound finishes.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(20.dp))
        Button(onClick = onStart) { Text("Start") }
    }
}

@Composable
private fun SpeakArea(s: VoiceUiState, onReplay: () -> Unit, onShowMe: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            Modifier
                .size(120.dp)
                .background(
                    if (s.micReady) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceContainerHigh,
                    CircleShape,
                ),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                when {
                    s.playing -> "♪"
                    s.micReady -> "🎤"
                    else -> "…"
                },
                style = MaterialTheme.typography.displaySmall,
                color = if (s.micReady) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface,
            )
        }
        Spacer(Modifier.height(16.dp))
        Text(
            when {
                s.playing -> "Listen…"
                s.micReady -> "Say it"
                else -> "Getting ready to listen…"
            },
            style = MaterialTheme.typography.titleLarge,
        )
        s.hint?.let {
            Spacer(Modifier.height(8.dp))
            Text(it, style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.height(20.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedButton(onClick = onReplay, enabled = s.phase == VoicePhase.SPEAK) { Text("Replay") }
            OutlinedButton(onClick = onShowMe, enabled = s.phase == VoicePhase.SPEAK) { Text("Show me") }
        }
    }
}

@Composable
private fun ResultArea(s: VoiceUiState) {
    val right = s.outcome == VoiceOutcome.RIGHT
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            when {
                s.reviewing -> "Listen again"
                right -> "Right"
                s.outcome == VoiceOutcome.SHOWN -> "Here it is"
                else -> "It was"
            },
            style = MaterialTheme.typography.titleLarge,
            color = if (right) CorrectGreen else MaterialTheme.colorScheme.onSurface,
        )
        Spacer(Modifier.height(12.dp))
        Text(
            s.target,
            style = MaterialTheme.typography.displayMedium,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.SemiBold,
            color = if (right) CorrectGreen else MaterialTheme.colorScheme.onSurface,
        )
        Spacer(Modifier.height(8.dp))
        // Each character with its phonetic word and pattern: sound, letter and name together.
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            s.target.forEach { ch ->
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    MorsePattern(ch.toString(), unit = 5.dp, color = MaterialTheme.colorScheme.secondary)
                    Spacer(Modifier.height(4.dp))
                    Text(VoiceAnswers.phonetic[ch] ?: ch.toString(), style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
        if (s.outcome == VoiceOutcome.WRONG) {
            Spacer(Modifier.height(12.dp))
            Text(
                if (s.heard.isEmpty()) "You asked to see it" else "Heard: ${s.heard}",
                style = MaterialTheme.typography.bodyLarge,
                color = WrongRed,
            )
        } else if (right && s.heard.isNotEmpty() && s.heard != s.target) {
            Text("Heard: ${s.heard}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        CopyContent.meanings[s.target]?.takeIf { s.level == VoiceLevel.WORDS }?.let {
            Spacer(Modifier.height(8.dp))
            Text("${s.target}: $it", style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
        }
    }
}

/** The phonetic words for the characters in play: a reference, like the keyboard is elsewhere. */
@Composable
private fun PhoneticStrip(chars: Set<Char>) {
    Text(
        chars.sorted().joinToString("  ·  ") { "$it ${VoiceAnswers.phonetic[it] ?: ""}" },
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center,
        modifier = Modifier.fillMaxWidth().padding(16.dp),
    )
}

@Composable
private fun DoneCard(s: VoiceUiState, onBack: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text("Session saved", style = MaterialTheme.typography.headlineMedium)
        Spacer(Modifier.height(8.dp))
        Text(
            "${s.correct} of ${s.items} right (${if (s.items == 0) 0 else s.correct * 100 / s.items}%) in ${formatMs(s.elapsedMs)}",
            style = MaterialTheme.typography.bodyLarge,
        )
        Text("Level: ${s.level.label}", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(20.dp))
        Button(onClick = onBack) { Text("Done") }
    }
}
