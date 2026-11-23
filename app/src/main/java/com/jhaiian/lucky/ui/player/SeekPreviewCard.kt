package com.jhaiian.lucky.ui.player

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import com.jhaiian.lucky.ui.home.formatDuration
import com.jhaiian.lucky.ui.theme.LocalLuckyColors
import kotlin.math.roundToInt

private const val EXPANDED_WIDTH_DP = 600

internal fun stripPositions(durationMs: Long): List<Long> {
    val count = (durationMs / 20_000L).coerceIn(4L, 40L).toInt()
    val step = durationMs / count
    val order = LinkedHashSet<Int>()
    var stride = 1
    while (stride < count) stride *= 2
    while (stride >= 1) {
        for (index in 0 until count step stride) order.add(index)
        stride /= 2
    }
    return order.map { it * step + step / 2 }
}

@Composable
internal fun SeekPreviewCard(
    bitmap: Bitmap?,
    positionMs: Long,
    anchor: Float,
    topLimit: Float,
    modifier: Modifier = Modifier
) {
    val colors = LocalLuckyColors.current
    val expanded = LocalConfiguration.current.screenWidthDp >= EXPANDED_WIDTH_DP
    val gap = with(LocalDensity.current) { 10.dp.roundToPx() }
    val outer = RoundedCornerShape(if (expanded) 20.dp else 16.dp)
    val inner = RoundedCornerShape(if (expanded) 14.dp else 11.dp)
    val image = remember(bitmap) { bitmap?.asImageBitmap() }
    val aspect = if (bitmap != null && bitmap.height > 0) bitmap.width.toFloat() / bitmap.height else 16f / 9f
    val imageHeight = if (expanded) 128.dp else 96.dp
    val imageWidth = minOf(imageHeight * aspect, if (expanded) 300.dp else 220.dp)
    Box(
        modifier.layout { measurable, constraints ->
            val placeable = measurable.measure(Constraints(maxWidth = constraints.maxWidth))
            val room = (constraints.maxWidth - placeable.width).coerceAtLeast(0).toFloat()
            val x = (anchor - placeable.width / 2f).coerceIn(0f, room).roundToInt()
            val y = (-placeable.height - gap).coerceAtLeast(-topLimit.roundToInt())
            layout(0, 0) { placeable.place(x, y) }
        }
    ) {
        Column(
            Modifier
                .clip(outer)
                .background(Color.Black.copy(alpha = 0.78f))
                .border(1.dp, colors.primary.copy(alpha = 0.55f), outer)
                .padding(6.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Box(
                Modifier
                    .size(imageWidth, imageHeight)
                    .clip(inner)
                    .background(Color.White.copy(alpha = 0.08f)),
                contentAlignment = Alignment.Center
            ) {
                if (image != null) {
                    Image(
                        bitmap = image,
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.matchParentSize()
                    )
                } else {
                    CircularProgressIndicator(color = colors.primary, strokeWidth = 2.dp, modifier = Modifier.size(20.dp))
                }
            }
            Text(
                text = formatDuration(positionMs),
                color = Color.White,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(top = 5.dp, bottom = 1.dp)
            )
        }
    }
}
