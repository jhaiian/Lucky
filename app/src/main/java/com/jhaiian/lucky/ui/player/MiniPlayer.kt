package com.jhaiian.lucky.ui.player

import android.os.SystemClock
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
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
import com.jhaiian.lucky.playback.NowPlaying
import com.jhaiian.lucky.ui.home.formatDuration
import com.jhaiian.lucky.ui.library.ProgressStrip
import com.jhaiian.lucky.ui.library.VideoThumbnail
import com.jhaiian.lucky.ui.theme.LocalLuckyColors
import kotlinx.coroutines.delay

@Composable
fun MiniPlayerBar(fallbackTitle: String?, onExpand: () -> Unit, onClose: () -> Unit, modifier: Modifier = Modifier) {
    val colors = LocalLuckyColors.current
    val state by NowPlaying.state.collectAsState()
    val current = state
    val receivedAt = remember(current) { SystemClock.elapsedRealtime() }
    val position by produceState(current?.positionMs ?: 0L, current) {
        while (current != null) {
            val elapsed = if (current.playing) ((SystemClock.elapsedRealtime() - receivedAt) * current.speed).toLong() else 0L
            value = (current.positionMs + elapsed).coerceIn(0L, current.durationMs.coerceAtLeast(0L))
            if (!current.playing) break
            delay(500)
        }
    }
    val shape = RoundedCornerShape(18.dp)
    Column(
        modifier
            .widthIn(max = 640.dp)
            .fillMaxWidth()
            .shadow(8.dp, shape)
            .clip(shape)
            .background(colors.cardBackground)
            .clickable(role = Role.Button, onClick = onExpand)
    ) {
        Row(
            Modifier.padding(start = 8.dp, top = 8.dp, bottom = 8.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            val uri = current?.uri
            if (uri != null) {
                VideoThumbnail(
                    uri = uri,
                    corner = 10.dp,
                    modifier = Modifier
                        .width(80.dp)
                        .aspectRatio(16f / 9f)
                )
            } else {
                Box(
                    Modifier
                        .width(80.dp)
                        .aspectRatio(16f / 9f)
                        .clip(RoundedCornerShape(10.dp))
                        .background(colors.surfaceVariant)
                )
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    current?.title ?: fallbackTitle.orEmpty(),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = colors.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                if (current != null && current.durationMs > 0) {
                    Text(
                        "${formatDuration(position)} / ${formatDuration(current.durationMs)}",
                        style = MaterialTheme.typography.labelMedium,
                        color = colors.secondaryText,
                        maxLines = 1
                    )
                }
            }
            val playing = current?.playing == true
            IconButton(onClick = { NowPlaying.controls?.let { if (playing) it.pause() else it.play() } }) {
                Icon(
                    if (playing) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                    contentDescription = stringResource(if (playing) R.string.mini_player_pause else R.string.mini_player_play),
                    tint = colors.primary
                )
            }
            IconButton(onClick = onClose) {
                Icon(Icons.Rounded.Close, contentDescription = stringResource(R.string.mini_player_close), tint = colors.secondaryText)
            }
        }
        val duration = current?.durationMs ?: 0L
        ProgressStrip(if (duration > 0) position.toFloat() / duration else 0f, Modifier.fillMaxWidth(), height = 3.dp)
    }
}
