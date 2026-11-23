package com.jhaiian.lucky.ui.library

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.layoutId
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jhaiian.lucky.ui.theme.LocalLuckyColors
import kotlin.math.ceil
import kotlin.math.roundToInt

private const val TileLineHeight = 1.3f

internal val ListMaxWidth = 900.dp
internal val ListEndSpacer = 24.dp
internal val GridHorizontalPadding = 16.dp
private val ListPaddingH = 20.dp
private val ListSubtitleGap = 2.dp
private val MetaChipGap = 4.dp
private val GridTextInset = 8.dp
private val GridTextBottom = 7.dp
private val GridScrimPadding = 18.dp
private val ListProgressHeight = 2.dp
private val GridProgressHeight = 3.dp

private val ListArtHeights = intArrayOf(0, 36, 45, 54, 68, 86, 104)
private val ListArtCorners = intArrayOf(0, 8, 10, 12, 15, 18, 20)
private val ListPaddingV = intArrayOf(2, 2, 3, 5, 8, 12, 14)
private val ListArtSpacing = intArrayOf(0, 8, 10, 12, 14, 18, 20)
private val ListTrailingSpacing = intArrayOf(6, 6, 8, 10, 12, 14, 16)
private val ListTitleSizes = intArrayOf(12, 12, 14, 16, 18, 20, 22)
private val ListSubtitleSizes = intArrayOf(10, 10, 11, 12, 14, 15, 16)
private val ListChipSizes = intArrayOf(8, 8, 9, 10, 11, 12, 13)
private val ListChipPaddingH = intArrayOf(4, 4, 5, 6, 8, 10, 11)
private val ListChipPaddingV = intArrayOf(1, 1, 1, 2, 3, 4, 5)
private val ListIconSizes = intArrayOf(14, 14, 18, 22, 26, 30, 34)
private val GridMinSizes = intArrayOf(220, 160, 116)
private val GridCorners = intArrayOf(16, 12, 8)
private val GridSpacings = intArrayOf(12, 9, 6)
private val GridTitleSizes = intArrayOf(14, 13, 11)
private val GridSubtitleSizes = intArrayOf(12, 11, 10)
private val GridIconSizes = intArrayOf(34, 28, 22)

private val TileLineHeightStyle = LineHeightStyle(
    alignment = LineHeightStyle.Alignment.Center,
    trim = LineHeightStyle.Trim.None
)

internal val StaticFraction: () -> Float = { 0f }

private const val ArtId = "art"
private const val ScrimId = "scrim"
private const val TextFromId = "text_from"
private const val TextToId = "text_to"
private const val MetaFromId = "meta_from"
private const val MetaToId = "meta_to"

internal fun mix(start: Float, stop: Float, fraction: Float): Float = start + (stop - start) * fraction

internal class TileStyle(
    val isGrid: Boolean,
    val artWidth: Dp,
    val artHeight: Dp,
    val artCorner: Dp,
    val paddingH: Dp,
    val paddingV: Dp,
    val artSpacing: Dp,
    val trailingSpacing: Dp,
    val titleSize: TextUnit,
    val subtitleSize: TextUnit,
    val durationSize: TextUnit,
    val chipSize: TextUnit,
    val chipPaddingH: Dp,
    val chipPaddingV: Dp,
    val iconSize: Dp,
    val gridMinSize: Dp,
    val gridSpacing: Dp
) {
    val containerCorner: Dp get() = if (isGrid) artCorner else 0.dp
}

internal class TileFrame(val style: TileStyle, val width: Int, val height: Int)

private fun buildTileStyle(mode: ViewMode, cornerScale: Float): TileStyle = if (mode.grid) {
    val level = mode.level.coerceIn(0, 2)
    TileStyle(
        isGrid = true,
        artWidth = 0.dp,
        artHeight = 0.dp,
        artCorner = (GridCorners[level] * cornerScale).dp,
        paddingH = GridTextInset,
        paddingV = 0.dp,
        artSpacing = 0.dp,
        trailingSpacing = 0.dp,
        titleSize = GridTitleSizes[level].sp,
        subtitleSize = GridSubtitleSizes[level].sp,
        durationSize = GridSubtitleSizes[level].sp,
        chipSize = GridSubtitleSizes[level].sp,
        chipPaddingH = 0.dp,
        chipPaddingV = 0.dp,
        iconSize = GridIconSizes[level].dp,
        gridMinSize = GridMinSizes[level].dp,
        gridSpacing = GridSpacings[level].dp
    )
} else {
    val level = mode.level.coerceIn(0, 6)
    TileStyle(
        isGrid = false,
        artWidth = (ListArtHeights[level] * THUMB_ASPECT).dp,
        artHeight = ListArtHeights[level].dp,
        artCorner = (ListArtCorners[level] * cornerScale).dp,
        paddingH = ListPaddingH,
        paddingV = ListPaddingV[level].dp,
        artSpacing = ListArtSpacing[level].dp,
        trailingSpacing = ListTrailingSpacing[level].dp,
        titleSize = ListTitleSizes[level].sp,
        subtitleSize = ListSubtitleSizes[level].sp,
        durationSize = ListSubtitleSizes[level].sp,
        chipSize = ListChipSizes[level].sp,
        chipPaddingH = ListChipPaddingH[level].dp,
        chipPaddingV = ListChipPaddingV[level].dp,
        iconSize = ListIconSizes[level].dp,
        gridMinSize = 0.dp,
        gridSpacing = 0.dp
    )
}

private val TileStyles = HashMap<Pair<ViewMode, Float>, TileStyle>()

internal fun tileStyleFor(mode: ViewMode, cornerScale: Float = 1f): TileStyle =
    TileStyles.getOrPut(mode to cornerScale) { buildTileStyle(mode, cornerScale) }

private fun TextStyle.tileSized(size: TextUnit): TextStyle = copy(
    fontSize = size,
    lineHeight = (size.value * TileLineHeight).sp,
    lineHeightStyle = TileLineHeightStyle
)

private fun Density.lineHeightPx(size: TextUnit): Int = ceil((size.value * TileLineHeight).sp.toPx()).toInt()

internal fun Density.listRowHeight(style: TileStyle): Int {
    val text = lineHeightPx(style.titleSize) + ListSubtitleGap.roundToPx() + lineHeightPx(style.subtitleSize)
    val meta = lineHeightPx(style.durationSize) + MetaChipGap.roundToPx() +
        lineHeightPx(style.chipSize) + style.chipPaddingV.roundToPx() * 2
    val content = maxOf(style.artHeight.roundToPx(), text, meta)
    return content + style.paddingV.roundToPx() * 2
}

@Immutable
class LibraryTile(
    val id: String,
    val title: String,
    val listSubtitle: String,
    val gridSubtitle: String,
    val thumbUri: String?,
    val icon: ImageVector,
    val progress: Float,
    val meta: String?,
    val chips: List<String>,
    val badge: String?
) {
    val hasMeta: Boolean get() = meta != null || chips.isNotEmpty()
}

private class TileRects(
    val artX: Float,
    val artY: Float,
    val artWidth: Float,
    val artHeight: Float,
    val textX: Float,
    val textCenterY: Float,
    val metaRight: Float,
    val metaCenterY: Float
)

private fun Density.tileRects(style: TileStyle, width: Int, height: Int, textHeight: Int): TileRects {
    val inset = style.paddingH.toPx()
    return if (style.isGrid) {
        TileRects(
            artX = 0f,
            artY = 0f,
            artWidth = width.toFloat(),
            artHeight = height.toFloat(),
            textX = inset,
            textCenterY = height - GridTextBottom.toPx() - textHeight / 2f,
            metaRight = width - inset,
            metaCenterY = height / 2f
        )
    } else {
        val artWidth = style.artWidth.toPx()
        val artHeight = style.artHeight.toPx()
        TileRects(
            artX = inset,
            artY = (height - artHeight) / 2f,
            artWidth = artWidth,
            artHeight = artHeight,
            textX = inset + artWidth + style.artSpacing.toPx(),
            textCenterY = height / 2f,
            metaRight = width - inset,
            metaCenterY = height / 2f
        )
    }
}

private fun Density.textMaxWidth(style: TileStyle, width: Int, metaWidth: Int): Int {
    val inset = style.paddingH.roundToPx() * 2
    val available = if (style.isGrid) {
        width - inset
    } else {
        val meta = if (metaWidth > 0) style.trailingSpacing.roundToPx() + metaWidth else 0
        width - inset - style.artWidth.roundToPx() - style.artSpacing.roundToPx() - meta
    }
    return available.coerceAtLeast(0)
}

private fun textOutAlpha(fraction: Float, crossKind: Boolean): Float =
    if (crossKind) (1f - fraction / 0.6f).coerceIn(0f, 1f) else 1f - fraction * fraction

private fun textInAlpha(fraction: Float, crossKind: Boolean): Float =
    if (crossKind) ((fraction - 0.4f) / 0.6f).coerceIn(0f, 1f) else fraction

internal fun Modifier.fixedHeight(height: Int): Modifier = layout { measurable, constraints ->
    val width = constraints.maxWidth
    val placeable = measurable.measure(Constraints.fixed(width, height))
    layout(width, height) { placeable.place(0, 0) }
}

@Composable
internal fun LibraryTileView(
    tile: LibraryTile,
    from: TileFrame,
    to: TileFrame?,
    fraction: () -> Float,
    modifier: Modifier = Modifier
) {
    val colors = LocalLuckyColors.current
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    val startOrigin = if (rtl) TransformOrigin(1f, 0f) else TransformOrigin(0f, 0f)
    val endOrigin = if (rtl) TransformOrigin(0f, 0f) else TransformOrigin(1f, 0f)
    val fromStyle = from.style
    val toStyle = to?.style
    val targetStyle = toStyle ?: fromStyle
    val gridFrom = if (fromStyle.isGrid) 1f else 0f
    val gridTo = if (targetStyle.isGrid) 1f else 0f
    val bareFrom = if (!fromStyle.isGrid && fromStyle.artHeight == 0.dp) 1f else 0f
    val bareTo = if (!targetStyle.isGrid && targetStyle.artHeight == 0.dp) 1f else 0f
    val crossKind = fromStyle.isGrid != targetStyle.isGrid
    val titleRatio = targetStyle.titleSize.value / fromStyle.titleSize.value
    val metaRatio = if (!fromStyle.isGrid && !targetStyle.isGrid) {
        targetStyle.durationSize.value / fromStyle.durationSize.value
    } else {
        1f
    }
    val listBackground = colors.surfaceVariant.copy(alpha = 0f)
    val gridBackground = colors.surfaceVariant
    val progressTrack = colors.primary.copy(alpha = 0.16f)
    val progressFill = colors.primary
    val progress: () -> Float = if (toStyle == null) StaticFraction else fraction
    val showArt = fromStyle.isGrid || targetStyle.isGrid || fromStyle.artHeight > 0.dp || targetStyle.artHeight > 0.dp
    val thumbUri = tile.thumbUri.takeIf { showArt }

    Layout(
        content = {
            Box(
                modifier = Modifier
                    .layoutId(ArtId)
                    .graphicsLayer {
                        val t = progress().coerceIn(0f, 1f)
                        shape = RoundedCornerShape(mix(fromStyle.artCorner.toPx(), targetStyle.artCorner.toPx(), t))
                        clip = true
                    }
                    .background(colors.surfaceVariant),
                contentAlignment = Alignment.Center
            ) {
                if (thumbUri != null) {
                    VideoThumbnail(
                        uri = thumbUri,
                        corner = 0.dp,
                        progress = if (fromStyle.isGrid || targetStyle.isGrid) 0f else tile.progress,
                        fallbackIcon = tile.icon,
                        iconSize = fromStyle.iconSize,
                        modifier = Modifier.fillMaxSize()
                    )
                } else if (showArt) {
                    Box(
                        Modifier
                            .fillMaxSize()
                            .background(colors.primary.copy(alpha = 0.14f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(tile.icon, contentDescription = null, tint = colors.primary, modifier = Modifier.size(fromStyle.iconSize))
                    }
                }
                if (tile.badge != null && (fromStyle.isGrid || targetStyle.isGrid)) {
                    Text(
                        tile.badge,
                        fontSize = 11.sp,
                        lineHeight = 13.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = Color.White,
                        maxLines = 1,
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .graphicsLayer { alpha = mix(gridFrom, gridTo, progress().coerceIn(0f, 1f)) }
                            .padding(6.dp)
                            .clip(RoundedCornerShape(6.dp))
                            .background(Color.Black.copy(alpha = 0.55f))
                            .padding(horizontal = 6.dp, vertical = 2.dp)
                    )
                }
            }
            Box(
                modifier = Modifier
                    .layoutId(ScrimId)
                    .graphicsLayer { alpha = mix(gridFrom, gridTo, progress().coerceIn(0f, 1f)) }
                    .background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.72f))))
            )
            TileText(
                tile = tile,
                style = fromStyle,
                modifier = Modifier
                    .layoutId(TextFromId)
                    .graphicsLayer {
                        val t = progress().coerceIn(0f, 1f)
                        val scale = mix(1f, titleRatio, t)
                        scaleX = scale
                        scaleY = scale
                        transformOrigin = startOrigin
                        alpha = textOutAlpha(t, crossKind)
                    }
            )
            if (!fromStyle.isGrid && tile.hasMeta) {
                TileMeta(
                    tile = tile,
                    style = fromStyle,
                    modifier = Modifier
                        .layoutId(MetaFromId)
                        .graphicsLayer {
                            val t = progress().coerceIn(0f, 1f)
                            val scale = mix(1f, metaRatio, t)
                            scaleX = scale
                            scaleY = scale
                            transformOrigin = endOrigin
                            alpha = when {
                                toStyle == null -> 1f
                                targetStyle.isGrid -> (1f - t * 2f).coerceIn(0f, 1f)
                                else -> 1f - t * t
                            }
                        }
                )
            }
            if (toStyle != null) {
                TileText(
                    tile = tile,
                    style = toStyle,
                    modifier = Modifier
                        .layoutId(TextToId)
                        .graphicsLayer {
                            val t = progress().coerceIn(0f, 1f)
                            val scale = mix(1f / titleRatio, 1f, t)
                            scaleX = scale
                            scaleY = scale
                            transformOrigin = startOrigin
                            alpha = textInAlpha(t, crossKind)
                        }
                )
                if (!toStyle.isGrid && tile.hasMeta) {
                    TileMeta(
                        tile = tile,
                        style = toStyle,
                        modifier = Modifier
                            .layoutId(MetaToId)
                            .graphicsLayer {
                                val t = progress().coerceIn(0f, 1f)
                                val scale = mix(1f / metaRatio, 1f, t)
                                scaleX = scale
                                scaleY = scale
                                transformOrigin = endOrigin
                                alpha = if (fromStyle.isGrid) (t * 2f - 1f).coerceIn(0f, 1f) else t
                            }
                    )
                }
            }
        },
        modifier = modifier
            .graphicsLayer {
                val t = progress().coerceIn(0f, 1f)
                shape = RoundedCornerShape(mix(fromStyle.containerCorner.toPx(), targetStyle.containerCorner.toPx(), t))
                clip = true
            }
            .drawWithContent {
                val t = progress().coerceIn(0f, 1f)
                val gridWeight = mix(gridFrom, gridTo, t)
                val radius = mix(fromStyle.containerCorner.toPx(), targetStyle.containerCorner.toPx(), t)
                drawRoundRect(
                    color = lerp(listBackground, gridBackground, gridWeight),
                    cornerRadius = CornerRadius(radius, radius)
                )
                drawContent()
                val fill = tile.progress.coerceIn(0f, 1f)
                if (fill > 0f && gridWeight > 0f) {
                    val barHeight = GridProgressHeight.toPx()
                    val top = size.height - barHeight
                    drawRect(color = progressTrack, topLeft = Offset(0f, top), size = Size(size.width, barHeight), alpha = gridWeight)
                    drawRect(
                        color = progressFill,
                        topLeft = Offset(if (rtl) size.width * (1f - fill) else 0f, top),
                        size = Size(size.width * fill, barHeight),
                        alpha = gridWeight
                    )
                }
                val bareWeight = mix(bareFrom, bareTo, t)
                if (fill > 0f && bareWeight > 0f) {
                    val inset = ListPaddingH.toPx()
                    val barHeight = ListProgressHeight.toPx()
                    val barWidth = (size.width - inset * 2f).coerceAtLeast(0f)
                    val top = size.height - barHeight
                    val barRadius = CornerRadius(barHeight / 2f, barHeight / 2f)
                    drawRoundRect(
                        color = progressTrack,
                        topLeft = Offset(inset, top),
                        size = Size(barWidth, barHeight),
                        cornerRadius = barRadius,
                        alpha = bareWeight
                    )
                    drawRoundRect(
                        color = progressFill,
                        topLeft = Offset(if (rtl) inset + barWidth * (1f - fill) else inset, top),
                        size = Size(barWidth * fill, barHeight),
                        cornerRadius = barRadius,
                        alpha = bareWeight
                    )
                }
            }
    ) { measurables, constraints ->
        val t = progress().coerceIn(0f, 1f)
        val width = constraints.maxWidth
        val height = constraints.maxHeight
        val fromWidth = if (from.width > 0) from.width else width
        val fromHeight = if (from.height > 0) from.height else height
        val toWidth = if (to != null && to.width > 0) to.width else width
        val toHeight = if (to != null && to.height > 0) to.height else height

        val metaFrom = measurables.firstOrNull { it.layoutId == MetaFromId }?.measure(Constraints())
        val metaTo = measurables.firstOrNull { it.layoutId == MetaToId }?.measure(Constraints())
        val textFrom = measurables.first { it.layoutId == TextFromId }
            .measure(Constraints(maxWidth = textMaxWidth(fromStyle, fromWidth, metaFrom?.width ?: 0)))
        val textTo = measurables.firstOrNull { it.layoutId == TextToId }
            ?.measure(Constraints(maxWidth = textMaxWidth(targetStyle, toWidth, metaTo?.width ?: 0)))

        val fromRects = tileRects(fromStyle, fromWidth, fromHeight, textFrom.height)
        val toRects = if (textTo != null) tileRects(targetStyle, toWidth, toHeight, textTo.height) else fromRects

        val art = measurables.first { it.layoutId == ArtId }.measure(
            Constraints.fixed(
                mix(fromRects.artWidth, toRects.artWidth, t).roundToInt().coerceAtLeast(0),
                mix(fromRects.artHeight, toRects.artHeight, t).roundToInt().coerceAtLeast(0)
            )
        )

        val scrimPadding = GridScrimPadding.roundToPx()
        val scrimFrom = if (fromStyle.isGrid) textFrom.height + scrimPadding else 0
        val scrimTo = if (targetStyle.isGrid) (textTo?.height ?: textFrom.height) + scrimPadding else 0
        val scrimHeight = when {
            fromStyle.isGrid && targetStyle.isGrid -> mix(scrimFrom.toFloat(), scrimTo.toFloat(), t).roundToInt()
            fromStyle.isGrid -> scrimFrom
            targetStyle.isGrid -> scrimTo
            else -> 0
        }.coerceIn(0, height)
        val scrim = measurables.first { it.layoutId == ScrimId }.measure(Constraints.fixed(width, scrimHeight))

        val textX = mix(fromRects.textX, toRects.textX, t).roundToInt()
        val textCenter = mix(fromRects.textCenterY, toRects.textCenterY, t)
        val textScaleFrom = mix(1f, titleRatio, t)
        val textScaleTo = mix(1f / titleRatio, 1f, t)
        val metaRight = mix(fromRects.metaRight, toRects.metaRight, t)
        val metaCenter = mix(fromRects.metaCenterY, toRects.metaCenterY, t)
        val metaScaleFrom = mix(1f, metaRatio, t)
        val metaScaleTo = mix(1f / metaRatio, 1f, t)

        layout(width, height) {
            art.placeRelative(
                mix(fromRects.artX, toRects.artX, t).roundToInt(),
                mix(fromRects.artY, toRects.artY, t).roundToInt()
            )
            scrim.placeRelative(0, height - scrim.height)
            textFrom.placeRelative(textX, (textCenter - textFrom.height * textScaleFrom / 2f).roundToInt())
            if (textTo != null) {
                textTo.placeRelative(textX, (textCenter - textTo.height * textScaleTo / 2f).roundToInt())
            }
            if (metaFrom != null) {
                metaFrom.placeRelative(
                    (metaRight - metaFrom.width).roundToInt(),
                    (metaCenter - metaFrom.height * metaScaleFrom / 2f).roundToInt()
                )
            }
            if (metaTo != null) {
                metaTo.placeRelative(
                    (metaRight - metaTo.width).roundToInt(),
                    (metaCenter - metaTo.height * metaScaleTo / 2f).roundToInt()
                )
            }
        }
    }
}

@Composable
private fun TileText(tile: LibraryTile, style: TileStyle, modifier: Modifier = Modifier) {
    val colors = LocalLuckyColors.current
    val typography = MaterialTheme.typography
    val titleColor = if (style.isGrid) Color.White else colors.onSurface
    val subtitleColor = if (style.isGrid) Color.White.copy(alpha = 0.78f) else colors.secondaryText
    val subtitle = if (style.isGrid) tile.gridSubtitle else tile.listSubtitle
    Column(modifier = modifier) {
        Text(
            text = tile.title,
            style = typography.bodyLarge.tileSized(style.titleSize),
            fontWeight = FontWeight.SemiBold,
            color = titleColor,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        if (subtitle.isNotEmpty()) {
            Text(
                text = subtitle,
                style = typography.bodySmall.tileSized(style.subtitleSize),
                color = subtitleColor,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = if (style.isGrid) Modifier else Modifier.padding(top = ListSubtitleGap)
            )
        }
    }
}

@Composable
private fun TileMeta(tile: LibraryTile, style: TileStyle, modifier: Modifier = Modifier) {
    val colors = LocalLuckyColors.current
    Column(modifier = modifier, horizontalAlignment = Alignment.End) {
        if (tile.meta != null) {
            Text(
                text = tile.meta,
                style = MaterialTheme.typography.labelMedium.tileSized(style.durationSize),
                color = colors.secondaryText,
                maxLines = 1
            )
        }
        if (tile.chips.isNotEmpty()) {
            Row(modifier = Modifier.padding(top = if (tile.meta != null) MetaChipGap else 0.dp)) {
                tile.chips.forEachIndexed { position, chip ->
                    if (position > 0) Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = chip,
                        style = MaterialTheme.typography.labelSmall.tileSized(style.chipSize),
                        fontWeight = FontWeight.Bold,
                        color = colors.primary,
                        maxLines = 1,
                        modifier = Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .background(colors.primary.copy(alpha = 0.14f))
                            .padding(horizontal = style.chipPaddingH, vertical = style.chipPaddingV)
                    )
                }
            }
        }
    }
}
