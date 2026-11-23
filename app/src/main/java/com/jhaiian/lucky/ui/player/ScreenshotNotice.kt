package com.jhaiian.lucky.ui.player

import android.graphics.Bitmap
import android.net.Uri
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.jhaiian.lucky.R
import com.jhaiian.lucky.data.ScreenshotStore
import com.jhaiian.lucky.ui.theme.LocalLuckyColors

class ScreenshotNotice(val uri: Uri, val preview: Bitmap)

internal fun screenshotPreview(source: Bitmap, longestSide: Int = 256): Bitmap {
    val longest = maxOf(source.width, source.height)
    if (longest <= longestSide) return source
    val scale = longestSide.toFloat() / longest
    return Bitmap.createScaledBitmap(source, (source.width * scale).toInt().coerceAtLeast(1), (source.height * scale).toInt().coerceAtLeast(1), true)
}

@Composable
internal fun ScreenshotNoticeCard(
    notice: ScreenshotNotice,
    onOpen: () -> Unit,
    onShare: () -> Unit,
    modifier: Modifier = Modifier
) {
    val colors = LocalLuckyColors.current
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(18.dp),
        color = colors.cardBackground,
        contentColor = colors.onSurface,
        shadowElevation = 6.dp
    ) {
        Column(Modifier.padding(start = 12.dp, top = 12.dp, end = 4.dp, bottom = 4.dp)) {
            Row(Modifier.padding(end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Image(
                    bitmap = notice.preview.asImageBitmap(),
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .size(64.dp)
                        .clip(RoundedCornerShape(10.dp))
                )
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        stringResource(R.string.player_screenshot_saved),
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = colors.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        stringResource(R.string.player_screenshot_location, ScreenshotStore.DISPLAY_PATH),
                        style = MaterialTheme.typography.labelMedium,
                        color = colors.secondaryText,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = onOpen) {
                    Text(stringResource(R.string.player_screenshot_open), color = colors.primary, fontWeight = FontWeight.SemiBold)
                }
                TextButton(onClick = onShare) {
                    Text(stringResource(R.string.player_screenshot_share), color = colors.primary, fontWeight = FontWeight.SemiBold)
                }
            }
        }
    }
}
