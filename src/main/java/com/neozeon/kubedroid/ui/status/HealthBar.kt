package com.neozeon.kubedroid.ui.status

import androidx.compose.foundation.background
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp

/**
 * Spec 12 goal 3: fleet-health bar over the loaded window — stacked
 * proportional segments in severity order plus a token-label legend,
 * pod-like list screens only (Q2 decision).
 */
@Composable
fun HealthBar(counts: Map<HealthBucket, Int>, modifier: Modifier = Modifier) {
    val shown = HealthBucket.values().filter { (counts[it] ?: 0) > 0 }
    if (shown.isEmpty()) return
    Column(modifier = modifier.testTag("health-bar")) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(6.dp)
                .clip(RoundedCornerShape(3.dp)),
        ) {
            val total = shown.sumOf { counts[it] ?: 0 }.coerceAtLeast(1)
            shown.forEach { bucket ->
                val n = counts[bucket] ?: 0
                Box(
                    modifier = Modifier
                        .weight(n.toFloat() / total)
                        .fillMaxHeight()
                        .background(bucket.token.color)
                        .testTag("health-segment-${bucket.name}"),
                )
            }
        }
        Row(horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(12.dp)) {
            shown.forEach { bucket ->
                Text(
                    "${bucket.token.label} ${counts[bucket]}",
                    style = MaterialTheme.typography.labelSmall,
                    color = bucket.token.color,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
        }
    }
}
