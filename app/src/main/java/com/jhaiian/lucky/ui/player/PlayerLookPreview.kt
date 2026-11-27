package com.jhaiian.lucky.ui.player

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.jhaiian.lucky.R
import com.jhaiian.lucky.engine.Chapter
import com.jhaiian.lucky.engine.PlaybackState
import com.jhaiian.lucky.engine.PlayerStatus
import com.jhaiian.lucky.ui.theme.LocalLuckyColors

private const val PREVIEW_DURATION_MS = 5_532_000L
private val PreviewChapters = listOf(
    Chapter(0L, 1_840_000L, null),
    Chapter(1_840_000L, 3_710_000L, null),
    Chapter(3_710_000L, null, null)
)

internal val PreviewBackdrop = Brush.linearGradient(listOf(Color(0xFF2B2F4F), Color(0xFF151726)))

@Composable
internal fun PreviewScene(modifier: Modifier = Modifier) {
    Canvas(modifier.fillMaxSize()) {
        val w = size.width
        val h = size.height
        drawRect(
            Brush.verticalGradient(
                0f to Color(0xFF1B1B3A),
                0.45f to Color(0xFF5E3A78),
                0.75f to Color(0xFFD9705E),
                1f to Color(0xFFF2A65A)
            )
        )
        val sun = Offset(w * 0.68f, h * 0.62f)
        val glow = h * 0.5f
        drawCircle(
            Brush.radialGradient(
                0f to Color(0xFFFFE3A3).copy(alpha = 0.85f),
                1f to Color.Transparent,
                center = sun,
                radius = glow
            ),
            radius = glow,
            center = sun
        )
        drawCircle(Color(0xFFFFD58A), radius = h * 0.11f, center = sun)
        val far = Path().apply {
            moveTo(0f, h * 0.72f)
            lineTo(w * 0.18f, h * 0.57f)
            lineTo(w * 0.34f, h * 0.7f)
            lineTo(w * 0.52f, h * 0.54f)
            lineTo(w * 0.74f, h * 0.72f)
            lineTo(w * 0.9f, h * 0.6f)
            lineTo(w, h * 0.67f)
            lineTo(w, h)
            lineTo(0f, h)
            close()
        }
        drawPath(far, Color(0xFF3B2A4F))
        val near = Path().apply {
            moveTo(0f, h * 0.86f)
            quadraticTo(w * 0.25f, h * 0.73f, w * 0.5f, h * 0.86f)
            quadraticTo(w * 0.75f, h * 0.97f, w, h * 0.81f)
            lineTo(w, h)
            lineTo(0f, h)
            close()
        }
        drawPath(near, Color(0xFF1E1530))
    }
}

@Composable
internal fun PlayerLookPreview(
    look: PlayerLook,
    quickButtons: Set<String>,
    quickOrder: List<QuickButton>,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null
) {
    val colors = LocalLuckyColors.current
    val palette = rememberPlayerPalette(look.iconTint, look.accentTint)
    val chrome = remember(look.buttonStyle, look.playShape, look.glow) { PlayerChromeStyle(look.buttonStyle, look.playShape, look.glow) }
    var playing by remember { mutableStateOf(true) }
    var positionMs by remember { mutableLongStateOf(2_412_000L) }
    var scrub by remember { mutableStateOf<Float?>(null) }
    var showRemaining by remember { mutableStateOf(false) }
    var subtitlesOn by remember { mutableStateOf(true) }
    val docked = look.layout == ControlsLayout.DOCKED
    val status = PlayerStatus(
        state = if (playing) PlaybackState.PLAYING else PlaybackState.PAUSED,
        positionMs = positionMs,
        durationMs = PREVIEW_DURATION_MS,
        seekable = true,
        chapters = PreviewChapters
    )
    val quickState = QuickButtonState(
        speed = 1f,
        aspectActive = false,
        repeat = RepeatMode.OFF,
        mirror = false,
        rotated = false,
        loopStartMs = null,
        loopEndMs = null,
        interpolating = true
    )
    val shape = RoundedCornerShape(24.dp)
    val title = stringResource(R.string.settings_player_preview_title)
    val chapter = stringResource(R.string.player_chapter_number, 2)
    BoxWithConstraints(
        modifier
            .aspectRatio(16f / 9f)
            .clip(shape)
            .border(1.dp, colors.divider, shape)
    ) {
        val factor = (maxWidth / 640.dp).coerceAtMost(1f)
        val density = LocalDensity.current
        CompositionLocalProvider(LocalDensity provides Density(density.density * factor, density.fontScale)) {
            Box(Modifier.fillMaxSize()) {
                PreviewScene()
                PlayerScrim(look.scrim, docked)
                CompositionLocalProvider(LocalPlayerPalette provides palette, LocalPlayerChromeStyle provides chrome) {
                    val scale = look.controlsSize.scale
                    val toggle: () -> Unit = { playing = !playing }
                    val seekRow: @Composable () -> Unit = {
                        PlayerSeekRow(
                            status = status,
                            scrubFraction = scrub,
                            showRemaining = showRemaining,
                            style = look.seekStyle,
                            timeLayout = look.timeLayout,
                            onToggleRemaining = { showRemaining = !showRemaining },
                            onScrub = { scrub = it },
                            onScrubFinished = {
                                scrub?.let { positionMs = (PREVIEW_DURATION_MS * it).toLong() }
                                scrub = null
                            }
                        )
                    }
                    val quickRow: @Composable (Modifier, Alignment) -> Unit = { rowModifier, rowAlignment ->
                        PlayerQuickButtons(
                            buttons = quickButtons,
                            order = quickOrder,
                            state = quickState,
                            pipAvailable = true,
                            screenshotAvailable = true,
                            onAspect = {},
                            onRotate = {},
                            onSpeed = {},
                            onRepeat = {},
                            onMirror = {},
                            onPip = {},
                            onBackground = {},
                            onMotion = {},
                            onLoop = {},
                            onScreenshot = {},
                            modifier = rowModifier,
                            alignment = rowAlignment
                        )
                    }
                    Column(Modifier.fillMaxSize()) {
                        PlayerTopBar(
                            title = title,
                            subtitle = chapter,
                            clock = null,
                            compact = false,
                            onBack = {},
                            onSubtitles = { subtitlesOn = !subtitlesOn },
                            subtitlesOn = subtitlesOn,
                            onAudio = {},
                            onChapters = null,
                            onQuickSettings = {},
                            onStats = null,
                            statsOn = false
                        )
                        Box(
                            Modifier
                                .weight(1f)
                                .fillMaxWidth()
                        ) {
                            if (!docked) {
                                PlayerCenterControls(
                                    state = status.state,
                                    scale = scale,
                                    seekStepMs = 10_000L,
                                    showSeekButtons = true,
                                    onToggle = toggle,
                                    onRewind = { positionMs = (positionMs - 10_000L).coerceAtLeast(0L) },
                                    onForward = { positionMs = (positionMs + 10_000L).coerceAtMost(PREVIEW_DURATION_MS) },
                                    onPrevious = null,
                                    onNext = null,
                                    modifier = Modifier.align(Alignment.Center)
                                )
                            }
                            if (QuickButton.LOCK.key in quickButtons) {
                                PlayerSideLock(
                                    onLock = {},
                                    modifier = Modifier
                                        .align(Alignment.CenterEnd)
                                        .padding(end = 16.dp)
                                )
                            }
                        }
                        if (docked) {
                            PlayerPanel(
                                Modifier
                                    .padding(start = 12.dp, end = 12.dp, bottom = 8.dp)
                                    .fillMaxWidth()
                            ) {
                                Column(
                                    Modifier
                                        .fillMaxWidth()
                                        .padding(top = 6.dp, bottom = 4.dp)
                                ) {
                                    seekRow()
                                    Row(
                                        Modifier
                                            .fillMaxWidth()
                                            .padding(horizontal = 6.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        PlayerCenterControls(
                                            state = status.state,
                                            scale = scale,
                                            seekStepMs = 10_000L,
                                            showSeekButtons = true,
                                            onToggle = toggle,
                                            onRewind = { positionMs = (positionMs - 10_000L).coerceAtLeast(0L) },
                                            onForward = { positionMs = (positionMs + 10_000L).coerceAtMost(PREVIEW_DURATION_MS) },
                                            onPrevious = null,
                                            onNext = null,
                                            docked = true
                                        )
                                        quickRow(Modifier.weight(1f), Alignment.CenterEnd)
                                    }
                                }
                            }
                        } else {
                            seekRow()
                            quickRow(
                                Modifier
                                    .fillMaxWidth()
                                    .padding(bottom = 6.dp),
                                Alignment.CenterEnd
                            )
                        }
                    }
                }
            }
        }
        if (onClick != null) {
            Box(
                Modifier
                    .matchParentSize()
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        role = Role.Button,
                        onClick = onClick
                    )
            )
        }
    }
}
