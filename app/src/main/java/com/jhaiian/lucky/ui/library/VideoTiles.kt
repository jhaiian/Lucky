package com.jhaiian.lucky.ui.library

import android.net.Uri
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.Movie
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jhaiian.lucky.R
import com.jhaiian.lucky.data.VideoItem
import com.jhaiian.lucky.data.VideoThumbnails
import com.jhaiian.lucky.data.WatchHistory
import com.jhaiian.lucky.ui.components.LoadingShimmer
import com.jhaiian.lucky.ui.home.formatDuration
import com.jhaiian.lucky.ui.theme.LocalLuckyColors

const val THUMB_ASPECT = 16f / 9f

@Composable
fun VideoThumbnail(uri: String, corner: Dp, modifier: Modifier = Modifier, progress: Float = 0f, fallbackIcon: ImageVector = Icons.Rounded.Movie, iconSize: Dp = 26.dp) {
    val colors = LocalLuckyColors.current
    val context = LocalContext.current
    val parsed = Uri.parse(uri)
    val state by produceState<Pair<Boolean, ImageBitmap?>>(
        VideoThumbnails.cached(parsed)?.let { true to it.asImageBitmap() } ?: (false to null),
        uri
    ) {
        if (!value.first) value = true to VideoThumbnails.load(context, parsed)?.asImageBitmap()
    }
    val image = state.second
    val loading = !state.first
    val history by WatchHistory.entries(context).collectAsState()
    val watched = history[uri]?.finished == true
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(corner))
            .background(colors.surfaceVariant),
        contentAlignment = Alignment.Center
    ) {
        val current = image
        if (current != null) {
            Image(current, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        } else if (loading) {
            LoadingShimmer()
        } else {
            Box(
                Modifier
                    .fillMaxSize()
                    .background(colors.primary.copy(alpha = 0.14f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(fallbackIcon, contentDescription = null, tint = colors.primary, modifier = Modifier.size(iconSize))
            }
        }
        if (watched) {
            Box(
                Modifier
                    .align(Alignment.TopEnd)
                    .padding(6.dp)
                    .size(22.dp)
                    .clip(CircleShape)
                    .background(colors.primary),
                contentAlignment = Alignment.Center
            ) {
                Icon(Icons.Rounded.Check, contentDescription = stringResource(R.string.library_watched), tint = colors.onPrimary, modifier = Modifier.size(15.dp))
            }
        } else if (progress > 0f) {
            ProgressStrip(progress, Modifier.align(Alignment.BottomCenter))
        }
    }
}

@Composable
fun ProgressStrip(progress: Float, modifier: Modifier = Modifier, height: Dp = 3.dp) {
    val colors = LocalLuckyColors.current
    Box(
        modifier
            .fillMaxWidth()
            .height(height)
            .background(colors.primary.copy(alpha = 0.16f))
    ) {
        Box(
            Modifier
                .fillMaxWidth(progress.coerceIn(0f, 1f))
                .height(height)
                .background(colors.primary)
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun CarouselVideoCard(
    item: VideoItem?,
    uri: String,
    title: String,
    subtitle: String,
    progress: Float,
    width: Dp,
    corner: Dp,
    onLongClick: (() -> Unit)? = null,
    onClick: () -> Unit
) {
    val colors = LocalLuckyColors.current
    Column(
        Modifier
            .width(width)
            .clip(RoundedCornerShape(corner))
            .combinedClickable(onLongClick = onLongClick, onClick = onClick)
    ) {
        VideoThumbnail(
            uri = item?.uri ?: uri,
            corner = corner,
            progress = progress,
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(THUMB_ASPECT)
        )
        Text(
            title,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.SemiBold,
            color = colors.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(start = 2.dp, end = 2.dp, top = 8.dp)
        )
        if (subtitle.isNotEmpty()) {
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = colors.secondaryText,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(horizontal = 2.dp)
            )
        }
    }
}
