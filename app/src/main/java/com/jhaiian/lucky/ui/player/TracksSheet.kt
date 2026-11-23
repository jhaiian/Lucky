package com.jhaiian.lucky.ui.player

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.jhaiian.lucky.R
import com.jhaiian.lucky.engine.TrackInfo
import com.jhaiian.lucky.ui.home.codecDisplayName
import com.jhaiian.lucky.ui.home.languageDisplayName
import com.jhaiian.lucky.ui.theme.LocalLuckyColors

@Composable
internal fun trackLabel(track: TrackInfo, position: Int): String =
    track.language?.let { languageDisplayName(it) } ?: stringResource(R.string.track_number, position + 1)

@Composable
internal fun trackDetail(track: TrackInfo): String {
    val context = LocalContext.current
    return listOfNotNull(
        track.title?.takeIf { it.isNotBlank() },
        codecDisplayName(track.codec),
        track.channels?.takeIf { it > 0 }?.let { context.resources.getQuantityString(R.plurals.audio_channels, it, it) },
        stringResource(R.string.badge_forced).takeIf { track.isForced },
        stringResource(R.string.badge_default).takeIf { track.isDefault },
        stringResource(R.string.tracks_external).takeIf { track.external }
    ).joinToString(" · ")
}

@Composable
internal fun TrackSectionTitle(text: String) {
    val colors = LocalLuckyColors.current
    Text(
        text = text,
        style = MaterialTheme.typography.titleSmall,
        fontWeight = FontWeight.SemiBold,
        color = colors.primary,
        modifier = Modifier.padding(start = 24.dp, end = 24.dp, top = 16.dp, bottom = 4.dp)
    )
}

@Composable
internal fun TrackRow(title: String, detail: String?, selected: Boolean, onClick: () -> Unit) {
    val colors = LocalLuckyColors.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(role = Role.RadioButton, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        RadioButton(
            selected = selected,
            onClick = null,
            colors = RadioButtonDefaults.colors(selectedColor = colors.primary, unselectedColor = colors.secondaryText)
        )
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                color = colors.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            if (!detail.isNullOrBlank()) {
                Text(
                    text = detail,
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.secondaryText,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}
