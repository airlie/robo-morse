package com.robomorse.app.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import com.robomorse.core.MorseCode

/** Width of [text]'s pattern in units: dit 1, dah 3, 1 between elements, 3 between characters. */
private fun patternUnits(text: String): Int {
    val chars = text.filter { MorseCode.isSupported(it) }
    if (chars.isEmpty()) return 0
    val inChars = chars.sumOf { c ->
        val p = MorseCode.patternOf(c)!!
        p.sumOf { if (it == '.') 1 else 3 } + (p.length - 1)
    }
    return inChars + 3 * (chars.length - 1)
}

/**
 * Draws the dits and dahs of [text] at a fixed [unit] size (a dit is a dot one unit
 * across, a dah three units long), so every character is drawn at the same scale.
 */
@Composable
fun MorsePattern(text: String, unit: Dp, color: Color, modifier: Modifier = Modifier) {
    val units = patternUnits(text)
    if (units == 0) return
    Canvas(modifier.size(width = unit * units, height = unit)) {
        val u = size.height
        var x = 0f
        text.filter { MorseCode.isSupported(it) }.forEachIndexed { ci, c ->
            if (ci > 0) x += 3 * u
            MorseCode.patternOf(c)!!.forEachIndexed { ei, el ->
                if (ei > 0) x += u
                val w = if (el == '.') u else 3 * u
                drawRoundRect(color, Offset(x, 0f), Size(w, u), CornerRadius(u / 2, u / 2))
                x += w
            }
        }
    }
}
