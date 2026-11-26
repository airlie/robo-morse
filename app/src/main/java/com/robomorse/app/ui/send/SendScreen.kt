package com.robomorse.app.ui.send

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Switch
import androidx.compose.material3.SliderState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.robomorse.app.audio.Sidetone
import com.robomorse.app.ui.CorrectGreen
import com.robomorse.app.ui.MorsePattern
import com.robomorse.app.ui.WrongRed
import com.robomorse.core.FistReport
import com.robomorse.core.EchoLevel
import com.robomorse.core.IambicMode
import com.robomorse.core.KeyInterval
import kotlin.math.abs
import kotlin.math.roundToInt

private val Amber = Color(0xFFE0A526)

@Composable
fun SendScreen(vm: SendViewModel, sidetone: Sidetone, onBack: () -> Unit) {
    val s by vm.state.collectAsStateWithLifecycle()
    DisposableEffect(Unit) {
        sidetone.start()
        onDispose { sidetone.stop() }
    }
    LaunchedEffect(Unit) {
        kotlinx.coroutines.delay(300)
        vm.onSidetoneReady()
    }

    Column(Modifier.fillMaxSize().safeDrawingPadding().padding(horizontal = 16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 8.dp)) {
            Column(Modifier.weight(1f)) {
                Text(if (s.mode == SendMode.FREE) "Free sending" else "Echo", style = MaterialTheme.typography.titleMedium)
                Text(
                    if (s.mode == SendMode.FREE) "Send anything; it's decoded as you go." else "Hear it, then send it back.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            TextButton(onClick = onBack) { Text("Done") }
        }

        Column(
            Modifier.weight(1f).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (s.mode == SendMode.FREE) {
                FreeOutput(s, onClear = vm::clear)
            } else {
                EchoOptions(s, onLevel = vm::setEchoLevel, onAuto = vm::setEchoAuto, onAllChars = vm::setEchoAllChars)
                EchoOutput(s, onReplay = vm::replayEcho, onSkip = vm::skipEcho)
            }
            Timeline(s.recent, s.ditMs)
            FistCard(s.fist, s.fistBest, s.estWpm, showBest = s.mode == SendMode.FREE)
        }
        Spacer(Modifier.height(8.dp))

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            FilterChip(s.keyType == KeyType.STRAIGHT, onClick = { vm.setKeyType(KeyType.STRAIGHT) }, label = { Text("Straight key") })
            FilterChip(s.keyType == KeyType.PADDLE, onClick = { vm.setKeyType(KeyType.PADDLE) }, label = { Text("Paddle") })
            if (s.keyType == KeyType.PADDLE) {
                FilterChip(
                    true,
                    onClick = { vm.setIambicMode(if (s.iambicMode == IambicMode.A) IambicMode.B else IambicMode.A) },
                    label = { Text("Mode ${s.iambicMode}") },
                )
            }
        }
        if (s.keyType == KeyType.PADDLE) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Keyer ${s.paddleWpm} WPM", style = MaterialTheme.typography.bodySmall, modifier = Modifier.width(110.dp))
                val wpmState = remember(s.paddleWpm) { SliderState(s.paddleWpm.toFloat(), 0, 5f..40f) }
                Slider(
                    state = wpmState,
                    onValueChange = { wpmState.value = it; vm.setPaddleWpm(it.roundToInt()) },
                    modifier = Modifier.weight(1f),
                )
            }
        }

        Box(Modifier.fillMaxWidth().height(170.dp).padding(bottom = 8.dp)) {
            if (s.keyType == KeyType.STRAIGHT) {
                Pad("KEY", s.keyDown, Modifier.fillMaxSize(), onDown = vm::straightDown, onUp = vm::straightUp)
            } else {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxSize()) {
                    Pad("DIT", false, Modifier.weight(1f).fillMaxHeight(), onDown = { vm.paddle(true, true) }, onUp = { vm.paddle(true, false) })
                    Pad("DAH", false, Modifier.weight(1f).fillMaxHeight(), onDown = { vm.paddle(false, true) }, onUp = { vm.paddle(false, false) })
                }
            }
        }
        if (s.sidetoneMs > 0) {
            Text(
                "Sidetone buffer ≈ ${s.sidetoneMs} ms",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 4.dp),
            )
        }
    }
}

/** A touch pad that reports touch-down and lift-off with the events' own timestamps. */
@Composable
private fun Pad(label: String, lit: Boolean, modifier: Modifier, onDown: (Long) -> Unit, onUp: (Long) -> Unit) {
    val down by rememberUpdatedState(onDown)
    val up by rememberUpdatedState(onUp)
    Box(
        modifier
            .clip(RoundedCornerShape(20.dp))
            .background(if (lit) Amber else MaterialTheme.colorScheme.primary)
            .pointerInput(Unit) {
                awaitEachGesture {
                    val first = awaitFirstDown()
                    down(first.uptimeMillis)
                    var upTime = first.uptimeMillis
                    while (true) {
                        val e = awaitPointerEvent()
                        val ch = e.changes.firstOrNull { it.id == first.id } ?: break
                        if (!ch.pressed) {
                            upTime = ch.uptimeMillis
                            break
                        }
                    }
                    up(upTime)
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        Text(label, color = MaterialTheme.colorScheme.onPrimary, fontSize = 22.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun FreeOutput(s: SendUiState, onClear: () -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp)) {
            Text(
                s.text.takeLast(60).ifEmpty { "Start keying…" },
                fontFamily = FontFamily.Monospace,
                fontSize = 26.sp,
                fontWeight = FontWeight.Bold,
                color = if (s.text.isEmpty()) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.height(100.dp),
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    s.pattern.replace('.', '·').replace('-', '−'),
                    fontFamily = FontFamily.Monospace,
                    fontSize = 20.sp,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = onClear) { Text("Clear") }
            }
        }
    }
}

@Composable
private fun EchoOutput(s: SendUiState, onReplay: () -> Unit, onSkip: () -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp).fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                "${s.echoCorrect} of ${s.echoAttempts} correct",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(6.dp))
            when (s.echoPhase) {
                EchoPhase.IDLE, EchoPhase.LISTEN -> {
                    Text("♪ Listen…", style = MaterialTheme.typography.headlineSmall)
                    Spacer(Modifier.height(54.dp))
                }
                EchoPhase.SEND -> {
                    Text("Now send it back", style = MaterialTheme.typography.headlineSmall)
                    Text(
                        s.echoSent.ifEmpty { " " },
                        fontFamily = FontFamily.Monospace, fontSize = 30.sp, fontWeight = FontWeight.Bold,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = onReplay) { Text("Hear again") }
                        OutlinedButton(onClick = onSkip) { Text("Skip") }
                    }
                }
                EchoPhase.RESULT -> {
                    val r = s.echoResult
                    if (r != null) {
                        Text(
                            if (r.correct) "Correct" else "Not quite",
                            style = MaterialTheme.typography.headlineSmall,
                            color = if (r.correct) CorrectGreen else WrongRed,
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                            EchoAnswer("It was", r.target, if (!r.correct && s.showPatterns) r.target else null)
                            EchoAnswer("You sent", r.sent, if (!r.correct && s.showPatterns) r.sent else null)
                        }
                        r.fist?.let { Text("Rhythm score ${it.score}", style = MaterialTheme.typography.bodyMedium) }
                    }
                }
            }
        }
    }
}

/**
 * The last few marks and gaps on a time line. Each mark is coloured by how close it
 * is to an ideal dit (1) or dah (3) at the sender's own speed; tick marks show dit units.
 */
@Composable
private fun Timeline(intervals: List<KeyInterval>, ditMs: Double) {
    val good = CorrectGreen
    val off = Amber
    val grid = MaterialTheme.colorScheme.outlineVariant
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp)) {
            Text("Rhythm", style = MaterialTheme.typography.labelLarge)
            Canvas(Modifier.fillMaxWidth().height(44.dp)) {
                if (intervals.isEmpty()) return@Canvas
                // Long pauses are drawn as a fixed short break so the marks stay visible.
                val shown = intervals.map { if (!it.on && it.ms > 8 * ditMs) KeyInterval(false, 8 * ditMs) else it }
                val total = shown.sumOf { it.ms }.coerceAtLeast(30 * ditMs)
                val pxPerMs = size.width / total.toFloat()
                var x = size.width - shown.sumOf { it.ms }.toFloat() * pxPerMs
                val unitPx = (ditMs * pxPerMs).toFloat()
                if (unitPx > 4) {
                    var gx = size.width
                    while (gx > 0) {
                        drawLine(grid, Offset(gx, size.height - 6), Offset(gx, size.height), 1f)
                        gx -= unitPx
                    }
                }
                for (iv in shown) {
                    val w = iv.ms.toFloat() * pxPerMs
                    if (iv.on) {
                        val units = iv.ms / ditMs
                        val err = minOf(abs(units - 1), abs(units - 3) / 3)
                        drawRoundRect(
                            if (err < 0.25) good else off,
                            topLeft = Offset(x, 8f),
                            size = Size(w.coerceAtLeast(2f), size.height - 20f),
                            cornerRadius = CornerRadius(4f, 4f),
                        )
                    }
                    x += w
                }
            }
        }
    }
}

@Composable
private fun FistCard(f: FistReport?, best: Int?, estWpm: Double, showBest: Boolean) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.width(96.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(f?.score?.toString() ?: "–", fontSize = 34.sp, fontWeight = FontWeight.Bold)
                    Text("rhythm", style = MaterialTheme.typography.labelSmall)
                    Text("last few letters", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    if (f == null) {
                        Text("Send a few letters to get a score.", style = MaterialTheme.typography.bodySmall)
                    }
                    f?.parts?.forEach { p -> Part(p.name, p.score, p.reading) }
                }
            }
            if (f != null) {
                Text(f.tip, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
            }
            Row {
                Text(
                    if (estWpm > 0) "Your speed: %.0f WPM".format(estWpm) else "",
                    style = MaterialTheme.typography.labelMedium,
                    modifier = Modifier.weight(1f),
                )
                if (showBest && best != null) Text("Best this session: $best", style = MaterialTheme.typography.labelMedium)
            }
        }
    }
}

@Composable
private fun Part(name: String, score: Int, reading: String) {
    Column {
        Row {
            Text(name, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
            Text(reading, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        LinearProgressIndicator(
            progress = { score / 100f },
            modifier = Modifier.fillMaxWidth().height(6.dp),
            color = if (score >= 80) CorrectGreen else if (score >= 50) Amber else WrongRed,
            trackColor = MaterialTheme.colorScheme.surface,
            drawStopIndicator = {},
        )
    }
}

/** Echo level choices: tap a level, or let the app move you up and down. */
@Composable
private fun EchoOptions(
    s: SendUiState,
    onLevel: (EchoLevel) -> Unit,
    onAuto: (Boolean) -> Unit,
    onAllChars: (Boolean) -> Unit,
) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                s.echoLevels.forEach { (level, available) ->
                    FilterChip(
                        selected = s.echoLevel == level,
                        enabled = available,
                        onClick = { onLevel(level) },
                        label = { Text(level.label) },
                    )
                }
            }
            Text(
                s.echoLevel.description +
                    if (s.echoRecentCount > 0) " · ${s.echoRecentCorrect} of last ${s.echoRecentCount} right" else "",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Level up automatically", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                Switch(checked = s.echoAuto, onCheckedChange = onAuto)
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Use all characters", style = MaterialTheme.typography.bodyMedium)
                    Text(
                        if (s.echoAllChars) "Every letter, number and sign" else "Only your Koch characters (${s.learned.length})",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(checked = s.echoAllChars, onCheckedChange = onAllChars)
            }
            s.echoNotice?.let { Text(it, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold, color = CorrectGreen) }
        }
    }
}

/** One side of an Echo result, with an optional faint pattern underneath. */
@Composable
private fun EchoAnswer(label: String, text: String, pattern: String?) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(text.ifEmpty { "–" }, fontFamily = FontFamily.Monospace, fontSize = 24.sp, fontWeight = FontWeight.Bold)
        if (pattern != null) {
            MorsePattern(
                pattern,
                unit = 4.dp,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                modifier = Modifier.padding(top = 4.dp),
            )
        }
    }
}
