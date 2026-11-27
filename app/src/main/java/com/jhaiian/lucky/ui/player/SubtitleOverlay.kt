package com.jhaiian.lucky.ui.player

import android.content.Context
import android.graphics.Bitmap
import android.os.SystemClock
import android.view.accessibility.CaptioningManager
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jhaiian.lucky.engine.SubtitleCue
import com.jhaiian.lucky.engine.SubtitleFrame
import com.jhaiian.lucky.engine.SubtitleStyle
import kotlinx.coroutines.isActive
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private data class VideoBox(val left: Float, val top: Float, val width: Float, val height: Float)

private fun videoBox(container: IntSize, placement: Placement): VideoBox {
    val rect = placement.rect(container.width.toFloat(), container.height.toFloat())
    return VideoBox(rect.left, rect.top, rect.width, rect.height)
}

private fun VideoBox.visible(container: IntSize): VideoBox {
    val left = left.coerceIn(0f, container.width.toFloat())
    val top = top.coerceIn(0f, container.height.toFloat())
    val right = (this.left + width).coerceIn(left, container.width.toFloat())
    val bottom = (this.top + height).coerceIn(top, container.height.toFloat())
    return VideoBox(left, top, right - left, bottom - top)
}

internal fun subtitleText(raw: String): AnnotatedString {
    val builder = AnnotatedString.Builder()
    val segment = StringBuilder()
    var italic = false
    var bold = false
    var underline = false
    var strike = false
    fun flush() {
        if (segment.isEmpty()) return
        val decorations = listOfNotNull(
            TextDecoration.Underline.takeIf { underline },
            TextDecoration.LineThrough.takeIf { strike }
        )
        val start = builder.length
        builder.append(segment.toString())
        builder.addStyle(
            SpanStyle(
                fontStyle = if (italic) FontStyle.Italic else null,
                fontWeight = if (bold) FontWeight.Bold else null,
                textDecoration = if (decorations.isEmpty()) null else TextDecoration.combine(decorations)
            ),
            start,
            builder.length
        )
        segment.clear()
    }
    var index = 0
    while (index < raw.length) {
        val char = raw[index]
        if (char == '{') {
            val end = raw.indexOf('}', index)
            if (end > index) {
                flush()
                raw.substring(index + 1, end).split('\\').forEach { tag ->
                    when {
                        tag == "i1" -> italic = true
                        tag == "i0" -> italic = false
                        tag == "u1" -> underline = true
                        tag == "u0" -> underline = false
                        tag == "s1" -> strike = true
                        tag == "s0" -> strike = false
                        tag == "b0" -> bold = false
                        tag.startsWith("b") -> tag.drop(1).toIntOrNull()?.let { bold = it == 1 || it >= 500 }
                        tag == "r" -> {
                            italic = false
                            bold = false
                            underline = false
                            strike = false
                        }
                    }
                }
                index = end + 1
                continue
            }
        }
        if (char == '\\' && index + 1 < raw.length) {
            when (raw[index + 1]) {
                'N', 'n' -> {
                    segment.append('\n')
                    index += 2
                    continue
                }
                'h' -> {
                    segment.append(' ')
                    index += 2
                    continue
                }
            }
        }
        segment.append(char)
        index++
    }
    flush()
    return builder.toAnnotatedString()
}

private data class CaptionLook(
    val foreground: Color,
    val background: Color,
    val edgeType: Int,
    val edgeColor: Color,
    val fontScale: Float,
    val bold: Boolean
)

private fun captionLook(context: Context, look: SubtitleLook, scale: Float): CaptionLook =
    if (look.followSystem) {
        val manager = context.getSystemService(CaptioningManager::class.java)
        val style = manager?.takeIf { it.isEnabled }?.userStyle
        CaptionLook(
            foreground = style?.takeIf { it.hasForegroundColor() }?.let { Color(it.foregroundColor) } ?: Color.White,
            background = style?.takeIf { it.hasBackgroundColor() }?.let { Color(it.backgroundColor) } ?: Color.Transparent,
            edgeType = style?.takeIf { it.hasEdgeType() }?.edgeType ?: CaptioningManager.CaptionStyle.EDGE_TYPE_OUTLINE,
            edgeColor = style?.takeIf { it.hasEdgeColor() }?.let { Color(it.edgeColor) } ?: Color.Black,
            fontScale = (manager?.fontScale ?: 1f) * scale,
            bold = look.bold
        )
    } else {
        CaptionLook(
            foreground = look.color.color,
            background = if (look.edge == SubtitleEdge.BOX) Color.Black.copy(alpha = 0.62f) else Color.Transparent,
            edgeType = when (look.edge) {
                SubtitleEdge.OUTLINE -> CaptioningManager.CaptionStyle.EDGE_TYPE_OUTLINE
                SubtitleEdge.SHADOW -> CaptioningManager.CaptionStyle.EDGE_TYPE_DROP_SHADOW
                else -> CaptioningManager.CaptionStyle.EDGE_TYPE_NONE
            },
            edgeColor = Color.Black,
            fontScale = look.sizePercent / 100f * scale,
            bold = look.bold
        )
    }

@Composable
private fun rememberCaptionLook(look: SubtitleLook, scale: Float): CaptionLook {
    val context = LocalContext.current
    return remember(look, scale) { captionLook(context, look, scale) }
}

private const val NATIVE_SIZE = 0.06f
private const val NATIVE_OUTLINE = 0.06f
private const val NATIVE_SHADOW = 0.05f
private const val NATIVE_BOX_PADDING = 0.2f
private const val NATIVE_SHADOW_ALPHA = 0.8f

internal fun nativeSubtitleStyle(context: Context, look: SubtitleLook, scale: Float): SubtitleStyle {
    val caption = captionLook(context, look, scale)
    val size = NATIVE_SIZE * caption.fontScale
    val boxed = caption.background.alpha > 0f
    val shadowed = caption.edgeType == CaptioningManager.CaptionStyle.EDGE_TYPE_DROP_SHADOW ||
        caption.edgeType == CaptioningManager.CaptionStyle.EDGE_TYPE_DEPRESSED ||
        caption.edgeType == CaptioningManager.CaptionStyle.EDGE_TYPE_RAISED
    val outlined = !boxed && caption.edgeType == CaptioningManager.CaptionStyle.EDGE_TYPE_OUTLINE
    return SubtitleStyle(
        size = size,
        primaryColor = caption.foreground.toArgb(),
        outlineColor = (if (boxed) caption.background else caption.edgeColor).toArgb(),
        backColor = (if (shadowed) caption.edgeColor.copy(alpha = NATIVE_SHADOW_ALPHA) else Color.Transparent).toArgb(),
        bold = caption.bold,
        outlineWidth = when {
            boxed -> size * NATIVE_BOX_PADDING
            outlined -> size * NATIVE_OUTLINE
            else -> 0f
        },
        shadow = if (shadowed) size * NATIVE_SHADOW else 0f,
        bottomMargin = look.position.margin,
        box = boxed
    )
}

@Composable
private fun CaptionText(text: AnnotatedString, sizePx: Float, look: CaptionLook, maxWidth: Dp) {
    val density = LocalDensity.current
    val fontSize = with(density) { sizePx.toSp() }.let { if (it.value < 12f) 12.sp else it }
    val base = TextStyle(
        fontSize = fontSize,
        textAlign = TextAlign.Center,
        lineHeight = fontSize * 1.2f,
        fontWeight = if (look.bold) FontWeight.Bold else FontWeight.Medium
    )
    Box(
        modifier = Modifier
            .widthIn(max = maxWidth)
            .clip(RoundedCornerShape(4.dp))
            .background(look.background)
            .padding(horizontal = 6.dp, vertical = 2.dp)
    ) {
        when (look.edgeType) {
            CaptioningManager.CaptionStyle.EDGE_TYPE_OUTLINE -> {
                Text(
                    text = text,
                    style = base.copy(color = look.edgeColor, drawStyle = Stroke(width = sizePx * 0.12f))
                )
                Text(text = text, style = base.copy(color = look.foreground))
            }
            CaptioningManager.CaptionStyle.EDGE_TYPE_DROP_SHADOW,
            CaptioningManager.CaptionStyle.EDGE_TYPE_DEPRESSED,
            CaptioningManager.CaptionStyle.EDGE_TYPE_RAISED -> Text(
                text = text,
                style = base.copy(
                    color = look.foreground,
                    shadow = Shadow(look.edgeColor, Offset(sizePx * 0.06f, sizePx * 0.06f), sizePx * 0.1f)
                )
            )
            else -> Text(text = text, style = base.copy(color = look.foreground))
        }
    }
}

@Composable
private fun BitmapCue(
    cue: SubtitleCue,
    box: VideoBox,
    videoWidth: Int,
    videoHeight: Int,
    load: suspend (Long, Int) -> Bitmap?
) {
    val images = remember(cue.id) { mutableStateMapOf<Int, ImageBitmap>() }
    LaunchedEffect(cue.id) {
        cue.rects.indices.forEach { index ->
            val bitmap = withContext(Dispatchers.Default) { load(cue.id, index) }
            if (bitmap != null) images[index] = bitmap.asImageBitmap()
        }
    }
    val frameWidth = (if (cue.frameWidth > 0) cue.frameWidth else videoWidth).coerceAtLeast(1)
    val frameHeight = (if (cue.frameHeight > 0) cue.frameHeight else videoHeight).coerceAtLeast(1)
    val scaleX = box.width / frameWidth
    val scaleY = box.height / frameHeight
    Canvas(Modifier.fillMaxSize()) {
        cue.rects.forEachIndexed { index, rect ->
            val image = images[index] ?: return@forEachIndexed
            drawImage(
                image = image,
                dstOffset = IntOffset((box.left + rect.x * scaleX).toInt(), (box.top + rect.y * scaleY).toInt()),
                dstSize = IntSize((rect.width * scaleX).toInt().coerceAtLeast(1), (rect.height * scaleY).toInt().coerceAtLeast(1))
            )
        }
    }
}

private const val MAX_ASS_FRAME = 4096

private class AssImage(val x: Int, val y: Int, val image: ImageBitmap)

@Composable
private fun AssLayer(
    position: () -> Long,
    placement: Placement,
    scale: Float,
    text: Boolean,
    raised: Boolean,
    raiseBy: Dp,
    render: (Long, Int, Int, Float, Float, Boolean) -> SubtitleFrame
) {
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val density = LocalDensity.current
        val container = with(density) { IntSize(maxWidth.roundToPx(), maxHeight.roundToPx()) }
        val full = videoBox(container, placement)
        val box = if (text) full.visible(container) else full
        val lift = if (text && raised && box.height > 0f) {
            (with(density) { raiseBy.toPx() } / box.height * 100f).coerceIn(0f, 60f)
        } else {
            0f
        }
        val longest = maxOf(box.width, box.height)
        val factor = if (longest > MAX_ASS_FRAME) MAX_ASS_FRAME / longest else 1f
        val frameWidth = (box.width * factor).roundToInt()
        val frameHeight = (box.height * factor).roundToInt()
        val currentPosition by rememberUpdatedState(position)
        val currentRender by rememberUpdatedState(render)
        var shown by remember { mutableStateOf<AssImage?>(null) }
        LaunchedEffect(frameWidth, frameHeight, scale, lift) {
            var force = true
            while (isActive) {
                withFrameMillis { }
                val time = currentPosition()
                val frame = withContext(Dispatchers.Default) { currentRender(time, frameWidth, frameHeight, scale, lift, force) }
                force = false
                when (frame) {
                    is SubtitleFrame.Image -> shown = AssImage(frame.x, frame.y, frame.bitmap.asImageBitmap())
                    SubtitleFrame.Empty -> shown = null
                    SubtitleFrame.Unchanged -> Unit
                }
            }
        }
        val current = shown
        if (current != null && frameWidth > 0 && frameHeight > 0) {
            Canvas(Modifier.fillMaxSize()) {
                drawImage(
                    image = current.image,
                    dstOffset = IntOffset(
                        (box.left + current.x / factor).roundToInt(),
                        (box.top + current.y / factor).roundToInt()
                    ),
                    dstSize = IntSize(
                        (current.image.width / factor).roundToInt().coerceAtLeast(1),
                        (current.image.height / factor).roundToInt().coerceAtLeast(1)
                    )
                )
            }
        }
    }
}

@Composable
fun SubtitleOverlay(
    cues: List<SubtitleCue>,
    position: () -> Long,
    placement: Placement,
    videoWidth: Int,
    videoHeight: Int,
    raised: Boolean,
    style: SubtitleLook,
    raiseBy: Dp = 120.dp,
    scale: Float,
    assActive: Boolean,
    textAss: Boolean,
    renderAss: (Long, Int, Int, Float, Float, Boolean) -> SubtitleFrame,
    loadBitmap: suspend (Long, Int) -> Bitmap?,
    modifier: Modifier = Modifier
) {
    if (assActive) {
        AssLayer(position, placement, style.sizePercent / 100f * scale, textAss, raised, raiseBy, renderAss)
        return
    }
    val look = rememberCaptionLook(style, scale)
    val currentCues by rememberUpdatedState(cues)
    val currentPosition by rememberUpdatedState(position)
    var active by remember { mutableStateOf<List<SubtitleCue>>(emptyList()) }
    LaunchedEffect(Unit) {
        while (true) {
            withFrameMillis { }
            val now = currentPosition()
            val visible = currentCues.filter { it.startMs <= now && now < (it.endMs ?: Long.MAX_VALUE) }.sortedBy { it.startMs }
            if (visible.map { it.id } != active.map { it.id }) active = visible
        }
    }
    if (active.isEmpty()) return
    BoxWithConstraints(modifier.fillMaxSize()) {
        val density = LocalDensity.current
        val container = with(density) { IntSize(maxWidth.roundToPx(), maxHeight.roundToPx()) }
        val box = videoBox(container, placement)
        val shown = box.visible(container)
        val bitmaps = active.filter { it.rects.isNotEmpty() }.let { list ->
            val latest = list.maxOfOrNull { it.startMs }
            list.filter { it.startMs == latest }
        }
        bitmaps.forEach { cue -> BitmapCue(cue, box, videoWidth, videoHeight, loadBitmap) }
        val texts = active.mapNotNull { it.text }.filter { it.isNotBlank() }
        if (texts.isNotEmpty()) {
            val text = remember(texts) { subtitleText(texts.joinToString("\\N")) }
            val reference = shown.height.coerceAtLeast(container.height * 0.3f)
            val sizePx = (reference * 0.052f * look.fontScale).coerceAtLeast(with(density) { 12.sp.toPx() })
            val bottomInset = container.height - (shown.top + shown.height)
            val lift = with(density) { (bottomInset + shown.height * style.position.margin).toDp() } + if (raised) raiseBy else 0.dp
            Box(
                Modifier
                    .fillMaxSize()
                    .padding(bottom = lift),
                contentAlignment = Alignment.BottomCenter
            ) {
                CaptionText(text, sizePx, look, with(density) { (shown.width.coerceAtLeast(container.width * 0.5f) * 0.92f).toDp() })
            }
        }
    }
}

internal fun extrapolatedPosition(anchorMs: Long, anchorUptime: Long, playing: Boolean, speed: Float = 1f): Long =
    if (playing) anchorMs + ((SystemClock.uptimeMillis() - anchorUptime) * speed).toLong() else anchorMs
