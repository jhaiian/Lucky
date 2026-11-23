package com.jhaiian.lucky.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

private class ScrollSnapshot(val first: Int, val visible: Int, val total: Int, val scrolling: Boolean)

@Composable
fun BoxScope.VerticalScrollbar(state: LazyListState, color: Color, topPadding: Dp = 0.dp, bottomPadding: Dp = 0.dp) {
    val snapshot = ScrollSnapshot(
        state.firstVisibleItemIndex,
        state.layoutInfo.visibleItemsInfo.size,
        state.layoutInfo.totalItemsCount,
        state.isScrollInProgress
    )
    ScrollbarImpl(snapshot, color, topPadding, bottomPadding) { index -> state.scrollToItem(index) }
}

@Composable
fun BoxScope.VerticalScrollbar(state: LazyGridState, color: Color, topPadding: Dp = 0.dp, bottomPadding: Dp = 0.dp) {
    val snapshot = ScrollSnapshot(
        state.firstVisibleItemIndex,
        state.layoutInfo.visibleItemsInfo.size,
        state.layoutInfo.totalItemsCount,
        state.isScrollInProgress
    )
    ScrollbarImpl(snapshot, color, topPadding, bottomPadding) { index -> state.scrollToItem(index) }
}

@Composable
private fun BoxScope.ScrollbarImpl(
    snapshot: ScrollSnapshot,
    color: Color,
    topPadding: Dp,
    bottomPadding: Dp,
    scrollTo: suspend (Int) -> Unit
) {
    if (snapshot.total <= snapshot.visible || snapshot.visible == 0) return
    val density = LocalDensity.current
    val scope = rememberCoroutineScope()
    var trackHeight by remember { mutableStateOf(0) }
    var dragging by remember { mutableStateOf(false) }
    var dragFraction by remember { mutableStateOf(0f) }
    var visible by remember { mutableStateOf(false) }
    LaunchedEffect(snapshot.scrolling, dragging) {
        if (snapshot.scrolling || dragging) {
            visible = true
        } else {
            delay(900)
            visible = false
        }
    }
    val alpha by animateFloatAsState(if (visible) 1f else 0f, tween(if (visible) 120 else 400), label = "alpha")
    val widthScale by animateFloatAsState(if (dragging) 1.4f else 1f, tween(150), label = "width")
    val scrollable = (snapshot.total - snapshot.visible).coerceAtLeast(1)
    val fraction = if (dragging) dragFraction else (snapshot.first.toFloat() / scrollable).coerceIn(0f, 1f)
    val minThumb = with(density) { 48.dp.toPx() }
    val thumbHeight = (trackHeight * snapshot.visible.toFloat() / snapshot.total).coerceIn(minThumb, trackHeight.toFloat().coerceAtLeast(minThumb))
    val thumbOffset = ((trackHeight - thumbHeight) * fraction).roundToInt()
    Box(
        Modifier
            .align(Alignment.TopEnd)
            .fillMaxHeight()
            .padding(top = topPadding, bottom = bottomPadding)
            .width(32.dp)
            .onSizeChanged { trackHeight = it.height }
            .pointerInput(snapshot.total, snapshot.visible) {
                detectVerticalDragGestures(
                    onDragStart = { offset ->
                        dragging = true
                        dragFraction = ((offset.y - thumbHeight / 2) / (trackHeight - thumbHeight).coerceAtLeast(1f)).coerceIn(0f, 1f)
                    },
                    onDragEnd = { dragging = false },
                    onDragCancel = { dragging = false }
                ) { change, amount ->
                    change.consume()
                    dragFraction = (dragFraction + amount / (trackHeight - thumbHeight).coerceAtLeast(1f)).coerceIn(0f, 1f)
                    val target = (dragFraction * scrollable).roundToInt()
                    scope.launch { scrollTo(target) }
                }
            }
    ) {
        if (alpha > 0f) {
            Box(
                Modifier
                    .align(Alignment.TopEnd)
                    .offset { IntOffset(0, thumbOffset) }
                    .padding(end = 5.dp)
                    .alpha(alpha)
                    .size(width = 9.dp * widthScale, height = with(density) { thumbHeight.toDp() })
                    .shadow(3.dp, RoundedCornerShape(50), ambientColor = color.copy(alpha = 0.45f), spotColor = color.copy(alpha = 0.45f))
                    .clip(RoundedCornerShape(50))
                    .background(Brush.verticalGradient(listOf(lerp(color, Color.White, 0.28f), color, lerp(color, Color.Black, 0.18f))))
            )
        }
    }
}
