package com.robomorse.app.ui

import android.view.HapticFeedbackConstants
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

const val BACKSPACE = '\b'

private val letterRows = listOf("QWERTYUIOP", "ASDFGHJKL", "ZXCVBNM")
private const val NUMBER_ROW = "1234567890"
private const val PUNCT_ROW = ".,?/="

/**
 * Fixed-layout QWERTY answer keyboard. Keys never move: characters not yet learned are
 * dimmed and inactive (unless [hardMode]). The number and punctuation rows appear only
 * once one of their characters is learned. Keys fire on touch-down, reporting the
 * event's uptime so response times are measured precisely.
 */
@Composable
fun MorseKeyboard(
    learned: Set<Char>,
    hardMode: Boolean,
    haptics: Boolean,
    showBackspace: Boolean,
    onKey: (Char, Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    val showNumbers = learned.any { it in NUMBER_ROW } || hardMode
    val showPunct = learned.any { it in PUNCT_ROW } || hardMode
    val rows = buildList {
        if (showNumbers) add(NUMBER_ROW)
        addAll(letterRows)
        if (showPunct) add(PUNCT_ROW)
    }
    Column(
        modifier.fillMaxWidth().padding(4.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        rows.forEachIndexed { index, row ->
            Row(
                Modifier.fillMaxWidth().height(58.dp),
                horizontalArrangement = Arrangement.spacedBy(5.dp, Alignment.CenterHorizontally),
            ) {
                // Every row uses ten slots so key widths stay identical across rows.
                val pad = (10 - row.length) / 2f
                if (pad > 0) Box(Modifier.weight(pad))
                row.forEach { c ->
                    Key(
                        label = c.toString(),
                        enabled = hardMode || c in learned,
                        haptics = haptics,
                        modifier = Modifier.weight(1f),
                    ) { t -> onKey(c, t) }
                }
                val isLastLetterRow = row == letterRows.last()
                if (isLastLetterRow && showBackspace) {
                    Key("⌫", enabled = true, haptics = haptics, modifier = Modifier.weight(pad.coerceAtLeast(1f)), description = "Backspace") { t ->
                        onKey(BACKSPACE, t)
                    }
                } else if (pad > 0) {
                    Box(Modifier.weight(pad))
                }
            }
        }
    }
}

@Composable
private fun Key(
    label: String,
    enabled: Boolean,
    haptics: Boolean,
    modifier: Modifier,
    description: String = label,
    onDown: (Long) -> Unit,
) {
    val view = LocalView.current
    val currentOnDown by rememberUpdatedState(onDown)
    val bg = if (enabled) MaterialTheme.colorScheme.surfaceVariant else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f)
    val fg = if (enabled) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.25f)
    Box(
        modifier
            .fillMaxHeight()
            .clip(RoundedCornerShape(8.dp))
            .background(bg)
            .semantics { contentDescription = description }
            .then(
                if (enabled) {
                    Modifier.pointerInput(Unit) {
                        awaitEachGesture {
                            val down = awaitFirstDown()
                            if (haptics) view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                            currentOnDown(down.uptimeMillis)
                        }
                    }
                } else {
                    Modifier
                },
            ),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, color = fg, fontSize = 22.sp, fontWeight = FontWeight.Medium)
    }
}
