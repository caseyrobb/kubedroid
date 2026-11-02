package com.neozeon.kubedroid.ui.common

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Spec 12 goal 4: uppercase letter-spaced micro-label keys with monospace
 * values — the "dev tool" metadata grid from argoproj-labs/mobile-for-argocd
 * app cards. Cells flow [columns] per row; values never wrap (ellipsized).
 */
@Composable
fun MetaGrid(
    rows: List<Pair<String, String>>,
    modifier: Modifier = Modifier,
    columns: Int = 3,
) {
    Column(modifier) {
        rows.chunked(columns).forEach { cells ->
            Row(Modifier.fillMaxWidth()) {
                cells.forEach { (label, value) ->
                    Column(Modifier.weight(1f).padding(end = 8.dp)) {
                        Text(
                            label.uppercase(),
                            style = MaterialTheme.typography.labelSmall
                                .copy(letterSpacing = 1.2.sp),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.testTag("meta-label-$label"),
                        )
                        Text(
                            value,
                            style = MaterialTheme.typography.bodySmall
                                .copy(fontFamily = FontFamily.Monospace),
                            color = MaterialTheme.colorScheme.onSurface,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.testTag("meta-value-$label"),
                        )
                    }
                }
                // Keep equal column widths on short trailing rows.
                repeat(columns - cells.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}
