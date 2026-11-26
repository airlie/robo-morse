package com.robomorse.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.robomorse.core.MasteryTier

/**
 * Every character in Koch order. Learned characters are coloured by mastery tier;
 * the rest are shown faintly, so the whole journey is visible from day one.
 */
@Composable
fun CharacterMap(
    order: String,
    tiers: Map<Char, MasteryTier>,
    columns: Int = 8,
    onClick: ((Char) -> Unit)? = null,
) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        order.chunked(columns).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
                row.forEach { c ->
                    val tier = tiers[c]
                    Box(
                        Modifier
                            .weight(1f)
                            .aspectRatio(1f)
                            .clip(RoundedCornerShape(8.dp))
                            .background(tierColor(tier))
                            .then(if (onClick != null) Modifier.clickable { onClick(c) } else Modifier),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(c.toString(), color = onTierColor(tier), fontWeight = FontWeight.SemiBold, fontSize = 18.sp)
                    }
                }
                repeat(columns - row.length) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}

@Composable
fun TierLegend() {
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
        MasteryTier.entries.forEach { t ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(12.dp).clip(RoundedCornerShape(3.dp)).background(tierColor(t)))
                Spacer(Modifier.width(4.dp))
                Text(t.label, style = MaterialTheme.typography.labelSmall)
            }
        }
    }
}
