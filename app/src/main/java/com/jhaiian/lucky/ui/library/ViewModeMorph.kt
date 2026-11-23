package com.jhaiian.lucky.ui.library

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.spring
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.layout
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.roundToInt

private const val PinchStepScale = 1.35f
private const val VelocityWindowMillis = 120L
private const val FlingProjectionSeconds = 0.12f

internal class MorphSession(
    val tiles: List<LibraryTile>,
    val anchorIndex: Int,
    val anchorViewportY: Float
)

internal class ModeGeometry(
    val style: TileStyle,
    val count: Int,
    val columns: Int,
    private val columnWidths: IntArray,
    private val columnLefts: IntArray,
    val rowHeight: Int,
    private val pitch: Int,
    private val firstItemTop: Int,
    val headerLeft: Int,
    val headerWidth: Int,
    private val maxScroll: Int,
    private val viewportHeight: Int
) {
    val isGrid: Boolean get() = style.isGrid
    private val lines: Int get() = (count + columns - 1) / columns

    fun itemLeft(index: Int): Int = columnLefts[index % columns]

    fun itemWidth(index: Int): Int = columnWidths[index % columns]

    fun itemHeight(): Int = rowHeight

    fun itemTop(index: Int): Int = firstItemTop + (index / columns) * pitch

    fun contentTopOf(anchor: Int): Int = if (anchor < 0) 0 else itemTop(anchor)

    fun scrollFor(session: MorphSession): Float =
        (contentTopOf(session.anchorIndex) - session.anchorViewportY).coerceIn(0f, maxScroll.toFloat())

    fun anchorAt(scroll: Int): Int {
        if (count == 0 || scroll < firstItemTop) return -1
        return (((scroll - firstItemTop) / pitch) * columns).coerceAtMost(count - 1)
    }

    fun scrollFromLazy(index: Int, offset: Int): Int = when {
        index <= 0 -> offset
        index > count -> firstItemTop + lines * pitch + offset
        else -> firstItemTop + ((index - 1) / columns) * pitch + offset
    }

    fun lazyPosition(scroll: Int): Pair<Int, Int> {
        if (scroll < firstItemTop) return 0 to scroll
        val itemsOffset = scroll - firstItemTop
        val line = itemsOffset / pitch
        if (line >= lines) return (count + 1) to (itemsOffset - lines * pitch)
        return (1 + line * columns) to (itemsOffset - line * pitch)
    }

    fun visibleRange(scroll: Float): IntRange {
        if (count == 0) return IntRange.EMPTY
        val top = scroll - firstItemTop
        val bottom = scroll + viewportHeight - firstItemTop
        if (bottom < 0f) return IntRange.EMPTY
        val firstLine = floor(top / pitch).toInt().coerceAtLeast(0)
        val lastLine = floor(bottom / pitch).toInt()
        val first = firstLine * columns
        val last = minOf((lastLine + 1) * columns - 1, count - 1)
        return if (first > last) IntRange.EMPTY else first..last
    }
}

internal fun computeModeGeometry(
    mode: ViewMode,
    viewportWidth: Int,
    viewportHeight: Int,
    headerHeight: Int,
    bottomPadding: Int,
    count: Int,
    density: Density,
    cornerScale: Float
): ModeGeometry = with(density) {
    val style = tileStyleFor(mode, cornerScale)
    val endSpacer = ListEndSpacer.roundToPx()
    val contentWidth = minOf(viewportWidth, ListMaxWidth.roundToPx()).coerceAtLeast(1)
    val contentLeft = ((viewportWidth - contentWidth) / 2f).roundToInt()
    if (style.isGrid) {
        val padding = GridHorizontalPadding.roundToPx()
        val spacing = style.gridSpacing.roundToPx()
        val available = (contentWidth - padding * 2).coerceAtLeast(1)
        val columns = ((available + spacing) / (style.gridMinSize.roundToPx() + spacing)).coerceAtLeast(1)
        val withoutSpacing = (available - spacing * (columns - 1)).coerceAtLeast(0)
        val slot = withoutSpacing / columns
        val remaining = withoutSpacing % columns
        val widths = IntArray(columns) { slot + if (it < remaining) 1 else 0 }
        val lefts = IntArray(columns)
        var x = contentLeft + padding
        for (column in 0 until columns) {
            lefts[column] = x
            x += widths[column] + spacing
        }
        val cellHeight = (widths[0] / THUMB_ASPECT).roundToInt().coerceAtLeast(1)
        val pitch = cellHeight + spacing
        val lines = (count + columns - 1) / columns
        val firstItemTop = headerHeight + spacing
        val content = firstItemTop + lines * cellHeight + (lines - 1).coerceAtLeast(0) * spacing + endSpacer + bottomPadding
        ModeGeometry(
            style = style,
            count = count,
            columns = columns,
            columnWidths = widths,
            columnLefts = lefts,
            rowHeight = cellHeight,
            pitch = pitch.coerceAtLeast(1),
            firstItemTop = firstItemTop,
            headerLeft = contentLeft + padding,
            headerWidth = available,
            maxScroll = (content - viewportHeight).coerceAtLeast(0),
            viewportHeight = viewportHeight
        )
    } else {
        val rowHeight = listRowHeight(style).coerceAtLeast(1)
        val content = headerHeight + count * rowHeight + endSpacer + bottomPadding
        ModeGeometry(
            style = style,
            count = count,
            columns = 1,
            columnWidths = intArrayOf(contentWidth),
            columnLefts = intArrayOf(contentLeft),
            rowHeight = rowHeight,
            pitch = rowHeight,
            firstItemTop = headerHeight,
            headerLeft = contentLeft,
            headerWidth = contentWidth,
            maxScroll = (content - viewportHeight).coerceAtLeast(0),
            viewportHeight = viewportHeight
        )
    }
}

@Stable
internal class ViewModeMorphState(private val scope: CoroutineScope) {
    var session by mutableStateOf<MorphSession?>(null)
        private set
    var fromMode by mutableStateOf(ViewMode.LIST)
        private set
    var toMode by mutableStateOf(ViewMode.LIST)
        private set
    var fraction by mutableFloatStateOf(0f)
        private set

    var pinchEnabled = true
    var currentMode: () -> ViewMode = { ViewMode.LIST }
    var captureSession: () -> MorphSession? = { null }
    var commit: suspend (ViewMode) -> Unit = {}
    var onStep: () -> Unit = {}

    private val maxIndex = ViewMode.entries.size - 1
    private var position = 0f
    private var lastStep = 0
    private var directMorph = false
    private var job: Job? = null
    private val samples = ArrayDeque<Pair<Long, Float>>()

    fun startPinch(): Boolean {
        if (directMorph) return false
        if (session == null && !pinchEnabled) return false
        job?.cancel()
        job = null
        if (session == null) {
            val captured = captureSession() ?: return false
            session = captured
            position = currentMode().ordinal.toFloat()
            lastStep = position.roundToInt()
            applyPosition()
        }
        samples.clear()
        return true
    }

    fun pinchBy(zoom: Float, timeMillis: Long) {
        if (session == null || directMorph || zoom <= 0f) return
        position = (position - ln(zoom) / ln(PinchStepScale)).coerceIn(0f, maxIndex.toFloat())
        samples.addLast(timeMillis to position)
        while (samples.size > 2 && timeMillis - samples.first().first > VelocityWindowMillis) {
            samples.removeFirst()
        }
        applyPosition()
    }

    fun endPinch(timeMillis: Long) {
        if (session == null || directMorph) return
        val velocity = pinchVelocity(timeMillis)
        val nearest = position.roundToInt()
        val target = (position + velocity * FlingProjectionSeconds).roundToInt()
            .coerceIn(nearest - 1, nearest + 1)
            .coerceIn(0, maxIndex)
        val start = position
        job = scope.launch {
            animate(
                initialValue = start,
                targetValue = target.toFloat(),
                initialVelocity = velocity,
                animationSpec = spring(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = Spring.StiffnessMediumLow)
            ) { value, _ ->
                position = value.coerceIn(0f, maxIndex.toFloat())
                applyPosition()
            }
            position = target.toFloat()
            applyPosition()
            finish(ViewMode.entries[target])
        }
    }

    fun morphTo(target: ViewMode): Boolean {
        if (session != null) return true
        val from = currentMode()
        if (from == target) return true
        val captured = captureSession() ?: return false
        session = captured
        directMorph = true
        fromMode = from
        toMode = target
        fraction = 0f
        job = scope.launch {
            animate(
                initialValue = 0f,
                targetValue = 1f,
                animationSpec = spring(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = 240f)
            ) { value, _ ->
                fraction = value.coerceIn(0f, 1f)
            }
            fraction = 1f
            finish(target)
        }
        return true
    }

    private suspend fun finish(mode: ViewMode) {
        commit(mode)
        withFrameNanos { }
        withFrameNanos { }
        session = null
        directMorph = false
        position = mode.ordinal.toFloat()
    }

    private fun pinchVelocity(timeMillis: Long): Float {
        if (samples.size < 2) return 0f
        val first = samples.first()
        val last = samples.last()
        if (timeMillis - last.first > VelocityWindowMillis) return 0f
        val seconds = (last.first - first.first) / 1000f
        return if (seconds > 0f) (last.second - first.second) / seconds else 0f
    }

    private fun applyPosition() {
        val lower = floor(position).toInt().coerceIn(0, maxIndex - 1)
        fromMode = ViewMode.entries[lower]
        toMode = ViewMode.entries[lower + 1]
        fraction = (position - lower).coerceIn(0f, 1f)
        val step = position.roundToInt()
        if (step != lastStep) {
            lastStep = step
            onStep()
        }
    }
}

internal fun Modifier.viewModePinch(morph: ViewModeMorphState): Modifier = pointerInput(morph) {
    awaitEachGesture {
        awaitFirstDown(requireUnconsumed = false)
        var pinching = false
        var lastTime = 0L
        do {
            val event = awaitPointerEvent(PointerEventPass.Initial)
            val pressed = event.changes.count { it.pressed }
            lastTime = event.changes.firstOrNull()?.uptimeMillis ?: lastTime
            if (!pinching && pressed >= 2) {
                pinching = morph.startPinch()
            }
            if (pinching) {
                if (pressed >= 2) {
                    val zoom = event.calculateZoom()
                    if (zoom != 1f) morph.pinchBy(zoom, lastTime)
                }
                event.changes.forEach { it.consume() }
            }
        } while (event.changes.any { it.pressed })
        if (pinching) morph.endPinch(lastTime)
    }
}

@Composable
internal fun ViewModeMorphOverlay(
    morph: ViewModeMorphState,
    session: MorphSession,
    geometries: Map<ViewMode, ModeGeometry>,
    header: @Composable () -> Unit,
    modifier: Modifier = Modifier
) {
    val fromGeometry = geometries.getValue(morph.fromMode)
    val toGeometry = geometries.getValue(morph.toMode)
    val fromScroll = fromGeometry.scrollFor(session)
    val toScroll = toGeometry.scrollFor(session)
    val fromRange = fromGeometry.visibleRange(fromScroll)
    val toRange = toGeometry.visibleRange(toScroll)
    val tiles = session.tiles
    val fraction: () -> Float = { morph.fraction }
    val ordered = remember(fromRange, toRange) {
        val fading = (fromRange.toList() + toRange.toList())
            .distinct()
            .filter { (it in fromRange) != (it in toRange) }
        val moving = fromRange.filter { it in toRange }
        fading + moving
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .clipToBounds()
            .pointerInput(Unit) {
                awaitPointerEventScope {
                    while (true) {
                        awaitPointerEvent()
                    }
                }
            }
    ) {
        Box(
            modifier = Modifier.layout { measurable, constraints ->
                val t = morph.fraction
                val width = mix(fromGeometry.headerWidth.toFloat(), toGeometry.headerWidth.toFloat(), t)
                    .roundToInt()
                    .coerceAtLeast(0)
                val placeable = measurable.measure(Constraints(minWidth = width, maxWidth = width))
                layout(constraints.maxWidth, constraints.maxHeight) {
                    placeable.placeRelative(
                        mix(fromGeometry.headerLeft.toFloat(), toGeometry.headerLeft.toFloat(), t).roundToInt(),
                        mix(-fromScroll, -toScroll, t).roundToInt()
                    )
                }
            }
        ) {
            header()
        }
        for (index in ordered) {
            val tile = tiles.getOrNull(index) ?: continue
            val inFrom = index in fromRange
            val inTo = index in toRange
            key(tile.id) {
                if (inFrom && inTo) {
                    val fromX = fromGeometry.itemLeft(index).toFloat()
                    val fromY = fromGeometry.itemTop(index) - fromScroll
                    val fromWidth = fromGeometry.itemWidth(index)
                    val fromHeight = fromGeometry.itemHeight()
                    val toX = toGeometry.itemLeft(index).toFloat()
                    val toY = toGeometry.itemTop(index) - toScroll
                    val toWidth = toGeometry.itemWidth(index)
                    val toHeight = toGeometry.itemHeight()
                    LibraryTileView(
                        tile = tile,
                        from = TileFrame(fromGeometry.style, fromWidth, fromHeight),
                        to = TileFrame(toGeometry.style, toWidth, toHeight),
                        fraction = fraction,
                        modifier = Modifier.layout { measurable, constraints ->
                            val t = morph.fraction
                            val placeable = measurable.measure(
                                Constraints.fixed(
                                    mix(fromWidth.toFloat(), toWidth.toFloat(), t).roundToInt().coerceAtLeast(0),
                                    mix(fromHeight.toFloat(), toHeight.toFloat(), t).roundToInt().coerceAtLeast(0)
                                )
                            )
                            layout(constraints.maxWidth, constraints.maxHeight) {
                                placeable.placeRelative(mix(fromX, toX, t).roundToInt(), mix(fromY, toY, t).roundToInt())
                            }
                        }
                    )
                } else {
                    val geometry = if (inFrom) fromGeometry else toGeometry
                    val scroll = if (inFrom) fromScroll else toScroll
                    val x = geometry.itemLeft(index)
                    val y = (geometry.itemTop(index) - scroll).roundToInt()
                    val width = geometry.itemWidth(index)
                    val height = geometry.itemHeight()
                    LibraryTileView(
                        tile = tile,
                        from = TileFrame(geometry.style, width, height),
                        to = null,
                        fraction = StaticFraction,
                        modifier = Modifier.layout { measurable, constraints ->
                            val placeable = measurable.measure(Constraints.fixed(width, height))
                            layout(constraints.maxWidth, constraints.maxHeight) {
                                placeable.placeRelativeWithLayer(x, y) {
                                    val t = morph.fraction
                                    val visibility = if (inFrom) 1f - t else t
                                    alpha = visibility
                                    scaleX = 0.92f + 0.08f * visibility
                                    scaleY = 0.92f + 0.08f * visibility
                                }
                            }
                        }
                    )
                }
            }
        }
    }
}
