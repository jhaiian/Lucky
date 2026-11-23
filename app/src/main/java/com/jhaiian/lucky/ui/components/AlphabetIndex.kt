package com.jhaiian.lucky.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jhaiian.lucky.ui.theme.LocalLuckyColors
import java.text.Normalizer
import kotlin.math.roundToInt

private val marks = Regex("\\p{Mn}+")

fun indexLetter(text: String): String {
    val first = text.trimStart().firstOrNull() ?: return "#"
    if (!first.isLetter()) return "#"
    return marks.replace(Normalizer.normalize(first.toString(), Normalizer.Form.NFD), "").uppercase().ifEmpty { "#" }
}

@Composable
fun BoxScope.AlphabetIndex(
    letters: List<String>,
    onSelect: (String) -> Unit,
    topPadding: Dp = 0.dp,
    bottomPadding: Dp = 0.dp
) {
    if (letters.size < 2) return
    val colors = LocalLuckyColors.current
    val density = LocalDensity.current
    val haptics = rememberHaptics()
    val select by rememberUpdatedState(onSelect)
    val currentLetters by rememberUpdatedState(letters)
    var height by remember { mutableStateOf(0) }
    var active by remember { mutableStateOf<Int?>(null) }
    var touchY by remember { mutableStateOf(0f) }
    val stripAlpha by animateFloatAsState(if (active != null) 1f else 0.85f, tween(150), label = "strip")

    fun pick(y: Float) {
        val list = currentLetters
        if (height <= 0 || list.isEmpty()) return
        touchY = y.coerceIn(0f, height.toFloat())
        val index = ((touchY / height) * list.size).toInt().coerceIn(0, list.lastIndex)
        if (index != active) {
            active = index
            haptics.tick()
            select(list[index])
        }
    }

    Box(
        Modifier
            .align(Alignment.CenterEnd)
            .fillMaxHeight()
            .padding(top = topPadding + 8.dp, bottom = bottomPadding + 8.dp, end = 2.dp),
        contentAlignment = Alignment.CenterEnd
    ) {
        Column(
            Modifier
                .width(28.dp)
                .heightIn(max = (letters.size * 20).dp)
                .fillMaxHeight()
                .alpha(stripAlpha)
                .clip(RoundedCornerShape(14.dp))
                .background(if (active != null) colors.primary.copy(alpha = 0.12f) else colors.cardBackground.copy(alpha = 0f))
                .onSizeChanged { height = it.height }
                .pointerInput(Unit) {
                    detectTapGestures(onPress = { offset ->
                        pick(offset.y)
                        tryAwaitRelease()
                        active = null
                    })
                }
                .pointerInput(Unit) {
                    detectVerticalDragGestures(
                        onDragStart = { pick(it.y) },
                        onDragEnd = { active = null },
                        onDragCancel = { active = null }
                    ) { change, _ ->
                        change.consume()
                        pick(change.position.y)
                    }
                }
                .padding(vertical = 4.dp)
        ) {
            letters.forEachIndexed { index, letter ->
                Box(
                    Modifier
                        .weight(1f)
                        .width(28.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        letter,
                        fontSize = 10.sp,
                        lineHeight = 10.sp,
                        fontWeight = if (index == active) FontWeight.Black else FontWeight.Bold,
                        color = if (index == active) colors.primary else colors.secondaryText,
                        textAlign = TextAlign.Center,
                        maxLines = 1
                    )
                }
            }
        }
        val current = active
        if (current != null) {
            val bubbleSize = 56.dp
            val offsetY = with(density) { (touchY - height / 2f).roundToInt() }
            Box(
                Modifier
                    .offset { IntOffset(-with(density) { 44.dp.roundToPx() }, offsetY) }
                    .size(bubbleSize)
                    .clip(CircleShape)
                    .background(colors.primary),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    letters.getOrElse(current) { "" },
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                    color = colors.onPrimary
                )
            }
        }
    }
}
