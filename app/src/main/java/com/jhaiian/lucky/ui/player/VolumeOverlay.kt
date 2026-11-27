package com.jhaiian.lucky.ui.player

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.VolumeDown
import androidx.compose.material.icons.automirrored.rounded.VolumeOff
import androidx.compose.material.icons.automirrored.rounded.VolumeUp
import androidx.compose.material.icons.rounded.BrightnessHigh
import androidx.compose.material.icons.rounded.BrightnessLow
import androidx.compose.material.icons.rounded.BrightnessMedium
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.AbsoluteAlignment
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.jhaiian.lucky.R
import com.jhaiian.lucky.ui.theme.LocalLuckyColors
import kotlin.math.roundToInt

const val VOLUME_BOOST_MAX = 3f
const val VOLUME_BOOST_STEP = 0.1f

data class VolumeIndicator(val level: Float, val boost: Float)

@Composable
fun BoxScope.VolumeOverlay(indicator: VolumeIndicator?) {
    val colors = LocalLuckyColors.current
    val holder = remember { LastValue<VolumeIndicator>() }
    if (indicator != null) holder.last = indicator
    val current = holder.last
    val boosting = (current?.boost ?: 1f) > 1.001f
    val level = (current?.level ?: 0f).coerceIn(0f, 1f)
    val icon = when {
        boosting -> Icons.AutoMirrored.Rounded.VolumeUp
        level <= 0f -> Icons.AutoMirrored.Rounded.VolumeOff
        level < 0.5f -> Icons.AutoMirrored.Rounded.VolumeDown
        else -> Icons.AutoMirrored.Rounded.VolumeUp
    }
    SideLevelPanel(
        visible = indicator != null,
        onLeft = false,
        percent = (((if (boosting) current?.boost else current?.level) ?: 0f) * 100).roundToInt(),
        levelFill = if (boosting) 1f else level,
        extraFill = if (boosting) (((current?.boost ?: 1f) - 1f) / (VOLUME_BOOST_MAX - 1f)).coerceIn(0f, 1f) else 0f,
        badge = if (boosting) stringResource(R.string.player_volume_boost_badge) else null,
        icon = icon,
        accent = if (boosting) colors.colorError else colors.primary
    )
}

@Composable
fun BoxScope.BrightnessOverlay(level: Float?) {
    val colors = LocalLuckyColors.current
    val holder = remember { LastValue<Float>() }
    if (level != null) holder.last = level
    val shown = (holder.last ?: 0f).coerceIn(0f, 1f)
    val icon = when {
        shown < 0.34f -> Icons.Rounded.BrightnessLow
        shown < 0.67f -> Icons.Rounded.BrightnessMedium
        else -> Icons.Rounded.BrightnessHigh
    }
    SideLevelPanel(
        visible = level != null,
        onLeft = true,
        percent = (shown * 100).roundToInt(),
        levelFill = shown,
        extraFill = 0f,
        badge = null,
        icon = icon,
        accent = colors.primary
    )
}

@Composable
private fun BoxScope.SideLevelPanel(
    visible: Boolean,
    onLeft: Boolean,
    percent: Int,
    levelFill: Float,
    extraFill: Float,
    badge: String?,
    icon: ImageVector,
    accent: Color
) {
    val colors = LocalLuckyColors.current
    val trackHeight = (LocalConfiguration.current.screenHeightDp * 0.34f).coerceIn(96f, 220f).dp
    val level by animateFloatAsState(levelFill.coerceIn(0f, 1f), tween(140), label = "sideLevel")
    val extra by animateFloatAsState(extraFill.coerceIn(0f, 1f), tween(140), label = "sideExtra")
    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(tween(120)) + slideInHorizontally(tween(180)) { if (onLeft) -it / 2 else it / 2 },
        exit = fadeOut(tween(260)) + slideOutHorizontally(tween(260)) { if (onLeft) -it / 2 else it / 2 },
        modifier = Modifier
            .align(if (onLeft) AbsoluteAlignment.CenterLeft else AbsoluteAlignment.CenterRight)
            .padding(horizontal = 28.dp)
    ) {
        Column(
            Modifier
                .widthIn(min = 60.dp)
                .clip(RoundedCornerShape(30.dp))
                .background(Color.Black.copy(alpha = 0.62f))
                .padding(horizontal = 10.dp, vertical = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                "$percent%",
                color = Color.White,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center
            )
            if (badge != null) {
                Text(
                    badge,
                    color = accent,
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center,
                    maxLines = 1
                )
            }
            Spacer(Modifier.height(12.dp))
            Box(
                Modifier
                    .width(8.dp)
                    .height(trackHeight)
                    .clip(RoundedCornerShape(4.dp))
                    .background(Color.White.copy(alpha = 0.25f)),
                contentAlignment = Alignment.BottomCenter
            ) {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .fillMaxHeight(level)
                        .background(colors.primary)
                )
                Box(
                    Modifier
                        .fillMaxWidth()
                        .fillMaxHeight(extra)
                        .background(colors.colorError)
                )
            }
            Spacer(Modifier.height(12.dp))
            Icon(icon, contentDescription = null, tint = if (badge != null) accent else Color.White, modifier = Modifier.size(22.dp))
        }
    }
}

private class LastValue<T> {
    var last: T? = null
}
