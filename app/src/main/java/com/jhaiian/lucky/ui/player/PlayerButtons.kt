package com.jhaiian.lucky.ui.player

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Replay
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.jhaiian.lucky.R
import com.jhaiian.lucky.engine.PlaybackState

@Immutable
internal data class PlayerChromeStyle(val buttonStyle: ButtonStyle, val playShape: PlayShape, val glow: Boolean)

internal val LocalPlayerChromeStyle = compositionLocalOf { PlayerChromeStyle(ButtonStyle.GLASS, PlayShape.ROUNDED, true) }

private val LocalInGroup = compositionLocalOf { false }

internal val GlassFill = Color(0xFF0D0D12).copy(alpha = 0.5f)
internal val GlassEdge = Color.White.copy(alpha = 0.14f)
internal val BubbleFill = Color.Black.copy(alpha = 0.4f)
internal val ChromeShadow = Shadow(Color.Black.copy(alpha = 0.6f), Offset(0f, 1.5f), 6f)

internal enum class ChromeEdge { TOP, CENTER, BOTTOM }

internal fun chromeTextStyle(base: TextStyle): TextStyle = base.copy(shadow = ChromeShadow, fontFeatureSettings = "tnum")

@Composable
internal fun Modifier.chromeSurface(shape: Shape, forced: Boolean = false): Modifier {
    val style = LocalPlayerChromeStyle.current.buttonStyle
    return when {
        style == ButtonStyle.GLASS -> this.clip(shape).background(GlassFill).border(1.dp, GlassEdge, shape)
        style == ButtonStyle.BUBBLE || forced -> this.clip(shape).background(BubbleFill)
        else -> this
    }
}

@Composable
internal fun PlayerButton(
    icon: ImageVector,
    description: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    active: Boolean = false,
    size: Float = 1f
) {
    val palette = LocalPlayerPalette.current
    val style = LocalPlayerChromeStyle.current.buttonStyle
    val grouped = LocalInGroup.current
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(
        if (pressed) 0.84f else 1f,
        spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessMedium),
        label = "press"
    )
    val tint by animateColorAsState(if (active) palette.active else palette.icon, tween(200), label = "tint")
    val fill = when {
        active && style != ButtonStyle.PLAIN -> palette.active.copy(alpha = 0.22f)
        pressed -> Color.White.copy(alpha = 0.16f)
        grouped || style == ButtonStyle.PLAIN -> Color.Transparent
        style == ButtonStyle.BUBBLE -> BubbleFill
        else -> GlassFill
    }
    val background by animateColorAsState(fill, tween(180), label = "fill")
    val edged = style == ButtonStyle.GLASS && !grouped
    Box(
        modifier
            .size((48 * size).dp)
            .clickable(interactionSource = interaction, indication = null, role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Box(
            Modifier
                .size((40 * size).dp)
                .graphicsLayer {
                    scaleX = scale
                    scaleY = scale
                }
                .clip(CircleShape)
                .background(background)
                .then(if (edged) Modifier.border(1.dp, GlassEdge, CircleShape) else Modifier),
            contentAlignment = Alignment.Center
        ) {
            Icon(icon, contentDescription = description, tint = tint, modifier = Modifier.size((24 * size).dp))
        }
    }
}

@Composable
internal fun PlayerTextButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    description: String? = null,
    active: Boolean = false,
    size: Float = 1f
) {
    val palette = LocalPlayerPalette.current
    val style = LocalPlayerChromeStyle.current.buttonStyle
    val grouped = LocalInGroup.current
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(
        if (pressed) 0.88f else 1f,
        spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessMedium),
        label = "press"
    )
    val tint by animateColorAsState(if (active) palette.active else palette.icon, tween(200), label = "tint")
    val fill = when {
        active && style != ButtonStyle.PLAIN -> palette.active.copy(alpha = 0.22f)
        pressed -> Color.White.copy(alpha = 0.16f)
        grouped || style == ButtonStyle.PLAIN -> Color.Transparent
        style == ButtonStyle.BUBBLE -> BubbleFill
        else -> GlassFill
    }
    val background by animateColorAsState(fill, tween(180), label = "fill")
    val shape = RoundedCornerShape(50)
    val edged = style == ButtonStyle.GLASS && !grouped
    Box(
        modifier
            .heightIn(min = (48 * size).dp)
            .then(if (description != null) Modifier.semantics { contentDescription = description } else Modifier)
            .clickable(interactionSource = interaction, indication = null, role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Box(
            Modifier
                .height((40 * size).dp)
                .graphicsLayer {
                    scaleX = scale
                    scaleY = scale
                }
                .clip(shape)
                .background(background)
                .then(if (edged) Modifier.border(1.dp, GlassEdge, shape) else Modifier)
                .padding(horizontal = (12 * size).dp),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = text,
                color = tint,
                style = chromeTextStyle(MaterialTheme.typography.labelLarge),
                fontWeight = FontWeight.Bold,
                maxLines = 1
            )
        }
    }
}

@Composable
internal fun PlayerButtonGroup(
    modifier: Modifier = Modifier,
    content: @Composable RowScope.() -> Unit
) {
    val style = LocalPlayerChromeStyle.current.buttonStyle
    val nested = LocalInGroup.current
    if (style == ButtonStyle.GLASS && !nested) {
        val shape = RoundedCornerShape(50)
        CompositionLocalProvider(LocalInGroup provides true) {
            Row(
                modifier
                    .clip(shape)
                    .background(GlassFill)
                    .border(1.dp, GlassEdge, shape)
                    .padding(horizontal = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
                content = content
            )
        }
    } else {
        Row(
            modifier,
            horizontalArrangement = Arrangement.spacedBy(if (style == ButtonStyle.BUBBLE && !nested) 4.dp else 0.dp),
            verticalAlignment = Alignment.CenterVertically,
            content = content
        )
    }
}

@Composable
internal fun PlayerPill(
    modifier: Modifier = Modifier,
    shape: Shape = RoundedCornerShape(50),
    inner: Dp = 2.dp,
    content: @Composable RowScope.() -> Unit
) {
    val glass = LocalPlayerChromeStyle.current.buttonStyle == ButtonStyle.GLASS
    CompositionLocalProvider(LocalInGroup provides true) {
        Row(
            modifier
                .clip(shape)
                .background(if (glass) GlassFill else BubbleFill)
                .then(if (glass) Modifier.border(1.dp, GlassEdge, shape) else Modifier)
                .padding(horizontal = inner),
            verticalAlignment = Alignment.CenterVertically,
            content = content
        )
    }
}

@Composable
internal fun PlayerPanel(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit
) {
    val glass = LocalPlayerChromeStyle.current.buttonStyle == ButtonStyle.GLASS
    if (glass) {
        val shape = RoundedCornerShape(28.dp)
        CompositionLocalProvider(LocalInGroup provides true) {
            Box(
                modifier
                    .background(GlassFill, shape)
                    .border(1.dp, GlassEdge, shape)
            ) {
                content()
            }
        }
    } else {
        Box(modifier) { content() }
    }
}

@Composable
internal fun PlayPauseButton(
    state: PlaybackState,
    onClick: () -> Unit,
    diameter: Dp,
    modifier: Modifier = Modifier
) {
    val palette = LocalPlayerPalette.current
    val chrome = LocalPlayerChromeStyle.current
    val playing = state == PlaybackState.PLAYING || state == PlaybackState.BUFFERING
    val (icon, label) = when {
        state == PlaybackState.ENDED -> Icons.Rounded.Replay to stringResource(R.string.player_replay)
        playing -> Icons.Rounded.Pause to stringResource(R.string.player_pause)
        else -> Icons.Rounded.PlayArrow to stringResource(R.string.player_play)
    }
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(
        if (pressed) 0.88f else 1f,
        spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessMedium),
        label = "press"
    )
    val corner by animateDpAsState(
        when {
            chrome.playShape == PlayShape.ROUNDED && !playing -> diameter * 0.3f
            else -> diameter / 2f
        },
        spring(dampingRatio = 0.72f, stiffness = Spring.StiffnessMediumLow),
        label = "corner"
    )
    val glowTarget = when {
        chrome.playShape == PlayShape.BARE -> 0f
        !chrome.glow -> 0f
        playing -> 0.34f
        else -> 0.52f
    }
    val glow by animateFloatAsState(glowTarget, tween(420), label = "glow")
    val shape = RoundedCornerShape(corner)
    val accent = palette.accent
    val (fill, content) = when (chrome.playShape) {
        PlayShape.ROUNDED, PlayShape.CIRCLE -> accent to palette.onAccent
        PlayShape.RING -> GlassFill to palette.icon
        PlayShape.BARE -> Color.Transparent to palette.icon
    }
    val iconSize = if (chrome.playShape == PlayShape.BARE) diameter * 0.78f else diameter * 0.54f
    Box(
        modifier
            .size(diameter)
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .drawBehind {
                val radius = size.minDimension * 0.98f
                if (chrome.playShape == PlayShape.BARE) {
                    drawCircle(
                        Brush.radialGradient(
                            0f to Color.Black.copy(alpha = 0.32f),
                            1f to Color.Transparent,
                            center = center,
                            radius = radius
                        ),
                        radius = radius
                    )
                } else if (glow > 0f) {
                    drawCircle(
                        Brush.radialGradient(
                            0f to accent.copy(alpha = glow),
                            0.55f to accent.copy(alpha = glow * 0.35f),
                            1f to Color.Transparent,
                            center = center,
                            radius = radius
                        ),
                        radius = radius
                    )
                }
            }
            .clip(shape)
            .background(fill)
            .then(if (chrome.playShape == PlayShape.RING) Modifier.border(2.5.dp, accent, shape) else Modifier)
            .clickable(interactionSource = interaction, indication = null, role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        AnimatedContent(
            targetState = icon,
            transitionSpec = {
                (scaleIn(spring(dampingRatio = 0.6f, stiffness = Spring.StiffnessMedium), initialScale = 0.5f) + fadeIn(tween(140))) togetherWith
                    (scaleOut(tween(120), targetScale = 0.5f) + fadeOut(tween(100)))
            },
            contentAlignment = Alignment.Center,
            label = "playIcon"
        ) { current ->
            Icon(current, contentDescription = label, tint = content, modifier = Modifier.size(iconSize))
        }
    }
}

@Composable
internal fun PlayerScrim(level: ScrimLevel, docked: Boolean, modifier: Modifier = Modifier) {
    if (level == ScrimLevel.NONE) return
    val strength = level.strength
    val top = (0.62f * strength).coerceAtMost(0.9f)
    val bottom = (0.8f * strength).coerceAtMost(0.94f)
    Box(
        modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    0f to Color.Black.copy(alpha = top),
                    0.24f to Color.Transparent,
                    (if (docked) 0.52f else 0.6f) to Color.Transparent,
                    1f to Color.Black.copy(alpha = bottom)
                )
            )
            .then(if (strength > 1f) Modifier.background(Color.Black.copy(alpha = (strength - 1f) * 0.4f)) else Modifier)
    )
}

@Composable
internal fun AnimatedVisibilityScope.chromeMotion(motion: ControlsMotion, edge: ChromeEdge): Modifier = when (motion) {
    ControlsMotion.FADE -> Modifier
    ControlsMotion.SLIDE -> when (edge) {
        ChromeEdge.TOP -> Modifier.animateEnterExit(
            enter = slideInVertically(spring(dampingRatio = 0.86f, stiffness = Spring.StiffnessMediumLow)) { -it },
            exit = slideOutVertically(tween(200)) { -it }
        )
        ChromeEdge.BOTTOM -> Modifier.animateEnterExit(
            enter = slideInVertically(spring(dampingRatio = 0.86f, stiffness = Spring.StiffnessMediumLow)) { it },
            exit = slideOutVertically(tween(200)) { it }
        )
        ChromeEdge.CENTER -> Modifier.animateEnterExit(
            enter = scaleIn(spring(dampingRatio = 0.8f, stiffness = Spring.StiffnessMediumLow), initialScale = 0.9f),
            exit = scaleOut(tween(180), targetScale = 0.92f)
        )
    }
    ControlsMotion.POP -> when (edge) {
        ChromeEdge.TOP -> Modifier.animateEnterExit(
            enter = slideInVertically(spring(dampingRatio = 0.6f, stiffness = Spring.StiffnessMedium)) { -it / 3 },
            exit = slideOutVertically(tween(160)) { -it / 4 }
        )
        ChromeEdge.BOTTOM -> Modifier.animateEnterExit(
            enter = slideInVertically(spring(dampingRatio = 0.6f, stiffness = Spring.StiffnessMedium)) { it / 3 },
            exit = slideOutVertically(tween(160)) { it / 4 }
        )
        ChromeEdge.CENTER -> Modifier.animateEnterExit(
            enter = scaleIn(spring(dampingRatio = 0.5f, stiffness = Spring.StiffnessMedium), initialScale = 0.55f),
            exit = scaleOut(tween(160), targetScale = 0.8f)
        )
    }
}
