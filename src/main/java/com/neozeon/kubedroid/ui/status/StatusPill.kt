package com.neozeon.kubedroid.ui.status

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

/**
 * Spec 12 goal 2: icon + label on a translucent token fill/border, matching
 * the argoproj-labs/mobile-for-argocd status pill recipe. Exposes the token
 * label as content-desc for tests and TalkBack.
 */
@Composable
fun StatusPill(token: StatusToken, modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(50)
    Row(
        modifier = modifier
            .clip(shape)
            .background(token.containerColor)
            .border(1.dp, token.outlineColor, shape)
            .semantics { contentDescription = token.label },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            token.icon,
            contentDescription = null,
            tint = token.color,
            modifier = Modifier
                .padding(start = 8.dp, top = 2.dp, bottom = 2.dp)
                .size(12.dp),
        )
        Spacer(Modifier.width(4.dp))
        Text(
            token.label,
            style = MaterialTheme.typography.labelMedium,
            color = token.color,
            modifier = Modifier.padding(end = 8.dp, top = 2.dp, bottom = 2.dp),
        )
    }
}
