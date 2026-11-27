package com.jhaiian.lucky.ui.player

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Replay
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.jhaiian.lucky.R
import com.jhaiian.lucky.ui.home.formatDuration
import com.jhaiian.lucky.ui.theme.LocalLuckyColors

@Composable
internal fun ResumeNotice(
    resumedFromMs: Long?,
    raised: Boolean,
    onStartOver: () -> Unit,
    modifier: Modifier = Modifier
) {
    val colors = LocalLuckyColors.current
    val lastShown = remember { longArrayOf(0L) }
    if (resumedFromMs != null) lastShown[0] = resumedFromMs
    val bottom by animateDpAsState(if (raised) 132.dp else 28.dp, tween(220), label = "resumeOffset")
    AnimatedVisibility(
        visible = resumedFromMs != null,
        modifier = modifier.padding(start = 24.dp, end = 24.dp, bottom = bottom),
        enter = fadeIn(tween(220)) + slideInVertically(tween(220)) { it / 2 },
        exit = fadeOut(tween(180)) + slideOutVertically(tween(180)) { it / 2 }
    ) {
        val shape = RoundedCornerShape(50)
        Row(
            Modifier
                .shadow(6.dp, shape)
                .clip(shape)
                .background(colors.cardBackground.copy(alpha = 0.94f))
                .padding(start = 16.dp, end = 6.dp, top = 6.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(Icons.Rounded.History, contentDescription = null, tint = colors.secondaryText, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text(
                stringResource(R.string.player_resumed_from, formatDuration(lastShown[0])),
                style = MaterialTheme.typography.labelLarge,
                color = colors.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false)
            )
            Spacer(Modifier.width(10.dp))
            Box(
                Modifier
                    .width(1.dp)
                    .height(20.dp)
                    .background(colors.divider)
            )
            Spacer(Modifier.width(4.dp))
            Row(
                Modifier
                    .clip(shape)
                    .clickable(role = Role.Button, onClick = onStartOver)
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(Icons.Rounded.Replay, contentDescription = null, tint = colors.primary, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text(
                    stringResource(R.string.player_start_over),
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold,
                    color = colors.primary,
                    maxLines = 1
                )
            }
        }
    }
}
